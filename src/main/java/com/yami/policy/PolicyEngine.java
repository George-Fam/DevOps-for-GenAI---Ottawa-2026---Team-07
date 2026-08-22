package com.yami.policy;

import com.yami.core.Decision;
import com.yami.core.Finding;
import com.yami.core.RiskContextPacket;

import java.util.List;

/**
 * Veto constitutionnel (Java, pré-Judge) : liste courte et figée de patterns
 * PPE+secrets / exfiltration → BLOCK immédiat. Jamais envoyé au modèle.
 *
 * <p>Ce n'est pas une heuristique par cas — c'est une liste constitutionnelle
 * de ~20 lignes. Un nouveau pattern de menace = mise à jour manuelle du veto.
 */
public class PolicyEngine {

    private static final List<String> VETO_PATTERNS = List.of(
        "pull_request_target",
        "secrets.",
        "curl | bash",
        "curl \\| bash"
    );

    public Decision veto(Finding finding) {
        String lowerMessage = finding.message().toLowerCase();
        String lowerFile = finding.file().toLowerCase();
        String combined = lowerMessage + " " + lowerFile;

        for (String pattern : VETO_PATTERNS) {
            if (combined.contains(pattern.toLowerCase())) {
                return new Decision(
                    Decision.DecisionType.BLOCK,
                    finding.resource(),
                    null,
                    "constitutional veto: pattern '" + pattern + "' matched for rule " + finding.ruleId(),
                    1.0,
                    List.of(),
                    false
                );
            }
        }
        return null; // pas de veto, le Judge décide
    }

    /** Méthode legacy v4 — gardée pour compatibilité spike-gated. */
    @Deprecated
    public String categoryFor(String findingRuleId) {
        return "UNCATEGORIZED";
    }

    /** Méthode legacy v4 — gardée pour compatibilité spike-gated. */
    @Deprecated
    public Decision.DecisionType classify(String ruleCategory, RiskContextPacket packet, String resourceAddress) {
        return Decision.DecisionType.HUMAN_REVIEW;
    }
}
