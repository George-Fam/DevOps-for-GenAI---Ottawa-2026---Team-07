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
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class AuditStoreTest {

    @Test
    void recordsAndPersistsARun(@TempDir Path tempDir) throws SQLException {
        Path db = tempDir.resolve("audit.db");
        RiskContextPacket packet = new RiskContextPacket("hash123", "aws_s3_bucket.data", List.of(), Map.of(), List.of());
        Decision decision = new Decision(Decision.Outcome.HUMAN_REVIEW, "hash123", null, "needs a human", false);

        try (AuditStore store = new AuditStore(db)) {
            store.record(packet, decision, null);
        }

        try (Connection conn = DriverManager.getConnection("jdbc:sqlite:" + db);
             ResultSet rs = conn.createStatement().executeQuery("SELECT packet_hash, decision_json, fallback_mode FROM runs")) {
            assertTrue(rs.next());
            assertEquals("hash123", rs.getString("packet_hash"));
            assertTrue(rs.getString("decision_json").contains("HUMAN_REVIEW"));
            assertEquals(0, rs.getInt("fallback_mode"));
            assertTrue(!rs.next(), "exactly one row expected");
        }
    }
}
