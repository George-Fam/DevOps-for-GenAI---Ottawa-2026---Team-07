package com.yami.policy;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.dataformat.yaml.YAMLFactory;
import com.yami.core.Decision;
import com.yami.core.Finding;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Set;

/**
 * Deterministic gate applied to a resource's findings before anything reaches the Judge:
 * an explicit rule_id in human_review always wins, then the worst severity present decides
 * between BLOCK / HUMAN_REVIEW / SAFE_FIX. A finding whose severity isn't listed anywhere
 * in policies/yami.yml is treated as HUMAN_REVIEW rather than silently allowed through.
 */
public class PolicyEngine {

    private final PolicyConfig config;

    public PolicyEngine(Path policyFile) {
        try {
            String yaml = Files.readString(policyFile);
            this.config = new ObjectMapper(new YAMLFactory()).readValue(yaml, PolicyConfig.class);
        } catch (IOException e) {
            throw new UncheckedIOException("failed to load policy file: " + policyFile, e);
        }
    }

    public Decision.Outcome classify(List<Finding> findings) {
        if (findings.isEmpty()) {
            throw new IllegalArgumentException("classify() requires at least one finding");
        }

        Set<String> humanReviewRuleIds = Set.copyOf(config.humanReview().ruleIds());
        if (findings.stream().anyMatch(f -> humanReviewRuleIds.contains(f.ruleId()))) {
            return Decision.Outcome.HUMAN_REVIEW;
        }

        Set<String> blockSeverities = severityNames(config.block().severities());
        Set<String> humanReviewSeverities = severityNames(config.humanReview().severities());
        Set<String> safeFixSeverities = severityNames(config.safeFix().severities());

        boolean anyBlock = findings.stream().anyMatch(f -> blockSeverities.contains(f.severity().name()));
        if (anyBlock) {
            return Decision.Outcome.BLOCK;
        }

        boolean anyHumanReview = findings.stream().anyMatch(f -> humanReviewSeverities.contains(f.severity().name()));
        if (anyHumanReview) {
            return Decision.Outcome.HUMAN_REVIEW;
        }

        boolean allSafeFix = findings.stream().allMatch(f -> safeFixSeverities.contains(f.severity().name()));
        return allSafeFix ? Decision.Outcome.SAFE_FIX : Decision.Outcome.HUMAN_REVIEW;
    }

    private static Set<String> severityNames(List<String> severities) {
        return Set.copyOf(severities);
    }
}
