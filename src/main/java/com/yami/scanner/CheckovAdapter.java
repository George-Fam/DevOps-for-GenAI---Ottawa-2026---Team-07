package com.yami.scanner;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.yami.core.Finding;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * Runs checkov as a subprocess and normalizes its JSON output into {@link Finding}
 * records. Open-source checkov leaves {@code severity} null on most community checks
 * (it's a Prisma Cloud platform feature), so severity here comes from a local override
 * map keyed by check_id, defaulting to MEDIUM. Extend SEVERITY_OVERRIDES as fixtures
 * surface checks that need a different tier.
 */
public class CheckovAdapter {

    private static final Map<String, Finding.Severity> SEVERITY_OVERRIDES = Map.of(
        "CKV_AWS_21", Finding.Severity.HIGH,    // versioning
        "CKV_AWS_145", Finding.Severity.HIGH,   // KMS encryption
        "CKV_AWS_18", Finding.Severity.MEDIUM,  // access logging
        "CKV_AWS_144", Finding.Severity.LOW,    // cross-region replication
        "CKV2_AWS_61", Finding.Severity.LOW,    // lifecycle configuration
        "CKV2_AWS_62", Finding.Severity.LOW     // event notifications
    );

    private final ObjectMapper mapper = new ObjectMapper();

    public List<Finding> scan(Path terraformDir) {
        ProcessBuilder pb = new ProcessBuilder(
            "checkov", "-d", terraformDir.toString(), "--output", "json", "--quiet", "--compact")
            .redirectErrorStream(false);

        Process process;
        try {
            process = pb.start();
        } catch (IOException e) {
            throw new UncheckedIOException("failed to start checkov - is it on PATH?", e);
        }

        String stdout;
        String stderr;
        try {
            stdout = new String(process.getInputStream().readAllBytes());
            stderr = new String(process.getErrorStream().readAllBytes());
            process.waitFor();
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new RuntimeException(e);
        }

        // exit code 1 means "scan ran, findings failed" - normal. anything else with no
        // parseable JSON on stdout means checkov itself errored out.
        if (stdout.isBlank()) {
            throw new IllegalStateException("checkov produced no output; stderr: " + stderr);
        }

        JsonNode root;
        try {
            root = mapper.readTree(stdout);
        } catch (IOException e) {
            throw new IllegalStateException("checkov output was not valid JSON; stderr: " + stderr, e);
        }

        List<Finding> findings = new ArrayList<>();
        for (JsonNode scanResult : resultsArray(root)) {
            JsonNode failedChecks = scanResult.path("results").path("failed_checks");
            for (JsonNode check : failedChecks) {
                findings.add(toFinding(check));
            }
        }
        return findings;
    }

    private static Iterable<JsonNode> resultsArray(JsonNode root) {
        // checkov emits a single object when scanning one framework, an array when scanning
        // multiple (e.g. terraform + dockerfile in the same run)
        return root.isArray() ? root : List.of(root);
    }

    private Finding toFinding(JsonNode check) {
        String checkId = check.path("check_id").asText();
        String resourceAddress = check.path("resource").asText();
        String filePath = check.path("file_path").asText();
        JsonNode range = check.path("file_line_range");
        int startLine = range.isArray() && range.size() > 0 ? range.get(0).asInt() : -1;
        int endLine = range.isArray() && range.size() > 1 ? range.get(1).asInt() : -1;
        String description = check.path("check_name").asText();

        Finding.Severity severity = SEVERITY_OVERRIDES.getOrDefault(checkId, Finding.Severity.MEDIUM);

        return new Finding(checkId, Finding.Source.CHECKOV, checkId, severity,
            resourceAddress, filePath, startLine, endLine, description);
    }
}
