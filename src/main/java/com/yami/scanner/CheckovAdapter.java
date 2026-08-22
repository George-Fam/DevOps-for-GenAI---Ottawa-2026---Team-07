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
        "CKV_AWS_21", Finding.Severity.HIGH,      // versioning
        "CKV_AWS_145", Finding.Severity.HIGH,     // KMS encryption
        "CKV_AWS_18", Finding.Severity.MEDIUM,    // access logging
        "CKV_AWS_144", Finding.Severity.LOW,      // cross-region replication
        "CKV2_AWS_61", Finding.Severity.LOW,      // lifecycle configuration
        "CKV2_AWS_62", Finding.Severity.LOW,      // event notifications
        "CKV_AWS_70", Finding.Severity.CRITICAL   // bucket policy allows any principal
    );

    private final ObjectMapper mapper = new ObjectMapper();

    public List<Finding> scan(Path terraformDir) {
        return scan(terraformDir, List.of(), List.of());
    }

    public List<Finding> scan(Path terraformDir, List<String> scanPaths, List<String> excludePaths) {
        // -d/--directory and -f/--file are mutually exclusive in checkov, and --file
        // takes literal file paths, not glob patterns - passing both (as this used to)
        // makes checkov log "could not process file" for every --file entry and silently
        // fall back to scanning the whole -d tree, ignoring scanPaths entirely. --file
        // mode is also unreliable across checkov versions on its own (checkov 3.2.50,
        // the version this project's Dockerfile pins, reports resource_count: 0 for a
        // standalone .tf file passed via -f - it can't establish module context outside
        // a real directory scan). -d is the only path proven reliable (it's what every
        // other scan in this codebase already uses), so when scanPaths cleanly resolves
        // to real subdirectories, run one -d scan per subdirectory instead of touching
        // --file at all. Anything that doesn't resolve (globs, bare filenames like
        // "Dockerfile"/"pom.xml") falls back to a single full -d scan from terraformDir -
        // exactly today's behavior for the default policy scope.
        List<Path> scanDirs = ScopeResolver.resolveScanDirectories(terraformDir, scanPaths);
        if (scanDirs.isEmpty()) {
            return runCheckov(terraformDir, excludePaths);
        }
        List<Finding> findings = new ArrayList<>();
        for (Path dir : scanDirs) {
            findings.addAll(runCheckov(dir, excludePaths));
        }
        return findings;
    }

    private List<Finding> runCheckov(Path directory, List<String> excludePaths) {
        List<String> args = new ArrayList<>();
        args.add("checkov");
        args.add("-d");
        args.add(directory.toString());
        for (String p : excludePaths) {
            args.add("--skip-path");
            // checkov compiles --skip-path values as Python regexes, but the policy
            // file expresses them as globs - "**" is an invalid regex ("multiple
            // repeat") and crashes any checkov runner that compiles exclusions
            // (bicep, terraform_json, secrets, base_runner).
            args.add(p.replace("**", ".*"));
        }
        args.add("--output");
        args.add("json");
        args.add("--quiet");
        args.add("--compact");

        // Fixed argv list built from args.add(...) calls, never a shell string - no shell
        // interpretation happens, so no metacharacter injection vector.
        // nosemgrep: java.lang.security.audit.command-injection-process-builder.command-injection-process-builder
        ProcessBuilder pb = new ProcessBuilder(args)
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
        // file_path is relative to whatever root checkov scanned from - in -f mode that's
        // each file's own directory (e.g. just "/main.tf", losing the repo-relative
        // prefix). repo_file_path is always repo-root-relative regardless of -d vs -f,
        // so prefer it; file_path is only a fallback for older checkov output shapes.
        JsonNode repoFilePath = check.path("repo_file_path");
        String filePath = repoFilePath.isMissingNode() || repoFilePath.asText().isBlank()
            ? check.path("file_path").asText()
            : repoFilePath.asText();
        // checkov emits file_path with a leading '/' even though it is relative to
        // the scanned dir (e.g. "/fixtures/safe_fix/main.tf"). Path.resolve() treats
        // a leading slash as absolute and escapes repoDir - normalize to repo-relative.
        if (filePath.startsWith("/")) {
            filePath = filePath.substring(1);
        }
        JsonNode range = check.path("file_line_range");
        int line = range.isArray() && range.size() > 0 ? range.get(0).asInt() : -1;
        String description = check.path("check_name").asText();

        Finding.Severity severity = SEVERITY_OVERRIDES.getOrDefault(checkId, Finding.Severity.MEDIUM);

        return new Finding(checkId, severity, filePath, line, resourceAddress, description, Finding.FindingSource.CHECKOV);
    }
}
