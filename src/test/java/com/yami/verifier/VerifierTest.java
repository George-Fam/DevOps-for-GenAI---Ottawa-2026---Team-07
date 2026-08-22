package com.yami.verifier;

import com.yami.core.Finding;
import com.yami.core.ProposedPatch;
import com.yami.core.VerificationResult;
import com.yami.core.VerificationResult.StepResult;
import com.yami.scanner.CheckovAdapter;
import org.junit.jupiter.api.Test;

import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * End-to-end against the real fixture, real terraform, and real checkov - the whole
 * Verifier contract in one pass: the happy path, and the tech plan §13 "core V4 test"
 * list of patch-rejection scenarios (missing resource, invalid syntax, new critical
 * finding introduced) - the one the product plan calls "the one worth demonstrating live."
 */
class VerifierTest {

    private static final Path FIXTURE = Path.of("fixtures/safe_fix");

    private static final String GOOD_BLOCK = """
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

    private static final String SYNTACTICALLY_INVALID_BLOCK = """
        resource "aws_s3_bucket" "data" {
          bucket = "yami-demo-bucket"
        """; // missing closing brace - fmt should reject this outright

    private static final String INTRODUCES_NEW_CRITICAL_FINDING_BLOCK = """
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
        }""";

    private final Verifier verifier = new Verifier(new HclBlockReplacer(), new CheckovAdapter());

    @Test
    void goodPatchPassesAndReducesFindings() {
        // scoped to what GOOD_BLOCK actually fixes (versioning + encryption) - the fixture's
        // other findings on this resource (logging, replication, lifecycle, notifications)
        // are real and untouched by this patch, so they must not be part of "the original
        // finding" this particular decision is checked against
        List<Finding> originalFindings = new CheckovAdapter().scan(FIXTURE).stream()
            .filter(f -> f.ruleId().equals("CKV_AWS_21") || f.ruleId().equals("CKV_AWS_145"))
            .toList();
        ProposedPatch patch = new ProposedPatch("aws_s3_bucket.data", GOOD_BLOCK, "adds versioning + KMS encryption");

        VerificationResult result = verifier.verify(FIXTURE, Path.of("main.tf"), patch, originalFindings);

        assertTrue(result.passed(), () -> "expected pass, got fmt=" + result.terraformFormat()
            + " validate=" + result.terraformValidate() + " rescan=" + result.rescan());
        assertEquals(StepResult.PASS, result.terraformFormat());
        assertEquals(StepResult.PASS, result.terraformValidate());
        assertEquals(StepResult.PASS, result.rescan());
        assertTrue(result.newFindings().isEmpty());
    }

    @Test
    void patchForMissingResourceIsRejectedNotThrown() {
        ProposedPatch patch = new ProposedPatch("aws_s3_bucket.does_not_exist", GOOD_BLOCK, "hallucinated resource");

        VerificationResult result = verifier.verify(FIXTURE, Path.of("main.tf"), patch, List.of());

        assertFalse(result.passed());
        assertEquals(StepResult.NOT_RUN, result.terraformFormat());
        assertEquals(StepResult.NOT_RUN, result.terraformValidate());
    }

    @Test
    void syntacticallyInvalidBlockFailsFormatAndIsRejected() {
        ProposedPatch patch = new ProposedPatch("aws_s3_bucket.data", SYNTACTICALLY_INVALID_BLOCK, "broken block");

        VerificationResult result = verifier.verify(FIXTURE, Path.of("main.tf"), patch, List.of());

        assertFalse(result.passed());
        assertEquals(StepResult.FAIL, result.terraformFormat());
        assertEquals(StepResult.NOT_RUN, result.terraformValidate(), "validate never runs after fmt fails");
    }

    @Test
    void patchIntroducingNewCriticalFindingIsRejectedEvenThoughOriginalFindingIsFixed() {
        List<Finding> originalFindings = new CheckovAdapter().scan(FIXTURE).stream()
            .filter(f -> f.ruleId().equals("CKV_AWS_21") || f.ruleId().equals("CKV_AWS_145"))
            .toList();
        ProposedPatch patch = new ProposedPatch("aws_s3_bucket.data",
            INTRODUCES_NEW_CRITICAL_FINDING_BLOCK, "fixes versioning/encryption but adds a wide-open bucket policy");

        VerificationResult result = verifier.verify(FIXTURE, Path.of("main.tf"), patch, originalFindings);

        assertFalse(result.passed(), "a new CRITICAL finding must reject the patch even though fmt/validate pass");
        assertEquals(StepResult.PASS, result.terraformFormat());
        assertEquals(StepResult.PASS, result.terraformValidate());
        assertEquals(StepResult.FAIL, result.rescan());
        assertTrue(result.newFindings().stream().anyMatch(f -> f.ruleId().equals("CKV_AWS_70")));
    }
}
