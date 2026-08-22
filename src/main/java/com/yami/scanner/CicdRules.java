package com.yami.scanner;

import com.bertramlabs.plugins.hcl4j.HCLParser;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.dataformat.yaml.YAMLFactory;
import com.yami.core.Finding;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;
import java.util.stream.Stream;

/**
 * Hand-written checks Checkov's resource-attribute scanning doesn't cover: pipeline/state
 * risk (does this config behave safely when applied repeatedly and concurrently by CI) and
 * GitHub workflow-file risk (does this workflow hand a forked PR write access or secrets).
 *
 * <p>Terraform checks use the same hcl4j evaluated-Map approach as
 * {@link com.yami.investigator.Investigator} - findings from those checks don't carry line
 * ranges (unlike CheckovAdapter's, which come from checkov's own line tracking). The
 * workflow-file check (YAMI_CICD_4 / CICD-001) does carry line -1 too, for the same reason.
 */
public class CicdRules {

    private static final List<String> IDENTITY_ATTRIBUTE_NAMES = List.of("bucket", "name");
    private static final List<String> INTERPOLATION_MARKERS = List.of("var.", "local.", "terraform.workspace", "data.", "random_");

    private final ObjectMapper yamlMapper = new ObjectMapper(new YAMLFactory());

    public List<Finding> evaluate(Path repoRoot) {
        return evaluate(repoRoot, List.of(), List.of());
    }

    public List<Finding> evaluate(Path repoRoot, List<String> scanPaths, List<String> excludePaths) {
        List<Finding> findings = new ArrayList<>();

        boolean scanTerraform = scanPaths.isEmpty() || scanPaths.stream().anyMatch(p -> p.endsWith(".tf"));
        boolean scanWorkflows = scanPaths.isEmpty() || scanPaths.stream().anyMatch(p -> p.contains(".github/workflows"));

        if (scanTerraform) {
            findings.addAll(evaluateTerraform(parseAll(repoRoot, excludePaths)));
        }
        if (scanWorkflows) {
            findings.addAll(checkWorkflowFiles(repoRoot, excludePaths));
        }
        return findings;
    }

    List<Finding> evaluateTerraform(Map<String, Object> parsed) {
        List<Finding> findings = new ArrayList<>();
        Map<String, Object> terraformBlock = asMap(parsed.get("terraform"));
        Map<String, Object> resourceSection = asMap(parsed.get("resource"));

        checkRemoteBackend(terraformBlock, findings);
        checkProviderVersionPinning(terraformBlock, findings);
        checkHardcodedIdentityAttributes(resourceSection, findings);

        return findings;
    }

    private void checkRemoteBackend(Map<String, Object> terraformBlock, List<Finding> findings) {
        if (asMap(terraformBlock.get("backend")).isEmpty()) {
            findings.add(new Finding(
                "YAMI_CICD_1", Finding.Severity.HIGH, "", -1, "terraform.backend",
                "No remote backend configured - state will be written to the CI runner's local "
                + "disk and lost (or corrupted by a concurrent run) between pipeline executions",
                Finding.FindingSource.CICD_RULES));
        }
    }

    private void checkProviderVersionPinning(Map<String, Object> terraformBlock, List<Finding> findings) {
        Map<String, Object> requiredProviders = asMap(terraformBlock.get("required_providers"));
        for (Map.Entry<String, Object> entry : requiredProviders.entrySet()) {
            String provider = entry.getKey();
            Map<String, Object> providerConfig = asMap(entry.getValue());
            Object version = providerConfig.get("version");
            String versionStr = version == null ? "" : String.valueOf(version).trim();
            if (versionStr.isEmpty() || versionStr.equals("*")) {
                findings.add(new Finding(
                    "YAMI_CICD_2", Finding.Severity.MEDIUM, "", -1, "terraform.required_providers." + provider,
                    "Provider \"" + provider + "\" has no pinned version constraint - CI runs are "
                    + "not reproducible across time, a provider release can change plan/apply "
                    + "behavior without any change to this repo",
                    Finding.FindingSource.CICD_RULES));
            }
        }
    }

    private void checkHardcodedIdentityAttributes(Map<String, Object> resourceSection, List<Finding> findings) {
        for (Map.Entry<String, Object> typeEntry : resourceSection.entrySet()) {
            String type = typeEntry.getKey();
            for (Map.Entry<String, Object> nameEntry : asMap(typeEntry.getValue()).entrySet()) {
                String name = nameEntry.getKey();
                Map<String, Object> attrs = asMap(nameEntry.getValue());
                for (String identityAttr : IDENTITY_ATTRIBUTE_NAMES) {
                    Object value = attrs.get(identityAttr);
                    if (isHardcodedLiteral(value)) {
                        findings.add(new Finding(
                            "YAMI_CICD_3", Finding.Severity.MEDIUM, "", -1, type + "." + name,
                            "\"" + identityAttr + "\" on " + type + "." + name + " is a hardcoded "
                            + "literal (\"" + value + "\") with no environment/workspace "
                            + "differentiation - running this config through more than one CI "
                            + "stage (dev/staging/prod) will collide on the same name",
                            Finding.FindingSource.CICD_RULES));
                    }
                }
            }
        }
    }

    /**
     * CICD-001: pull_request_target with untrusted checkout and secrets in scope. The
     * dangerous combination is: (1) trigger is pull_request_target, which runs with the
     * base repo's permissions and secrets even for a forked PR; (2) a checkout step pins
     * ref to the PR head instead of the default base-branch checkout; (3) secrets are
     * referenced anywhere in the workflow. Together, a forked PR's code runs with the base
     * repo's secrets and write permissions.
     */
    private List<Finding> checkWorkflowFiles(Path repoRoot, List<String> excludePaths) {
        Path workflowsDir = repoRoot.resolve(".github").resolve("workflows");
        if (!Files.isDirectory(workflowsDir)) {
            return List.of();
        }

        List<Path> files;
        try (Stream<Path> paths = Files.list(workflowsDir)) {
            files = paths.filter(p -> p.toString().endsWith(".yml") || p.toString().endsWith(".yaml"))
                .filter(p -> excludePaths.stream().noneMatch(ex -> p.toString().matches(globToRegex(ex))))
                .sorted().collect(Collectors.toList());
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }

        List<Finding> findings = new ArrayList<>();
        for (Path file : files) {
            String content;
            JsonNode root;
            try {
                content = Files.readString(file);
                root = yamlMapper.readTree(content);
            } catch (IOException e) {
                throw new UncheckedIOException(e);
            }

            boolean pullRequestTarget = triggersOnPullRequestTarget(root);
            boolean untrustedCheckout = hasUntrustedCheckout(root);
            boolean usesSecrets = content.contains("secrets.");

            if (pullRequestTarget && untrustedCheckout && usesSecrets) {
                String workflowName = file.getFileName().toString().replaceFirst("\\.ya?ml$", "");
                findings.add(new Finding(
                    "YAMI_CICD_4", Finding.Severity.CRITICAL,
                    workflowsDir.relativize(file).toString(), -1, "workflow." + workflowName,
                    "pull_request_target trigger checks out untrusted PR head content while secrets "
                    + "are in scope - a forked PR can exfiltrate secrets or run arbitrary code with "
                    + "the base repo's write permissions",
                    Finding.FindingSource.CICD_RULES));
            }
        }
        return findings;
    }

    private static boolean triggersOnPullRequestTarget(JsonNode root) {
        JsonNode on = root.path("on");
        if (on.isTextual()) {
            return on.asText().equals("pull_request_target");
        }
        if (on.isArray()) {
            for (JsonNode n : on) {
                if (n.isTextual() && n.asText().equals("pull_request_target")) {
                    return true;
                }
            }
            return false;
        }
        return on.isObject() && on.has("pull_request_target");
    }

    private static boolean hasUntrustedCheckout(JsonNode root) {
        for (JsonNode job : root.path("jobs")) {
            for (JsonNode step : job.path("steps")) {
                String uses = step.path("uses").asText("");
                if (uses.contains("actions/checkout")) {
                    String ref = step.path("with").path("ref").asText("");
                    if (ref.contains("head")) {
                        return true;
                    }
                }
            }
        }
        return false;
    }

    private static boolean isHardcodedLiteral(Object value) {
        if (!(value instanceof String s) || s.isBlank()) {
            return false;
        }
        return INTERPOLATION_MARKERS.stream().noneMatch(s::contains);
    }

    private Map<String, Object> parseAll(Path terraformDir, List<String> excludePaths) {
        Map<String, Object> mergedResources = new LinkedHashMap<>();
        Map<String, Object> mergedTerraform = new LinkedHashMap<>();

        List<Path> tfFiles;
        try (Stream<Path> paths = Files.list(terraformDir)) {
            tfFiles = paths.filter(p -> p.toString().endsWith(".tf"))
                .filter(p -> excludePaths.stream().noneMatch(ex -> p.toString().matches(globToRegex(ex))))
                .sorted().collect(Collectors.toList());
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }

        for (Path file : tfFiles) {
            Map<String, Object> parsed;
            try {
                parsed = new HCLParser().parse(file.toFile());
            } catch (Exception e) {
                throw new IllegalStateException("failed to parse " + file, e);
            }

            mergeInto(mergedResources, asMap(parsed.get("resource")));
            mergeInto(mergedTerraform, asMap(parsed.get("terraform")));
        }

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("resource", mergedResources);
        result.put("terraform", mergedTerraform);
        return result;
    }

    @SuppressWarnings("unchecked")
    private static void mergeInto(Map<String, Object> target, Map<String, Object> source) {
        for (Map.Entry<String, Object> entry : source.entrySet()) {
            if (entry.getValue() instanceof Map<?, ?> nested) {
                Map<String, Object> targetNested = asMap(target.computeIfAbsent(entry.getKey(), k -> new LinkedHashMap<String, Object>()));
                mergeInto(targetNested, (Map<String, Object>) nested);
            } else {
                target.put(entry.getKey(), entry.getValue());
            }
        }
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> asMap(Object o) {
        return o instanceof Map ? (Map<String, Object>) o : Map.of();
    }

    private static String globToRegex(String glob) {
        String regex = glob.replace(".", "\\.")
            .replace("**", ".*")
            .replace("*", "[^/]*")
            .replace("?", ".");
        return regex;
    }
}
