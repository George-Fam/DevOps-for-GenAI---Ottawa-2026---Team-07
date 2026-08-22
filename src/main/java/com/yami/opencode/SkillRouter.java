package com.yami.opencode;

import com.yami.core.Finding;

import java.util.List;

/**
 * Routage mécanique : finding.source() → skill(s) OWASP.
 * CHECKOV → owasp-iac-security ; CICD_RULES → owasp-cicd-top10 ;
 * TRIVY → owasp-supplychain.
 */
public class SkillRouter {

    public List<String> route(Finding finding) {
        return switch (finding.source()) {
            case CHECKOV -> List.of("owasp-iac-security");
            case CICD_RULES -> List.of("owasp-cicd-top10");
            case TRIVY -> List.of("owasp-supplychain");
        };
    }
}
