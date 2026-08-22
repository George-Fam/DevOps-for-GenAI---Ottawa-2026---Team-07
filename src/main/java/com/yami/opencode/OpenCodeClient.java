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
 * Fallback : voir docs/OPENCODE_FALLBACK.md (Solution B : mode primary)
 */
public class OpenCodeClient {

    private static final String DEFAULT_BASE_URL = "http://127.0.0.1:4096";
    private static final Duration DEFAULT_TIMEOUT = Duration.ofSeconds(120);

    private final HttpClient httpClient;
    private final ObjectMapper mapper;
    private final String baseUrl;
    private final Duration timeout;
    private final ReplayHttpShim shim;

    public OpenCodeClient() {
        this(DEFAULT_BASE_URL, DEFAULT_TIMEOUT, null);
    }

    /** Route tous les appels HTTP via le shim (record ou replay — voir {@link ReplayHttpShim}). */
    public OpenCodeClient(ReplayHttpShim shim) {
        this(DEFAULT_BASE_URL, DEFAULT_TIMEOUT, shim);
    }

    public OpenCodeClient(String baseUrl, Duration timeout) {
        this(baseUrl, timeout, null);
    }

    public OpenCodeClient(String baseUrl, Duration timeout, ReplayHttpShim shim) {
        this.httpClient = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(10))
            // opencode serve (Bun) only speaks HTTP/1.1; the JDK client defaults to
            // trying an HTTP/2 h2c upgrade first, which stalls indefinitely against
            // it instead of falling back cleanly - forcing 1.1 avoids that hang.
            .version(HttpClient.Version.HTTP_1_1)
            .build();
        this.mapper = new ObjectMapper();
        this.baseUrl = baseUrl;
        this.timeout = timeout;
        this.shim = shim;
    }

    /**
     * Point d'entrée HTTP unique : passe par le {@link ReplayHttpShim} si présent
     * (record ou replay), sinon appelle directement le serveur OpenCode.
     */
    private ReplayHttpShim.SimpleResponse execute(HttpRequest request) {
        java.time.Instant start = java.time.Instant.now();
        try {
            if (shim != null) {
                return shim.send(httpClient, request);
            }
            HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
            return new ReplayHttpShim.SimpleResponse(response.statusCode(), response.body());
        } catch (java.net.http.HttpTimeoutException e) {
            // The client timeout firing only tells us opencode serve never responded in
            // time - not why. The real cause (model access denied, IAM missing
            // bedrock:InvokeModel, throttling) is in opencode serve's own log, which the
            // caller (Harness) surfaces separately; this message at least pins down
            // which call stalled and for how long, instead of a bare stack trace.
            Duration elapsed = Duration.between(start, java.time.Instant.now());
            throw new RuntimeException("OpenCode request to " + request.uri() + " timed out after "
                + elapsed.toSeconds() + "s (configured timeout: " + timeout
                + ") - opencode serve accepted the connection but never returned a response in time; "
                + "check .yami-audit/opencode-serve.log for the actual provider/model error", e);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new RuntimeException("OpenCode request interrupted", e);
        }
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

        ReplayHttpShim.SimpleResponse response = execute(request);
        if (response.statusCode() != 200) {
            throw new RuntimeException("OpenCode createSession returned " + response.statusCode() + ": " + response.body());
        }
        try {
            JsonNode node = mapper.readTree(response.body());
            return node.path("id").asText();
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    /**
     * Envoie un message à un subagent dans une session existante.
     *
     * @param sessionId ID de la session (retourné par createSession)
     * @param agentName nom de l'agent (judge, surgeon, publisher, auditor)
     * @param prompt le prompt structuré avec contexte
     * @param model modèle Bedrock (ex: "amazon-bedrock/anthropic.claude-sonnet-5") ;
     *               {@code null} pour utiliser le {@code model:} du frontmatter de l'agent
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

        ReplayHttpShim.SimpleResponse response = execute(request);
        if (response.statusCode() != 200) {
            throw new RuntimeException("OpenCode sendMessage returned " + response.statusCode() + ": " + response.body());
        }
        JsonNode result;
        try {
            result = mapper.readTree(response.body());
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }

        // OpenCode returns HTTP 200 even when the agent turn itself failed (bad model,
        // missing AWS credentials, provider error) - the failure is embedded in
        // info.error with parts left empty. Surface it immediately with a clear
        // message instead of letting Decision/PatchReport parsing fail opaquely.
        JsonNode error = result.path("info").path("error");
        if (!error.isMissingNode() && !error.isNull()) {
            String message = error.path("data").path("message").asText(error.toString());
            throw new RuntimeException("OpenCode agent '" + agentName + "' invocation failed: " + message);
        }
        return result;
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

        ReplayHttpShim.SimpleResponse response = execute(request);
        if (response.statusCode() != 200) {
            throw new RuntimeException("OpenCode updateConfig returned " + response.statusCode() + ": " + response.body());
        }
    }

    /** Résultat d'une invocation d'agent : session (pour l'audit) + tokens consommés. */
    public record JudgeInvocation(String sessionId, Decision decision, long tokensUsed) {}
    public record SurgeonInvocation(String sessionId, PatchReport patchReport, long tokensUsed) {}
    public record AuditorInvocation(String sessionId, String summaryMarkdown, long tokensUsed) {}
    public record PublisherInvocation(String sessionId, String prUrl, String rawOutput, long tokensUsed) {}

    /**
     * Invoque le Judge et retourne une Decision schema-validée.
     * Crée une session dédiée, envoie le message, parse la réponse.
     */
    public JudgeInvocation invokeJudge(String prompt, String permissionJson) {
        String sessionId = createSession("yami-judge");
        updateConfig(permissionJson);
        JsonNode response = sendMessage(sessionId, "judge", prompt, null);
        return new JudgeInvocation(sessionId, parseDecision(response), extractTokens(response));
    }

    /**
     * Invoque le Surgeon et retourne un PatchReport.
     * Crée une session dédiée, envoie le message, parse la réponse.
     */
    public SurgeonInvocation invokeSurgeon(String prompt, String permissionJson) {
        String sessionId = createSession("yami-surgeon");
        updateConfig(permissionJson);
        JsonNode response = sendMessage(sessionId, "surgeon", prompt, null);
        return new SurgeonInvocation(sessionId, parsePatchReport(response), extractTokens(response));
    }

    /**
     * Invoque l'Auditor (read-only) et retourne le résumé Markdown de la PR,
     * adossé à la trace machine qu'il ne peut pas falsifier.
     */
    public AuditorInvocation invokeAuditor(String prompt, String permissionJson) {
        String sessionId = createSession("yami-auditor");
        updateConfig(permissionJson);
        JsonNode response = sendMessage(sessionId, "auditor", prompt, null);
        return new AuditorInvocation(sessionId, extractText(response), extractTokens(response));
    }

    /**
     * Invoque le Publisher (seul agent autorisé à toucher git/gh, scope bash
     * {@code git *} / {@code gh pr *}). Le Publisher exécute lui-même la
     * séquence branche → commit → push → PR ; sa réponse texte doit contenir
     * l'URL de la PR ouverte.
     */
    public PublisherInvocation invokePublisher(String prompt, String permissionJson) {
        String sessionId = createSession("yami-publisher");
        updateConfig(permissionJson);
        JsonNode response = sendMessage(sessionId, "publisher", prompt, null);
        String text = extractText(response);
        return new PublisherInvocation(sessionId, extractPrUrl(text), text, extractTokens(response));
    }

    /** Real shape observed from `opencode serve`: {@code info.tokens.{input,output,reasoning}}. */
    private static long extractTokens(JsonNode node) {
        JsonNode tokens = node.path("info").path("tokens");
        if (tokens.isMissingNode()) {
            return 0L;
        }
        return tokens.path("input").asLong(0) + tokens.path("output").asLong(0) + tokens.path("reasoning").asLong(0);
    }

    private static String extractText(JsonNode node) {
        JsonNode parts = node.path("parts");
        if (parts.isArray()) {
            StringBuilder sb = new StringBuilder();
            for (JsonNode part : parts) {
                if ("text".equals(part.path("type").asText())) {
                    if (sb.length() > 0) {
                        sb.append('\n');
                    }
                    sb.append(part.path("text").asText());
                }
            }
            return sb.toString();
        }
        return node.path("text").asText(node.toString());
    }

    private static final java.util.regex.Pattern PR_URL_PATTERN =
        java.util.regex.Pattern.compile("https://github\\.com/\\S+/pull/\\d+");

    private static String extractPrUrl(String text) {
        java.util.regex.Matcher matcher = PR_URL_PATTERN.matcher(text);
        return matcher.find() ? matcher.group() : null;
    }

    private static final java.util.regex.Pattern FENCED_BLOCK =
        java.util.regex.Pattern.compile("```(?:json)?\\s*\\n?(.*?)```", java.util.regex.Pattern.DOTALL);

    /**
     * The agent's actual output lives in {@code parts[].text} (see {@link #extractText}),
     * not at the top level of the raw session-message envelope. No server-side
     * structured-output enforcement yet — the Judge/Surgeon agents are prompted to
     * reply with a JSON blob, but in practice (confirmed against a real Bedrock
     * response) they wrap it in their own reasoning prose and a "self-check"
     * section per their own agent .md instructions, e.g.:
     * {@code "Analyse ...\n```json\n{...}\n```\n## Self-check\n- [x] ..."}.
     * So: prefer the first fenced ``` block anywhere in the text; if there isn't
     * one, fall back to the outermost {@code {...}} span; if neither is found,
     * fall back to the whole trimmed text so the error message stays informative.
     */
    private JsonNode parseJsonBody(JsonNode envelope, String agentName) {
        String raw = extractText(envelope).trim();

        java.util.regex.Matcher fence = FENCED_BLOCK.matcher(raw);
        String candidate = fence.find() ? fence.group(1).trim() : raw;

        if (!candidate.startsWith("{")) {
            int start = candidate.indexOf('{');
            int end = candidate.lastIndexOf('}');
            if (start >= 0 && end > start) {
                candidate = candidate.substring(start, end + 1);
            }
        }

        try {
            return mapper.readTree(candidate);
        } catch (IOException e) {
            throw new RuntimeException("OpenCode agent '" + agentName + "' did not return valid JSON: " + raw, e);
        }
    }

    private Decision parseDecision(JsonNode envelope) {
        JsonNode node = parseJsonBody(envelope, "judge");
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
                shim != null && shim.isReplayMode()
            );
        } catch (Exception e) {
            throw new RuntimeException("Failed to parse Decision from OpenCode response: " + node, e);
        }
    }

    private PatchReport parsePatchReport(JsonNode envelope) {
        JsonNode node = parseJsonBody(envelope, "surgeon");
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
