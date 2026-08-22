package com.yami.audit;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.yami.core.Decision;
import com.yami.core.RiskContextPacket;
import com.yami.core.VerificationResult;

import java.io.IOException;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;

/**
 * Append-only, hash-chained audit log - "tamper-evident", not claimed immutable (per the
 * product plan's own framing). Each row's row_hash covers its own content plus the
 * previous row's row_hash, so altering any past row breaks the chain for every row after
 * it - detectable by recomputing hashes forward, without needing external signing.
 */
public class AuditStore implements AutoCloseable {

    private static final String SCHEMA = """
        CREATE TABLE IF NOT EXISTS runs (
            id INTEGER PRIMARY KEY AUTOINCREMENT,
            packet_hash TEXT NOT NULL,
            policy_version TEXT,
            commit_hash TEXT,
            decision_json TEXT NOT NULL,
            verification_json TEXT,
            fallback_mode INTEGER NOT NULL,
            previous_hash TEXT,
            row_hash TEXT NOT NULL,
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

    public void record(RiskContextPacket packet, Decision decision, VerificationResult verification, String commitHash) {
        String decisionJson;
        String verificationJson;
        try {
            decisionJson = mapper.writeValueAsString(decision);
            verificationJson = verification == null ? null : mapper.writeValueAsString(verification);
        } catch (IOException e) {
            throw new RuntimeException(e);
        }

        String previousHash = lastRowHash();
        String rowHash = hashRow(packet.packetHash(), packet.policyVersion(), commitHash,
            decisionJson, verificationJson, decision.fallbackMode(), previousHash);

        String sql = """
            INSERT INTO runs (packet_hash, policy_version, commit_hash, decision_json, verification_json,
                               fallback_mode, previous_hash, row_hash)
            VALUES (?, ?, ?, ?, ?, ?, ?, ?)""";
        try (PreparedStatement ps = connection.prepareStatement(sql)) {
            ps.setString(1, packet.packetHash());
            ps.setString(2, packet.policyVersion());
            ps.setString(3, commitHash);
            ps.setString(4, decisionJson);
            ps.setString(5, verificationJson);
            ps.setInt(6, decision.fallbackMode() ? 1 : 0);
            ps.setString(7, previousHash);
            ps.setString(8, rowHash);
            ps.executeUpdate();
        } catch (SQLException e) {
            throw new RuntimeException(e);
        }
    }

    private String lastRowHash() {
        String sql = "SELECT row_hash FROM runs ORDER BY id DESC LIMIT 1";
        try (Statement st = connection.createStatement();
             ResultSet rs = st.executeQuery(sql)) {
            return rs.next() ? rs.getString("row_hash") : null;
        } catch (SQLException e) {
            throw new RuntimeException(e);
        }
    }

    /**
     * Recomputes the chain from the first row and reports the id of the first row whose
     * stored row_hash doesn't match what its content + previous_hash would produce - the
     * tamper-evidence check. Returns -1 if the whole chain is intact.
     */
    public long verifyChainIntegrity() {
        String sql = "SELECT id, packet_hash, policy_version, commit_hash, decision_json, verification_json, "
            + "fallback_mode, previous_hash, row_hash FROM runs ORDER BY id ASC";
        try (Statement st = connection.createStatement();
             ResultSet rs = st.executeQuery(sql)) {
            String expectedPrevious = null;
            while (rs.next()) {
                String expected = hashRow(
                    rs.getString("packet_hash"), rs.getString("policy_version"), rs.getString("commit_hash"),
                    rs.getString("decision_json"), rs.getString("verification_json"),
                    rs.getInt("fallback_mode") == 1, expectedPrevious);
                if (!expected.equals(rs.getString("row_hash"))) {
                    return rs.getLong("id");
                }
                expectedPrevious = rs.getString("row_hash");
            }
            return -1;
        } catch (SQLException e) {
            throw new RuntimeException(e);
        }
    }

    private static String hashRow(String packetHash, String policyVersion, String commitHash,
                                   String decisionJson, String verificationJson, boolean fallbackMode,
                                   String previousHash) {
        String canonical = String.join("|",
            nullToEmpty(packetHash), nullToEmpty(policyVersion), nullToEmpty(commitHash),
            nullToEmpty(decisionJson), nullToEmpty(verificationJson), String.valueOf(fallbackMode),
            nullToEmpty(previousHash));
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256").digest(canonical.getBytes());
            StringBuilder hex = new StringBuilder();
            for (byte b : digest) {
                hex.append(String.format("%02x", b));
            }
            return hex.toString();
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }

    private static String nullToEmpty(String s) {
        return s == null ? "" : s;
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
