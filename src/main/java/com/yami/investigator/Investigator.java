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
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.stream.Collectors;
import java.util.stream.Stream;

/**
 * Read-only, no network call. Builds a {@link RiskContextPacket} of extracted facts about
 * one resource - never the raw HCL text - so the Judge reasons over structured signal
 * instead of arbitrary repo content.
 *
 * <p>Uses hcl4j's evaluated Map output (not the Symbol/position API) since here we only
 * need attribute values, not a lossless round-trip - the opposite tradeoff from
 * {@link com.yami.verifier.HclBlockReplacer}, which deliberately avoids hcl4j for that
 * reason.
 *
 * <p>Because the fixtures target AWS provider v4+, S3 configuration (versioning,
 * encryption, logging, ...) lives in separate sibling resources rather than inline
 * attributes. "Unknown facts" here means: which of those sibling resources the findings
 * imply are needed, but aren't present in this file.
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

    public RiskContextPacket buildPacket(String resourceAddress, List<Finding> findings, Path terraformDir) {
        int dot = resourceAddress.indexOf('.');
        if (dot < 0) {
            throw new IllegalArgumentException("resourceAddress must be \"<type>.<name>\": " + resourceAddress);
        }
        String type = resourceAddress.substring(0, dot);
        String name = resourceAddress.substring(dot + 1);

        Map<String, Object> resourceSection = asMap(parseAll(terraformDir).get("resource"));
        Map<String, Object> typeBlock = asMap(resourceSection.get(type));
        if (typeBlock == null || !typeBlock.containsKey(name)) {
            throw new IllegalArgumentException("no resource block for \"" + resourceAddress + "\" found under " + terraformDir);
        }
        Map<String, Object> attrs = asMap(typeBlock.get(name));

        Map<String, String> knownFacts = new LinkedHashMap<>();
        for (Map.Entry<String, Object> e : attrs.entrySet()) {
            if (!(e.getValue() instanceof Map)) {
                knownFacts.put(e.getKey(), String.valueOf(e.getValue()));
            }
        }

        Set<String> referencingResources = findReferencingResources(resourceSection, resourceAddress);
        if (!referencingResources.isEmpty()) {
            knownFacts.put("referencedBy", String.join(",", new TreeSet<>(referencingResources)));
        }

        List<String> unknownFacts = new ArrayList<>();
        for (Finding f : findings) {
            String expectedSibling = EXPECTED_SIBLING_FOR_RULE.get(f.ruleId());
            if (expectedSibling == null) {
                continue;
            }
            boolean siblingPresent = referencingResources.stream().anyMatch(r -> r.startsWith(expectedSibling + "."));
            if (!siblingPresent) {
                unknownFacts.add("no " + expectedSibling + " configured for " + resourceAddress
                    + " (needed to resolve " + f.ruleId() + ")");
            }
        }

        String packetHash = hash(resourceAddress, findings, knownFacts, unknownFacts);
        return new RiskContextPacket(packetHash, resourceAddress, findings, knownFacts, unknownFacts);
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

    private static String hash(String resourceAddress, List<Finding> findings, Map<String, String> knownFacts, List<String> unknownFacts) {
        StringBuilder canonical = new StringBuilder(resourceAddress).append('|');
        findings.stream().map(Finding::id).sorted().forEach(id -> canonical.append(id).append(','));
        canonical.append('|');
        knownFacts.entrySet().stream()
            .sorted(Map.Entry.comparingByKey())
            .forEach(e -> canonical.append(e.getKey()).append('=').append(e.getValue()).append(','));
        canonical.append('|');
        unknownFacts.stream().sorted().forEach(f -> canonical.append(f).append(','));

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
