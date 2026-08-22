package com.yami.opencode;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class PermissionInjectorTest {

    private final PermissionInjector injector = new PermissionInjector();
    private final ObjectMapper mapper = new ObjectMapper();

    @Test
    void buildsValidPermissionJson() throws Exception {
        String json = injector.buildPermissionJson(
            List.of("/repo/fixtures/safe_fix/"),
            List.of("/repo/fixtures/safe_fix/main.tf"),
            List.of()
        );

        JsonNode root = mapper.readTree(json);
        JsonNode perms = root.path("permissions");

        assertTrue(perms.path("read").isArray());
        assertEquals("/repo/fixtures/safe_fix/", perms.path("read").get(0).asText());
        assertTrue(perms.path("edit").isArray());
        assertEquals("/repo/fixtures/safe_fix/main.tf", perms.path("edit").get(0).asText());
        assertTrue(perms.path("bash").isArray());
    }

    @Test
    void emptyListsProduceEmptyArrays() throws Exception {
        String json = injector.buildPermissionJson(List.of(), List.of(), List.of());
        JsonNode root = mapper.readTree(json);
        JsonNode perms = root.path("permissions");

        assertEquals(0, perms.path("read").size());
        assertEquals(0, perms.path("edit").size());
        assertEquals(0, perms.path("bash").size());
    }
}
