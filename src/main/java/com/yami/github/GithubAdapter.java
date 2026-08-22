package com.yami.github;

import com.yami.audit.KillSwitch;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Path;
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
