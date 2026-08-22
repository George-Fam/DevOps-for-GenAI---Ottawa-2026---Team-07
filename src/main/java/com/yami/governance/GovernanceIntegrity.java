package com.yami.governance;

import java.nio.file.Path;

/**
 * Hook runtime pour le Harness. Vérifie l'intégrité du manifest de gouvernance
 * au démarrage du pipeline.
 *
 * <p>Si la vérification échoue, le Harness doit :
 * 1. Logger dans audit.json (governanceIntegrity: FAIL, fallbackMode: true)
 * 2. Escalader en commentaire PR
 * 3. Déclencher le KillSwitch (arrêt immédiat)
 */
public class GovernanceIntegrity {

    public record Result(boolean passed, String details) {}

    public static Result verify(Path repoDir) {
        try {
            new GovManifestTool().verify(repoDir);
            return new Result(true, "Manifest integrity verified");
        } catch (GovManifestException e) {
            return new Result(false, e.getMessage());
        }
    }
}
