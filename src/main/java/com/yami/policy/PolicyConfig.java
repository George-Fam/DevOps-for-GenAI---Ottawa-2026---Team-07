package com.yami.policy;

import com.fasterxml.jackson.annotation.JsonProperty;

import java.util.List;
import java.util.Map;

record PolicyConfig(
    String version,
    Map<String, Rule> rules
) {
    record Rule(
        String description,
        @JsonProperty("default_decision") String defaultDecision,
        @JsonProperty("safe_fix_when") List<String> safeFixWhen
    ) {
        Rule {
            safeFixWhen = safeFixWhen == null ? List.of() : safeFixWhen;
        }
    }
}
