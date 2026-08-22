package com.yami.verifier;

import com.yami.core.Finding;
import com.yami.core.PatchReport;
import com.yami.core.VerificationResult;
import com.yami.scanner.CheckovAdapter;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.List;
import java.util.stream.Collectors;
import java.util.stream.Stream;

/**
 * Stratégie de vérification pour les findings Terraform (CHECKOV).
 * terraform fmt → init → validate → checkov re-scan.
 */
public class TerraformStrategy implements VerificationStrategy {

    private final CheckovAdapter checkovAdapter;

    public TerraformStrategy(CheckovAdapter checkovAdapter) {
        this.checkovAdapter = checkovAdapter;
    }

    @Override
    public boolean supports(Finding.FindingSource source) {
        return source == Finding.FindingSource.CHECKOV;
    }

    @Override
    public VerificationResult verify(Path workDir, Finding originalFinding, PatchReport patchReport) {
        // Le patch a déjà été appliqué par le Surgeon dans le repo réel.
        // Le Harness copie le repo dans workDir avant d'appeler le Verifier.
        // Donc workDir contient déjà les modifications.

        List<Finding> before;
        try {
            before = checkovAdapter.scan(workDir);
        } catch (RuntimeException e) {
            return result(VerificationResult.StepResult.NOT_RUN, VerificationResult.StepResult.NOT_RUN, VerificationResult.StepResult.NOT_RUN, VerificationResult.StepResult.NOT_VERIFIED, List.of(), false, false);
        }

        VerificationResult.StepResult formatResult = run(workDir, "terraform", "fmt", "-check=false") == 0
            ? VerificationResult.StepResult.PASS : VerificationResult.StepResult.FAIL;
        if (formatResult != VerificationResult.StepResult.PASS) {
            return result(formatResult, VerificationResult.StepResult.NOT_RUN, VerificationResult.StepResult.NOT_RUN, VerificationResult.StepResult.NOT_RUN, List.of(), false, false);
        }

        VerificationResult.StepResult initResult = run(workDir, "terraform", "init", "-input=false") == 0
            ? VerificationResult.StepResult.PASS : VerificationResult.StepResult.FAIL;
        if (initResult != VerificationResult.StepResult.PASS) {
            return result(formatResult, initResult, VerificationResult.StepResult.NOT_RUN, VerificationResult.StepResult.NOT_RUN, List.of(), false, false);
        }

        VerificationResult.StepResult validateResult = run(workDir, "terraform", "validate") == 0
            ? VerificationResult.StepResult.PASS : VerificationResult.StepResult.FAIL;
        if (validateResult != VerificationResult.StepResult.PASS) {
            return result(formatResult, initResult, validateResult, VerificationResult.StepResult.NOT_RUN, List.of(), false, false);
        }

        List<Finding> after;
        try {
            after = checkovAdapter.scan(workDir);
        } catch (RuntimeException e) {
            return result(formatResult, initResult, validateResult, VerificationResult.StepResult.NOT_VERIFIED, List.of(), false, false);
        }

        List<Finding> newFindings = after.stream()
            .filter(f -> before.stream().noneMatch(b -> sameFinding(b, f)))
            .collect(Collectors.toList());

        boolean originalResolved = !after.stream().anyMatch(f -> sameFinding(originalFinding, f));
        boolean newCriticalOrHigh = newFindings.stream()
            .anyMatch(f -> f.severity() == Finding.Severity.HIGH || f.severity() == Finding.Severity.CRITICAL);

        VerificationResult.StepResult rescanResult = (originalResolved && !newCriticalOrHigh)
            ? VerificationResult.StepResult.PASS : VerificationResult.StepResult.FAIL;

        return result(formatResult, initResult, validateResult, rescanResult, newFindings, originalResolved, newCriticalOrHigh);
    }

    private static VerificationResult result(VerificationResult.StepResult format, VerificationResult.StepResult init,
                                              VerificationResult.StepResult validate, VerificationResult.StepResult rescan,
                                              List<Finding> newFindings, boolean originalResolved, boolean newCriticalOrHigh) {
        return new VerificationResult(VerificationResult.Strategy.TERRAFORM, format, init, validate, rescan,
            newFindings, originalResolved, newCriticalOrHigh, null);
    }

    private static boolean sameFinding(Finding a, Finding b) {
        return a.ruleId().equals(b.ruleId()) && a.resource().equals(b.resource());
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
