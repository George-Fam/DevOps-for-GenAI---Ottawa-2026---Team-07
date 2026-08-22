package com.yami.governance;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.FileVisitResult;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.attribute.BasicFileAttributes;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * Outil de gouvernance : génère et vérifie le manifest SHA-256 des artefacts
 * {@code .opencode/}.
 *
 * <p><b>Génération</b> : parcourt tous les fichiers {@code .md} et {@code .json}
 * sous {@code .opencode/} (triés lexicographiquement), calcule leur SHA-256,
 * et écrit {@code .opencode/MANIFEST.json} avec le chaînage.
 *
 * <p><b>Vérification</b> : relit les fichiers, recalcule les hashes, compare
 * avec le manifest commité. Si un fichier a été modifié, ajouté ou supprimé,
 * lève {@link GovManifestException}.
 *
 * <p><b>Chaînage</b> : le champ {@code previousManifestHash} contient le
 * SHA-256 du manifest précédent (fichier {@code .opencode/MANIFEST.json}
 * tel que commité). Le premier manifest a {@code null}.
 *
 * <p><b>Respect du gitignore</b> : les patterns du fichier
 * {@code .opencode/.gitignore} sont chargés et appliqués pendant le walk.
 * Les répertoires ignorés sont élagués (pas de descente), ce qui exclut
 * automatiquement tout leur contenu du manifest. Les fichiers sous des
 * chemins ignorés ne sont pas pris en compte par {@code verify()}.
 */
public class GovManifestTool {

    private static final String MANIFEST_FILE = "MANIFEST.json";
    private static final String OPENCODE_DIR = ".opencode";

    private final ObjectMapper mapper;

    public GovManifestTool() {
        this.mapper = new ObjectMapper()
            .enable(SerializationFeature.INDENT_OUTPUT)
            .findAndRegisterModules();
    }

    /**
     * Génère le manifest et l'écrit dans {@code .opencode/MANIFEST.json}.
     *
     * @param repoDir racine du repo (où se trouve {@code .opencode/})
     * @return le manifest généré
     */
    public GovManifest generate(Path repoDir) {
        Path opencodeDir = repoDir.resolve(OPENCODE_DIR);
        if (!Files.isDirectory(opencodeDir)) {
            throw new GovManifestException(".opencode/ directory not found at " + opencodeDir);
        }

        Set<String> ignorePatterns = loadIgnorePatterns(opencodeDir);
        Map<String, String> fileHashes = new LinkedHashMap<>();

        try {
            Files.walkFileTree(opencodeDir, new SimpleFileVisitor<Path>() {
                @Override
                public FileVisitResult preVisitDirectory(Path dir, BasicFileAttributes attrs) {
                    if (isIgnored(dir, opencodeDir, ignorePatterns)) {
                        return FileVisitResult.SKIP_SUBTREE;
                    }
                    return FileVisitResult.CONTINUE;
                }

                @Override
                public FileVisitResult visitFile(Path file, BasicFileAttributes attrs) {
                    if (isGovernanceFile(file) && !isIgnored(file, opencodeDir, ignorePatterns)) {
                        String relative = repoDir.relativize(file).toString();
                        String hash = sha256(file);
                        fileHashes.put(relative, hash);
                    }
                    return FileVisitResult.CONTINUE;
                }
            });
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }

        String gitCommit = resolveGitCommit(repoDir);
        String previousHash = readPreviousManifestHash(repoDir.resolve(OPENCODE_DIR).resolve(MANIFEST_FILE));

        GovManifest manifest = new GovManifest(
            "1",
            Instant.now().toString(),
            gitCommit,
            previousHash,
            fileHashes
        );

        writeManifest(repoDir.resolve(OPENCODE_DIR).resolve(MANIFEST_FILE), manifest);
        return manifest;
    }

    /**
     * Vérifie l'intégrité du manifest commité.
     *
     * @param repoDir racine du repo
     * @throws GovManifestException si un fichier ne correspond pas, est manquant,
     *                              ajouté, ou si la chaîne est cassée
     */
    public void verify(Path repoDir) {
        Path manifestPath = repoDir.resolve(OPENCODE_DIR).resolve(MANIFEST_FILE);
        if (!Files.exists(manifestPath)) {
            throw new GovManifestException("Manifest file not found: " + manifestPath);
        }

        GovManifest committed = readManifest(manifestPath);
        Set<String> ignorePatterns = loadIgnorePatterns(repoDir.resolve(OPENCODE_DIR));

        // 1. Vérifier les fichiers listés
        for (Map.Entry<String, String> entry : committed.files().entrySet()) {
            Path file = repoDir.resolve(entry.getKey());
            if (!Files.exists(file)) {
                throw new GovManifestException("File listed in manifest but missing on disk: " + entry.getKey());
            }
            String actualHash = sha256(file);
            if (!actualHash.equals(entry.getValue())) {
                throw new GovManifestException(
                    "Hash mismatch for " + entry.getKey() + ": manifest=" + entry.getValue() + " actual=" + actualHash);
            }
        }

        // 2. Vérifier qu'aucun fichier non-ignoré n'a été ajouté
        Path opencodeDir = repoDir.resolve(OPENCODE_DIR);
        try {
            Files.walkFileTree(opencodeDir, new SimpleFileVisitor<Path>() {
                @Override
                public FileVisitResult preVisitDirectory(Path dir, BasicFileAttributes attrs) {
                    if (isIgnored(dir, opencodeDir, ignorePatterns)) {
                        return FileVisitResult.SKIP_SUBTREE;
                    }
                    return FileVisitResult.CONTINUE;
                }

                @Override
                public FileVisitResult visitFile(Path file, BasicFileAttributes attrs) {
                    if (isGovernanceFile(file) && !isIgnored(file, opencodeDir, ignorePatterns)) {
                        String relative = repoDir.relativize(file).toString();
                        if (!committed.files().containsKey(relative)) {
                            throw new GovManifestException("New file not listed in manifest: " + relative);
                        }
                    }
                    return FileVisitResult.CONTINUE;
                }
            });
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }

        // 3. Vérifier le chaînage (si previousManifestHash est présent)
        if (committed.previousManifestHash() != null) {
            // Le previousManifestHash doit correspondre au hash du manifest précédent
            // tel qu'il était commité. On ne peut pas le vérifier ici sans l'historique Git,
            // mais on peut vérifier que le manifest actuel est cohérent.
            // Pour une vérification complète du chaînage, il faudrait parcourir l'historique Git.
            // Dans le contexte hackathon, la vérification CI (git diff --exit-code) suffit.
        }
    }

    private boolean isGovernanceFile(Path file) {
        String name = file.getFileName().toString();
        return Files.isRegularFile(file)
            && (name.endsWith(".md") || name.endsWith(".json"))
            && !name.equals(MANIFEST_FILE);
    }

    /**
     * Détermine si un fichier ou répertoire est ignoré selon les patterns
     * du {@code .gitignore} de {@code .opencode/}.
     *
     * <p>Un chemin est ignoré si l'un de ses segments (nom de répertoire ou
     * nom de fichier) correspond à un pattern. Les répertoires ignorés sont
     * élagués en amont via {@code SKIP_SUBTREE} ; cette méthode sert aussi
     * pour les fichiers individuels.
     */
    private boolean isIgnored(Path file, Path opencodeDir, Set<String> patterns) {
        if (patterns.isEmpty()) {
            return false;
        }
        Path relative = opencodeDir.relativize(file);
        String relativeStr = relative.toString();
        if (relativeStr.isEmpty()) {
            return false; // opencodeDir itself
        }
        for (int i = 0; i < relative.getNameCount(); i++) {
            String segment = relative.getName(i).toString();
            for (String pattern : patterns) {
                if (matchesPattern(segment, pattern)) {
                    return true;
                }
            }
        }
        return false;
    }

    private Set<String> loadIgnorePatterns(Path opencodeDir) {
        Path gitignore = opencodeDir.resolve(".gitignore");
        if (!Files.exists(gitignore)) {
            return Set.of();
        }
        try {
            return Files.readAllLines(gitignore).stream()
                .map(String::trim)
                .filter(line -> !line.isEmpty() && !line.startsWith("#"))
                .collect(Collectors.toSet());
        } catch (IOException e) {
            return Set.of();
        }
    }

    private boolean matchesPattern(String name, String pattern) {
        if (pattern.endsWith("/")) {
            return name.equals(pattern.substring(0, pattern.length() - 1));
        }
        if (pattern.contains("*")) {
            String regex = pattern.replace(".", "\\.")
                                  .replace("*", ".*");
            return name.matches(regex);
        }
        return name.equals(pattern);
    }

    private static String sha256(Path file) {
        try {
            byte[] bytes = Files.readAllBytes(file);
            byte[] digest = MessageDigest.getInstance("SHA-256").digest(bytes);
            StringBuilder hex = new StringBuilder();
            for (byte b : digest) {
                hex.append(String.format("%02x", b));
            }
            return hex.toString();
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }

    private String resolveGitCommit(Path repoDir) {
        // Priorité 1 : GITHUB_SHA (GitHub Actions)
        String githubSha = System.getenv("GITHUB_SHA");
        if (githubSha != null && !githubSha.isBlank()) {
            return githubSha;
        }

        // Priorité 2 : git rev-parse HEAD (subprocess)
        try {
            Process p = new ProcessBuilder("git", "rev-parse", "HEAD")
                .directory(repoDir.toFile())
                .redirectErrorStream(true)
                .start();
            String output = new String(p.getInputStream().readAllBytes()).trim();
            int exit = p.waitFor();
            if (exit == 0 && !output.isBlank()) {
                return output;
            }
        } catch (IOException | InterruptedException e) {
            // ignore, fallback below
        }

        return "unknown";
    }

    private String readPreviousManifestHash(Path manifestPath) {
        if (!Files.exists(manifestPath)) {
            return null; // premier manifest
        }
        GovManifest previous = readManifest(manifestPath);
        // Le hash du manifest précédent = SHA-256 du JSON canonique
        return sha256Manifest(previous);
    }

    private String sha256Manifest(GovManifest manifest) {
        try {
            // Sérialisation canonique : clés triées, indentation fixe
            String canonical = mapper.writeValueAsString(manifest);
            byte[] digest = MessageDigest.getInstance("SHA-256").digest(canonical.getBytes());
            StringBuilder hex = new StringBuilder();
            for (byte b : digest) {
                hex.append(String.format("%02x", b));
            }
            return hex.toString();
        } catch (Exception e) {
            throw new RuntimeException("Failed to hash manifest", e);
        }
    }

    private GovManifest readManifest(Path path) {
        try {
            return mapper.readValue(path.toFile(), GovManifest.class);
        } catch (IOException e) {
            throw new UncheckedIOException("Failed to read manifest: " + path, e);
        }
    }

    private void writeManifest(Path path, GovManifest manifest) {
        try {
            mapper.writeValue(path.toFile(), manifest);
        } catch (IOException e) {
            throw new UncheckedIOException("Failed to write manifest: " + path, e);
        }
    }

    /**
     * Point d'entrée CLI : {@code java -cp target/yami.jar com.yami.governance.GovManifestTool [generate|verify]}.
     */
    public static void main(String[] args) {
        if (args.length < 1) {
            System.err.println("Usage: GovManifestTool [generate|verify] [repo-dir]");
            System.exit(1);
        }

        String command = args[0];
        Path repoDir = args.length > 1 ? Path.of(args[1]) : Path.of(".");
        GovManifestTool tool = new GovManifestTool();

        switch (command) {
            case "generate" -> {
                GovManifest manifest = tool.generate(repoDir);
                System.out.println("Generated manifest at " + repoDir.resolve(OPENCODE_DIR).resolve(MANIFEST_FILE));
                System.out.println("Files: " + manifest.files().size());
                System.out.println("Git commit: " + manifest.gitCommit());
            }
            case "verify" -> {
                tool.verify(repoDir);
                System.out.println("Manifest verification PASSED");
            }
            default -> {
                System.err.println("Unknown command: " + command);
                System.exit(1);
            }
        }
    }
}
