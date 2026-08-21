package com.yami.judge;

import com.yami.core.Decision;
import com.yami.core.RiskContextPacket;
import software.amazon.awssdk.services.bedrockruntime.BedrockRuntimeClient;

public class LiveBedrockClient implements BedrockClient {

    private final BedrockRuntimeClient client;

    public LiveBedrockClient(BedrockRuntimeClient client) {
        this.client = client;
    }

    @Override
    public Decision invoke(RiskContextPacket packet) {
        throw new UnsupportedOperationException(
            "single outbound call: send packet, get back schema-valid Decision JSON via jackson, "
            + "cross-check resourceAddress against the packet");
    }
}
