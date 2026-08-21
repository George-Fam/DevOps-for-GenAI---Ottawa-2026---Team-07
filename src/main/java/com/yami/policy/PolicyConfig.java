package com.yami.policy;

import com.fasterxml.jackson.annotation.JsonProperty;

import java.util.List;

record PolicyConfig(
    Rule block,
    @JsonProperty("human_review") Rule humanReview,
    @JsonProperty("safe_fix") Rule safeFix
) {
    record Rule(
        List<String> severities,
        @JsonProperty("rule_ids") List<String> ruleIds
    ) {
        Rule {
            severities = severities == null ? List.of() : severities;
            ruleIds = ruleIds == null ? List.of() : ruleIds;
        }
    }
}
