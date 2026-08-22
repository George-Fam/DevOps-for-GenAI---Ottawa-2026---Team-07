package com.yami.judge;

import com.yami.core.Decision;
import com.yami.core.RiskContextPacket;
import org.junit.jupiter.api.Test;
import software.amazon.awssdk.core.document.Document;
import software.amazon.awssdk.services.bedrockruntime.model.ContentBlock;
import software.amazon.awssdk.services.bedrockruntime.model.ConverseRequest;
import software.amazon.awssdk.services.bedrockruntime.model.ConverseResponse;
import software.amazon.awssdk.services.bedrockruntime.model.Message;
import software.amazon.awssdk.services.bedrockruntime.model.ToolUseBlock;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Tests the request/response marshalling in isolation - no network call, no AWS
 * credentials required. The real Bedrock round trip was proven manually against live
 * Bedrock during setup (see REFACTOR_STATUS.md); re-running that on every `mvn test`
 * would cost money and require credentials on every machine, which is what
 * ReplayBedrockClient exists for.
 */
class LiveBedrockClientTest {

    private static final String RULE_ID = "CLOUD-001";
    private static final String RESOURCE_ADDRESS = "aws_s3_bucket.data";

    private final LiveBedrockClient client = new LiveBedrockClient(null);
    private final RiskContextPacket packet = new RiskContextPacket(
        "hash123", List.of(), List.of(), Map.of("bucket", "yami-demo-bucket"),
        List.of(), List.of(), List.of(), List.of(), List.of(), "v4");

    @Test
    void buildRequestForcesTheDecisionTool() {
        ConverseRequest request = client.buildRequest(packet, RULE_ID, RESOURCE_ADDRESS);

        assertEquals(1, request.toolConfig().tools().size());
        assertEquals(LiveBedrockClient.TOOL_NAME, request.toolConfig().tools().get(0).toolSpec().name());
        assertEquals(LiveBedrockClient.TOOL_NAME, request.toolConfig().toolChoice().tool().name());
        assertEquals(1, request.messages().size());
    }

    @Test
    void parsesSafeFixWithMatchingResourceAddress() {
        ConverseResponse response = toolUseResponse(Document.mapBuilder()
            .putString("outcome", "SAFE_FIX")
            .putString("resourceAddress", RESOURCE_ADDRESS)
            .putString("replacementBlock", "resource \"aws_s3_bucket_versioning\" \"data\" {}")
            .putString("reason", "adds versioning")
            .putNumber("confidence", 0.9)
            .putBoolean("verificationRequired", true)
            .build());

        Decision decision = client.parseResponse(RULE_ID, RESOURCE_ADDRESS, response);

        assertEquals(Decision.DecisionType.SAFE_FIX, decision.decision());
        assertEquals(RULE_ID, decision.ruleId());
        assertEquals(RESOURCE_ADDRESS, decision.proposedPatch().resourceAddress());
        assertFalse(decision.fallbackMode());
        assertEquals(0.9, decision.confidence());
        assertTrue(decision.verificationRequired());
    }

    @Test
    void mismatchedResourceAddressDegradesToHumanReviewInsteadOfTrustingTheModel() {
        ConverseResponse response = toolUseResponse(Document.mapBuilder()
            .putString("outcome", "SAFE_FIX")
            .putString("resourceAddress", "aws_s3_bucket.WRONG")
            .putString("replacementBlock", "resource \"aws_s3_bucket_versioning\" \"data\" {}")
            .putString("reason", "adds versioning")
            .build());

        Decision decision = client.parseResponse(RULE_ID, RESOURCE_ADDRESS, response);

        assertEquals(Decision.DecisionType.HUMAN_REVIEW, decision.decision());
        assertEquals(RULE_ID, decision.ruleId(), "ruleId always comes from the input, never the model");
        assertNull(decision.proposedPatch());
        assertTrue(decision.reason().contains("cross-check failed"));
    }

    @Test
    void safeFixWithoutReplacementBlockDegradesToHumanReview() {
        ConverseResponse response = toolUseResponse(Document.mapBuilder()
            .putString("outcome", "SAFE_FIX")
            .putString("resourceAddress", RESOURCE_ADDRESS)
            .putString("reason", "adds versioning")
            .build());

        Decision decision = client.parseResponse(RULE_ID, RESOURCE_ADDRESS, response);

        assertEquals(Decision.DecisionType.HUMAN_REVIEW, decision.decision());
        assertTrue(decision.reason().contains("no replacementBlock"));
    }

    @Test
    void noToolCallDegradesToHumanReview() {
        ConverseResponse response = ConverseResponse.builder()
            .output(o -> o.message(Message.builder().content(ContentBlock.fromText("I refuse.")).build()))
            .build();

        Decision decision = client.parseResponse(RULE_ID, RESOURCE_ADDRESS, response);

        assertEquals(Decision.DecisionType.HUMAN_REVIEW, decision.decision());
        assertEquals(RULE_ID, decision.ruleId());
    }

    private static ConverseResponse toolUseResponse(Document input) {
        ToolUseBlock toolUse = ToolUseBlock.builder()
            .toolUseId("t1")
            .name(LiveBedrockClient.TOOL_NAME)
            .input(input)
            .build();
        return ConverseResponse.builder()
            .output(o -> o.message(Message.builder().content(ContentBlock.fromToolUse(toolUse)).build()))
            .build();
    }
}
