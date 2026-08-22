package com.yami.investigator;

import com.bertramlabs.plugins.hcl4j.HCLParser;
import com.yami.core.Finding;
import com.yami.core.RiskContextPacket;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.stream.Collectors;
import java.util.stream.Stream;

/**
 * Read-only, no network call. Builds one {@link RiskContextPacket} for the whole PR -
 * every finding across every resource - so Judge reasons over the full change, not one
 * resource in isolation. Never emits raw HCL text: {@code known}/{@code unknown} are
 * extracted facts, not source content.
 *
 * <p>{@code changedFiles} and {@code beforeAfter} are PR-diff concerns Investigator can't
 * derive from a plain directory scan on its own (no git history here) - they're accepted
 * as input, computed by whatever reads the GitHub event/diff. Everything else
 * (terraformRelations, deployingWorkflows, known/unknown facts, packetHash) is computed
 * here from static analysis of the resulting tree.
 */
public class Investigator {

    private static final Map<String, String> EXPECTED_SIBLING_FOR_RULE = Map.ofEntries(
        Map.entry("CKV_AWS_21", "aws_s3_bucket_versioning"),
        Map.entry("CKV_AWS_145", "aws_s3_bucket_server_side_encryption_configuration"),
        Map.entry("CKV_AWS_18", "aws_s3_bucket_logging"),
        Map.entry("CKV2_AWS_61", "aws_s3_bucket_lifecycle_configuration"),
        Map.entry("CKV2_AWS_62", "aws_s3_bucket_notification"),
        Map.entry("CKV_AWS_144", "aws_s3_bucket_replication_configuration")
    );

    private static final List<String> ALLOWED_ACTIONS = List.of(
        "open_remediation_pr", "escalate_human_review", "block");

    public RiskContextPacket buildPacket(
            Path repoRoot,
            Path terraformDir,
            List<Finding> findings,
            List<String> changedFiles,
            Map<String, String> beforeAfter,
            String policyVersion) {

        Map<String, Object> resourceSection = asMap(parseAll(terraformDir).get("resource"));

        List<String> terraformRelations = findAllRelations(resourceSection);
        List<String> deployingWorkflows = findDeployingWorkflows(repoRoot);
        List<String> known = new ArrayList<>();
        List<String> unknown = new ArrayList<>();
        buildKnownAndUnknown(resourceSection, findings, known, unknown);

        String packetHash = hash(findings, changedFiles, beforeAfter, terraformRelations,
            deployingWorkflows, known, unknown, policyVersion);

        return new RiskContextPacket(packetHash, findings, changedFiles, beforeAfter,
            terraformRelations, deployingWorkflows, known, unknown, ALLOWED_ACTIONS, policyVersion);
    }

    private void buildKnownAndUnknown(Map<String, Object> resourceSection, List<Finding> findings,
                                       List<String> known, List<String> unknown) {
        Set<String> resourceAddresses = findings.stream().map(Finding::resource).collect(Collectors.toCollection(LinkedHashSet::new));

        for (String resourceAddress : resourceAddresses) {
            int dot = resourceAddress.indexOf('.');
            if (dot < 0) {
                continue;
            }
            String type = resourceAddress.substring(0, dot);
            String name = resourceAddress.substring(dot + 1);
            Map<String, Object> attrs = asMap(asMap(resourceSection.get(type)).get(name));

            for (Map.Entry<String, Object> e : attrs.entrySet()) {
                if (!(e.getValue() instanceof Map)) {
                    known.add(resourceAddress + "." + e.getKey() + " = " + e.getValue());
                }
            }

            Set<String> referencing = findReferencingResources(resourceSection, resourceAddress);
            for (String ref : referencing) {
                known.add(ref + " references " + resourceAddress);
            }

            for (Finding f : findings) {
                if (!f.resource().equals(resourceAddress)) {
                    continue;
                }
                String expectedSibling = EXPECTED_SIBLING_FOR_RULE.get(f.ruleId());
                if (expectedSibling == null) {
                    continue;
                }
                boolean siblingPresent = referencing.stream().anyMatch(r -> r.startsWith(expectedSibling + "."));
                if (!siblingPresent) {
                    unknown.add("no " + expectedSibling + " configured for " + resourceAddress
                        + " (needed to resolve " + f.ruleId() + ")");
                }
            }
        }
    }

    private List<String> findAllRelations(Map<String, Object> resourceSection) {
        List<String> relations = new ArrayList<>();
        for (Map.Entry<String, Object> typeEntry : resourceSection.entrySet()) {
            for (Map.Entry<String, Object> nameEntry : asMap(typeEntry.getValue()).entrySet()) {
                String address = typeEntry.getKey() + "." + nameEntry.getKey();
                Set<String> referencing = findReferencingResources(resourceSection, address);
                for (String ref : referencing) {
                    relations.add(ref + " -> " + address);
                }
            }
        }
        return relations;
    }

    private List<String> findDeployingWorkflows(Path repoRoot) {
        Path workflowsDir = repoRoot.resolve(".github").resolve("workflows");
        if (!Files.isDirectory(workflowsDir)) {
            return List.of();
        }
        List<String> deploying = new ArrayList<>();
        try (Stream<Path> paths = Files.list(workflowsDir)) {
            for (Path file : (Iterable<Path>) paths.filter(p -> p.toString().endsWith(".yml") || p.toString().endsWith(".yaml"))::iterator) {
                String content = Files.readString(file);
                if (content.contains("terraform apply") || content.contains("terraform plan")) {
                    deploying.add(workflowsDir.relativize(file).toString());
                }
            }
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
        return deploying;
    }

    private Map<String, Object> parseAll(Path terraformDir) {
        Map<String, Object> mergedResources = new LinkedHashMap<>();
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
            Map<String, Object> resourceSection = asMap(parsed.get("resource"));
            for (Map.Entry<String, Object> typeEntry : resourceSection.entrySet()) {
                Map<String, Object> mergedType = asMap(mergedResources.computeIfAbsent(
                    typeEntry.getKey(), k -> new LinkedHashMap<String, Object>()));
                mergedType.putAll(asMap(typeEntry.getValue()));
            }
        }

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("resource", mergedResources);
        return result;
    }

    private static Set<String> findReferencingResources(Map<String, Object> resourceSection, String resourceAddress) {
        Set<String> referencing = new TreeSet<>();
        String prefix = resourceAddress + ".";
        for (Map.Entry<String, Object> typeEntry : resourceSection.entrySet()) {
            for (Map.Entry<String, Object> nameEntry : asMap(typeEntry.getValue()).entrySet()) {
                String candidateAddress = typeEntry.getKey() + "." + nameEntry.getKey();
                if (candidateAddress.equals(resourceAddress)) {
                    continue;
                }
                if (referencesPrefix(nameEntry.getValue(), prefix)) {
                    referencing.add(candidateAddress);
                }
            }
        }
        return referencing;
    }

    private static boolean referencesPrefix(Object value, String prefix) {
        if (value instanceof Map<?, ?> m) {
            return m.values().stream().anyMatch(v -> referencesPrefix(v, prefix));
        }
        if (value instanceof List<?> l) {
            return l.stream().anyMatch(v -> referencesPrefix(v, prefix));
        }
        return value != null && String.valueOf(value).startsWith(prefix);
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> asMap(Object o) {
        return o instanceof Map ? (Map<String, Object>) o : Map.of();
    }

    private static String hash(List<Finding> findings, List<String> changedFiles, Map<String, String> beforeAfter,
                                List<String> terraformRelations, List<String> deployingWorkflows,
                                List<String> known, List<String> unknown, String policyVersion) {
        StringBuilder canonical = new StringBuilder();
        findings.stream().map(f -> f.ruleId() + ":" + f.resource()).sorted().forEach(s -> canonical.append(s).append(','));
        canonical.append('|');
        changedFiles.stream().sorted().forEach(s -> canonical.append(s).append(','));
        canonical.append('|');
        beforeAfter.entrySet().stream().sorted(Map.Entry.comparingByKey())
            .forEach(e -> canonical.append(e.getKey()).append('=').append(e.getValue()).append(','));
        canonical.append('|');
        terraformRelations.stream().sorted().forEach(s -> canonical.append(s).append(','));
        canonical.append('|');
        deployingWorkflows.stream().sorted().forEach(s -> canonical.append(s).append(','));
        canonical.append('|');
        known.stream().sorted().forEach(s -> canonical.append(s).append(','));
        canonical.append('|');
        unknown.stream().sorted().forEach(s -> canonical.append(s).append(','));
        canonical.append('|').append(policyVersion);

        try {
            byte[] digest = MessageDigest.getInstance("SHA-256").digest(canonical.toString().getBytes());
            StringBuilder hex = new StringBuilder();
            for (byte b : digest) {
                hex.append(String.format("%02x", b));
            }
            return hex.toString();
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }
}
