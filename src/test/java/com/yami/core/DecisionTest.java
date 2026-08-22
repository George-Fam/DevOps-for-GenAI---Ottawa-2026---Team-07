package com.yami.core;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertThrows;

class DecisionTest {

    @Test
    void safeFixWithoutPatchIsRejected() {
        assertThrows(IllegalArgumentException.class, () ->
            new Decision(Decision.DecisionType.SAFE_FIX, "CLOUD-001", "reason", 0.9, null, true, false));
    }
}
