package com.yami.audit;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class TokenBudgetTest {

    @Test
    void tracksConsumption() {
        TokenBudget budget = new TokenBudget(1000);
        assertEquals(1000, budget.remaining());
        assertFalse(budget.isExhausted());

        budget.consume(300);
        assertEquals(300, budget.consumed());
        assertEquals(700, budget.remaining());
    }

    @Test
    void hardCapTriggersKillSwitch() {
        TokenBudget budget = new TokenBudget(100);
        budget.consume(50);
        assertFalse(KillSwitch.isDisabled());

        budget.consume(60); // exceeds hard cap
        assertTrue(KillSwitch.isDisabled());
    }

    @Test
    void invalidCapIsRejected() {
        assertThrows(IllegalArgumentException.class, () -> new TokenBudget(0));
        assertThrows(IllegalArgumentException.class, () -> new TokenBudget(-1));
    }
}
