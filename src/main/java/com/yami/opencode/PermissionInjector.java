package com.yami.opencode;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;

import java.util.List;

/**
 * Construit le JSON {@code OPENCODE_PERMISSION} avec Jackson uniquement
 * (jamais par concaténation de chaînes). Injecte le fichier cible pour le
 * Surgeon, les dirs scopés pour le Judge.
 */
public class PermissionInjector {

    private final ObjectMapper mapper = new ObjectMapper();

    public String buildPermissionJson(List<String> readDirs, List<String> editFiles, List<String> bashCommands) {
        ObjectNode root = mapper.createObjectNode();
        ObjectNode perms = root.putObject("permissions");

        ArrayNode readNode = perms.putArray("read");
        for (String dir : readDirs) {
            readNode.add(dir);
        }

        ArrayNode editNode = perms.putArray("edit");
        for (String file : editFiles) {
            editNode.add(file);
        }

        ArrayNode bashNode = perms.putArray("bash");
        for (String cmd : bashCommands) {
            bashNode.add(cmd);
        }

        try {
            return mapper.writeValueAsString(root);
        } catch (Exception e) {
            throw new IllegalStateException("failed to serialize permission JSON", e);
        }
    }
}
