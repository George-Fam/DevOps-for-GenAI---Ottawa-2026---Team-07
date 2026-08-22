package com.yami.verifier;

import com.yami.core.Finding;
import com.yami.core.PatchReport;
import com.yami.core.VerificationResult;
import com.yami.core.VerificationResult.StepResult;
import com.yami.scanner.CheckovAdapter;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/**
 * End-to-end integration test for the Verifier with TerraformStrategy.
 * Requires terraform and checkov on PATH; skipped otherwise.
 *
 * <p>The new architecture (post-pivot) assumes the Surgeon has already modified
 * the target file before the Verifier runs. This test simulates that by writing
 * the patched content directly to the temp fixture directory.
 */
class VerifierTest {

    private static final Path FIXTURE = Path.of("fixtures/safe_fix");

    private static final String ORIGINAL_MAIN_TF = """
        terraform {
          required_providers {
            aws = {
              source  = "hashicorp/aws"
              version = "~> 5.0"
            }
          }
        }

        provider "aws" {
          region = "us-east-1"
        }

        resource "aws_s3_bucket" "data" {
          bucket = "yami-demo-bucket"
        }
        """;

    private static final String PATCHED_MAIN_TF = """
        terraform {
          required_providers {
            aws = {
              source  = "hashicorp/aws"
              version = "~> 5.0"
            }
          }
        }

        provider "aws" {
          region = "us-east-1"
        }

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
        }
        """;

    private static final String BROKEN_MAIN_TF = """
        resource "aws_s3_bucket" "data" {
          bucket = "yami-demo-bucket"
        """; // missing closing brace

    private static final String POLICY_MAIN_TF = """
        terraform {
          required_providers {
            aws = {
              source  = "hashicorp/aws"
              version = "~> 5.0"
            }
          }
        }

        provider "aws" {
          region = "us-east-1"
        }

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
        }

        resource "aws_s3_bucket_policy" "data" {
          bucket = aws_s3_bucket.data.id
          policy = jsonencode({
            Version = "2012-10-17"
            Statement = [{
              Effect    = "Allow"
              Principal = "*"
              Action    = "s3:*"
              Resource  = "${aws_s3_bucket.data.arn}/*"
            }]
          })
        }
        """;

    @BeforeAll
    static void checkTools() {
        assumeTrue(hasTool("terraform"), "terraform not on PATH — skipping integration test");
        assumeTrue(hasTool("checkov"), "checkov not on PATH — skipping integration test");
    }

    private static boolean hasTool(String tool) {
        try {
            Process p = new ProcessBuilder(tool, "--version").start();
            return p.waitFor() == 0;
        } catch (Exception e) {
            return false;
        }
    }

    private Verifier createVerifier() {
        return new Verifier(List.of(new TerraformStrategy(new CheckovAdapter())));
    }

    @Test
    void goodPatchPassesAndReducesFindings(@TempDir Path tempDir) throws IOException {
        // Simulate the Surgeon's work: write the patched file
        Path targetFile = tempDir.resolve("main.tf");
        Files.writeString(targetFile, PATCHED_MAIN_TF);

        Finding finding = new Finding("CKV_AWS_21", Finding.Severity.HIGH, "main.tf", 1,
            "aws_s3_bucket.data", "Ensure S3 bucket has versioning enabled", Finding.FindingSource.CHECKOV);
        PatchReport patchReport = new PatchReport(List.of("main.tf"), "adds versioning + KMS encryption",
            List.of("ISR03"), List.of());

        Verifier verifier = createVerifier();
        VerificationResult result = verifier.verify(tempDir, finding, patchReport);

        assertTrue(result.passed(), () -> "expected pass, got fmt=" + result.format()
            + " init=" + result.init() + " validate=" + result.validate() + " rescan=" + result.rescan());
        assertEquals(StepResult.PASS, result.format());
        assertEquals(StepResult.PASS, result.init());
        assertEquals(StepResult.PASS, result.validate());
        assertEquals(StepResult.PASS, result.rescan());
        assertTrue(result.originalFindingsResolved(), "the original finding should be resolved");
        assertFalse(result.newCriticalOrHigh(), "no new critical/high finding should be introduced");
    }

    @Test
    void syntacticallyInvalidBlockFailsFormatAndIsRejected(@TempDir Path tempDir) throws IOException {
        Path targetFile = tempDir.resolve("main.tf");
        Files.writeString(targetFile, BROKEN_MAIN_TF);

        Finding finding = new Finding("CKV_AWS_21", Finding.Severity.HIGH, "main.tf", 1,
            "aws_s3_bucket.data", "Ensure S3 bucket has versioning enabled", Finding.FindingSource.CHECKOV);
        PatchReport patchReport = new PatchReport(List.of("main.tf"), "broken block",
            List.of(), List.of());

        Verifier verifier = createVerifier();
        VerificationResult result = verifier.verify(tempDir, finding, patchReport);

        assertFalse(result.passed());
        assertEquals(StepResult.FAIL, result.format());
        assertEquals(StepResult.NOT_RUN, result.init(), "init never runs after fmt fails");
        assertEquals(StepResult.NOT_RUN, result.validate(), "validate never runs after fmt fails");
    }

    @Test
    void patchIntroducingNewCriticalFindingIsRejectedEvenThoughOriginalFindingIsFixed(@TempDir Path tempDir) throws IOException {
        Path targetFile = tempDir.resolve("main.tf");
        Files.writeString(targetFile, POLICY_MAIN_TF);

        Finding finding = new Finding("CKV_AWS_21", Finding.Severity.HIGH, "main.tf", 1,
            "aws_s3_bucket.data", "Ensure S3 bucket has versioning enabled", Finding.FindingSource.CHECKOV);
        PatchReport patchReport = new PatchReport(List.of("main.tf"), "fixes versioning/encryption but adds wide-open bucket policy",
            List.of("ISR03"), List.of());

        Verifier verifier = createVerifier();
        VerificationResult result = verifier.verify(tempDir, finding, patchReport);

        assertFalse(result.passed(), "a new CRITICAL finding must reject the patch even though fmt/validate pass");
        assertEquals(StepResult.PASS, result.format());
        assertEquals(StepResult.PASS, result.init());
        assertEquals(StepResult.PASS, result.validate());
        assertEquals(StepResult.FAIL, result.rescan());
        assertTrue(result.newCriticalOrHigh(), "a new critical/high finding should be detected");
        assertTrue(result.newFindings().stream().anyMatch(f -> f.ruleId().equals("CKV_AWS_70")),
            "the new finding should be CKV_AWS_70 (bucket policy allows any principal)");
    }
}
