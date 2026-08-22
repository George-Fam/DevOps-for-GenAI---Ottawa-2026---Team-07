package com.yami.judge;

import com.yami.core.Decision;
import com.yami.core.Finding;
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
 * {@link RiskContextPacket} (structured facts only, never raw file content) and forces a
 * tool call so the response is schema-shaped JSON, not free text to parse. Never trusts
 * the model's resourceAddress claim - it's cross-checked against the packet before the
 * Decision is allowed to leave this class, and any schema violation degrades to
 * HUMAN_REVIEW rather than throwing and killing the pipeline.
 */
public class LiveBedrockClient implements BedrockClient {

    static final String TOOL_NAME = "propose_decision";

    private static final String SYSTEM_PROMPT = """
        You are Yami's Judge: a governance step for automated Terraform remediation.

        You receive a RiskContextPacket describing ONE Terraform resource: its policy
        findings, known facts extracted from the resource block, and unknown facts (gaps
        the deterministic scan could not resolve, e.g. a required sibling resource like
        aws_s3_bucket_versioning that does not exist yet).

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

        resourceAddress in your response MUST exactly equal the resourceAddress given to you.
        Never propose changes to a resource you were not asked about.
        """;

    private final BedrockRuntimeClient client;
    private final String modelId;

    public LiveBedrockClient(BedrockRuntimeClient client) {
        this(client, "anthropic.claude-haiku-4-5-20251001-v1:0");
    }

    public LiveBedrockClient(BedrockRuntimeClient client, String modelId) {
        this.client = client;
        this.modelId = modelId;
    }

    @Override
    public Decision invoke(RiskContextPacket packet) {
        ConverseResponse response = client.converse(buildRequest(packet));
        return parseResponse(packet, response);
    }

    ConverseRequest buildRequest(RiskContextPacket packet) {
        return ConverseRequest.builder()
            .modelId(modelId)
            .system(SystemContentBlock.builder().text(SYSTEM_PROMPT).build())
            .messages(Message.builder()
                .role(ConversationRole.USER)
                .content(ContentBlock.fromText(packetToJson(packet)))
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

    Decision parseResponse(RiskContextPacket packet, ConverseResponse response) {
        ToolUseBlock toolUse = response.output().message().content().stream()
            .map(ContentBlock::toolUse)
            .filter(t -> t != null)
            .findFirst()
            .orElse(null);

        if (toolUse == null) {
            return humanReview(packet, "model did not return a tool call");
        }

        Map<String, Document> input = toolUse.input().asMap();

        String outcomeStr = stringField(input, "outcome");
        String resourceAddress = stringField(input, "resourceAddress");
        String replacementBlock = stringField(input, "replacementBlock");
        String rationale = stringField(input, "rationale");

        if (outcomeStr == null || resourceAddress == null || rationale == null) {
            return humanReview(packet, "model response missing required fields");
        }

        if (!resourceAddress.equals(packet.resourceAddress())) {
            return humanReview(packet, "resourceAddress cross-check failed: model said \""
                + resourceAddress + "\", packet was for \"" + packet.resourceAddress() + "\"");
        }

        Decision.Outcome outcome;
        try {
            outcome = Decision.Outcome.valueOf(outcomeStr);
        } catch (IllegalArgumentException e) {
            return humanReview(packet, "model returned unknown outcome: " + outcomeStr);
        }

        ProposedPatch patch = null;
        if (outcome == Decision.Outcome.SAFE_FIX) {
            if (replacementBlock == null || replacementBlock.isBlank()) {
                return humanReview(packet, "model claimed SAFE_FIX but returned no replacementBlock");
            }
            patch = new ProposedPatch(resourceAddress, replacementBlock, rationale);
        }

        try {
            return new Decision(outcome, packet.packetHash(), patch, rationale, false);
        } catch (IllegalArgumentException e) {
            return humanReview(packet, "model response failed Decision validation: " + e.getMessage());
        }
    }

    private static Decision humanReview(RiskContextPacket packet, String reason) {
        return new Decision(Decision.Outcome.HUMAN_REVIEW, packet.packetHash(), null, reason, false);
    }

    private static String stringField(Map<String, Document> input, String key) {
        Document doc = input.get(key);
        return doc == null || doc.isNull() ? null : doc.asString();
    }

    private static String packetToJson(RiskContextPacket packet) {
        StringBuilder json = new StringBuilder("{");
        json.append("\"resourceAddress\":\"").append(escape(packet.resourceAddress())).append("\",");
        json.append("\"findings\":[");
        List<Finding> findings = packet.findings();
        for (int i = 0; i < findings.size(); i++) {
            Finding f = findings.get(i);
            if (i > 0) json.append(",");
            json.append("{\"ruleId\":\"").append(escape(f.ruleId())).append("\",")
                .append("\"severity\":\"").append(f.severity()).append("\",")
                .append("\"description\":\"").append(escape(f.description())).append("\"}");
        }
        json.append("],\"knownFacts\":{");
        boolean first = true;
        for (Map.Entry<String, String> e : packet.knownFacts().entrySet()) {
            if (!first) json.append(",");
            first = false;
            json.append("\"").append(escape(e.getKey())).append("\":\"").append(escape(e.getValue())).append("\"");
        }
        json.append("},\"unknownFacts\":[");
        for (int i = 0; i < packet.unknownFacts().size(); i++) {
            if (i > 0) json.append(",");
            json.append("\"").append(escape(packet.unknownFacts().get(i))).append("\"");
        }
        json.append("]}");
        return json.toString();
    }

    private static String escape(String s) {
        return s.replace("\\", "\\\\").replace("\"", "\\\"").replace("\n", "\\n");
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
                .putMap("rationale", m -> m.putString("type", "string")))
            .putList("required", List.of(
                Document.fromString("outcome"),
                Document.fromString("resourceAddress"),
                Document.fromString("rationale")))
            .build();
    }
}
