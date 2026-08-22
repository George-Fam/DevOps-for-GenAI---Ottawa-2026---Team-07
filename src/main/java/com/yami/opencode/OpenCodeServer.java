package com.yami.opencode;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;

/**
 * Démarre et arrête {@code opencode serve} comme sous-processus du Harness.
 *
 * <p><b>HACKATHON WORKAROUND (issue #4)</b> : le serveur tourne actuellement dans
 * {@code repoDir} au lieu d'un répertoire neutre. OpenCode en mode serve refuse
 * les requêtes vers des chemins hors du répertoire de travail ("external_directory"
 * permission hang). La vraie correction (CLI one-shot avec
 * {@code OPENCODE_CONFIG_CONTENT}) est reportée post-hackathon.
 *
 * <p><b>Impact sécurité</b> : en mode serve dans {@code repoDir}, une PR malveillante
 * pourrait théoriquement injecter un {@code .opencode/agents/judge.md} local et
 * écraser le plancher constitutionnel (§3.2 du pivot). Cette dégradation est
 * temporaire et acceptée pour la démo ; la mitigation actuelle repose sur
 * {@link com.yami.governance.GovernanceIntegrity} qui vérifie le manifeste
 * chain-linked SHA-256 de {@code .opencode/} avant tout appel agent.
 *
 * <p>Le sous-processus hérite de l'environnement Java (donc des credentials AWS
 * pour le provider Bedrock — voir {@code opencode.json}). Le Judge/Surgeon
 * accèdent aux fichiers du repo scanné via les chemins absolus injectés dans
 * {@code OPENCODE_PERMISSION}.
 */
public class OpenCodeServer {

    private final String hostname;
    private final int port;
    private final Path repoDir;
    private Process process;

    public OpenCodeServer(String hostname, int port, Path repoDir) {
        this.hostname = hostname;
        this.port = port;
        this.repoDir = repoDir;
    }

    /**
     * Lance {@code opencode serve} et bloque jusqu'à ce qu'il accepte des
     * connexions HTTP (ou lève après {@code timeout}).
     */
    public void start(Path logFile, Duration timeout) {
        // HACKATHON WORKAROUND (issue #4): serve runs in repoDir to avoid external_directory hang
        // Proper fix (one-shot CLI with OPENCODE_CONFIG_CONTENT) deferred post-hackathon.
        ProcessBuilder pb = new ProcessBuilder(
            "opencode", "serve", "--hostname", hostname, "--port", String.valueOf(port), "--print-logs")
            .directory(repoDir.toFile())
            .redirectErrorStream(true)
            .redirectOutput(logFile.toFile());

        try {
            process = pb.start();
        } catch (IOException e) {
            throw new UncheckedIOException("failed to start 'opencode serve' - is it on PATH?", e);
        }

        Runtime.getRuntime().addShutdownHook(new Thread(this::stop));
        waitUntilReady(timeout, logFile);
    }

    private void waitUntilReady(Duration timeout, Path logFile) {
        Instant deadline = Instant.now().plus(timeout);
        while (Instant.now().isBefore(deadline)) {
            if (!process.isAlive()) {
                throw new IllegalStateException("'opencode serve' exited before becoming ready (exit="
                    + process.exitValue() + ") - see " + logFile);
            }
            // Loopback readiness probe only - connects, sends zero bytes, closes
            // immediately. There's no data channel to encrypt here.
            // nosemgrep: java.lang.security.audit.crypto.unencrypted-socket.unencrypted-socket
            try (Socket socket = new Socket()) {
                socket.connect(new InetSocketAddress(hostname, port), 500);
                return; // accepting connections
            } catch (IOException notReadyYet) {
                sleep(300);
            }
        }
        stop();
        throw new IllegalStateException("'opencode serve' did not become ready within " + timeout
            + " - see " + logFile + " (check AWS/Bedrock credentials and opencode.json)");
    }

    private static void sleep(long millis) {
        try {
            Thread.sleep(millis);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    public void stop() {
        if (process != null && process.isAlive()) {
            process.destroy();
            try {
                if (!process.waitFor(5, java.util.concurrent.TimeUnit.SECONDS)) {
                    process.destroyForcibly();
                }
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                process.destroyForcibly();
            }
        }
    }
}
