package com.yami.opencode;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Replay mode must work across a realistic multi-agent run where OpenCode assigns a
 * different sessionId on every {@code createSession} call — so URIs like
 * {@code /session/{id}/message} legitimately differ from what was recorded. These
 * tests exercise replay in isolation (no live OpenCode server, no network).
 */
class ReplayHttpShimTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final HttpClient DUMMY_CLIENT = HttpClient.newHttpClient();

    private static void writeExchange(Path file, String method, String uri, int status, String body) {
        try {
            ObjectNode entry = MAPPER.createObjectNode();
            entry.put("method", method);
            entry.put("uri", uri);
            entry.put("statusCode", status);
            entry.put("responseBody", body);
            Files.writeString(file, MAPPER.writeValueAsString(entry) + "\n",
                StandardOpenOption.CREATE, StandardOpenOption.APPEND);
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    @Test
    void replaysExchangesInSequenceIgnoringSessionIdInUri(@TempDir Path tmp) throws Exception {
        Path replayFile = tmp.resolve("replay.jsonl");
        writeExchange(replayFile, "POST", "http://localhost:4096/session", 200, "session-1");
        writeExchange(replayFile, "PATCH", "http://localhost:4096/config", 200, "config-ok");
        writeExchange(replayFile, "POST", "http://localhost:4096/session/ses_recorded/message", 200, "judge-response");

        ReplayHttpShim shim = new ReplayHttpShim(replayFile, true);
        assertTrue(shim.isReplayMode());

        var r1 = shim.send(DUMMY_CLIENT, HttpRequest.newBuilder(URI.create("http://localhost:4096/session"))
            .POST(HttpRequest.BodyPublishers.noBody()).build());
        assertEquals(200, r1.statusCode());
        assertEquals("session-1", r1.body());

        var r2 = shim.send(DUMMY_CLIENT, HttpRequest.newBuilder(URI.create("http://localhost:4096/config"))
            .method("PATCH", HttpRequest.BodyPublishers.noBody()).build());
        assertEquals("config-ok", r2.body());

        // Live run generates a different sessionId ("ses_live") than what was recorded
        // ("ses_recorded") - the shim must still match this as the 3rd call in sequence.
        var r3 = shim.send(DUMMY_CLIENT, HttpRequest.newBuilder(URI.create("http://localhost:4096/session/ses_live/message"))
            .POST(HttpRequest.BodyPublishers.noBody()).build());
        assertEquals("judge-response", r3.body());
    }

    @Test
    void throwsWhenReplayFileExhausted(@TempDir Path tmp) throws Exception {
        Path replayFile = tmp.resolve("replay.jsonl");
        writeExchange(replayFile, "POST", "http://localhost:4096/session", 200, "only-one");

        ReplayHttpShim shim = new ReplayHttpShim(replayFile, true);
        HttpRequest req = HttpRequest.newBuilder(URI.create("http://localhost:4096/session"))
            .POST(HttpRequest.BodyPublishers.noBody()).build();

        shim.send(DUMMY_CLIENT, req);
        IllegalStateException ex = assertThrows(IllegalStateException.class, () -> shim.send(DUMMY_CLIENT, req));
        assertTrue(ex.getMessage().contains("exhausted"));
    }

    @Test
    void throwsOnMethodMismatch(@TempDir Path tmp) throws Exception {
        Path replayFile = tmp.resolve("replay.jsonl");
        writeExchange(replayFile, "POST", "http://localhost:4096/session", 200, "{}");

        ReplayHttpShim shim = new ReplayHttpShim(replayFile, true);
        HttpRequest wrongMethod = HttpRequest.newBuilder(URI.create("http://localhost:4096/session"))
            .method("PATCH", HttpRequest.BodyPublishers.noBody()).build();

        IllegalStateException ex = assertThrows(IllegalStateException.class, () -> shim.send(DUMMY_CLIENT, wrongMethod));
        assertTrue(ex.getMessage().contains("mismatch"));
    }

    @Test
    void missingReplayFileFailsFast(@TempDir Path tmp) {
        Path missing = tmp.resolve("does-not-exist.jsonl");
        assertThrows(IllegalStateException.class, () -> new ReplayHttpShim(missing, true));
    }

    @Test
    void emptyReplayFileFailsFast(@TempDir Path tmp) throws Exception {
        Path empty = tmp.resolve("empty.jsonl");
        Files.writeString(empty, "");
        assertThrows(IllegalStateException.class, () -> new ReplayHttpShim(empty, true));
    }

    @Test
    void recordModeDoesNotRequireAnExistingFile(@TempDir Path tmp) {
        Path replayFile = tmp.resolve("new-replay.jsonl");
        assertFalse(Files.exists(replayFile));
        ReplayHttpShim shim = new ReplayHttpShim(replayFile, false);
        assertFalse(shim.isReplayMode());
    }
}
