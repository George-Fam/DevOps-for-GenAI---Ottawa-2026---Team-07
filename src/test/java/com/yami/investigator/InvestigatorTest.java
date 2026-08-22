package com.yami.investigator;

import com.yami.core.Finding;
import com.yami.core.RiskContextPacket;
import com.yami.scanner.CheckovAdapter;
import org.junit.jupiter.api.Test;

import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class InvestigatorTest {

    private final Investigator investigator = new Investigator();
    private static final Path FIXTURE = Path.of("fixtures/safe_fix");

    @Test
    void buildsPacketFromRealFixtureAndFindings() {
        List<Finding> findings = new CheckovAdapter().scan(FIXTURE);

        RiskContextPacket packet = investigator.buildPacket(
            FIXTURE, FIXTURE, findings, List.of("main.tf"), Map.of(), "v4");

        assertEquals(findings.size(), packet.findings().size());
        assertEquals("v4", packet.policyVersion());
        assertEquals(List.of("main.tf"), packet.changedFiles());

        assertTrue(packet.known().stream().anyMatch(k -> k.equals("aws_s3_bucket.data.bucket = yami-demo-bucket")));
        assertTrue(packet.known().stream().anyMatch(k ->
                k.equals("aws_s3_bucket_public_access_block.data references aws_s3_bucket.data")),
            "the public_access_block sibling references our bucket by id and should be discovered");
        assertTrue(packet.terraformRelations().stream().anyMatch(r ->
            r.equals("aws_s3_bucket_public_access_block.data -> aws_s3_bucket.data")));

        // none of the sibling resources this fixture's findings need (versioning, encryption, ...)
        // exist in the file, so every rule with a known expected sibling should surface as unknown
        assertTrue(packet.unknown().stream().anyMatch(f -> f.contains("aws_s3_bucket_versioning")));
        assertTrue(packet.unknown().stream().anyMatch(f -> f.contains("aws_s3_bucket_server_side_encryption_configuration")));

        assertFalse(packet.allowedActions().isEmpty());
        assertEquals(64, packet.packetHash().length(), "sha-256 hex digest");
    }

    @Test
    void hashIsStableForIdenticalInput() {
        List<Finding> findings = new CheckovAdapter().scan(FIXTURE);
        RiskContextPacket a = investigator.buildPacket(FIXTURE, FIXTURE, findings, List.of("main.tf"), Map.of(), "v4");
        RiskContextPacket b = investigator.buildPacket(FIXTURE, FIXTURE, findings, List.of("main.tf"), Map.of(), "v4");
        assertEquals(a.packetHash(), b.packetHash());
    }

    @Test
    void noDeployingWorkflowsWhenNoWorkflowsDirExists() {
        RiskContextPacket packet = investigator.buildPacket(FIXTURE, FIXTURE, List.of(), List.of(), Map.of(), "v4");
        assertTrue(packet.deployingWorkflows().isEmpty());
    }
}
