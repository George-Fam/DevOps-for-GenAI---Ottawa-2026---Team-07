package com.yami.verifier;

import com.yami.core.ProposedPatch;
import com.yami.core.VerificationResult;
import com.yami.scanner.CheckovAdapter;
import org.junit.jupiter.api.Test;

import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * End-to-end against the real fixture, real terraform, and real checkov - the whole
 * Verifier contract in one pass, both the pass branch and a rejection branch.
 */
class VerifierTest {

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

    private final Verifier verifier = new Verifier(new HclBlockReplacer(), new CheckovAdapter());

    @Test
    void goodPatchPassesAndReducesFindings() {
        ProposedPatch patch = new ProposedPatch("aws_s3_bucket.data", FIXED_BLOCK, "adds versioning + KMS encryption");

        VerificationResult result = verifier.verify(Path.of("fixtures/safe-fix"), Path.of("main.tf"), patch);

        assertTrue(result.passed(), () -> "expected pass, got: " + result.rejectionReason());
        assertTrue(result.formatValid());
        assertTrue(result.validateValid());
        assertTrue(result.findingsAfter().size() < result.findingsBefore().size());
        assertTrue(result.findingsAfter().stream().noneMatch(f -> f.ruleId().equals("CKV_AWS_21")));
    }

    @Test
    void patchForMissingResourceIsRejectedNotThrown() {
        ProposedPatch patch = new ProposedPatch("aws_s3_bucket.does_not_exist", FIXED_BLOCK, "hallucinated resource");

        VerificationResult result = verifier.verify(Path.of("fixtures/safe-fix"), Path.of("main.tf"), patch);

        assertFalse(result.passed());
        assertEquals(false, result.formatValid());
        assertTrue(result.rejectionReason().contains("block replacement failed"));
    }
}
