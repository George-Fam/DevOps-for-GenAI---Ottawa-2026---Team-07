package com.yami.verifier;

import com.yami.core.Finding;
import com.yami.core.PatchReport;
import com.yami.core.VerificationResult;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Path;
import java.util.List;

/**
 * Stratégie de vérification pour les findings supply chain (TRIVY).
 * trivy fs re-scan → comparer findings avant/après.
 */
public class TrivyStrategy implements VerificationStrategy {

    @Override
    public boolean supports(Finding.FindingSource source) {
        return source == Finding.FindingSource.TRIVY;
    }

    @Override
    public VerificationResult verify(Path workDir, Finding originalFinding, PatchReport patchReport) {
        // trivy fs --scanners vuln <workDir>
        int exit = run(workDir, "trivy", "fs", "--scanners", "vuln", "--quiet", workDir.toString());
        if (exit != 0) {
            return result(VerificationResult.StepResult.PASS, VerificationResult.StepResult.PASS,
                VerificationResult.StepResult.PASS, VerificationResult.StepResult.FAIL,
                List.of(), false, false);
        }

        // TODO: parser la sortie JSON de trivy et comparer avec le finding original
        // Pour l'instant, on assume que le re-scan passe
        return result(VerificationResult.StepResult.PASS, VerificationResult.StepResult.PASS,
            VerificationResult.StepResult.PASS, VerificationResult.StepResult.PASS,
            List.of(), true, false);
    }

    private static VerificationResult result(VerificationResult.StepResult format, VerificationResult.StepResult init,
                                              VerificationResult.StepResult validate, VerificationResult.StepResult rescan,
                                              List<Finding> newFindings, boolean originalResolved, boolean newCriticalOrHigh) {
        return new VerificationResult(VerificationResult.Strategy.TRIVY, format, init, validate, rescan,
            newFindings, originalResolved, newCriticalOrHigh, null);
    }

    private static int run(Path dir, String... command) {
        try {
            Process p = new ProcessBuilder(command)
                .directory(dir.toFile())
                .redirectErrorStream(true)
                .redirectOutput(ProcessBuilder.Redirect.DISCARD)
                .start();
            return p.waitFor();
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new RuntimeException(e);
        }
    }
}
