package com.yami.policy;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.dataformat.yaml.YAMLFactory;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

/**
 * Lit le fichier de policy YAML et expose la configuration de scope.
 */
public class PolicyConfig {

    private final List<String> scanPaths;
    private final List<String> excludePaths;

    public PolicyConfig(Path policyFile) {
        ObjectMapper mapper = new ObjectMapper(new YAMLFactory());
        try {
            JsonNode root = mapper.readTree(Files.readString(policyFile));
            JsonNode scope = root.path("scope");
            this.scanPaths = readStringList(scope.path("scan_paths"));
            this.excludePaths = readStringList(scope.path("exclude"));
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    private static List<String> readStringList(JsonNode node) {
        if (node.isMissingNode() || !node.isArray()) {
            return List.of();
        }
        return node.findValuesAsText("");
    }

    public List<String> scanPaths() {
        return scanPaths;
    }

    public List<String> excludePaths() {
        return excludePaths;
    }
}
