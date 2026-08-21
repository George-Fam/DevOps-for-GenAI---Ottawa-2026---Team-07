package com.yami.audit;

import com.yami.core.Decision;
import com.yami.core.RiskContextPacket;
import com.yami.core.VerificationResult;

import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;

public class AuditStore implements AutoCloseable {

    private final Connection connection;

    public AuditStore(Path dbFile) {
        throw new UnsupportedOperationException(
            "open jdbc:sqlite:" + dbFile + " and create schema: "
            + "runs(packet_hash, decision_json, verification_json, fallback_mode, created_at)");
    }

    public void record(RiskContextPacket packet, Decision decision, VerificationResult verification) {
        throw new UnsupportedOperationException("append-only insert, one row per pipeline run");
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
