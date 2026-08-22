package com.yami.opencode;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Shim HTTP à la frontière Java↔OpenCode serve.
 *
 * <p>Deux modes :
 * <ul>
 *   <li><b>Record</b> : intercepte les appels HTTP réels, les écrit dans un fichier
 *       JSONL (une ligne par requête+réponse).</li>
 *   <li><b>Replay</b> : lit le fichier JSONL et rejoue les réponses sans appel
 *       réseau. Le pipeline Java tourne pour de vrai ; seul le modèle est rejoué.</li>
 * </ul>
 *
 * <p>Usage dans le Harness :
 * <pre>
 *   ReplayHttpShim shim = new ReplayHttpShim(Path.of("replay.jsonl"), false); // record
 *   OpenCodeClient client = new OpenCodeClient(shim);
 * </pre>
 */
public class ReplayHttpShim {

    private final Path replayFile;
    private final boolean replayMode;
    private final ObjectMapper mapper = new ObjectMapper();
    private final List<RecordedExchange> exchanges = new ArrayList<>();
    private final AtomicInteger replayIndex = new AtomicInteger(0);

    public ReplayHttpShim(Path replayFile, boolean replayMode) {
        this.replayFile = replayFile;
        this.replayMode = replayMode;
        if (replayMode) {
            loadExchanges();
        }
    }

    public boolean isReplayMode() {
        return replayMode;
    }

    /**
     * Envoie une requête HTTP. En mode record, la requête est exécutée pour de vrai
     * et la réponse est enregistrée. En mode replay, la réponse est lue depuis le
     * fichier de replay.
     *
     * @return un {@link SimpleResponse} contenant le status code et le body
     */
    public SimpleResponse send(HttpClient client, HttpRequest request) throws IOException, InterruptedException {
        if (replayMode) {
            return replay(request);
        } else {
            HttpResponse<String> response = client.send(request, HttpResponse.BodyHandlers.ofString());
            record(request, response);
            return new SimpleResponse(response.statusCode(), response.body());
        }
    }

    private void record(HttpRequest request, HttpResponse<String> response) {
        try {
            ObjectNode entry = mapper.createObjectNode();
            entry.put("timestamp", Instant.now().toString());
            entry.put("method", request.method());
            entry.put("uri", request.uri().toString());
            entry.put("statusCode", response.statusCode());
            entry.put("responseBody", response.body());

            String line = mapper.writeValueAsString(entry) + "\n";
            Files.writeString(replayFile, line, StandardOpenOption.CREATE, StandardOpenOption.APPEND);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    /**
     * Rejoue les échanges dans l'ordre exact où ils ont été enregistrés. Le matching
     * n'est pas fait par (method, uri) exact : OpenCode génère un sessionId différent
     * à chaque {@code createSession}, donc les URIs {@code /session/{id}/message}
     * divergent légitimement d'un run à l'autre. On compare la méthode + l'URI
     * normalisée (segment de sessionId neutralisé) contre l'échange suivant en
     * séquence — le pipeline Java appelle OpenCode dans un ordre déterministe, donc
     * "le Nème appel correspond au Nème enregistrement" est une garantie valide et
     * détecte quand même une vraie dérive de séquence (mauvais endpoint, appel
     * manquant ou en trop).
     */
    private SimpleResponse replay(HttpRequest request) {
        String method = request.method();
        String uri = request.uri().toString();

        int index = replayIndex.getAndIncrement();
        if (index >= exchanges.size()) {
            throw new IllegalStateException("Replay file exhausted (" + exchanges.size()
                + " recorded exchanges) — no entry left for call #" + (index + 1) + ": " + method + " " + uri);
        }

        RecordedExchange ex = exchanges.get(index);
        if (!ex.method.equals(method) || !normalizeUri(ex.uri).equals(normalizeUri(uri))) {
            throw new IllegalStateException("Replay sequence mismatch at call #" + (index + 1)
                + ": recorded " + ex.method + " " + ex.uri + " but pipeline requested " + method + " " + uri);
        }

        return new SimpleResponse(ex.statusCode, ex.responseBody);
    }

    private static String normalizeUri(String uri) {
        return uri.replaceAll("/session/[^/]+", "/session/{id}");
    }

    private void loadExchanges() {
        if (!Files.exists(replayFile)) {
            throw new IllegalStateException("Replay file not found: " + replayFile);
        }
        try {
            List<String> lines = Files.readAllLines(replayFile);
            for (String line : lines) {
                if (line.isBlank()) continue;
                JsonNode node = mapper.readTree(line);
                exchanges.add(new RecordedExchange(
                    node.get("method").asText(),
                    node.get("uri").asText(),
                    node.get("statusCode").asInt(),
                    node.get("responseBody").asText()
                ));
            }
            if (exchanges.isEmpty()) {
                throw new IllegalStateException("Replay file is empty: " + replayFile);
            }
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    private static class RecordedExchange {
        final String method;
        final String uri;
        final int statusCode;
        final String responseBody;

        RecordedExchange(String method, String uri, int statusCode, String responseBody) {
            this.method = method;
            this.uri = uri;
            this.statusCode = statusCode;
            this.responseBody = responseBody;
        }
    }

    /**
     * Réponse HTTP simplifiée (status code + body).
     */
    public static class SimpleResponse {
        private final int statusCode;
        private final String body;

        public SimpleResponse(int statusCode, String body) {
            this.statusCode = statusCode;
            this.body = body;
        }

        public int statusCode() {
            return statusCode;
        }

        public String body() {
            return body;
        }
    }
}
