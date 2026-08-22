package com.yami.judge;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.yami.core.Decision;
import com.yami.core.RiskContextPacket;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * Loads a frozen {@link Decision} fixture instead of calling Bedrock, keyed by
 * packetHash. Fixtures are recorded from the first successful live run (tech plan §9,
 * "replay first") - one JSON file per packetHash, named "&lt;packetHash&gt;.json", holding
 * a serialized Decision. The demo runs on replay by default; live is the bonus.
 */
public class ReplayBedrockClient implements BedrockClient {

    private final Path fixtureDir;
    private final ObjectMapper mapper = new ObjectMapper();

    public ReplayBedrockClient(Path fixtureDir) {
        this.fixtureDir = fixtureDir;
    }

    @Override
    public Decision invoke(RiskContextPacket packet, String ruleId, String resourceAddress) {
        Path fixtureFile = fixtureDir.resolve(packet.packetHash() + ".json");
        if (!Files.exists(fixtureFile)) {
            throw new IllegalStateException(
                "no replay fixture for packetHash=" + packet.packetHash() + " in " + fixtureDir);
        }
        try {
            return mapper.readValue(fixtureFile.toFile(), Decision.class);
        } catch (IOException e) {
            throw new UncheckedIOException("failed to load replay fixture " + fixtureFile, e);
        }
    }
}
