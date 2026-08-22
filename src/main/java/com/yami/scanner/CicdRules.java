package com.yami.scanner;

import com.bertramlabs.plugins.hcl4j.HCLParser;
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
 * Hand-written checks for pipeline/state-level risk that Checkov's resource-attribute
 * scanning doesn't cover: does this config behave safely when applied repeatedly and
 * concurrently by CI, not just "is this one resource configured securely."
 *
 * <p>Same hcl4j evaluated-Map approach as {@link com.yami.investigator.Investigator} -
 * these checks read attribute values, not source positions, so findings here don't carry
 * line ranges (unlike CheckovAdapter's, which come from checkov's own line tracking).
 */
public class CicdRules {

    private static final List<String> IDENTITY_ATTRIBUTE_NAMES = List.of("bucket", "name");
    private static final List<String> INTERPOLATION_MARKERS = List.of("var.", "local.", "terraform.workspace", "data.", "random_");

    public List<Finding> evaluate(Path terraformDir) {
        return evaluate(parseAll(terraformDir));
    }

    List<Finding> evaluate(Map<String, Object> parsed) {
        List<Finding> findings = new ArrayList<>();
        Map<String, Object> terraformBlock = asMap(parsed.get("terraform"));
        Map<String, Object> resourceSection = asMap(parsed.get("resource"));

        checkRemoteBackend(terraformBlock, findings);
        checkProviderVersionPinning(terraformBlock, findings);
        checkHardcodedIdentityAttributes(resourceSection, findings);

        return findings;
    }

    private void checkRemoteBackend(Map<String, Object> terraformBlock, List<Finding> findings) {
        if (!asMap(terraformBlock.get("backend")).keySet().stream().findAny().isPresent()) {
            findings.add(new Finding(
                "YAMI_CICD_1", Finding.Source.CICD_RULE, "YAMI_CICD_1", Finding.Severity.HIGH,
                "terraform.backend", "", -1, -1,
                "No remote backend configured - state will be written to the CI runner's local "
                + "disk and lost (or corrupted by a concurrent run) between pipeline executions"));
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
                    "YAMI_CICD_2:" + provider, Finding.Source.CICD_RULE, "YAMI_CICD_2", Finding.Severity.MEDIUM,
                    "terraform.required_providers." + provider, "", -1, -1,
                    "Provider \"" + provider + "\" has no pinned version constraint - CI runs are "
                    + "not reproducible across time, a provider release can change plan/apply "
                    + "behavior without any change to this repo"));
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
                            "YAMI_CICD_3:" + type + "." + name + "." + identityAttr,
                            Finding.Source.CICD_RULE, "YAMI_CICD_3", Finding.Severity.MEDIUM,
                            type + "." + name, "", -1, -1,
                            "\"" + identityAttr + "\" on " + type + "." + name + " is a hardcoded "
                            + "literal (\"" + value + "\") with no environment/workspace "
                            + "differentiation - running this config through more than one CI "
                            + "stage (dev/staging/prod) will collide on the same name"));
                    }
                }
            }
        }
    }

    private static boolean isHardcodedLiteral(Object value) {
        if (!(value instanceof String s) || s.isBlank()) {
            return false;
        }
        return INTERPOLATION_MARKERS.stream().noneMatch(s::contains);
    }

    private Map<String, Object> parseAll(Path terraformDir) {
        Map<String, Object> mergedResources = new LinkedHashMap<>();
        Map<String, Object> mergedTerraform = new LinkedHashMap<>();

        List<Path> tfFiles;
        try (Stream<Path> paths = Files.list(terraformDir)) {
            tfFiles = paths.filter(p -> p.toString().endsWith(".tf")).sorted().collect(Collectors.toList());
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
}
