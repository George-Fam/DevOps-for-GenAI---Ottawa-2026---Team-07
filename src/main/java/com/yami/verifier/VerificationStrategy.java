package com.yami.verifier;

import com.yami.core.Finding;
import com.yami.core.PatchReport;
import com.yami.core.VerificationResult;

import java.nio.file.Path;

/**
 * Interface pour les stratégies de vérification post-patch.
 * Chaque stratégie est spécialisée par type de finding.
 */
public interface VerificationStrategy {

    /**
     * Vérifie que le patch appliqué dans le workDir est valide.
     *
     * @param workDir répertoire de travail temporaire (copie du repo + patch)
     * @param originalFinding le finding original (pour localisation et comparaison)
     * @param patchReport le rapport du Surgeon (fichiers modifiés)
     * @return résultat de vérification
     */
    VerificationResult verify(Path workDir, Finding originalFinding, PatchReport patchReport);

    /**
     * Indique si cette stratégie supporte le type de finding donné.
     */
    boolean supports(Finding.FindingSource source);
}
