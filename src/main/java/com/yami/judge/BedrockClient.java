package com.yami.judge;

import com.yami.core.Decision;
import com.yami.core.RiskContextPacket;

public interface BedrockClient {

    /**
     * @param packet the full PR-scoped packet, for context
     * @param ruleId the Yami rule category (e.g. CLOUD-001) this decision is about
     * @param resourceAddress which resource within the packet this decision is about
     */
    Decision invoke(RiskContextPacket packet, String ruleId, String resourceAddress);
}
