package com.yami.github;

import com.yami.core.RunResult;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ActionsOutputTest {

    @Test
    void writesSimpleOutputs(@TempDir Path tempDir) throws Exception {
        Path outputFile = tempDir.resolve("github_output");
        RunResult result = new RunResult(
            tempDir.resolve("audit.json"),
            7,
            List.of()
        );

        ActionsOutput.write(outputFile, result);

        String content = Files.readString(outputFile);
        assertTrue(content.contains("audit-json=" + tempDir.resolve("audit.json")));
        assertTrue(content.contains("findings-count=7"));
        assertTrue(!content.contains("pr-url"));
    }

    @Test
    void writesMultilinePrUrlViaHeredoc(@TempDir Path tempDir) throws Exception {
        Path outputFile = tempDir.resolve("github_output");
        RunResult result = new RunResult(
            null,
            3,
            List.of(
                "https://github.com/org/repo/pull/101",
                "https://github.com/org/repo/pull/102"
            )
        );

        ActionsOutput.write(outputFile, result);

        String content = Files.readString(outputFile);
        assertTrue(!content.contains("audit-json"));
        assertTrue(content.contains("findings-count=3"));
        assertTrue(content.contains("pr-url<<EOF"));
        assertTrue(content.contains("https://github.com/org/repo/pull/101"));
        assertTrue(content.contains("https://github.com/org/repo/pull/102"));
        assertTrue(content.contains("EOF"));
    }

    @Test
    void skipsNullAuditJson(@TempDir Path tempDir) throws Exception {
        Path outputFile = tempDir.resolve("github_output");
        RunResult result = new RunResult(null, 0, List.of());

        ActionsOutput.write(outputFile, result);

        String content = Files.readString(outputFile);
        assertTrue(!content.contains("audit-json"));
        assertTrue(content.contains("findings-count=0"));
    }

    @Test
    void appendsToExistingFile(@TempDir Path tempDir) throws Exception {
        Path outputFile = tempDir.resolve("github_output");
        Files.writeString(outputFile, "previous=value\n");

        RunResult result = new RunResult(null, 5, List.of());
        ActionsOutput.write(outputFile, result);

        String content = Files.readString(outputFile);
        assertTrue(content.contains("previous=value"));
        assertTrue(content.contains("findings-count=5"));
    }
}
