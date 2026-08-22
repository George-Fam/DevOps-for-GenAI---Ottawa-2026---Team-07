package com.yami.policy;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.dataformat.yaml.YAMLFactory;
import com.yami.core.Decision;
import com.yami.core.RiskContextPacket;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;

/**
 * Rule-ID + conditional-predicate policy engine. Findings from Checkov/CicdRules carry
 * their own tool-specific ruleId (e.g. CKV_AWS_21, YAMI_CICD_4); {@link #categoryFor}
 * maps those into Yami's own rule categories (CLOUD-001, CICD-001, ...) that
 * policies/yami.yml is keyed by. This is the layer that decides *whether* SAFE_FIX is
 * even possible for a rule category - the *content* of the fix still comes from Judge.
 *
 * <p>{@code safe_fix_when} condition names are evaluated against the packet by a small
 * fixed registry in {@link #evaluateConditions} - only the two conditions named in the
 * frozen policy example are implemented (no_cloudfront_relation, no_public_site_signal).
 * An unrecognized condition name is treated as unsatisfied (safe default: can't confirm
 * safety, don't allow SAFE_FIX).
 */
public class PolicyEngine {

    private static final Map<String, String> RULE_CATEGORY = Map.ofEntries(
        Map.entry("CKV_AWS_21", "CLOUD-001"),
        Map.entry("CKV_AWS_145", "CLOUD-001"),
        Map.entry("CKV_AWS_18", "CLOUD-001"),
        Map.entry("CKV2_AWS_61", "CLOUD-001"),
        Map.entry("CKV2_AWS_62", "CLOUD-001"),
        Map.entry("CKV_AWS_144", "CLOUD-001"),
        Map.entry("YAMI_CICD_4", "CICD-001")
    );

    private final PolicyConfig config;

    public PolicyEngine(Path policyFile) {
        try {
            String yaml = Files.readString(policyFile);
            this.config = new ObjectMapper(new YAMLFactory()).readValue(yaml, PolicyConfig.class);
        } catch (IOException e) {
            throw new UncheckedIOException("failed to load policy file: " + policyFile, e);
        }
    }

    public String version() {
        return config.version();
    }

    public String categoryFor(String findingRuleId) {
        return RULE_CATEGORY.getOrDefault(findingRuleId, "UNCATEGORIZED");
    }

    public Decision.DecisionType classify(String ruleCategory, RiskContextPacket packet, String resourceAddress) {
        PolicyConfig.Rule rule = config.rules().get(ruleCategory);
        if (rule == null) {
            return Decision.DecisionType.HUMAN_REVIEW;
        }

        Decision.DecisionType defaultDecision = Decision.DecisionType.valueOf(rule.defaultDecision());
        if (rule.safeFixWhen().isEmpty()) {
            return defaultDecision;
        }

        Set<String> satisfied = evaluateConditions(packet, resourceAddress);
        boolean allSatisfied = rule.safeFixWhen().stream().allMatch(satisfied::contains);
        return allSatisfied ? Decision.DecisionType.SAFE_FIX : defaultDecision;
    }

    private Set<String> evaluateConditions(RiskContextPacket packet, String resourceAddress) {
        Set<String> satisfied = new HashSet<>();
        if (noCloudfrontRelation(packet, resourceAddress)) {
            satisfied.add("no_cloudfront_relation");
        }
        if (noPublicSiteSignal(packet, resourceAddress)) {
            satisfied.add("no_public_site_signal");
        }
        return satisfied;
    }

    private boolean noCloudfrontRelation(RiskContextPacket packet, String resourceAddress) {
        return packet.terraformRelations().stream().noneMatch(r ->
            r.contains("aws_cloudfront_distribution") && r.contains(resourceAddress));
    }

    private boolean noPublicSiteSignal(RiskContextPacket packet, String resourceAddress) {
        return packet.known().stream().noneMatch(k ->
            k.startsWith(resourceAddress) && (k.contains("website") || k.contains("index_document")));
    }
}
