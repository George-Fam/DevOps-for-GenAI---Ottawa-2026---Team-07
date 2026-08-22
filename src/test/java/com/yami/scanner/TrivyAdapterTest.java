package com.yami.scanner;

import com.yami.core.Finding;
import org.junit.jupiter.api.Test;

import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/**
 * Unit test for TrivyAdapter argv construction (no subprocess needed), plus an
 * integration smoke test that shells out to a real trivy binary - requires
 * trivy on PATH, skipped otherwise.
 */
class TrivyAdapterTest {

    @Test
    void offlineScanFlagIsPresentInArgs() {
        List<String> args = new TrivyAdapter().buildArgs(Path.of("/tmp/fake-repo"), List.of());

        assertTrue(args.contains("--offline-scan"),
            "trivy must run with --offline-scan to avoid live Maven Central resolution (issue #8)");
    }

    @Test
    void scansRealRepoAndParsesFindings() {
        assumeTrue(hasTool("trivy"), "trivy not on PATH — skipping integration test");

        List<Finding> findings = new TrivyAdapter().scan(Path.of("."));

        // Only assert the offline scan completes and parses; exact finding counts
        // vary with dependency versions, so asserting specific CVEs would be brittle.
        assertNotNull(findings, "findings list must not be null");
    }

    private static boolean hasTool(String tool) {
        try {
            Process p = new ProcessBuilder(tool, "--version").start();
            return p.waitFor() == 0;
        } catch (Exception e) {
            return false;
        }
    }
}
