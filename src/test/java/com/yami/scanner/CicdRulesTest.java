package com.yami.scanner;

import com.bertramlabs.plugins.hcl4j.HCLParser;
import com.yami.core.Finding;
import org.junit.jupiter.api.Test;

import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class CicdRulesTest {

    private final CicdRules rules = new CicdRules();

    @Test
    void realFixtureHasNoBackendAndAHardcodedBucketName() {
        List<Finding> findings = rules.evaluate(Path.of("fixtures/safe-fix"));

        assertTrue(findings.stream().anyMatch(f -> f.ruleId().equals("YAMI_CICD_1")),
            "fixture has no backend block configured");
        assertFalse(findings.stream().anyMatch(f -> f.ruleId().equals("YAMI_CICD_2")),
            "fixture's aws provider has a pinned version (~> 5.0)");
        assertTrue(findings.stream().anyMatch(f ->
                f.ruleId().equals("YAMI_CICD_3") && f.resourceAddress().equals("aws_s3_bucket.data")),
            "fixture's bucket name is a hardcoded literal with no env differentiation");
    }

    @Test
    void configuredBackendSuppressesTheBackendFinding() {
        List<Finding> findings = rules.evaluate(parse("""
            terraform {
              backend "s3" {
                bucket = "my-tf-state"
                key    = "yami/terraform.tfstate"
              }
            }
            """));

        assertFalse(findings.stream().anyMatch(f -> f.ruleId().equals("YAMI_CICD_1")));
    }

    @Test
    void unpinnedProviderVersionIsFlagged() {
        List<Finding> findings = rules.evaluate(parse("""
            terraform {
              required_providers {
                aws = {
                  source  = "hashicorp/aws"
                  version = "*"
                }
              }
            }
            """));

        assertTrue(findings.stream().anyMatch(f -> f.ruleId().equals("YAMI_CICD_2")));
    }

    @Test
    void interpolatedBucketNameIsNotFlagged() {
        List<Finding> findings = rules.evaluate(parse("""
            resource "aws_s3_bucket" "data" {
              bucket = "${var.environment}-yami-demo-bucket"
            }
            """));

        assertFalse(findings.stream().anyMatch(f -> f.ruleId().equals("YAMI_CICD_3")));
    }

    private static Map<String, Object> parse(String hcl) {
        try {
            return new HCLParser().parse(hcl);
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }
}
