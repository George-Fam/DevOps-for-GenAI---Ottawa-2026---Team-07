package com.yami.judge;

import com.yami.core.Decision;
import com.yami.core.RiskContextPacket;

public interface BedrockClient {

    Decision invoke(RiskContextPacket packet);
}
