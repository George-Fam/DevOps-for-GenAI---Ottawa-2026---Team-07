package com.yami.core;

public record Decision(
    Outcome outcome,
    String packetHash,
    ProposedPatch proposedPatch,
    String rationale,
    boolean fallbackMode
) {
    public enum Outcome { SAFE_FIX, HUMAN_REVIEW, BLOCK }

    public Decision {
        if (outcome == Outcome.SAFE_FIX && proposedPatch == null) {
            throw new IllegalArgumentException("SAFE_FIX requires a ProposedPatch");
        }
    }
}
