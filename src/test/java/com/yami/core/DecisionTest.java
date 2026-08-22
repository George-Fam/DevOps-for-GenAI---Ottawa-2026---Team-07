package com.yami.core;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertThrows;

class DecisionTest {

    @Test
    void confidenceOutOfRangeIsRejected() {
        assertThrows(IllegalArgumentException.class, () ->
            new Decision(Decision.DecisionType.SAFE_FIX, "aws_s3_bucket.data", "intent", "reason", 1.5, List.of(), false));
    }

    @Test
    void negativeConfidenceIsRejected() {
        assertThrows(IllegalArgumentException.class, () ->
            new Decision(Decision.DecisionType.SAFE_FIX, "aws_s3_bucket.data", "intent", "reason", -0.1, List.of(), false));
    }

    @Test
    void validDecisionAccepted() {
        new Decision(Decision.DecisionType.SAFE_FIX, "aws_s3_bucket.data", "intent", "reason", 0.92, List.of("owasp-iac-security"), false);
    }
}
