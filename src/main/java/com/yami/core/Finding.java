package com.yami.core;

/**
 * Finding normalisé provenant des scanners (checkov, CicdRules, trivy).
 */
public record Finding(
    String ruleId,
    Severity severity,
    String file,
    int line,
    String resource,
    String message,
    FindingSource source
) {
    public enum Severity { LOW, MEDIUM, HIGH, CRITICAL }
    public enum FindingSource { CHECKOV, CICD_RULES, TRIVY }
}
