package com.yami.github;

import com.yami.core.RunResult;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.List;
import java.util.stream.Collectors;

/**
 * Writer pour les outputs GitHub Actions ({@code $GITHUB_OUTPUT}).
 *
 * <p>Écrit les valeurs au format standard {@code name=value} ou heredoc
 * {@code name<<EOF ... EOF} pour les valeurs multilignes. Ouvre le fichier
 * en mode {@code CREATE + APPEND} pour ne pas écraser d'éventuelles entrées
 * précédentes écrites par d'autres étapes du même job.
 */
public class ActionsOutput {

    private static final String EOF_DELIMITER = "EOF";

    /**
     * Écrit les outputs de Yami dans le fichier GitHub Actions.
     *
     * @param outputFile chemin vers le fichier {@code GITHUB_OUTPUT}
     * @param result     résultat du pipeline
     */
    public static void write(Path outputFile, RunResult result) {
        StringBuilder sb = new StringBuilder();

        if (result.auditJson() != null) {
            sb.append("audit-json=").append(result.auditJson()).append("\n");
        }

        sb.append("findings-count=").append(result.findingsCount()).append("\n");

        List<String> prUrls = result.prUrls();
        if (!prUrls.isEmpty()) {
            sb.append("pr-url<<").append(EOF_DELIMITER).append("\n");
            sb.append(prUrls.stream().collect(Collectors.joining("\n"))).append("\n");
            sb.append(EOF_DELIMITER).append("\n");
        }

        try {
            Files.writeString(outputFile, sb.toString(),
                StandardOpenOption.CREATE, StandardOpenOption.APPEND);
        } catch (IOException e) {
            throw new java.io.UncheckedIOException("Failed to write GitHub Actions outputs", e);
        }
    }
}
