package com.yami.governance;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class GovManifestToolTest {

    @Test
    void generatesManifestForOpencodeFiles(@TempDir Path tempDir) throws Exception {
        Path opencodeDir = tempDir.resolve(".opencode");
        Files.createDirectories(opencodeDir.resolve("agents"));
        Files.createDirectories(opencodeDir.resolve("skills").resolve("test"));

        Files.writeString(opencodeDir.resolve("agents").resolve("judge.md"), "---\nmode: subagent\n---\n");
        Files.writeString(opencodeDir.resolve("skills").resolve("test").resolve("SKILL.md"), "# test skill\n");
        Files.writeString(opencodeDir.resolve("opencode.json"), "{}\n");

        GovManifestTool tool = new GovManifestTool();
        GovManifest manifest = tool.generate(tempDir);

        assertNotNull(manifest);
        assertEquals("1", manifest.version());
        assertNotNull(manifest.generatedAt());
        assertNotNull(manifest.gitCommit());
        assertEquals(3, manifest.files().size());
        assertTrue(manifest.files().containsKey(".opencode/agents/judge.md"));
        assertTrue(manifest.files().containsKey(".opencode/skills/test/SKILL.md"));
        assertTrue(manifest.files().containsKey(".opencode/opencode.json"));

        // Les hashes doivent être des hex 64 caractères
        manifest.files().values().forEach(hash -> {
            assertEquals(64, hash.length());
        });
    }

    @Test
    void verifyPassesOnFreshManifest(@TempDir Path tempDir) throws Exception {
        Path opencodeDir = tempDir.resolve(".opencode");
        Files.createDirectories(opencodeDir.resolve("agents"));
        Files.writeString(opencodeDir.resolve("agents").resolve("judge.md"), "---\nmode: subagent\n---\n");

        GovManifestTool tool = new GovManifestTool();
        tool.generate(tempDir);
        tool.verify(tempDir); // ne doit pas lever d'exception
    }

    @Test
    void verifyFailsWhenFileModified(@TempDir Path tempDir) throws Exception {
        Path opencodeDir = tempDir.resolve(".opencode");
        Files.createDirectories(opencodeDir.resolve("agents"));
        Files.writeString(opencodeDir.resolve("agents").resolve("judge.md"), "---\nmode: subagent\n---\n");

        GovManifestTool tool = new GovManifestTool();
        tool.generate(tempDir);

        // Modifier le fichier après génération
        Files.writeString(opencodeDir.resolve("agents").resolve("judge.md"), "---\nmode: primary\n---\n");

        GovManifestException ex = assertThrows(GovManifestException.class, () -> tool.verify(tempDir));
        assertTrue(ex.getMessage().contains("Hash mismatch"));
    }

    @Test
    void verifyFailsWhenFileAdded(@TempDir Path tempDir) throws Exception {
        Path opencodeDir = tempDir.resolve(".opencode");
        Files.createDirectories(opencodeDir.resolve("agents"));
        Files.writeString(opencodeDir.resolve("agents").resolve("judge.md"), "---\nmode: subagent\n---\n");

        GovManifestTool tool = new GovManifestTool();
        tool.generate(tempDir);

        // Ajouter un nouveau fichier
        Files.writeString(opencodeDir.resolve("agents").resolve("surgeon.md"), "---\nmode: subagent\n---\n");

        GovManifestException ex = assertThrows(GovManifestException.class, () -> tool.verify(tempDir));
        assertTrue(ex.getMessage().contains("New file not listed"));
    }

    @Test
    void verifyFailsWhenFileMissing(@TempDir Path tempDir) throws Exception {
        Path opencodeDir = tempDir.resolve(".opencode");
        Files.createDirectories(opencodeDir.resolve("agents"));
        Path judgeFile = opencodeDir.resolve("agents").resolve("judge.md");
        Files.writeString(judgeFile, "---\nmode: subagent\n---\n");

        GovManifestTool tool = new GovManifestTool();
        tool.generate(tempDir);

        // Supprimer le fichier
        Files.delete(judgeFile);

        GovManifestException ex = assertThrows(GovManifestException.class, () -> tool.verify(tempDir));
        assertTrue(ex.getMessage().contains("missing on disk"));
    }

    @Test
    void governanceIntegrityReturnsPassedOnValidManifest(@TempDir Path tempDir) throws Exception {
        Path opencodeDir = tempDir.resolve(".opencode");
        Files.createDirectories(opencodeDir.resolve("agents"));
        Files.writeString(opencodeDir.resolve("agents").resolve("judge.md"), "---\nmode: subagent\n---\n");

        GovManifestTool tool = new GovManifestTool();
        tool.generate(tempDir);

        GovernanceIntegrity.Result result = GovernanceIntegrity.verify(tempDir);
        assertTrue(result.passed());
    }

    @Test
    void governanceIntegrityReturnsFailedOnModifiedManifest(@TempDir Path tempDir) throws Exception {
        Path opencodeDir = tempDir.resolve(".opencode");
        Files.createDirectories(opencodeDir.resolve("agents"));
        Files.writeString(opencodeDir.resolve("agents").resolve("judge.md"), "---\nmode: subagent\n---\n");

        GovManifestTool tool = new GovManifestTool();
        tool.generate(tempDir);

        Files.writeString(opencodeDir.resolve("agents").resolve("judge.md"), "modified");

        GovernanceIntegrity.Result result = GovernanceIntegrity.verify(tempDir);
        assertTrue(!result.passed());
        assertTrue(result.details().contains("Hash mismatch"));
    }
}
