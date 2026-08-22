package com.yami.verifier;

import com.yami.core.Finding;
import com.yami.core.ProposedPatch;
import com.yami.core.VerificationResult;
import com.yami.core.VerificationResult.StepResult;
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
 * Never trusts patch content before this runs: copies the repo to a disposable working
 * dir, splices in the block, then requires terraform fmt + validate + a clean re-scan
 * before anything is reported as passed. Progressive gating - each step only runs if the
 * previous one passed, otherwise it's NOT_RUN rather than a false FAIL.
 *
 * <p>The re-scan step passes only if (a) every finding in {@code originalFindings} is
 * actually gone, not just "no new finding appeared", and (b) no *new* HIGH or CRITICAL
 * finding was introduced - a new LOW/MEDIUM finding doesn't block the patch. If Checkov
 * itself fails mid-rescan (not "found findings", the subprocess erroring), that's
 * NOT_VERIFIED, distinct from the patch actually failing the check.
 */
public class Verifier {

    private final HclBlockReplacer blockReplacer;
    private final CheckovAdapter checkovAdapter;

    public Verifier(HclBlockReplacer blockReplacer, CheckovAdapter checkovAdapter) {
        this.blockReplacer = blockReplacer;
        this.checkovAdapter = checkovAdapter;
    }

    public VerificationResult verify(Path repoDir, Path relativeTerraformFile, ProposedPatch patch, List<Finding> originalFindings) {
        Path workDir;
        try {
            workDir = Files.createTempDirectory("yami-verify-");
            copyDirectory(repoDir, workDir);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }

        List<Finding> before;
        try {
            before = checkovAdapter.scan(workDir);
        } catch (RuntimeException e) {
            return new VerificationResult(StepResult.NOT_RUN, StepResult.NOT_RUN, StepResult.NOT_VERIFIED, List.of(), null);
        }

        Path targetFile = workDir.resolve(relativeTerraformFile);
        try {
            blockReplacer.replace(targetFile, patch.resourceAddress(), patch.replacementBlock());
        } catch (HclBlockReplacer.BlockNotFoundException | IllegalStateException e) {
            return new VerificationResult(StepResult.NOT_RUN, StepResult.NOT_RUN, StepResult.NOT_RUN, List.of(), null);
        }

        StepResult formatResult = run(workDir, "terraform", "fmt", "-check=false") == 0 ? StepResult.PASS : StepResult.FAIL;
        if (formatResult != StepResult.PASS) {
            return new VerificationResult(formatResult, StepResult.NOT_RUN, StepResult.NOT_RUN, List.of(), null);
        }

        if (run(workDir, "terraform", "init", "-input=false") != 0) {
            return new VerificationResult(formatResult, StepResult.FAIL, StepResult.NOT_RUN, List.of(), null);
        }

        StepResult validateResult = run(workDir, "terraform", "validate") == 0 ? StepResult.PASS : StepResult.FAIL;
        if (validateResult != StepResult.PASS) {
            return new VerificationResult(formatResult, validateResult, StepResult.NOT_RUN, List.of(), null);
        }

        List<Finding> after;
        try {
            after = checkovAdapter.scan(workDir);
        } catch (RuntimeException e) {
            return new VerificationResult(formatResult, validateResult, StepResult.NOT_VERIFIED, List.of(), null);
        }

        List<Finding> newFindings = after.stream()
            .filter(f -> before.stream().noneMatch(b -> sameFinding(b, f)))
            .collect(Collectors.toList());

        boolean originalFindingsResolved = originalFindings.stream()
            .noneMatch(orig -> after.stream().anyMatch(f -> sameFinding(orig, f)));

        boolean newCriticalOrHigh = newFindings.stream()
            .anyMatch(f -> f.severity() == Finding.Severity.HIGH || f.severity() == Finding.Severity.CRITICAL);

        StepResult rescanResult = (originalFindingsResolved && !newCriticalOrHigh) ? StepResult.PASS : StepResult.FAIL;

        return new VerificationResult(formatResult, validateResult, rescanResult, newFindings, null);
    }

    private static boolean sameFinding(Finding a, Finding b) {
        return a.ruleId().equals(b.ruleId()) && a.resource().equals(b.resource());
    }

    private static void copyDirectory(Path source, Path target) throws IOException {
        try (Stream<Path> paths = Files.walk(source)) {
            for (Path src : (Iterable<Path>) paths::iterator) {
                Path relative = source.relativize(src);
                boolean underTerraformDir = false;
                for (Path part : relative) {
                    if (part.toString().equals(".terraform")) {
                        underTerraformDir = true;
                        break;
                    }
                }
                if (underTerraformDir) {
                    continue;
                }
                Path dest = target.resolve(relative);
                if (Files.isDirectory(src)) {
                    Files.createDirectories(dest);
                } else {
                    Files.createDirectories(dest.getParent());
                    Files.copy(src, dest, StandardCopyOption.REPLACE_EXISTING);
                }
            }
        }
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
