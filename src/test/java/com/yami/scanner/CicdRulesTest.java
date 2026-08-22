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
        List<Finding> findings = rules.evaluate(Path.of("fixtures/safe_fix"));

        assertTrue(findings.stream().anyMatch(f -> f.ruleId().equals("YAMI_CICD_1")),
            "fixture has no backend block configured");
        assertFalse(findings.stream().anyMatch(f -> f.ruleId().equals("YAMI_CICD_2")),
            "fixture's aws provider has a pinned version (~> 5.0)");
        assertTrue(findings.stream().anyMatch(f ->
                f.ruleId().equals("YAMI_CICD_3") && f.resource().equals("aws_s3_bucket.data")),
            "fixture's bucket name is a hardcoded literal with no env differentiation");
    }

    @Test
    void realBlockFixtureDetectsUnsafePullRequestTargetWorkflow() {
        List<Finding> findings = rules.evaluate(Path.of("fixtures/block"));

        assertTrue(findings.stream().anyMatch(f ->
                f.ruleId().equals("YAMI_CICD_4") && f.severity() == Finding.Severity.CRITICAL
                && f.resource().equals("workflow.vulnerable")),
            "pull_request_target + untrusted checkout + secrets should be CRITICAL");
    }

    @Test
    void trustedCheckoutWithSecretsIsNotFlagged() throws java.io.IOException {
        java.nio.file.Path repoRoot = java.nio.file.Files.createTempDirectory("cicd-rules-test-");
        java.nio.file.Path workflowsDir = repoRoot.resolve(".github").resolve("workflows");
        java.nio.file.Files.createDirectories(workflowsDir);
        java.nio.file.Files.writeString(workflowsDir.resolve("safe.yml"), """
            name: safe
            on:
              pull_request_target:
                types: [opened]
            jobs:
              build:
                runs-on: ubuntu-latest
                steps:
                  - uses: actions/checkout@v4
                  - run: echo "${{ secrets.DEPLOY_TOKEN }}"
            """);

        List<Finding> findings = rules.evaluate(repoRoot);

        assertFalse(findings.stream().anyMatch(f -> f.ruleId().equals("YAMI_CICD_4")),
            "default checkout (no ref override) checks out the base branch, not the PR head - not dangerous");
    }

    @Test
    void configuredBackendSuppressesTheBackendFinding() {
        List<Finding> findings = rules.evaluateTerraform(parse("""
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
        List<Finding> findings = rules.evaluateTerraform(parse("""
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
        List<Finding> findings = rules.evaluateTerraform(parse("""
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
