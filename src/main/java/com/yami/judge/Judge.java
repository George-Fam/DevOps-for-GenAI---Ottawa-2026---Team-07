package com.yami.judge;

import com.yami.core.Decision;
import com.yami.core.RiskContextPacket;
import com.yami.policy.PolicyEngine;

/**
 * Applies deterministic policy thresholds first: PolicyEngine decides whether SAFE_FIX is
 * even possible for this rule category. If policy already says BLOCK or HUMAN_REVIEW,
 * that's final - no Bedrock call is spent asking for a patch policy won't allow anyway.
 * Only when policy allows SAFE_FIX does Bedrock get asked for the actual patch content
 * (and it can still decide not to, given the fuller context in the packet).
 */
public class Judge {

    private final PolicyEngine policyEngine;
    private final BedrockClient bedrockClient;

    public Judge(PolicyEngine policyEngine, BedrockClient bedrockClient) {
        this.policyEngine = policyEngine;
        this.bedrockClient = bedrockClient;
    }

    public Decision decide(RiskContextPacket packet, String ruleId, String resourceAddress) {
        Decision.DecisionType policyDecision = policyEngine.classify(ruleId, packet, resourceAddress);
        if (policyDecision != Decision.DecisionType.SAFE_FIX) {
            return new Decision(policyDecision, ruleId, "policy default for " + ruleId, null, null, false, false);
        }
        return bedrockClient.invoke(packet, ruleId, resourceAddress);
    }
}
