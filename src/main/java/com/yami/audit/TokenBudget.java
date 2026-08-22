package com.yami.audit;

/**
 * Compteur cumulé de tokens par run. Hard cap → KillSwitch.
 * Le harness lit l'API OpenCode / les exports de session pour
 * incrémenter ce compteur après chaque invocation agent.
 */
public class TokenBudget {

    private final long hardCap;
    private long consumed;

    public TokenBudget(long hardCap) {
        if (hardCap <= 0) {
            throw new IllegalArgumentException("hardCap must be positive");
        }
        this.hardCap = hardCap;
        this.consumed = 0;
    }

    public synchronized void consume(long tokens) {
        this.consumed += tokens;
        if (this.consumed > hardCap) {
            KillSwitch.trigger("token budget exceeded: " + consumed + " > " + hardCap);
        }
    }

    public synchronized long consumed() {
        return consumed;
    }

    public synchronized long remaining() {
        return Math.max(0, hardCap - consumed);
    }

    public synchronized boolean isExhausted() {
        return consumed >= hardCap;
    }
}
