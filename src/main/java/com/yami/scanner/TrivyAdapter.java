package com.yami.scanner;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.yami.core.Finding;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/**
 * Runs `trivy fs` as a subprocess and normalizes its JSON output into {@link Finding}
 * records (supply chain: dependency CVEs + Dockerfile/IaC misconfigurations). Mirrors
 * {@link CheckovAdapter}'s subprocess + Jackson pattern.
 */
public class TrivyAdapter {

    private final ObjectMapper mapper = new ObjectMapper();

    public List<Finding> scan(Path repoRoot) {
        return scan(repoRoot, List.of(), List.of());
    }

    public List<Finding> scan(Path repoRoot, List<String> scanPaths, List<String> excludePaths) {
        List<String> args = new ArrayList<>();
        args.add("trivy");
        args.add("fs");
        args.add("--scanners");
        args.add("vuln,misconfig");
        for (String p : excludePaths) {
            args.add("--skip-dirs");
            args.add(p);
        }
        args.add("--format");
        args.add("json");
        args.add("--quiet");
        args.add(repoRoot.toString());

        // nosemgrep: java.lang.security.audit.command-injection-process-builder.command-injection-process-builder
        // Fixed argv list built from args.add(...) calls, never a shell string.
        ProcessBuilder pb = new ProcessBuilder(args).redirectErrorStream(false);

        Process process;
        try {
            process = pb.start();
        } catch (IOException e) {
            throw new UncheckedIOException("failed to start trivy - is it on PATH?", e);
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

        if (stdout.isBlank()) {
            throw new IllegalStateException("trivy produced no output; stderr: " + stderr);
        }

        JsonNode root;
        try {
            root = mapper.readTree(stdout);
        } catch (IOException e) {
            throw new IllegalStateException("trivy output was not valid JSON; stderr: " + stderr, e);
        }

        List<Finding> findings = new ArrayList<>();
        for (JsonNode result : root.path("Results")) {
            String target = result.path("Target").asText();
            for (JsonNode vuln : result.path("Vulnerabilities")) {
                findings.add(toVulnFinding(vuln, target));
            }
            for (JsonNode misconf : result.path("Misconfigurations")) {
                findings.add(toMisconfigFinding(misconf, target));
            }
        }
        return findings;
    }

    private Finding toVulnFinding(JsonNode vuln, String target) {
        String ruleId = vuln.path("VulnerabilityID").asText();
        String pkgName = vuln.path("PkgName").asText();
        String title = vuln.path("Title").asText(vuln.path("Description").asText(ruleId));
        Finding.Severity severity = mapSeverity(vuln.path("Severity").asText("UNKNOWN"));
        return new Finding(ruleId, severity, target, -1, pkgName, title, Finding.FindingSource.TRIVY);
    }

    private Finding toMisconfigFinding(JsonNode misconf, String target) {
        String ruleId = misconf.path("ID").asText();
        String title = misconf.path("Title").asText(ruleId);
        Finding.Severity severity = mapSeverity(misconf.path("Severity").asText("UNKNOWN"));
        JsonNode cause = misconf.path("CauseMetadata");
        int line = cause.path("StartLine").asInt(-1);
        return new Finding(ruleId, severity, target, line, target, title, Finding.FindingSource.TRIVY);
    }

    private static Finding.Severity mapSeverity(String trivySeverity) {
        return switch (trivySeverity.toUpperCase()) {
            case "CRITICAL" -> Finding.Severity.CRITICAL;
            case "HIGH" -> Finding.Severity.HIGH;
            case "MEDIUM" -> Finding.Severity.MEDIUM;
            default -> Finding.Severity.LOW;
        };
    }
}
