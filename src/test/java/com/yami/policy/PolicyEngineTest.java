package com.yami.policy;

import com.yami.core.Decision;
import com.yami.core.Finding;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;

class PolicyEngineTest {

    private final PolicyEngine engine = new PolicyEngine();

    @Test
    void vetoTriggersOnPullRequestTargetWithSecrets() {
        Finding finding = new Finding(
            "YAMI_CICD_4", Finding.Severity.CRITICAL,
            ".github/workflows/dangerous.yml", -1, "workflow.dangerous",
            "pull_request_target trigger with secrets in scope",
            Finding.FindingSource.CICD_RULES
        );

        Decision veto = engine.veto(finding);

        assertNotNull(veto);
        assertEquals(Decision.DecisionType.BLOCK, veto.outcome());
        assertEquals("workflow.dangerous", veto.resourceAddress());
    }

    @Test
    void vetoTriggersOnSecretsExposed() {
        Finding finding = new Finding(
            "YAMI_CICD_6", Finding.Severity.HIGH,
            ".github/workflows/leaky.yml", -1, "workflow.leaky",
            "secrets.GITHUB_TOKEN exposed in workflow step",
            Finding.FindingSource.CICD_RULES
        );

        Decision veto = engine.veto(finding);

        assertNotNull(veto);
        assertEquals(Decision.DecisionType.BLOCK, veto.outcome());
    }

    @Test
    void noVetoOnNormalFinding() {
        Finding finding = new Finding(
            "CKV_AWS_21", Finding.Severity.HIGH,
            "main.tf", 10, "aws_s3_bucket.data",
            "S3 bucket versioning not enabled",
            Finding.FindingSource.CHECKOV
        );

        Decision veto = engine.veto(finding);

        assertNull(veto);
    }

    @Test
    void legacyClassifyReturnsHumanReview() {
        assertEquals(Decision.DecisionType.HUMAN_REVIEW,
            engine.classify("CLOUD-001", null, "aws_s3_bucket.data"));
    }
}
