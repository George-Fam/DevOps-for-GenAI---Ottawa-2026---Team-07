package com.yami.judge;

import com.yami.core.Decision;
import com.yami.core.RiskContextPacket;

public class DegradedPatchGenerator implements BedrockClient {

    @Override
    public Decision invoke(RiskContextPacket packet) {
        throw new UnsupportedOperationException(
            "20:00 pivot fallback: fill a fixed template (e.g. S3 block) instead of calling an LLM. "
            + "must produce the same ProposedPatch contract as LiveBedrockClient. set fallbackMode=true");
    }
}
