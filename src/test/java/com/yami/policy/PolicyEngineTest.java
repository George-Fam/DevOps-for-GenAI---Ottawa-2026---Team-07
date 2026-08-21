package com.yami.policy;

import com.yami.core.Decision;
import com.yami.core.Finding;
import com.yami.scanner.CheckovAdapter;
import org.junit.jupiter.api.Test;

import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;

class PolicyEngineTest {

    private final PolicyEngine engine = new PolicyEngine(Path.of("policies/yami.yml"));

    @Test
    void realFixtureFindingsRouteToHumanReview() {
        // the safe-fix fixture's worst finding (CKV_AWS_21, CKV_AWS_145) is HIGH under
        // the starter policy, so as-shipped it should route to human review, not
        // straight to an auto-fix - the policy file is a starting point to tune, not gospel
        List<Finding> findings = new CheckovAdapter().scan(Path.of("fixtures/safe-fix"));
        assertEquals(Decision.Outcome.HUMAN_REVIEW, engine.classify(findings));
    }

    @Test
    void criticalSeverityBlocks() {
        Finding critical = finding(Finding.Severity.CRITICAL);
        assertEquals(Decision.Outcome.BLOCK, engine.classify(List.of(critical)));
    }

    @Test
    void lowSeverityIsSafeFix() {
        Finding low = finding(Finding.Severity.LOW);
        assertEquals(Decision.Outcome.SAFE_FIX, engine.classify(List.of(low)));
    }

    private static Finding finding(Finding.Severity severity) {
        return new Finding("id", Finding.Source.CHECKOV, "RULE", severity,
            "aws_s3_bucket.data", "main.tf", 1, 3, "desc");
    }
}
