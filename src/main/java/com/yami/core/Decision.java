package com.yami.core;

import java.util.List;

/**
 * Contrat Judge → harness. Le Judge émet une décision structurée avec
 * l'intention de remédiation (pas le bloc complet — le Surgeon écrit le code).
 *
 * <p>Le contrat v4 {@code ProposedPatch} est abandonné : le Judge ne produit
 * plus le bloc HCL complet. Il émet {@code remediationIntent} ; le Surgeon
 * lit le fichier réel et écrit le correctif.
 */
public record Decision(
    DecisionType outcome,
    String resourceAddress,
    String remediationIntent,
    String reason,
    double confidence,
    List<String> skillsUsed,
    boolean fallbackMode
) {
    public enum DecisionType { SAFE_FIX, HUMAN_REVIEW, BLOCK }

    public Decision {
        if (outcome == null) {
            throw new IllegalArgumentException("outcome is required");
        }
        if (confidence < 0.0 || confidence > 1.0) {
            throw new IllegalArgumentException("confidence must be in [0.0, 1.0]");
        }
        if (skillsUsed == null) {
            skillsUsed = List.of();
        }
    }
}
