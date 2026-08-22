package com.yami;

import com.yami.audit.AuditStore;
import com.yami.audit.KillSwitch;
import com.yami.audit.TokenBudget;
import com.yami.core.Decision;
import com.yami.core.Finding;
import com.yami.core.PatchReport;
import com.yami.core.RiskContextPacket;
import com.yami.core.RunResult;
import com.yami.core.VerificationResult;
import com.yami.github.GithubAdapter;
import com.yami.governance.GovernanceIntegrity;
import com.yami.investigator.Investigator;
import com.yami.opencode.OpenCodeClient;
import com.yami.opencode.OpenCodeServer;
import com.yami.opencode.PermissionInjector;
import com.yami.opencode.ReplayHttpShim;
import com.yami.opencode.SessionExporter;
import com.yami.opencode.SkillRouter;
import com.yami.policy.PolicyConfig;
import com.yami.policy.PolicyEngine;
import com.yami.scanner.CheckovAdapter;
import com.yami.scanner.CicdRules;
import com.yami.scanner.TrivyAdapter;
import com.yami.verifier.ActionlintYamlStrategy;
import com.yami.verifier.DeviationDiff;
import com.yami.verifier.HclBlockReplacer;
import com.yami.verifier.TerraformStrategy;
import com.yami.verifier.TrivyStrategy;
import com.yami.verifier.Verifier;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * Orchestrateur principal (harness) du pipeline Yami.
 *
 * <p>Flux complet :
 * 0. Lire scope.scan_paths
 * 1. Démarrer OpenCode serve/run
 * 2. Scanner (checkov + CicdRules)
 * 3. Normaliser + hasher
 * 4. Routage finding → skill
 * 4bis. Veto constitutionnel (Java, pré-Judge)
 * 5. Judge → Decision
 * 6. Surgeon → PatchReport
 * 7. Vérifier (stratégie par source)
 * 8. Deviation diff (Java)
 * 9. Publisher → branche + PR
 * 10. Assembler audit.json
 */
public class Harness {

    private static final String OPENCODE_HOST = "127.0.0.1";
    private static final int OPENCODE_PORT = 4096;

    private final Path repoDir;
    private final Path policyFile;
    private final String githubToken;
    private final boolean replayMode;
    private final OpenCodeServer openCodeServer;
    private final OpenCodeClient openCodeClient;
    private final PermissionInjector permissionInjector;
    private final SkillRouter skillRouter;
    private final TokenBudget tokenBudget;
    private final PolicyEngine policyEngine;
    private final PolicyConfig policyConfig;
    private final CheckovAdapter checkovAdapter;
    private final CicdRules cicdRules;
    private final TrivyAdapter trivyAdapter;
    private final Investigator investigator;
    private final Verifier verifier;
    private final DeviationDiff deviationDiff;
    private final GithubAdapter githubAdapter;
    private final AuditStore auditStore;
    private final SessionExporter sessionExporter;
    private final Path auditDir;
    private final List<Path> sessionExports = new ArrayList<>();
    private final List<VerificationResult> verifications = new ArrayList<>();
    private final List<String> prUrls = new ArrayList<>();

    /**
     * @param replayMode false = appels OpenCode réels, enregistrés dans replayFile
     *                   (record) ; true = aucun appel réseau, réponses rejouées
     *                   depuis replayFile (Acte 5 : Bedrock down / démo offline).
     * @param replayFile fichier JSONL des échanges HTTP Java↔OpenCode.
     * @param scanPathsOverride depuis l'input {@code scope} de l'action (YAMI_SCOPE) —
     *                          remplace {@code scope.scan_paths} de la policy quand
     *                          non vide.
     */
    public Harness(Path repoDir, Path policyFile, String githubToken, Path auditDb,
                   boolean replayMode, Path replayFile, List<String> scanPathsOverride) {
        this.repoDir = repoDir;
        this.policyFile = policyFile;
        this.githubToken = githubToken;
        this.replayMode = replayMode;
        this.openCodeServer = new OpenCodeServer(OPENCODE_HOST, OPENCODE_PORT, repoDir);
        ReplayHttpShim shim = new ReplayHttpShim(replayFile, replayMode);
        System.out.println("[Harness] OpenCode HTTP shim: " + (replayMode ? "REPLAY" : "RECORD") + " (" + replayFile + ")");
        this.openCodeClient = new OpenCodeClient("http://" + OPENCODE_HOST + ":" + OPENCODE_PORT, Duration.ofSeconds(120), shim);
        this.permissionInjector = new PermissionInjector();
        this.skillRouter = new SkillRouter();
        this.tokenBudget = new TokenBudget(1_000_000); // hard cap configurable
        this.policyEngine = new PolicyEngine();
        this.policyConfig = new PolicyConfig(policyFile, scanPathsOverride);
        this.checkovAdapter = new CheckovAdapter();
        this.cicdRules = new CicdRules();
        this.trivyAdapter = new TrivyAdapter();
        this.investigator = new Investigator();
        this.verifier = new Verifier(List.of(
            new TerraformStrategy(checkovAdapter),
            new ActionlintYamlStrategy(),
            new TrivyStrategy()
        ));
        this.deviationDiff = new DeviationDiff();
        this.githubAdapter = new GithubAdapter(repoDir, githubToken);
        this.auditStore = new AuditStore(auditDb);
        this.auditDir = repoDir.resolve(".yami-audit");
        this.sessionExporter = new SessionExporter(auditDir);
    }

    /**
     * @return the run's result, including {@code success = false} whenever
     *         the pipeline must fail the gate closed. Callers must fail the
     *         process (non-zero exit) on {@code !success} — this is a
     *         security gate, so a systemic failure (bad credentials, killed
     *         run, governance mismatch) must never look like a passing check.
     */
    public RunResult run() {
        if (KillSwitch.isDisabled()) {
            System.err.println("[Harness] YAMI_DISABLED=true — aborting run");
            return new RunResult(null, 0, List.of(), false);
        }

        System.out.println("[Harness] Starting Yami pipeline");

        // 0. Governance integrity check
        GovernanceIntegrity.Result govResult = GovernanceIntegrity.verify(repoDir);
        if (!govResult.passed()) {
            System.err.println("[Harness] GOVERNANCE INTEGRITY FAILED: " + govResult.details());
            KillSwitch.trigger("governance manifest mismatch: " + govResult.details());
            githubAdapter.escalateToHumanReview("Yami governance integrity check failed — .opencode/ artefacts do not match the committed manifest. Human review required.");
            return new RunResult(null, 0, List.of(), false);
        }
        System.out.println("[Harness] Governance integrity verified");

        // 1. Start opencode serve (skipped in replay mode — no live model calls, no
        // network needed at all; the ReplayHttpShim answers every OpenCode HTTP call).
        if (!replayMode) {
            Path serverLog = auditDir.resolve("opencode-serve.log");
            try {
                Files.createDirectories(auditDir);
            } catch (IOException e) {
                throw new UncheckedIOException(e);
            }
            System.out.println("[Harness] Starting opencode serve on " + OPENCODE_HOST + ":" + OPENCODE_PORT + " (log: " + serverLog + ")");
            openCodeServer.start(serverLog, Duration.ofSeconds(30));
            System.out.println("[Harness] opencode serve is ready");
        }

        List<Finding> findings = List.of();
        Path auditJson = null;

        try {
            // 2. Scan
            findings = scan();
            System.out.println("[Harness] Found " + findings.size() + " findings");

            // 3. Build context packet
            Path terraformDir = repoDir;
            List<String> changedFiles = githubAdapter.changedFiles();
            Map<String, String> beforeAfter = githubAdapter.diffsFor(changedFiles);
            String policyVersion = "pivot-v2.2";
            RiskContextPacket packet = investigator.buildPacket(repoDir, terraformDir, findings, changedFiles, beforeAfter, policyVersion);

            // Process each finding
            int failureCount = 0;
            for (Finding finding : findings) {
                if (KillSwitch.isDisabled()) {
                    System.err.println("[Harness] Kill switch triggered mid-run — escalating remaining findings without further agent calls");
                    githubAdapter.escalateToHumanReview("Yami pipeline halted (kill switch triggered) before processing " + finding.ruleId() + ". Human review required.");
                    failureCount++;
                    continue;
                }
                try {
                    processFinding(finding, packet);
                } catch (Exception e) {
                    System.err.println("[Harness] Error processing finding " + finding.ruleId() + ": " + e.getMessage());
                    githubAdapter.escalateToHumanReview("Error processing finding " + finding.ruleId() + ": " + e.getMessage());
                    failureCount++;
                }
            }

            // 10. Assemble audit.json
            String govManifestHash = hashFile(repoDir.resolve(".opencode/MANIFEST.json"));
            auditJson = sessionExporter.assembleAuditJson(sessionExports, govManifestHash, packet.packetHash(), verifications);
            System.out.println("[Harness] Pipeline complete — audit trail: " + auditStore + " ; audit.json: " + auditJson);
            if (failureCount > 0) {
                System.err.println("[Harness] " + failureCount + "/" + findings.size() + " finding(s) failed processing — failing the gate closed");
            }
            return new RunResult(auditJson, findings.size(), List.copyOf(prUrls), failureCount == 0);
        } finally {
            if (!replayMode) {
                openCodeServer.stop();
            }
        }
    }

    private List<Finding> scan() {
        System.out.println("[Harness] Scanning with scope: " + policyConfig.scanPaths());
        List<Finding> checkovFindings = scanOrDegrade("checkov",
            () -> checkovAdapter.scan(repoDir, policyConfig.scanPaths(), policyConfig.excludePaths()));
        List<Finding> cicdFindings = scanOrDegrade("cicd-rules",
            () -> cicdRules.evaluate(repoDir, policyConfig.scanPaths(), policyConfig.excludePaths()));
        List<Finding> trivyFindings = scanOrDegrade("trivy",
            () -> trivyAdapter.scan(repoDir, policyConfig.scanPaths(), policyConfig.excludePaths()));
        return java.util.stream.Stream.of(checkovFindings, cicdFindings, trivyFindings)
            .flatMap(List::stream)
            .toList();
    }

    /**
     * A scanner hitting a transient external failure (rate limit, network blip)
     * shouldn't take down the whole run - the other scanners still have real
     * findings to report. Logs and degrades to an empty result rather than
     * letting one scanner's exception abort scanning entirely.
     */
    private List<Finding> scanOrDegrade(String scannerName, java.util.function.Supplier<List<Finding>> scan) {
        try {
            return scan.get();
        } catch (RuntimeException e) {
            System.err.println("[Harness] " + scannerName + " scan failed, continuing without it: " + e.getMessage());
            return List.of();
        }
    }

    private void processFinding(Finding finding, RiskContextPacket packet) {
        System.out.println("[Harness] Processing finding: " + finding.ruleId() + " @ " + finding.resource());

        // 4. Route to skill
        List<String> skills = skillRouter.route(finding);

        // 4bis. Constitutional veto
        Decision veto = policyEngine.veto(finding);
        if (veto != null) {
            System.out.println("[Harness] Constitutional veto triggered for " + finding.ruleId());
            githubAdapter.escalateToHumanReview(veto.reason());
            auditStore.record(packet, veto, null, resolveCommitHash());
            return;
        }

        // 5. Judge
        String judgePrompt = buildJudgePrompt(finding, packet, skills);
        String judgePermission = permissionInjector.buildPermissionJson(
            List.of(repoDir.resolve(finding.file()).getParent().toString()),
            List.of(),
            List.of()
        );
        OpenCodeClient.JudgeInvocation judgeInvocation = openCodeClient.invokeJudge(judgePrompt, judgePermission);
        Decision decision = judgeInvocation.decision();
        recordSession(judgeInvocation.sessionId(), "judge", List.of(finding), decision, null, judgeInvocation.tokensUsed());
        System.out.println("[Harness] Judge decision: " + decision.outcome() + " (confidence=" + decision.confidence() + ")");

        if (decision.outcome() != Decision.DecisionType.SAFE_FIX) {
            githubAdapter.escalateToHumanReview(decision.reason());
            auditStore.record(packet, decision, null, resolveCommitHash());
            return;
        }

        // 6. Surgeon — snapshot the target file first so we can diff what the Surgeon
        // actually touched against the finding's own location (deviation detection is
        // Java's job, never self-reported by the Surgeon).
        Path targetFile = repoDir.resolve(finding.file());
        Path beforeSnapshot = snapshot(targetFile);
        try {
            String surgeonPrompt = buildSurgeonPrompt(finding, decision);
            String surgeonPermission = permissionInjector.buildPermissionJson(
                List.of(repoDir.resolve(finding.file()).getParent().toString()),
                List.of(targetFile.toString()),
                List.of()
            );
            OpenCodeClient.SurgeonInvocation surgeonInvocation = openCodeClient.invokeSurgeon(surgeonPrompt, surgeonPermission);
            PatchReport patchReport = surgeonInvocation.patchReport();
            recordSession(surgeonInvocation.sessionId(), "surgeon", List.of(finding), null, patchReport, surgeonInvocation.tokensUsed());
            System.out.println("[Harness] Surgeon modified: " + patchReport.filesModified());

            // 7. Verify
            VerificationResult verification = verifier.verify(repoDir, finding, patchReport);
            verifications.add(verification);
            System.out.println("[Harness] Verification: " + verification.strategy() + " = " + (verification.passed() ? "PASS" : "FAIL"));

            // 8. Deviation diff (Java-computed, PatchReport.deviationsFromIntent is a signal only)
            DeviationDiff.DiffResult diff = deviationDiff.compute(beforeSnapshot, targetFile, finding);
            boolean scopeViolation = patchReport.filesModified().stream()
                .anyMatch(f -> !samePath(f, finding.file()));
            boolean deviated = !diff.intersectsFinding() || scopeViolation;
            if (deviated) {
                System.out.println("[Harness] Deviation detected for " + finding.ruleId()
                    + " — changed regions do not intersect the finding location, or files outside scope were touched");
            }

            // 9. Publisher — Auditor drafts the human summary first (read-only, can't
            // falsify the trace), Publisher (only agent scoped to git/gh) opens the PR.
            if (verification.passed() && !deviated) {
                String auditorPermission = permissionInjector.buildPermissionJson(
                    List.of(repoDir.toString()), List.of(), List.of());
                String auditorPrompt = buildAuditorPrompt(finding, decision, patchReport, verification, diff);
                OpenCodeClient.AuditorInvocation auditorInvocation = openCodeClient.invokeAuditor(auditorPrompt, auditorPermission);
                recordSession(auditorInvocation.sessionId(), "auditor", List.of(finding), decision, patchReport, auditorInvocation.tokensUsed());

                String publisherPermission = permissionInjector.buildPermissionJson(
                    List.of(), List.of(), List.of("git *", "gh pr *"));
                String publisherPrompt = buildPublisherPrompt(finding, patchReport, auditorInvocation.summaryMarkdown());
                OpenCodeClient.PublisherInvocation publisherInvocation = openCodeClient.invokePublisher(publisherPrompt, publisherPermission);
                recordSession(publisherInvocation.sessionId(), "publisher", List.of(finding), decision, patchReport, publisherInvocation.tokensUsed());

                if (publisherInvocation.prUrl() != null) {
                    System.out.println("[Harness] Opened PR: " + publisherInvocation.prUrl());
                    prUrls.add(publisherInvocation.prUrl());
                } else {
                    System.err.println("[Harness] Publisher did not report a PR URL — output: " + publisherInvocation.rawOutput());
                    githubAdapter.escalateToHumanReview("Publisher agent completed for " + finding.ruleId() + " but reported no PR URL. Check the publisher session export for details.");
                }
            } else if (deviated) {
                githubAdapter.escalateToHumanReview("Surgeon deviated from intent for " + finding.ruleId()
                    + " — changed regions outside the finding location (or files outside scope were modified). Patch kept in audit trail, not published.");
            } else {
                githubAdapter.escalateToHumanReview("Verification failed for " + finding.ruleId() + ": " + verification.strategy() + " " + verification.rescan());
            }

            auditStore.record(packet, decision, verification, resolveCommitHash());
        } finally {
            deleteQuietly(beforeSnapshot);
        }
    }

    private void recordSession(String sessionId, String agentName, List<Finding> findings,
                                Decision decision, PatchReport patchReport, long tokensUsed) {
        tokenBudget.consume(tokensUsed);
        sessionExports.add(sessionExporter.export(sessionId, agentName, findings, decision, patchReport, tokensUsed));
    }

    private Path snapshot(Path file) {
        try {
            Path tmp = Files.createTempFile("yami-pre-patch-", ".snapshot");
            Files.copy(file, tmp, StandardCopyOption.REPLACE_EXISTING);
            return tmp;
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    private static void deleteQuietly(Path file) {
        try {
            Files.deleteIfExists(file);
        } catch (IOException ignored) {
            // best-effort cleanup of a temp snapshot
        }
    }

    private boolean samePath(String candidate, String findingFile) {
        return repoDir.resolve(candidate).normalize().equals(repoDir.resolve(findingFile).normalize());
    }

    private String buildJudgePrompt(Finding finding, RiskContextPacket packet, List<String> skills) {
        return """
            Finding: %s
            Resource: %s
            File: %s:%d
            Message: %s
            Skills: %s
            Context packet: %s
            """.formatted(
            finding.ruleId(), finding.resource(), finding.file(), finding.line(),
            finding.message(), skills, packet
        );
    }

    private String buildSurgeonPrompt(Finding finding, Decision decision) {
        return """
            Remediation intent: %s
            Target file: %s
            Resource: %s
            Reason: %s
            """.formatted(
            decision.remediationIntent(), finding.file(), finding.resource(), decision.reason()
        );
    }

    private String buildAuditorPrompt(Finding finding, Decision decision, PatchReport patchReport,
                                       VerificationResult verification, DeviationDiff.DiffResult diff) {
        return """
            Finding: %s
            Resource: %s
            Severity: %s
            File: %s:%d

            Decision outcome: %s
            Decision confidence: %s
            Decision reason: %s
            Remediation intent: %s
            Skills used: %s

            Patch summary: %s
            Files modified: %s
            OWASP references: %s
            Surgeon self-reported deviations: %s

            Verification strategy: %s
            Verification passed: %s
            fmt=%s init=%s validate=%s rescan=%s

            Pre-patch file hash (sha256): %s
            Post-patch file hash (sha256): %s
            Java diff intersects finding location: %s

            Write the PR summary comment per your output format.
            """.formatted(
            finding.ruleId(), finding.resource(), finding.severity(), finding.file(), finding.line(),
            decision.outcome(), decision.confidence(), decision.reason(), decision.remediationIntent(), decision.skillsUsed(),
            patchReport.summary(), patchReport.filesModified(), patchReport.owaspReferences(), patchReport.deviationsFromIntent(),
            verification.strategy(), verification.passed(),
            verification.format(), verification.init(), verification.validate(), verification.rescan(),
            diff.preHash(), diff.postHash(), diff.intersectsFinding()
        );
    }

    private String buildPublisherPrompt(Finding finding, PatchReport patchReport, String auditorSummary) {
        return """
            Open a remediation PR for finding %s (resource %s).

            Branch name: yami-fix-%s-<short-random-suffix>
            Files already modified in the working tree by the Surgeon: %s
            Commit message: "Yami: fix %s — %s"
            PR title: "Yami fix: %s"
            PR body (use verbatim, it was written by the Auditor agent):

            %s
            """.formatted(
            finding.ruleId(), finding.resource(),
            finding.ruleId().toLowerCase().replaceAll("[^a-z0-9]+", "-"),
            patchReport.filesModified(),
            finding.ruleId(), patchReport.summary(),
            finding.ruleId(),
            auditorSummary
        );
    }

    private String resolveCommitHash() {
        String sha = System.getenv("GITHUB_SHA");
        return sha != null ? sha : "unknown";
    }

    private static String hashFile(Path file) {
        try {
            byte[] bytes = Files.readAllBytes(file);
            byte[] digest = MessageDigest.getInstance("SHA-256").digest(bytes);
            StringBuilder hex = new StringBuilder();
            for (byte b : digest) {
                hex.append(String.format("%02x", b));
            }
            return hex.toString();
        } catch (IOException e) {
            return "unavailable: " + e.getMessage();
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }
}
