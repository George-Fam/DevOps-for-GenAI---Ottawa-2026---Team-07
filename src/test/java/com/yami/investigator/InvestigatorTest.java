package com.yami.investigator;

import com.yami.core.Finding;
import com.yami.core.RiskContextPacket;
import com.yami.scanner.CheckovAdapter;
import org.junit.jupiter.api.Test;

import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class InvestigatorTest {

    private final Investigator investigator = new Investigator();

    @Test
    void buildsPacketFromRealFixtureAndFindings() {
        List<Finding> findings = new CheckovAdapter().scan(Path.of("fixtures/safe-fix"));

        RiskContextPacket packet = investigator.buildPacket("aws_s3_bucket.data", findings, Path.of("fixtures/safe-fix"));

        assertEquals("aws_s3_bucket.data", packet.resourceAddress());
        assertEquals("yami-demo-bucket", packet.knownFacts().get("bucket"));
        assertTrue(packet.knownFacts().get("referencedBy").contains("aws_s3_bucket_public_access_block.data"),
            "the public_access_block sibling references our bucket by id and should be discovered");

        // none of the sibling resources this fixture's findings need (versioning, encryption, ...)
        // exist in the file, so every rule with a known expected sibling should surface as unknown
        assertTrue(packet.unknownFacts().stream().anyMatch(f -> f.contains("aws_s3_bucket_versioning")));
        assertTrue(packet.unknownFacts().stream().anyMatch(f -> f.contains("aws_s3_bucket_server_side_encryption_configuration")));

        assertEquals(64, packet.packetHash().length(), "sha-256 hex digest");
    }

    @Test
    void hashIsStableForIdenticalInput() {
        List<Finding> findings = new CheckovAdapter().scan(Path.of("fixtures/safe-fix"));
        RiskContextPacket a = investigator.buildPacket("aws_s3_bucket.data", findings, Path.of("fixtures/safe-fix"));
        RiskContextPacket b = investigator.buildPacket("aws_s3_bucket.data", findings, Path.of("fixtures/safe-fix"));
        assertEquals(a.packetHash(), b.packetHash());
    }

    @Test
    void unknownResourceAddressThrows() {
        try {
            investigator.buildPacket("aws_s3_bucket.nope", List.of(), Path.of("fixtures/safe-fix"));
            assertFalse(true, "expected IllegalArgumentException");
        } catch (IllegalArgumentException expected) {
            // ok
        }
    }
}
