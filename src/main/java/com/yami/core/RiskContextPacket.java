package com.yami.core;

import java.util.List;
import java.util.Map;

public record RiskContextPacket(
    String packetHash,
    List<Finding> findings,
    List<String> changedFiles,
    Map<String, String> beforeAfter,
    List<String> terraformRelations,
    List<String> deployingWorkflows,
    List<String> known,
    List<String> unknown,
    List<String> allowedActions,
    String policyVersion
) {}
