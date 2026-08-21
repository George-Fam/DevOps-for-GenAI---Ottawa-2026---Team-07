package com.yami.core;

public record Finding(
    String id,
    Source source,
    String ruleId,
    Severity severity,
    String resourceAddress,
    String filePath,
    int startLine,
    int endLine,
    String description
) {
    public enum Source { CHECKOV, CICD_RULE }
    public enum Severity { LOW, MEDIUM, HIGH, CRITICAL }
}
