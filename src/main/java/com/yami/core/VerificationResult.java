package com.yami.core;

import java.util.List;

/**
 * Résultat de vérification multi-stratégies. Le Verifier choisit sa stratégie
 * selon {@code finding.source()} — toujours déterministe, args fixes.
 */
public record VerificationResult(
    Strategy strategy,
    StepResult format,
    StepResult init,
    StepResult validate,
    StepResult rescan,
    List<Finding> newFindings,
    boolean originalFindingsResolved,
    boolean newCriticalOrHigh,
    String remediationPr
) {
    public enum Strategy { TERRAFORM, ACTIONLINT_YAML, TRIVY }
    public enum StepResult { PASS, FAIL, NOT_RUN, NOT_VERIFIED }

    public boolean passed() {
        return format == StepResult.PASS
            && init == StepResult.PASS
            && validate == StepResult.PASS
            && rescan == StepResult.PASS;
    }
}
