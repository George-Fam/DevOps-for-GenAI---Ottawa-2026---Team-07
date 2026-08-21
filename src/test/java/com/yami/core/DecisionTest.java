package com.yami.core;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertThrows;

class DecisionTest {

    @Test
    void safeFixWithoutPatchIsRejected() {
        assertThrows(IllegalArgumentException.class, () ->
            new Decision(Decision.Outcome.SAFE_FIX, "hash", null, "rationale", false));
    }
}
