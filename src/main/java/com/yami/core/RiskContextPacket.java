package com.yami.core;

import java.util.List;
import java.util.Map;

public record RiskContextPacket(
    String packetHash,
    String resourceAddress,
    List<Finding> findings,
    Map<String, String> knownFacts,
    List<String> unknownFacts
) {}
