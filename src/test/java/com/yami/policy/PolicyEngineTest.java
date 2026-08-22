package com.yami.policy;

import com.yami.core.Decision;
import com.yami.core.Finding;
import com.yami.core.RiskContextPacket;
import com.yami.investigator.Investigator;
import com.yami.scanner.CheckovAdapter;
import org.junit.jupiter.api.Test;

import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;

class PolicyEngineTest {

    private final PolicyEngine engine = new PolicyEngine(Path.of("policies/yami.yml"));

    @Test
    void realFixtureFindingsAreSafeFixWithNoCloudfrontOrPublicSiteSignal() {
        Path dir = Path.of("fixtures/safe_fix");
        List<Finding> findings = new CheckovAdapter().scan(dir);
        RiskContextPacket packet = new Investigator().buildPacket(
            dir, dir, findings, List.of("main.tf"), Map.of(), engine.version());

        assertEquals(Decision.DecisionType.SAFE_FIX, engine.classify("CLOUD-001", packet, "aws_s3_bucket.data"));
    }

    @Test
    void cloudfrontRelationForcesHumanReviewInsteadOfSafeFix() {
        RiskContextPacket packet = emptyPacket(
            List.of("aws_cloudfront_distribution.cdn -> aws_s3_bucket.data"), List.of());

        assertEquals(Decision.DecisionType.HUMAN_REVIEW, engine.classify("CLOUD-001", packet, "aws_s3_bucket.data"));
    }

    @Test
    void realHumanReviewFixtureIsDetectedAsCloudfrontOrigin() {
        Path dir = Path.of("fixtures/human_review");
        List<Finding> findings = new CheckovAdapter().scan(dir);
        RiskContextPacket packet = new Investigator().buildPacket(
            dir, dir, findings, List.of("main.tf"), Map.of(), engine.version());

        assertEquals(Decision.DecisionType.HUMAN_REVIEW, engine.classify("CLOUD-001", packet, "aws_s3_bucket.site"));
    }

    @Test
    void publicSiteSignalForcesHumanReviewInsteadOfSafeFix() {
        RiskContextPacket packet = emptyPacket(
            List.of(), List.of("aws_s3_bucket.data.website = true"));

        assertEquals(Decision.DecisionType.HUMAN_REVIEW, engine.classify("CLOUD-001", packet, "aws_s3_bucket.data"));
    }

    @Test
    void cicd001HasNoSafeFixPathAlwaysBlocks() {
        assertEquals(Decision.DecisionType.BLOCK,
            engine.classify("CICD-001", emptyPacket(List.of(), List.of()), "workflow.vulnerable"));
    }

    @Test
    void unknownRuleCategoryDefaultsToHumanReview() {
        assertEquals(Decision.DecisionType.HUMAN_REVIEW,
            engine.classify("UNKNOWN-999", emptyPacket(List.of(), List.of()), "x"));
    }

    private static RiskContextPacket emptyPacket(List<String> terraformRelations, List<String> known) {
        return new RiskContextPacket("h", List.of(), List.of(), Map.of(),
            terraformRelations, List.of(), known, List.of(), List.of(), "v4");
    }
}
