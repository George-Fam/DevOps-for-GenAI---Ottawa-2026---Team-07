package com.yami.opencode;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
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
 * Utilise l'API HTTP officielle OpenCode (serve) pour invoquer les subagents.
 *
 * <p>Flow correct :
 * 1. POST /session → crée une session, récupère sessionID
 * 2. POST /session/{id}/message → envoie un message avec l'agent dans le body
 * 3. PATCH /config → met à jour les permissions à chaud (per-finding scoping)
 * 4. opencode export {sessionId} → exporte la session pour l'audit
 *
 * <p>Documentation : https://opencode.ai/docs/server/
 * Fallback : voir docs/OPENCODE_FALLBACK (Solution B : mode primary)
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
     * Crée une nouvelle session OpenCode.
     *
     * @param title titre de la session (ex: "yami-judge-ckv-aws-21")
     * @return sessionID (ex: "ses_abc123")
     */
    public String createSession(String title) {
        ObjectNode body = mapper.createObjectNode();
        body.put("title", title);

        HttpRequest request = HttpRequest.newBuilder()
            .uri(URI.create(baseUrl + "/session"))
            .header("Content-Type", "application/json")
            .timeout(timeout)
            .POST(HttpRequest.BodyPublishers.ofString(body.toString()))
            .build();

        try {
            HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
            if (response.statusCode() != 200) {
                throw new RuntimeException("OpenCode createSession returned " + response.statusCode() + ": " + response.body());
            }
            JsonNode node = mapper.readTree(response.body());
            return node.path("id").asText();
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new RuntimeException("OpenCode createSession interrupted", e);
        }
    }

    /**
     * Envoie un message à un subagent dans une session existante.
     *
     * @param sessionId ID de la session (retourné par createSession)
     * @param agentName nom de l'agent (judge, surgeon, publisher, auditor)
     * @param prompt le prompt structuré avec contexte
     * @param model modèle Bedrock (ex: "amazon-bedrock/anthropic.claude-3-5-sonnet-20241022-v2:0")
     * @return JsonNode de la réponse
     */
    public JsonNode sendMessage(String sessionId, String agentName, String prompt, String model) {
        ObjectNode body = mapper.createObjectNode();
        body.put("agent", agentName);
        if (model != null) {
            body.put("model", model);
        }
        ArrayNode parts = body.putArray("parts");
        ObjectNode textPart = parts.addObject();
        textPart.put("type", "text");
        textPart.put("text", prompt);

        HttpRequest request = HttpRequest.newBuilder()
            .uri(URI.create(baseUrl + "/session/" + sessionId + "/message"))
            .header("Content-Type", "application/json")
            .timeout(timeout)
            .POST(HttpRequest.BodyPublishers.ofString(body.toString()))
            .build();

        try {
            HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
            if (response.statusCode() != 200) {
                throw new RuntimeException("OpenCode sendMessage returned " + response.statusCode() + ": " + response.body());
            }
            return mapper.readTree(response.body());
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new RuntimeException("OpenCode sendMessage interrupted", e);
        }
    }

    /**
     * Met à jour la configuration OpenCode à chaud (permissions scopées).
     *
     * @param permissionJson JSON OPENCODE_PERMISSION
     */
    public void updateConfig(String permissionJson) {
        HttpRequest request = HttpRequest.newBuilder()
            .uri(URI.create(baseUrl + "/config"))
            .header("Content-Type", "application/json")
            .timeout(timeout)
            .method("PATCH", java.net.http.HttpRequest.BodyPublishers.ofString(permissionJson))
            .build();

        try {
            HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
            if (response.statusCode() != 200) {
                throw new RuntimeException("OpenCode updateConfig returned " + response.statusCode() + ": " + response.body());
            }
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new RuntimeException("OpenCode updateConfig interrupted", e);
        }
    }

    /**
     * Invoque le Judge et retourne une Decision schema-validée.
     * Crée une session dédiée, envoie le message, parse la réponse.
     */
    public Decision invokeJudge(String prompt, String permissionJson) {
        String sessionId = createSession("yami-judge");
        updateConfig(permissionJson);
        JsonNode response = sendMessage(sessionId, "judge", prompt, null);
        return parseDecision(response);
    }

    /**
     * Invoque le Surgeon et retourne un PatchReport.
     * Crée une session dédiée, envoie le message, parse la réponse.
     */
    public PatchReport invokeSurgeon(String prompt, String permissionJson) {
        String sessionId = createSession("yami-surgeon");
        updateConfig(permissionJson);
        JsonNode response = sendMessage(sessionId, "surgeon", prompt, null);
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
