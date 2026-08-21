package com.yami.judge;

import com.yami.core.Decision;
import com.yami.core.RiskContextPacket;

public class Judge {

    private final BedrockClient bedrockClient;

    public Judge(BedrockClient bedrockClient) {
        this.bedrockClient = bedrockClient;
    }

    public Decision decide(RiskContextPacket packet) {
        return bedrockClient.invoke(packet);
    }
}
