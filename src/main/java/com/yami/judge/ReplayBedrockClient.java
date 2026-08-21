package com.yami.judge;

import com.yami.core.Decision;
import com.yami.core.RiskContextPacket;

import java.nio.file.Path;

public class ReplayBedrockClient implements BedrockClient {

    public ReplayBedrockClient(Path fixtureDir) {
        throw new UnsupportedOperationException("keyed by packetHash, load a frozen Decision fixture instead of calling Bedrock");
    }

    @Override
    public Decision invoke(RiskContextPacket packet) {
        throw new UnsupportedOperationException("look up packet.packetHash() in the frozen fixture set");
    }
}
