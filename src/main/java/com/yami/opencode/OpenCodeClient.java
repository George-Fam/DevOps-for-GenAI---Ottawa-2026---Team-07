package com.yami.opencode;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.yami.core.Decision;
import com.yami.core.PatchReport;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.List;

/**
 * Client HTTP vers OpenCode sur localhost:4096.
 * Supporte deux modes : {@code opencode serve} (persistant) ou
 * {@code opencode run} one-shot (cold start acceptable).
 *
 * <p>Le choix entre les deux modes est tranché par le spike H+0-2.
 * Cette classe encapsule l'appel HTTP et la validation du JSON
 * structured output.
 */
public class OpenCodeClient {

    private static final String DEFAULT_BASE_URL = "http://localhost:4096";
    private static final Duration DEFAULT_TIMEOUT = Duration.ofSeconds(120);

    private final HttpClient httpClient;
    private final ObjectMapper mapper;
    private final String baseUrl;
    private final Duration timeout;

    public OpenCodeClient() {
        this(DEFAULT_BASE_URL, DEFAULT_TIMEOUT);
    }

    public OpenCodeClient(String baseUrl, Duration timeout) {
        this.httpClient = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(10))
            .build();
        this.mapper = new ObjectMapper();
        this.baseUrl = baseUrl;
        this.timeout = timeout;
    }

    /**
     * Invoque un agent OpenCode et retourne sa réponse JSON.
     *
     * @param agentName nom de l'agent (judge, surgeon, publisher, auditor)
     * @param prompt le prompt structuré avec contexte
     * @param permissionJson JSON OPENCODE_PERMISSION (peut être null)
     * @return JsonNode de la réponse
     */
    public JsonNode invokeAgent(String agentName, String prompt, String permissionJson) {
        ObjectNode body = mapper.createObjectNode();
        body.put("agent", agentName);
        body.put("prompt", prompt);
        body.put("format", "json");
        if (permissionJson != null) {
            body.put("permissions", permissionJson);
        }

        HttpRequest request = HttpRequest.newBuilder()
            .uri(URI.create(baseUrl + "/v1/agents/" + agentName + "/run"))
            .header("Content-Type", "application/json")
            .timeout(timeout)
            .POST(HttpRequest.BodyPublishers.ofString(body.toString()))
            .build();

        try {
            HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
            if (response.statusCode() != 200) {
                throw new RuntimeException("OpenCode returned " + response.statusCode() + ": " + response.body());
            }
            return mapper.readTree(response.body());
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new RuntimeException("OpenCode invocation interrupted", e);
        }
    }

    /**
     * Invoque le Judge et retourne une Decision schema-validée.
     */
    public Decision invokeJudge(String prompt, String permissionJson) {
        JsonNode response = invokeAgent("judge", prompt, permissionJson);
        return parseDecision(response);
    }

    /**
     * Invoque le Surgeon et retourne un PatchReport.
     */
    public PatchReport invokeSurgeon(String prompt, String permissionJson) {
        JsonNode response = invokeAgent("surgeon", prompt, permissionJson);
        return parsePatchReport(response);
    }

    private Decision parseDecision(JsonNode node) {
        try {
            String outcome = node.path("outcome").asText();
            String resourceAddress = node.path("resourceAddress").asText();
            String remediationIntent = node.has("remediationIntent") ? node.path("remediationIntent").asText(null) : null;
            String reason = node.path("reason").asText();
            double confidence = node.path("confidence").asDouble(0.0);
            List<String> skillsUsed = mapper.readerForListOf(String.class).readValue(node.path("skillsUsed"));
            return new Decision(
                Decision.DecisionType.valueOf(outcome),
                resourceAddress,
                remediationIntent,
                reason,
                confidence,
                skillsUsed,
                false
            );
        } catch (Exception e) {
            throw new RuntimeException("Failed to parse Decision from OpenCode response: " + node, e);
        }
    }

    private PatchReport parsePatchReport(JsonNode node) {
        try {
            List<String> filesModified = mapper.readerForListOf(String.class).readValue(node.path("filesModified"));
            String summary = node.path("summary").asText();
            List<String> owaspReferences = mapper.readerForListOf(String.class).readValue(node.path("owaspReferences"));
            List<String> deviations = mapper.readerForListOf(String.class).readValue(node.path("deviationsFromIntent"));
            return new PatchReport(filesModified, summary, owaspReferences, deviations);
        } catch (Exception e) {
            throw new RuntimeException("Failed to parse PatchReport from OpenCode response: " + node, e);
        }
    }
}
