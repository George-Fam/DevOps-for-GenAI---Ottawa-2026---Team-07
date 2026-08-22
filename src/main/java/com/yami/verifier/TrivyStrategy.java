package com.yami.verifier;

import com.yami.core.Finding;
import com.yami.core.PatchReport;
import com.yami.core.VerificationResult;
import com.yami.scanner.TrivyAdapter;

import java.nio.file.Path;
import java.util.List;

/**
 * Stratégie de vérification pour les findings supply chain (TRIVY).
 * trivy fs re-scan → comparer findings avant/après (même convention que
 * ActionlintYamlStrategy : pas d'étape fmt/init, la comparaison porte sur
 * l'ensemble des findings post-patch).
 */
public class TrivyStrategy implements VerificationStrategy {

    private final TrivyAdapter trivyAdapter;

    public TrivyStrategy() {
        this(new TrivyAdapter());
    }

    public TrivyStrategy(TrivyAdapter trivyAdapter) {
        this.trivyAdapter = trivyAdapter;
    }

    @Override
    public boolean supports(Finding.FindingSource source) {
        return source == Finding.FindingSource.TRIVY;
    }

    @Override
    public VerificationResult verify(Path workDir, Finding originalFinding, PatchReport patchReport) {
        List<Finding> after;
        try {
            after = trivyAdapter.scan(workDir);
        } catch (RuntimeException e) {
            return result(VerificationResult.StepResult.PASS, VerificationResult.StepResult.PASS,
                VerificationResult.StepResult.FAIL, VerificationResult.StepResult.NOT_VERIFIED,
                List.of(), false, false);
        }

        boolean originalResolved = after.stream().noneMatch(f -> sameFinding(originalFinding, f));
        boolean newCriticalOrHigh = after.stream()
            .anyMatch(f -> f.severity() == Finding.Severity.HIGH || f.severity() == Finding.Severity.CRITICAL);

        VerificationResult.StepResult rescanResult = (originalResolved && !newCriticalOrHigh)
            ? VerificationResult.StepResult.PASS : VerificationResult.StepResult.FAIL;

        return result(VerificationResult.StepResult.PASS, VerificationResult.StepResult.PASS,
            VerificationResult.StepResult.PASS, rescanResult, after, originalResolved, newCriticalOrHigh);
    }

    private static boolean sameFinding(Finding a, Finding b) {
        return a.ruleId().equals(b.ruleId()) && a.resource().equals(b.resource());
    }

    private static VerificationResult result(VerificationResult.StepResult format, VerificationResult.StepResult init,
                                              VerificationResult.StepResult validate, VerificationResult.StepResult rescan,
                                              List<Finding> newFindings, boolean originalResolved, boolean newCriticalOrHigh) {
        return new VerificationResult(VerificationResult.Strategy.TRIVY, format, init, validate, rescan,
            newFindings, originalResolved, newCriticalOrHigh, null);
    }
}
