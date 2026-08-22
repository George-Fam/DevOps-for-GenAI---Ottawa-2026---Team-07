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
        // buildArgs always pointed trivy at repoRoot regardless of scanPaths, so a
        // scope/YAMI_SCOPE override never actually restricted what trivy scanned - it
        // silently scanned the whole repo (all fixtures, root Dockerfile, etc.) every
        // time. Mirrors CheckovAdapter's fix: when scanPaths cleanly resolves to real
        // subdirectories, run one scan per subdirectory instead of always scanning
        // repoRoot; otherwise (the default policy's mixed glob/bare-filename scope)
        // fall back to a single full-repo scan, unchanged from today's behavior.
        List<Path> scanDirs = ScopeResolver.resolveScanDirectories(repoRoot, scanPaths);
        if (scanDirs.isEmpty()) {
            return runTrivy(repoRoot, repoRoot, excludePaths);
        }
        List<Finding> findings = new ArrayList<>();
        for (Path dir : scanDirs) {
            findings.addAll(runTrivy(repoRoot, dir, excludePaths));
        }
        return findings;
    }

    private List<Finding> runTrivy(Path repoRoot, Path scanDir, List<String> excludePaths) {
        List<String> args = buildArgs(scanDir, excludePaths);

        // Fixed argv list built from args.add(...) calls, never a shell string.
        // nosemgrep: java.lang.security.audit.command-injection-process-builder.command-injection-process-builder
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

        // Scanning a subdirectory directly (rather than repoRoot) makes trivy report
        // Target relative to that subdirectory (e.g. "main.tf", or "." for the
        // directory itself) - re-anchor it to repoRoot so Harness can locate the file.
        String prefix = repoRoot.relativize(scanDir).toString();

        List<Finding> findings = new ArrayList<>();
        for (JsonNode result : root.path("Results")) {
            String target = toRepoRelative(prefix, result.path("Target").asText());
            for (JsonNode vuln : result.path("Vulnerabilities")) {
                findings.add(toVulnFinding(vuln, target));
            }
            for (JsonNode misconf : result.path("Misconfigurations")) {
                findings.add(toMisconfigFinding(misconf, target));
            }
        }
        return findings;
    }

    private static String toRepoRelative(String prefixDir, String target) {
        if (prefixDir.isEmpty()) {
            return target;
        }
        if (target.equals(".")) {
            return prefixDir;
        }
        return prefixDir + "/" + target;
    }

    /**
     * Builds the fixed argv list for the trivy subprocess.
     *
     * <p>Uses {@code --offline-scan} so Trivy resolves Java dependencies from the
     * local Maven repository only, never via live Maven Central requests. The action
     * container has no access to the host runner's {@code ~/.m2}, and live resolution
     * gets rate-limited (HTTP 429), which used to crash the scan (issue #8).
     *
     * <p><strong>Assumption:</strong> {@code ~/.m2/repository} is baked into the image
     * at build time ({@code make package} exports it to {@code target/m2-repo}, the
     * Dockerfile COPYs it to {@code /root/.m2/repository}). This holds for Yami
     * self-scanning because {@code mvn package} runs before the Docker build, so the
     * cache always contains the current PR's dependencies.
     *
     * <p><strong>Trade-off:</strong> a dependency missing from the baked cache is
     * silently skipped rather than fetched. Third-party reuse of this action on a
     * repository without a warm cache may under-report vulnerabilities; in that case
     * drop {@code --offline-scan} or provide a warm {@code ~/.m2}.
     */
    List<String> buildArgs(Path repoRoot, List<String> excludePaths) {
        List<String> args = new ArrayList<>();
        args.add("trivy");
        args.add("fs");
        args.add("--scanners");
        args.add("vuln,misconfig");
        args.add("--offline-scan");
        for (String p : excludePaths) {
            args.add("--skip-dirs");
            args.add(p);
        }
        args.add("--format");
        args.add("json");
        args.add("--quiet");
        args.add(repoRoot.toString());
        return args;
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
