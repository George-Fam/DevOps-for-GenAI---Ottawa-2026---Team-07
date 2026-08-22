package com.yami.verifier;

import com.yami.core.Finding;
import com.yami.core.PatchReport;
import com.yami.core.VerificationResult;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.List;
import java.util.stream.Stream;

/**
 * Vérificateur multi-stratégies. Le Harness copie le repo dans un workdir
 * temporaire (où le Surgeon a déjà appliqué ses modifications), puis le
 * Verifier choisit sa stratégie selon {@code finding.source()}.
 *
 * <p>Stratégies supportées :
 * - {@link TerraformStrategy} pour CHECKOV (terraform fmt/validate + re-scan)
 * - {@link ActionlintYamlStrategy} pour CICD_RULES (actionlint + cicd re-run)
 * - {@link TrivyStrategy} pour TRIVY (trivy re-scan)
 */
public class Verifier {

    private final List<VerificationStrategy> strategies;

    public Verifier(List<VerificationStrategy> strategies) {
        this.strategies = strategies;
    }

    public VerificationResult verify(Path repoDir, Finding originalFinding, PatchReport patchReport) {
        VerificationStrategy strategy = strategies.stream()
            .filter(s -> s.supports(originalFinding.source()))
            .findFirst()
            .orElseThrow(() -> new IllegalStateException("No verification strategy for source: " + originalFinding.source()));

        Path workDir = copyRepoToWorkDir(repoDir);
        return strategy.verify(workDir, originalFinding, patchReport);
    }

    private static Path copyRepoToWorkDir(Path repoDir) {
        try {
            Path workDir = Files.createTempDirectory("yami-verify-");
            copyDirectory(repoDir, workDir);
            return workDir;
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    private static void copyDirectory(Path source, Path target) throws IOException {
        try (Stream<Path> paths = Files.walk(source)) {
            for (Path src : (Iterable<Path>) paths::iterator) {
                Path relative = source.relativize(src);
                boolean underTerraformDir = false;
                for (Path part : relative) {
                    if (part.toString().equals(".terraform")) {
                        underTerraformDir = true;
                        break;
                    }
                }
                if (underTerraformDir) {
                    continue;
                }
                Path dest = target.resolve(relative);
                if (Files.isDirectory(src)) {
                    Files.createDirectories(dest);
                } else {
                    Files.createDirectories(dest.getParent());
                    Files.copy(src, dest, StandardCopyOption.REPLACE_EXISTING);
                }
            }
        }
    }
}
