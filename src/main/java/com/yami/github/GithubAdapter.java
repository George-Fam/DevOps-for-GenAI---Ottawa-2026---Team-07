package com.yami.github;

import com.yami.audit.KillSwitch;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Implémentation GitHub via git CLI et gh CLI. Token scopé, jamais merge.
 *
 * <p>Crée une branche jetable, pousse le commit, ouvre la PR.
 * Le token GitHub Actions doit avoir {@code contents:write} + {@code pull_requests:write}.
 */
public class GithubAdapter {

    private final Path repoDir;
    private final String githubToken;

    public GithubAdapter(Path repoDir, String githubToken) {
        this.repoDir = repoDir;
        this.githubToken = githubToken;
    }

    public String createBranch(String baseBranch) {
        KillSwitch.checkNotDisabled("create branch");
        String branchName = "yami-fix-" + UUID.randomUUID().toString().substring(0, 8);

        run("git", "checkout", "-b", branchName);
        run("git", "push", "origin", branchName);

        return branchName;
    }

    public void commitChanges(String branchName, String message) {
        KillSwitch.checkNotDisabled("commit changes");
        run("git", "add", ".");
        run("git", "commit", "-m", message);
        run("git", "push", "origin", branchName);
    }

    public String openPr(String branchName, String title, String body) {
        KillSwitch.checkNotDisabled("open PR");
        String prUrl = runWithOutput("gh", "pr", "create",
            "--head", branchName,
            "--title", title,
            "--body", body);
        return prUrl;
    }

    /**
     * Fichiers modifiés par la PR courante, via {@code git diff} contre
     * {@code GITHUB_BASE_REF}. Best-effort : un checkout superficiel (pas de
     * {@code origin/<base>} fetché) dégrade silencieusement vers une liste vide
     * plutôt que de faire échouer le run — ce n'est qu'un enrichissement du
     * contexte, pas un chemin critique.
     */
    public List<String> changedFiles() {
        String base = System.getenv("GITHUB_BASE_REF");
        if (base == null || base.isBlank()) {
            return List.of();
        }
        try {
            String output = runWithOutput("git", "diff", "--name-only", "origin/" + base + "...HEAD");
            return output.isBlank() ? List.of() : List.of(output.split("\n"));
        } catch (RuntimeException e) {
            System.err.println("[GithubAdapter] could not compute changed files (shallow checkout?): " + e.getMessage());
            return List.of();
        }
    }

    /**
     * Diff unifié par fichier pour les fichiers donnés, contre {@code GITHUB_BASE_REF}.
     * Même dégradation silencieuse que {@link #changedFiles()}.
     */
    public Map<String, String> diffsFor(List<String> files) {
        String base = System.getenv("GITHUB_BASE_REF");
        if (base == null || base.isBlank() || files.isEmpty()) {
            return Map.of();
        }
        Map<String, String> diffs = new LinkedHashMap<>();
        for (String file : files) {
            try {
                diffs.put(file, runWithOutput("git", "diff", "origin/" + base + "...HEAD", "--", file));
            } catch (RuntimeException e) {
                System.err.println("[GithubAdapter] could not diff " + file + ": " + e.getMessage());
            }
        }
        return diffs;
    }

    public void postComment(String prNumber, String body) {
        KillSwitch.checkNotDisabled("post comment");
        run("gh", "pr", "comment", prNumber, "--body", body);
    }

    public void escalateToHumanReview(String reason) {
        KillSwitch.checkNotDisabled("post escalation comment");
        String prNumber = System.getenv("GITHUB_REF_NAME");
        if (prNumber != null && prNumber.matches("\\d+")) {
            postComment(prNumber, "## Yami Escalation :eyes:\n\n" + reason
                + "\n\n*This finding requires human review — Yami will not auto-remediate.*");
        }
    }

    private void run(String... command) {
        try {
            // Fixed argv array, never a shell string - no shell interpretation happens, so
            // there's no metacharacter injection vector regardless of argument content.
            // nosemgrep: java.lang.security.audit.command-injection-process-builder.command-injection-process-builder
            ProcessBuilder pb = new ProcessBuilder(command)
                .directory(repoDir.toFile())
                .redirectErrorStream(true);
            if (githubToken != null) {
                pb.environment().put("GITHUB_TOKEN", githubToken);
            }
            Process p = pb.start();
            int exit = p.waitFor();
            if (exit != 0) {
                String output = new String(p.getInputStream().readAllBytes());
                throw new RuntimeException("command failed (exit " + exit + "): " + String.join(" ", command) + "\n" + output);
            }
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new RuntimeException(e);
        }
    }

    private String runWithOutput(String... command) {
        try {
            // Fixed argv array, never a shell string - see run() above.
            // nosemgrep: java.lang.security.audit.command-injection-process-builder.command-injection-process-builder
            ProcessBuilder pb = new ProcessBuilder(command)
                .directory(repoDir.toFile())
                .redirectErrorStream(true);
            if (githubToken != null) {
                pb.environment().put("GITHUB_TOKEN", githubToken);
            }
            Process p = pb.start();
            String output = new String(p.getInputStream().readAllBytes()).trim();
            int exit = p.waitFor();
            if (exit != 0) {
                throw new RuntimeException("command failed (exit " + exit + "): " + String.join(" ", command) + "\n" + output);
            }
            return output;
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new RuntimeException(e);
        }
    }
}
