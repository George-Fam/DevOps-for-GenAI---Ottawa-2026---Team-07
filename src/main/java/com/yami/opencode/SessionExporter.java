package com.yami.opencode;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.List;

/**
 * Exporte les sessions OpenCode (fichiers lus, éditions, appels Bedrock, tokens)
 * en un JSON structuré pour l'audit.
 *
 * <p>Usage : le Harness appelle {@link #export(String, List)} après chaque
 * invocation d'agent pour accumuler la trace machine.
 */
public class SessionExporter {

    private final ObjectMapper mapper = new ObjectMapper();
    private final Path auditDir;

    public SessionExporter(Path auditDir) {
        this.auditDir = auditDir;
        try {
            Files.createDirectories(auditDir);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    /**
     * Exporte une session d'agent.
     *
     * @param sessionId   identifiant de session OpenCode
     * @param agentName   nom de l'agent (judge, surgeon, publisher, auditor)
     * @param findings    findings traités dans cette session
     * @param decision    décision prise (pour Judge)
     * @param patchReport rapport de patch (pour Surgeon)
     * @param tokensUsed  tokens consommés (approximatif, depuis la réponse API)
     * @return chemin du fichier exporté
     */
    public Path export(String sessionId, String agentName, List<com.yami.core.Finding> findings,
                       com.yami.core.Decision decision, com.yami.core.PatchReport patchReport,
                       long tokensUsed) {
        ObjectNode root = mapper.createObjectNode();
        root.put("sessionId", sessionId);
        root.put("agent", agentName);
        root.put("timestamp", Instant.now().toString());
        root.put("tokensUsed", tokensUsed);

        ArrayNode findingsArray = root.putArray("findings");
        for (com.yami.core.Finding f : findings) {
            ObjectNode fn = findingsArray.addObject();
            fn.put("ruleId", f.ruleId());
            fn.put("resource", f.resource());
            fn.put("file", f.file());
            fn.put("severity", f.severity().name());
            fn.put("source", f.source().name());
        }

        if (decision != null) {
            ObjectNode d = root.putObject("decision");
            d.put("outcome", decision.outcome().name());
            d.put("confidence", decision.confidence());
            d.put("reason", decision.reason());
        }

        if (patchReport != null) {
            ObjectNode p = root.putObject("patchReport");
            ArrayNode files = p.putArray("filesModified");
            for (String f : patchReport.filesModified()) {
                files.add(f);
            }
            ArrayNode deviations = p.putArray("deviationsFromIntent");
            for (String d : patchReport.deviationsFromIntent()) {
                deviations.add(d);
            }
        }

        Path out = auditDir.resolve(sessionId + "_" + agentName + ".json");
        try {
            mapper.writerWithDefaultPrettyPrinter().writeValue(out.toFile(), root);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
        return out;
    }

    /**
     * Assemble le fichier audit.json final à partir de tous les exports de session
     * et des hashes.
     */
    public Path assembleAuditJson(List<Path> sessionExports, String govManifestHash,
                                   String findingsHash, com.yami.core.VerificationResult verification) {
        ObjectNode root = mapper.createObjectNode();
        root.put("timestamp", Instant.now().toString());
        root.put("govManifestHash", govManifestHash);
        root.put("findingsHash", findingsHash);

        ArrayNode sessions = root.putArray("sessions");
        for (Path p : sessionExports) {
            try {
                JsonNode session = mapper.readTree(p.toFile());
                sessions.add(session);
            } catch (IOException e) {
                throw new UncheckedIOException(e);
            }
        }

        if (verification != null) {
            ObjectNode v = root.putObject("verification");
            v.put("strategy", verification.strategy().name());
            v.put("passed", verification.passed());
        }

        Path out = auditDir.resolve("audit.json");
        try {
            mapper.writerWithDefaultPrettyPrinter().writeValue(out.toFile(), root);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
        return out;
    }
}
