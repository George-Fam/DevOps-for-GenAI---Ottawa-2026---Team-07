package com.yami.core;

public record Decision(
    DecisionType decision,
    String ruleId,
    String reason,
    Double confidence,
    ProposedPatch proposedPatch,
    boolean verificationRequired,
    boolean fallbackMode
) {
    public enum DecisionType { SAFE_FIX, HUMAN_REVIEW, BLOCK }

    public Decision {
        if (decision == DecisionType.SAFE_FIX && proposedPatch == null) {
            throw new IllegalArgumentException("SAFE_FIX requires a ProposedPatch");
        }
    }
}
