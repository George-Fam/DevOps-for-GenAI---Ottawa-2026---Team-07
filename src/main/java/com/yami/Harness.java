package com.yami;

import com.yami.audit.AuditStore;
import com.yami.audit.KillSwitch;
import com.yami.audit.TokenBudget;
import com.yami.core.Decision;
import com.yami.core.Finding;
import com.yami.core.PatchReport;
import com.yami.core.RiskContextPacket;
import com.yami.core.VerificationResult;
import com.yami.github.GithubAdapter;
import com.yami.governance.GovernanceIntegrity;
import com.yami.investigator.Investigator;
import com.yami.opencode.OpenCodeClient;
import com.yami.opencode.PermissionInjector;
import com.yami.opencode.SkillRouter;
import com.yami.policy.PolicyEngine;
import com.yami.scanner.CheckovAdapter;
import com.yami.scanner.CicdRules;
import com.yami.verifier.DeviationDiff;
import com.yami.verifier.HclBlockReplacer;
import com.yami.verifier.Verifier;

import java.nio.file.Path;
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

    private final Path repoDir;
    private final Path policyFile;
    private final String githubToken;
    private final OpenCodeClient openCodeClient;
    private final PermissionInjector permissionInjector;
    private final SkillRouter skillRouter;
    private final TokenBudget tokenBudget;
    private final PolicyEngine policyEngine;
    private final CheckovAdapter checkovAdapter;
    private final CicdRules cicdRules;
    private final Investigator investigator;
    private final Verifier verifier;
    private final DeviationDiff deviationDiff;
    private final GithubAdapter githubAdapter;
    private final AuditStore auditStore;

    public Harness(Path repoDir, Path policyFile, String githubToken, Path auditDb) {
        this.repoDir = repoDir;
        this.policyFile = policyFile;
        this.githubToken = githubToken;
        this.openCodeClient = new OpenCodeClient();
        this.permissionInjector = new PermissionInjector();
        this.skillRouter = new SkillRouter();
        this.tokenBudget = new TokenBudget(1_000_000); // hard cap configurable
        this.policyEngine = new PolicyEngine();
        this.checkovAdapter = new CheckovAdapter();
        this.cicdRules = new CicdRules();
        this.investigator = new Investigator();
        this.verifier = new Verifier(new HclBlockReplacer(), checkovAdapter);
        this.deviationDiff = new DeviationDiff();
        this.githubAdapter = new GithubAdapter(repoDir, githubToken);
        this.auditStore = new AuditStore(auditDb);
    }

    public void run() {
        if (KillSwitch.isDisabled()) {
            System.err.println("[Harness] YAMI_DISABLED=true — aborting run");
            return;
        }

        System.out.println("[Harness] Starting Yami pipeline");

        // 0. Governance integrity check
        GovernanceIntegrity.Result govResult = GovernanceIntegrity.verify(repoDir);
        if (!govResult.passed()) {
            System.err.println("[Harness] GOVERNANCE INTEGRITY FAILED: " + govResult.details());
            KillSwitch.trigger("governance manifest mismatch: " + govResult.details());
            githubAdapter.escalateToHumanReview("Yami governance integrity check failed — .opencode/ artefacts do not match the committed manifest. Human review required.");
            return;
        }
        System.out.println("[Harness] Governance integrity verified");

        // 2. Scan
        List<Finding> findings = scan();
        System.out.println("[Harness] Found " + findings.size() + " findings");

        // 3. Build context packet
        Path terraformDir = repoDir; // TODO: scope to scan_paths
        List<String> changedFiles = List.of(); // TODO: read from GITHUB_EVENT_PATH
        Map<String, String> beforeAfter = Map.of(); // TODO: read from git diff
        String policyVersion = "pivot-v2.2";
        RiskContextPacket packet = investigator.buildPacket(repoDir, terraformDir, findings, changedFiles, beforeAfter, policyVersion);

        // Process each finding
        for (Finding finding : findings) {
            try {
                processFinding(finding, packet);
            } catch (Exception e) {
                System.err.println("[Harness] Error processing finding " + finding.ruleId() + ": " + e.getMessage());
                githubAdapter.escalateToHumanReview("Error processing finding " + finding.ruleId() + ": " + e.getMessage());
            }
        }

        // 10. Assemble audit.json
        System.out.println("[Harness] Pipeline complete — audit stored in " + auditStore);
    }

    private List<Finding> scan() {
        List<Finding> checkovFindings = checkovAdapter.scan(repoDir);
        List<Finding> cicdFindings = cicdRules.evaluate(repoDir);
        // TODO: add Trivy scan for supply chain
        return java.util.stream.Stream.concat(checkovFindings.stream(), cicdFindings.stream()).toList();
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
        Decision decision = openCodeClient.invokeJudge(judgePrompt, judgePermission);
        System.out.println("[Harness] Judge decision: " + decision.outcome() + " (confidence=" + decision.confidence() + ")");

        if (decision.outcome() != Decision.DecisionType.SAFE_FIX) {
            githubAdapter.escalateToHumanReview(decision.reason());
            auditStore.record(packet, decision, null, resolveCommitHash());
            return;
        }

        // 6. Surgeon
        String surgeonPrompt = buildSurgeonPrompt(finding, decision);
        String surgeonPermission = permissionInjector.buildPermissionJson(
            List.of(repoDir.resolve(finding.file()).getParent().toString()),
            List.of(repoDir.resolve(finding.file()).toString()),
            List.of()
        );
        PatchReport patchReport = openCodeClient.invokeSurgeon(surgeonPrompt, surgeonPermission);
        System.out.println("[Harness] Surgeon modified: " + patchReport.filesModified());

        // 7. Verify
        // TODO: multi-strategy verification based on finding.source()
        // For now, delegate to legacy Verifier for Terraform findings
        VerificationResult verification = null;
        if (finding.source() == Finding.FindingSource.CHECKOV) {
            // Legacy verifier path — to be replaced with multi-strategy
            // verification = verifier.verify(repoDir, Path.of(finding.file()), ...);
        }

        // 8. Deviation diff
        // TODO: compute deviation diff

        // 9. Publisher
        if (verification != null && verification.passed()) {
            String branch = githubAdapter.createBranch("main");
            githubAdapter.commitChanges(branch, "Yami: fix " + finding.ruleId());
            String prUrl = githubAdapter.openPr(branch, "Yami fix: " + finding.ruleId(), buildPrBody(finding, decision, patchReport));
            System.out.println("[Harness] Opened PR: " + prUrl);
        } else {
            githubAdapter.escalateToHumanReview("Verification failed or not implemented for " + finding.ruleId());
        }

        auditStore.record(packet, decision, verification, resolveCommitHash());
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

    private String buildPrBody(Finding finding, Decision decision, PatchReport patchReport) {
        return """
            ## Yami Automated Fix

            - **Rule**: %s
            - **Resource**: %s
            - **Intent**: %s
            - **Files modified**: %s
            - **OWASP references**: %s

            ---
            *Generated by Yami — human review required before merge.*
            """.formatted(
            finding.ruleId(), finding.resource(), decision.remediationIntent(),
            patchReport.filesModified(), patchReport.owaspReferences()
        );
    }

    private String resolveCommitHash() {
        String sha = System.getenv("GITHUB_SHA");
        return sha != null ? sha : "unknown";
    }
}
