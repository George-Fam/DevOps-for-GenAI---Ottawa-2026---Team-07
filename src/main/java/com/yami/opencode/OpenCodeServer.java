package com.yami.opencode;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;

/**
 * Démarre et arrête {@code opencode serve} comme sous-processus du Harness.
 *
 * <p>Le sous-processus hérite de l'environnement Java (donc des credentials AWS
 * pour le provider Bedrock — voir {@code opencode.json}) mais tourne dans un
 * répertoire de travail neutre, jamais {@code repoDir}. Raison : OpenCode charge
 * un {@code .opencode/opencode.json} / {@code .opencode/agents/*.md} local au
 * répertoire de travail en plus de la config globale ({@code ~/.config/opencode}).
 * Si on démarrait le serveur dans le repo scanné, une PR malveillante pourrait
 * committer son propre {@code .opencode/agents/judge.md} et écraser le plancher
 * constitutionnel de Yami (§3.2 du pivot). Le répertoire de travail neutre
 * garantit que seule la config baked-in de l'image (gouvernée, hashée, vérifiée
 * par {@link com.yami.governance.GovernanceIntegrity}) s'applique — le Judge/
 * Surgeon accèdent quand même aux fichiers du repo scanné via les chemins
 * absolus injectés dans {@code OPENCODE_PERMISSION}.
 */
public class OpenCodeServer {

    private final String hostname;
    private final int port;
    private Process process;

    public OpenCodeServer(String hostname, int port) {
        this.hostname = hostname;
        this.port = port;
    }

    /**
     * Lance {@code opencode serve} et bloque jusqu'à ce qu'il accepte des
     * connexions HTTP (ou lève après {@code timeout}).
     */
    public void start(Path logFile, Duration timeout) {
        Path neutralCwd;
        try {
            neutralCwd = Files.createTempDirectory("yami-opencode-serve-cwd-");
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }

        ProcessBuilder pb = new ProcessBuilder(
            "opencode", "serve", "--hostname", hostname, "--port", String.valueOf(port), "--print-logs")
            .directory(neutralCwd.toFile())
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
