package com.yami.verifier;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The 20:00 pivot-gate spike, run ahead of time: prove that replacing an HCL resource
 * block by text splice still produces a file that {@code terraform validate} accepts,
 * and that the replacement actually fixes the finding it was meant to fix.
 * Requires a real `terraform` binary on PATH (shells out - not a pure unit test).
 */
class HclBlockReplacerSpikeTest {

    private static final String FIXED_BLOCK = """
        resource "aws_s3_bucket" "data" {
          bucket = "yami-demo-bucket"
        }

        resource "aws_s3_bucket_versioning" "data" {
          bucket = aws_s3_bucket.data.id
          versioning_configuration {
            status = "Enabled"
          }
        }

        resource "aws_s3_bucket_server_side_encryption_configuration" "data" {
          bucket = aws_s3_bucket.data.id
          rule {
            apply_server_side_encryption_by_default {
              sse_algorithm = "aws:kms"
            }
          }
        }""";

    @Test
    void replacementBlockPassesTerraformValidate(@org.junit.jupiter.api.io.TempDir Path tempDir) throws IOException, InterruptedException {
        Path fixtureSrc = Path.of("fixtures/safe_fix/main.tf");
        Path workDir = tempDir.resolve("safe_fix");
        Files.createDirectories(workDir);
        Path main = workDir.resolve("main.tf");
        Files.copy(fixtureSrc, main, StandardCopyOption.REPLACE_EXISTING);

        new HclBlockReplacer().replace(main, "aws_s3_bucket.data", FIXED_BLOCK);

        String content = Files.readString(main);
        assertFalse(content.contains("bucket = \"yami-demo-bucket\"\n}\n\nresource \"aws_s3_bucket_public_access_block\""),
            "old single-line bucket block should be gone");
        assertTrue(content.contains("aws_s3_bucket_versioning"));
        assertTrue(content.contains("aws_s3_bucket_public_access_block"), "untouched sibling resource must survive the splice");

        run(workDir, "terraform", "init", "-input=false");
        int fmtExit = run(workDir, "terraform", "fmt", "-check=false");
        assertEquals(0, fmtExit, "terraform fmt should succeed on the spliced file");
        int validateExit = run(workDir, "terraform", "validate");
        assertEquals(0, validateExit, "terraform validate should accept the spliced file");
    }

    private static int run(Path dir, String... command) throws IOException, InterruptedException {
        // Fixed argv array, never a shell string - see ActionlintYamlStrategy/TerraformStrategy.
        // nosemgrep: java.lang.security.audit.command-injection-process-builder.command-injection-process-builder
        Process p = new ProcessBuilder(command)
            .directory(dir.toFile())
            .redirectErrorStream(true)
            .redirectOutput(ProcessBuilder.Redirect.INHERIT)
            .start();
        return p.waitFor();
    }
}
