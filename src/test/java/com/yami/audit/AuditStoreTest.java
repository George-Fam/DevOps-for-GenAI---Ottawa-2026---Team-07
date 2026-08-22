package com.yami.audit;

import com.yami.core.Decision;
import com.yami.core.RiskContextPacket;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class AuditStoreTest {

    @Test
    void recordsAndPersistsARun(@TempDir Path tempDir) throws SQLException {
        Path db = tempDir.resolve("audit.db");
        RiskContextPacket packet = emptyPacket("hash123");
        Decision decision = new Decision(Decision.DecisionType.HUMAN_REVIEW, "CLOUD-001", "needs a human", null, null, false, false);

        try (AuditStore store = new AuditStore(db)) {
            store.record(packet, decision, null, "abc123commit");
        }

        try (Connection conn = DriverManager.getConnection("jdbc:sqlite:" + db);
             ResultSet rs = conn.createStatement().executeQuery(
                 "SELECT packet_hash, commit_hash, decision_json, fallback_mode, previous_hash, row_hash FROM runs")) {
            assertTrue(rs.next());
            assertEquals("hash123", rs.getString("packet_hash"));
            assertEquals("abc123commit", rs.getString("commit_hash"));
            assertTrue(rs.getString("decision_json").contains("HUMAN_REVIEW"));
            assertEquals(0, rs.getInt("fallback_mode"));
            assertEquals(null, rs.getString("previous_hash"), "first row has no predecessor");
            assertEquals(64, rs.getString("row_hash").length(), "sha-256 hex digest");
            assertTrue(!rs.next(), "exactly one row expected");
        }
    }

    @Test
    void chainIsIntactAcrossMultipleRunsAndTamperingIsDetected(@TempDir Path tempDir) throws SQLException {
        Path db = tempDir.resolve("audit.db");
        Decision decision = new Decision(Decision.DecisionType.BLOCK, "CICD-001", "unsafe workflow", null, null, false, false);

        try (AuditStore store = new AuditStore(db)) {
            store.record(emptyPacket("hash1"), decision, null, "commit1");
            store.record(emptyPacket("hash2"), decision, null, "commit2");
            store.record(emptyPacket("hash3"), decision, null, "commit3");

            assertEquals(-1, store.verifyChainIntegrity(), "freshly written chain should be intact");
        }

        try (Connection conn = DriverManager.getConnection("jdbc:sqlite:" + db);
             Statement st = conn.createStatement()) {
            st.executeUpdate("UPDATE runs SET packet_hash = 'tampered' WHERE id = 2");
        }

        try (AuditStore store = new AuditStore(db)) {
            assertEquals(2, store.verifyChainIntegrity(), "tampering row 2 should be detected at row 2");
        }
    }

    private static RiskContextPacket emptyPacket(String packetHash) {
        return new RiskContextPacket(packetHash, List.of(), List.of(), Map.of(),
            List.of(), List.of(), List.of(), List.of(), List.of(), "v4");
    }
}
