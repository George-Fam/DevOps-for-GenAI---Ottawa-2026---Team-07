package com.yami.judge;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.yami.core.Decision;
import com.yami.core.ProposedPatch;
import com.yami.core.RiskContextPacket;
import software.amazon.awssdk.core.document.Document;
import software.amazon.awssdk.services.bedrockruntime.BedrockRuntimeClient;
import software.amazon.awssdk.services.bedrockruntime.model.ContentBlock;
import software.amazon.awssdk.services.bedrockruntime.model.ConversationRole;
import software.amazon.awssdk.services.bedrockruntime.model.ConverseRequest;
import software.amazon.awssdk.services.bedrockruntime.model.ConverseResponse;
import software.amazon.awssdk.services.bedrockruntime.model.Message;
import software.amazon.awssdk.services.bedrockruntime.model.SpecificToolChoice;
import software.amazon.awssdk.services.bedrockruntime.model.SystemContentBlock;
import software.amazon.awssdk.services.bedrockruntime.model.Tool;
import software.amazon.awssdk.services.bedrockruntime.model.ToolChoice;
import software.amazon.awssdk.services.bedrockruntime.model.ToolConfiguration;
import software.amazon.awssdk.services.bedrockruntime.model.ToolInputSchema;
import software.amazon.awssdk.services.bedrockruntime.model.ToolSpecification;
import software.amazon.awssdk.services.bedrockruntime.model.ToolUseBlock;

import java.util.List;
import java.util.Map;

/**
 * The single outbound network call in the whole pipeline. Sends the Investigator's
 * {@link RiskContextPacket} (structured facts only, never raw file content) plus which
 * specific resource/rule category this decision is about, and forces a tool call so the
 * response is schema-shaped JSON, not free text to parse. Never trusts the model's
 * resourceAddress claim - it's cross-checked before the Decision is allowed to leave this
 * class, and ruleId is always set from the input, never trusted from the model. Any
 * schema violation degrades to HUMAN_REVIEW rather than throwing and killing the pipeline.
 */
public class LiveBedrockClient implements BedrockClient {

    static final String TOOL_NAME = "propose_decision";

    private static final String SYSTEM_PROMPT = """
        You are Yami's Judge: a governance step for automated Terraform remediation.

        You receive a RiskContextPacket describing the whole PR: findings, known and
        unknown facts, Terraform resource relations, and deploying workflows. You are
        asked to decide for ONE specific resource and rule category within that packet -
        use the rest of the packet only as context.

        Decide one outcome:
        - SAFE_FIX: only when you are confident the fix is mechanical and well-understood
          (e.g. adding a standard sibling resource block for versioning/encryption/logging
          with conventional settings). You must include a complete, syntactically valid
          replacementBlock: one or more full HCL resource blocks that will directly replace
          the named resource's block. Do not fabricate resource types or attributes you are
          not sure exist in the provider.
        - HUMAN_REVIEW: findings need judgment, business context, or a non-mechanical change.
        - BLOCK: the finding represents a severe, likely-intentional risk that should not be
          auto-remediated at all (e.g. public access deliberately configured).

        resourceAddress in your response MUST exactly equal the resourceAddress you were
        asked about. Never propose changes to a resource you were not asked about.
        confidence is a number from 0.0 to 1.0 reflecting how sure you are in this decision.
        """;

    private final BedrockRuntimeClient client;
    private final String modelId;
    private final ObjectMapper mapper = new ObjectMapper();

    public LiveBedrockClient(BedrockRuntimeClient client) {
        this(client, "us.anthropic.claude-haiku-4-5-20251001-v1:0");
    }

    public LiveBedrockClient(BedrockRuntimeClient client, String modelId) {
        this.client = client;
        this.modelId = modelId;
    }

    @Override
    public Decision invoke(RiskContextPacket packet, String ruleId, String resourceAddress) {
        ConverseResponse response = client.converse(buildRequest(packet, ruleId, resourceAddress));
        return parseResponse(ruleId, resourceAddress, response);
    }

    ConverseRequest buildRequest(RiskContextPacket packet, String ruleId, String resourceAddress) {
        String userMessage = "ruleId=" + ruleId + " resourceAddress=" + resourceAddress
            + "\npacket=" + packetToJson(packet);

        return ConverseRequest.builder()
            .modelId(modelId)
            .system(SystemContentBlock.builder().text(SYSTEM_PROMPT).build())
            .messages(Message.builder()
                .role(ConversationRole.USER)
                .content(ContentBlock.fromText(userMessage))
                .build())
            .toolConfig(ToolConfiguration.builder()
                .tools(Tool.builder().toolSpec(ToolSpecification.builder()
                    .name(TOOL_NAME)
                    .description("Report the governance decision for this resource")
                    .inputSchema(ToolInputSchema.builder().json(decisionSchema()).build())
                    .build()).build())
                .toolChoice(ToolChoice.builder().tool(SpecificToolChoice.builder().name(TOOL_NAME).build()).build())
                .build())
            .build();
    }

    Decision parseResponse(String ruleId, String resourceAddress, ConverseResponse response) {
        ToolUseBlock toolUse = response.output().message().content().stream()
            .map(ContentBlock::toolUse)
            .filter(t -> t != null)
            .findFirst()
            .orElse(null);

        if (toolUse == null) {
            return humanReview(ruleId, resourceAddress, "model did not return a tool call");
        }

        Map<String, Document> input = toolUse.input().asMap();

        String outcomeStr = stringField(input, "outcome");
        String responseResourceAddress = stringField(input, "resourceAddress");
        String replacementBlock = stringField(input, "replacementBlock");
        String reason = stringField(input, "reason");
        Document confidenceDoc = input.get("confidence");
        Double confidence = confidenceDoc == null || confidenceDoc.isNull() ? null : confidenceDoc.asNumber().doubleValue();
        Document verificationRequiredDoc = input.get("verificationRequired");
        boolean verificationRequired = verificationRequiredDoc != null && !verificationRequiredDoc.isNull()
            && verificationRequiredDoc.asBoolean();

        if (outcomeStr == null || responseResourceAddress == null || reason == null) {
            return humanReview(ruleId, resourceAddress, "model response missing required fields");
        }

        if (!responseResourceAddress.equals(resourceAddress)) {
            return humanReview(ruleId, resourceAddress, "resourceAddress cross-check failed: model said \""
                + responseResourceAddress + "\", asked about \"" + resourceAddress + "\"");
        }

        Decision.DecisionType outcome;
        try {
            outcome = Decision.DecisionType.valueOf(outcomeStr);
        } catch (IllegalArgumentException e) {
            return humanReview(ruleId, resourceAddress, "model returned unknown outcome: " + outcomeStr);
        }

        ProposedPatch patch = null;
        if (outcome == Decision.DecisionType.SAFE_FIX) {
            if (replacementBlock == null || replacementBlock.isBlank()) {
                return humanReview(ruleId, resourceAddress, "model claimed SAFE_FIX but returned no replacementBlock");
            }
            patch = new ProposedPatch(responseResourceAddress, replacementBlock, reason);
        }

        try {
            double conf = confidence != null ? confidence : 0.0;
            String intent = patch != null ? "legacy Bedrock patch for " + resourceAddress : null;
            return new Decision(outcome, resourceAddress, intent, reason, conf, List.of(), false);
        } catch (IllegalArgumentException e) {
            return humanReview(ruleId, resourceAddress, "model response failed Decision validation: " + e.getMessage());
        }
    }

    private static Decision humanReview(String ruleId, String resourceAddress, String reason) {
        return new Decision(Decision.DecisionType.HUMAN_REVIEW, resourceAddress, null, reason, 0.0, List.of(), false);
    }

    private static String stringField(Map<String, Document> input, String key) {
        Document doc = input.get(key);
        return doc == null || doc.isNull() ? null : doc.asString();
    }

    private String packetToJson(RiskContextPacket packet) {
        try {
            return mapper.writeValueAsString(packet);
        } catch (Exception e) {
            throw new IllegalStateException("failed to serialize packet", e);
        }
    }

    private static Document decisionSchema() {
        return Document.mapBuilder()
            .putString("type", "object")
            .putMap("properties", props -> props
                .putMap("outcome", m -> m
                    .putString("type", "string")
                    .putList("enum", List.of(
                        Document.fromString("SAFE_FIX"),
                        Document.fromString("HUMAN_REVIEW"),
                        Document.fromString("BLOCK"))))
                .putMap("resourceAddress", m -> m.putString("type", "string"))
                .putMap("replacementBlock", m -> m
                    .putString("type", "string")
                    .putString("description", "Complete replacement HCL resource block(s). Required when outcome is SAFE_FIX."))
                .putMap("reason", m -> m.putString("type", "string"))
                .putMap("confidence", m -> m
                    .putString("type", "number")
                    .putString("description", "0.0 to 1.0"))
                .putMap("verificationRequired", m -> m.putString("type", "boolean")))
            .putList("required", List.of(
                Document.fromString("outcome"),
                Document.fromString("resourceAddress"),
                Document.fromString("reason")))
            .build();
    }
}
