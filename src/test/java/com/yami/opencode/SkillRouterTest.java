package com.yami.opencode;

import com.yami.core.Finding;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;

class SkillRouterTest {

    private final SkillRouter router = new SkillRouter();

    @Test
    void checkovRoutesToIacSecurity() {
        Finding f = new Finding("CKV_AWS_21", Finding.Severity.HIGH, "main.tf", 1, "aws_s3_bucket.data", "msg", Finding.FindingSource.CHECKOV);
        assertEquals(List.of("owasp-iac-security"), router.route(f));
    }

    @Test
    void cicdRulesRoutesToCicdTop10() {
        Finding f = new Finding("YAMI_CICD_4", Finding.Severity.CRITICAL, "workflow.yml", -1, "workflow.dangerous", "msg", Finding.FindingSource.CICD_RULES);
        assertEquals(List.of("owasp-cicd-top10"), router.route(f));
    }

    @Test
    void trivyRoutesToSupplyChain() {
        Finding f = new Finding("CVE-2024-1234", Finding.Severity.HIGH, "pom.xml", 1, "dep", "msg", Finding.FindingSource.TRIVY);
        assertEquals(List.of("owasp-supplychain"), router.route(f));
    }
}
