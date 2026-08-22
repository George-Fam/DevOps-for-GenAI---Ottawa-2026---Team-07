package com.yami.verifier;

import com.yami.core.Finding;
import com.yami.core.PatchReport;
import com.yami.core.VerificationResult;
import com.yami.scanner.CicdRules;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.stream.Collectors;

/**
 * Stratégie de vérification pour les findings CI/CD (CICD_RULES).
 * actionlint sur workflows modifiés + re-run CicdRules.
 */
public class ActionlintYamlStrategy implements VerificationStrategy {

    @Override
    public boolean supports(Finding.FindingSource source) {
        return source == Finding.FindingSource.CICD_RULES;
    }

    @Override
    public VerificationResult verify(Path workDir, Finding originalFinding, PatchReport patchReport) {
        // Filtrer les workflows modifiés
        List<String> workflowFiles = patchReport.filesModified().stream()
            .filter(f -> f.startsWith(".github/workflows/") && (f.endsWith(".yml") || f.endsWith(".yaml")))
            .collect(Collectors.toList());

        if (workflowFiles.isEmpty()) {
            // Aucun workflow modifié — vérification passe (pas notre périmètre)
            return result(VerificationResult.StepResult.PASS, VerificationResult.StepResult.PASS,
                VerificationResult.StepResult.PASS, VerificationResult.StepResult.PASS,
                List.of(), true, false);
        }

        // actionlint sur chaque workflow modifié
        for (String wf : workflowFiles) {
            Path workflowPath = workDir.resolve(wf);
            if (!Files.exists(workflowPath)) {
                return result(VerificationResult.StepResult.PASS, VerificationResult.StepResult.PASS,
                    VerificationResult.StepResult.FAIL, VerificationResult.StepResult.NOT_RUN,
                    List.of(), false, false);
            }

            int exit = run(workDir, "actionlint", workflowPath.toString());
            if (exit != 0) {
                return result(VerificationResult.StepResult.PASS, VerificationResult.StepResult.PASS,
                    VerificationResult.StepResult.FAIL, VerificationResult.StepResult.NOT_RUN,
                    List.of(), false, false);
            }
        }

        // Re-run CicdRules sur le workDir
        CicdRules cicdRules = new CicdRules();
        List<Finding> after = cicdRules.evaluate(workDir);

        boolean originalResolved = !after.stream().anyMatch(f ->
            f.ruleId().equals(originalFinding.ruleId()) && f.resource().equals(originalFinding.resource()));

        boolean newCriticalOrHigh = after.stream()
            .anyMatch(f -> f.severity() == Finding.Severity.HIGH || f.severity() == Finding.Severity.CRITICAL);

        VerificationResult.StepResult rescanResult = (originalResolved && !newCriticalOrHigh)
            ? VerificationResult.StepResult.PASS : VerificationResult.StepResult.FAIL;

        return result(VerificationResult.StepResult.PASS, VerificationResult.StepResult.PASS,
            VerificationResult.StepResult.PASS, rescanResult, after, originalResolved, newCriticalOrHigh);
    }

    private static VerificationResult result(VerificationResult.StepResult format, VerificationResult.StepResult init,
                                              VerificationResult.StepResult validate, VerificationResult.StepResult rescan,
                                              List<Finding> newFindings, boolean originalResolved, boolean newCriticalOrHigh) {
        return new VerificationResult(VerificationResult.Strategy.ACTIONLINT_YAML, format, init, validate, rescan,
            newFindings, originalResolved, newCriticalOrHigh, null);
    }

    private static int run(Path dir, String... command) {
        try {
            // Fixed argv array, never a shell string.
            // nosemgrep: java.lang.security.audit.command-injection-process-builder.command-injection-process-builder
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
