package com.yami.policy;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;

class PolicyConfigTest {

    private Path writePolicy(Path tmp) throws IOException {
        Path policy = tmp.resolve("yami.yml");
        Files.writeString(policy, """
            scope:
              scan_paths:
                - "**/*.tf"
                - "Dockerfile"
              exclude:
                - "node_modules/**"
            """);
        return policy;
    }

    @Test
    void readsScanPathsFromPolicyWhenNoOverride(@TempDir Path tmp) throws IOException {
        PolicyConfig config = new PolicyConfig(writePolicy(tmp));
        assertEquals(List.of("**/*.tf", "Dockerfile"), config.scanPaths());
        assertEquals(List.of("node_modules/**"), config.excludePaths());
    }

    @Test
    void emptyOverrideFallsBackToPolicy(@TempDir Path tmp) throws IOException {
        PolicyConfig config = new PolicyConfig(writePolicy(tmp), List.of());
        assertEquals(List.of("**/*.tf", "Dockerfile"), config.scanPaths());
    }

    @Test
    void nonEmptyOverrideReplacesScanPathsButNotExclude(@TempDir Path tmp) throws IOException {
        PolicyConfig config = new PolicyConfig(writePolicy(tmp), List.of("pom.xml", "**/*.yml"));
        assertEquals(List.of("pom.xml", "**/*.yml"), config.scanPaths());
        assertEquals(List.of("node_modules/**"), config.excludePaths());
    }
}
