package com.yami;

import java.nio.file.Path;
import java.util.Arrays;
import java.util.List;

/**
 * Point d'entrée du container Docker Yami.
 *
 * <p>Lit le contexte GitHub Actions (GITHUB_EVENT_PATH, GITHUB_WORKSPACE,
 * GITHUB_TOKEN) et délègue au {@link Harness}.
 */
public class Main {

    public static void main(String[] args) {
        String workspace = System.getenv("GITHUB_WORKSPACE");
        if (workspace == null) {
            workspace = ".";
        }

        String token = System.getenv("GITHUB_TOKEN");
        String policyFile = System.getenv("YAMI_POLICY_FILE");
        if (policyFile == null) {
            policyFile = "policies/yami.yml";
        }

        boolean replayMode = "true".equalsIgnoreCase(System.getenv("YAMI_REPLAY_MODE"));
        String replayFileEnv = System.getenv("YAMI_REPLAY_FILE");
        if (replayFileEnv == null || replayFileEnv.isBlank()) {
            replayFileEnv = "replay.jsonl";
        }

        String auditDbEnv = System.getenv("YAMI_AUDIT_DB");
        if (auditDbEnv == null || auditDbEnv.isBlank()) {
            auditDbEnv = ".yami-audit.db";
        }

        List<String> scopeOverride = parseScope(System.getenv("YAMI_SCOPE"));

        Path repoDir = Path.of(workspace).toAbsolutePath().normalize();
        Path policyPath = repoDir.resolve(policyFile);
        Path auditDb = repoDir.resolve(auditDbEnv);
        Path replayFile = repoDir.resolve(replayFileEnv);

        Harness harness = new Harness(repoDir, policyPath, token, auditDb, replayMode, replayFile, scopeOverride);
        boolean success = harness.run();
        if (!success) {
            System.exit(1);
        }
    }

    /** YAMI_SCOPE is a comma-separated glob list (action input {@code scope}). */
    private static List<String> parseScope(String scopeEnv) {
        if (scopeEnv == null || scopeEnv.isBlank()) {
            return List.of();
        }
        return Arrays.stream(scopeEnv.split(","))
            .map(String::trim)
            .filter(s -> !s.isEmpty())
            .toList();
    }
}
