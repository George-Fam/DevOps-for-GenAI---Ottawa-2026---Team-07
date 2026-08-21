package com.yami.verifier;

import com.yami.core.Finding;
import com.yami.core.ProposedPatch;
import com.yami.core.VerificationResult;
import com.yami.scanner.CheckovAdapter;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.List;
import java.util.stream.Stream;

/**
 * Never trusts patch content before this runs: copies the repo to a disposable working
 * dir, splices in the block, then requires terraform fmt + validate + a clean re-scan
 * before anything is reported as passed. Any failure here is a rejection - the caller
 * escalates to human review, nothing gets written to the real repo.
 */
public class Verifier {

    private final HclBlockReplacer blockReplacer;
    private final CheckovAdapter checkovAdapter;

    public Verifier(HclBlockReplacer blockReplacer, CheckovAdapter checkovAdapter) {
        this.blockReplacer = blockReplacer;
        this.checkovAdapter = checkovAdapter;
    }

    public VerificationResult verify(Path repoDir, Path relativeTerraformFile, ProposedPatch patch) {
        Path workDir;
        try {
            workDir = Files.createTempDirectory("yami-verify-");
            copyDirectory(repoDir, workDir);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }

        List<Finding> before = checkovAdapter.scan(workDir);

        Path targetFile = workDir.resolve(relativeTerraformFile);
        try {
            blockReplacer.replace(targetFile, patch.resourceAddress(), patch.replacementBlock());
        } catch (HclBlockReplacer.BlockNotFoundException | IllegalStateException e) {
            return rejected(before, "block replacement failed: " + e.getMessage());
        }

        int fmtExit = run(workDir, "terraform", "fmt", "-check=false");
        boolean formatValid = fmtExit == 0;
        if (!formatValid) {
            return rejected(before, "terraform fmt failed with exit code " + fmtExit);
        }

        int initExit = run(workDir, "terraform", "init", "-input=false");
        if (initExit != 0) {
            return rejected(before, "terraform init failed with exit code " + initExit);
        }

        int validateExit = run(workDir, "terraform", "validate");
        boolean validateValid = validateExit == 0;
        if (!validateValid) {
            return new VerificationResult(false, true, false, before, null,
                "terraform validate failed with exit code " + validateExit);
        }

        List<Finding> after = checkovAdapter.scan(workDir);
        boolean noNewFindings = after.stream().noneMatch(f ->
            before.stream().noneMatch(b -> b.ruleId().equals(f.ruleId()) && b.resourceAddress().equals(f.resourceAddress())));

        if (!noNewFindings) {
            return new VerificationResult(false, true, true, before, after,
                "re-scan introduced a new finding not present before the patch");
        }

        return new VerificationResult(true, true, true, before, after, null);
    }

    private VerificationResult rejected(List<Finding> before, String reason) {
        return new VerificationResult(false, false, false, before, null, reason);
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
