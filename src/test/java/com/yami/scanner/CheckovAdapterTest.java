package com.yami.scanner;

import com.yami.core.Finding;
import org.junit.jupiter.api.Test;

import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Shells out to a real checkov binary against the checked-in fixture - not mocked.
 * Requires checkov on PATH.
 */
class CheckovAdapterTest {

    @Test
    void scansRealFixtureAndFindsKnownGaps() {
        List<Finding> findings = new CheckovAdapter().scan(Path.of("fixtures/safe_fix"));

        assertTrue(findings.stream().anyMatch(f -> f.ruleId().equals("CKV_AWS_21")),
            "missing-versioning finding should be present");
        assertTrue(findings.stream().anyMatch(f -> f.ruleId().equals("CKV_AWS_145")),
            "missing-encryption finding should be present");
        assertTrue(findings.stream().allMatch(f -> f.resource().equals("aws_s3_bucket.data")),
            "all findings in this fixture should be on the same resource");
        assertTrue(findings.stream().allMatch(f -> f.source() == Finding.FindingSource.CHECKOV));

        Finding versioning = findings.stream().filter(f -> f.ruleId().equals("CKV_AWS_21")).findFirst().orElseThrow();
        assertEquals(Finding.Severity.HIGH, versioning.severity());
        assertEquals(15, versioning.line());
    }
}
