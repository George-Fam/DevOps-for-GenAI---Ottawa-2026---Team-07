package com.yami.verifier;

import com.yami.core.Finding;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class DeviationDiffTest {

    private final DeviationDiff diff = new DeviationDiff();

    @Test
    void detectsIntersectionWithFindingLocation(@TempDir Path tempDir) throws Exception {
        Path before = tempDir.resolve("before.tf");
        Path after = tempDir.resolve("after.tf");

        Files.writeString(before, """
            resource "aws_s3_bucket" "data" {
              bucket = "demo"
            }
            """);

        Files.writeString(after, """
            resource "aws_s3_bucket" "data" {
              bucket = "demo"
              versioning {
                enabled = true
              }
            }
            """);

        Finding finding = new Finding("CKV_AWS_21", Finding.Severity.HIGH, "main.tf", 1, "aws_s3_bucket.data", "msg", Finding.FindingSource.CHECKOV);
        DeviationDiff.DiffResult result = diff.compute(before, after, finding);

        assertTrue(result.intersectsFinding(), "change near line 1 should intersect finding at line 1");
        assertEquals(64, result.preHash().length());
        assertEquals(64, result.postHash().length());
    }

    @Test
    void noIntersectionWhenChangeIsFar(@TempDir Path tempDir) throws Exception {
        Path before = tempDir.resolve("before.tf");
        Path after = tempDir.resolve("after.tf");

        Files.writeString(before, """
            resource "aws_s3_bucket" "data" {
              bucket = "demo"
            }

            resource "aws_s3_bucket_versioning" "data" {
              bucket = aws_s3_bucket.data.id
            }
            """);

        Files.writeString(after, """
            resource "aws_s3_bucket" "data" {
              bucket = "demo"
            }

            resource "aws_s3_bucket_versioning" "data" {
              bucket = aws_s3_bucket.data.id
              versioning_configuration {
                status = "Enabled"
              }
            }
            """);

        // Finding at line 1, change at line 7-9
        Finding finding = new Finding("CKV_AWS_21", Finding.Severity.HIGH, "main.tf", 1, "aws_s3_bucket.data", "msg", Finding.FindingSource.CHECKOV);
        DeviationDiff.DiffResult result = diff.compute(before, after, finding);

        assertFalse(result.intersectsFinding(), "change at line 7+ should not intersect finding at line 1");
    }
}
