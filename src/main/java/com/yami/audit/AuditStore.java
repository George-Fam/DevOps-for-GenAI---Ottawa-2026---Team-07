package com.yami.audit;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.yami.core.Decision;
import com.yami.core.RiskContextPacket;
import com.yami.core.VerificationResult;

import java.io.IOException;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.sql.Statement;

/**
 * Append-only audit log, one row per pipeline run. Decision/VerificationResult are stored
 * as JSON blobs rather than normalized columns - this table exists for the PR report and
 * for proving what happened after the fact, not for querying.
 */
public class AuditStore implements AutoCloseable {

    private static final String SCHEMA = """
        CREATE TABLE IF NOT EXISTS runs (
            id INTEGER PRIMARY KEY AUTOINCREMENT,
            packet_hash TEXT NOT NULL,
            decision_json TEXT NOT NULL,
            verification_json TEXT,
            fallback_mode INTEGER NOT NULL,
            created_at TEXT NOT NULL DEFAULT (strftime('%Y-%m-%dT%H:%M:%fZ', 'now'))
        )""";

    private final Connection connection;
    private final ObjectMapper mapper = new ObjectMapper();

    public AuditStore(Path dbFile) {
        try {
            this.connection = DriverManager.getConnection("jdbc:sqlite:" + dbFile);
            try (Statement st = connection.createStatement()) {
                st.execute(SCHEMA);
            }
        } catch (SQLException e) {
            throw new RuntimeException(e);
        }
    }

    public void record(RiskContextPacket packet, Decision decision, VerificationResult verification) {
        String decisionJson;
        String verificationJson;
        try {
            decisionJson = mapper.writeValueAsString(decision);
            verificationJson = verification == null ? null : mapper.writeValueAsString(verification);
        } catch (IOException e) {
            throw new RuntimeException(e);
        }

        String sql = "INSERT INTO runs (packet_hash, decision_json, verification_json, fallback_mode) VALUES (?, ?, ?, ?)";
        try (PreparedStatement ps = connection.prepareStatement(sql)) {
            ps.setString(1, packet.packetHash());
            ps.setString(2, decisionJson);
            ps.setString(3, verificationJson);
            ps.setInt(4, decision.fallbackMode() ? 1 : 0);
            ps.executeUpdate();
        } catch (SQLException e) {
            throw new RuntimeException(e);
        }
    }

    @Override
    public void close() {
        try {
            connection.close();
        } catch (SQLException e) {
            throw new RuntimeException(e);
        }
    }
}
