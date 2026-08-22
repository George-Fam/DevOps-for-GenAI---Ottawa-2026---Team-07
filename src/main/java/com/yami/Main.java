package com.yami;

import java.nio.file.Path;

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

        Path repoDir = Path.of(workspace).toAbsolutePath().normalize();
        Path policyPath = repoDir.resolve(policyFile);
        Path auditDb = repoDir.resolve(".yami-audit.db");

        Harness harness = new Harness(repoDir, policyPath, token, auditDb);
        harness.run();
    }
}
