# Yami Operational Runbook

> **Project:** Yami - AI-Powered Security Gate for IaC, CI/CD, and Supply Chain
> **Theme:** Track 1 - Autonomous DevOps (AI-Powered CI/CD)
> **Version:** 1.0
> **Date:** 2026-08-22
> **Status:** Active

---

## 1. Architecture Overview

Yami runs as a **single Docker container** inside a GitHub Actions runner. The container hosts two processes:

- **Java process** (`com.yami.Main`): The governor. Orchestrates the pipeline, runs deterministic verification, and assembles the audit trail.
- **OpenCode process** (`opencode serve` or `opencode run`): The intelligence layer. Hosts 4 agents (Judge, Surgeon, Publisher, Auditor) that communicate with Java via HTTP on `localhost:4096`.

External dependencies: GitHub API (PRs, branches), Amazon Bedrock API (AI inference), tool registries (for binary downloads during Docker build).

---

## 2. Prerequisites

### 2.1 GitHub Repository Setup

- **Branch protection** enabled on `main` (requires reviews, prevents force-push)
- **GitHub Actions enabled**
- **OIDC provider** configured for AWS (preferred) OR scoped IAM key stored as GitHub secret

### 2.2 AWS Setup (Bedrock)

- IAM role with `bedrock:InvokeModel` permission only
- Trust policy allowing GitHub Actions OIDC
- Region: `us-east-1`

### 2.3 Local Development

```bash
# Java 21 (Temurin)
# Maven 3.9+
# Docker
git clone <repo>
cd yami
```

---

## 3. Deployment

### 3.1 GitHub Action Integration

Add `.github/workflows/yami.yml` to your repository:

```yaml
name: Yami Security Gate
on:
  pull_request:
    types: [opened, synchronize]
permissions:
  contents: write
  pull-requests: write
jobs:
  yami:
    runs-on: ubuntu-latest
    steps:
      - uses: actions/checkout@v5
      - uses: ./
        with:
          github-token: ${{ secrets.GITHUB_TOKEN }}
          policy-file: policies/yami.yml
```

### 3.2 Action Inputs

| Input | Required | Default | Description |
|---|---|---|---|
| `github-token` | Yes | - | GitHub token for PR creation |
| `policy-file` | No | `policies/yami.yml` | Path to policy YAML |
| `audit-db` | No | `yami-audit.db` | SQLite audit database path |
| `scope` | No | `''` | Override scan paths (comma-separated) |
| `replay-mode` | No | `false` | Enable replay mode for testing |
| `replay-file` | No | `replay.jsonl` | Replay file path |

### 3.3 Action Outputs

| Output | Description |
|---|---|
| `audit-json` | Path to generated `audit.json` |
| `findings-count` | Number of findings detected |
| `pr-url` | URL of remediation PR (if any) |

---

## 4. Normal Operation Flow

### 4.1 Pipeline Steps

```
Step 0:  KillSwitch check. YAMI_DISABLED=true? → abort
Step 0b: Governance integrity. Verify MANIFEST.json SHA-256
Step 1:  Scan. Run checkov + CicdRules + trivy on scope.scan_paths
         (trivy runs with --offline-scan: Java deps resolve from the Maven
         cache baked into the image at build time - no live Maven Central,
         no 429 rate limits; see GitHub issue #8)
Step 2:  Build RiskContextPacket. findings + changed files + git diff
Step 3:  For each finding:
    3a: Route to OWASP skill(s)
    3b: Constitutional veto (Java). PPE/secrets? → BLOCK
    3c: Judge. Read scoped dirs, emit Decision JSON
    3d: If SAFE_FIX → Surgeon. Edit target file, emit PatchReport
    3e: Verifier. terraform fmt/validate + re-scan
    3f: Deviation diff. Changed regions intersect finding location?
    3g: If verification passed → Publisher. Branch + PR
    3h: AuditStore. Record finding + decision + verification
Step 4:  Assemble audit.json. Hashes + sessions + results
```

### 4.2 Expected Runtime

- **Scan:** 10-30 seconds (depends on repo size)
- **Judge:** 5-15 seconds per finding (Bedrock latency)
- **Surgeon:** 5-15 seconds per finding
- **Verifier:** 10-30 seconds per finding (terraform init/validate)
- **Publisher:** 5-10 seconds per PR
- **Total:** 1-3 minutes per finding

---

## 5. Failure Modes and Escalation

### 5.1 Controlled Failure Table

| Failure | Symptom | Response | Human Action |
|---|---|---|---|
| **Constitutional veto** | PR comment: "BLOCK - constitutional veto" | Finding rejected, no patch | Review PR manually |
| **Judge HUMAN_REVIEW** | PR comment with reasoning | No patch applied | Review and decide |
| **Verifier FAIL** | PR comment: "Verification failed" | Patch rejected, trace preserved | Review patch manually |
| **Token budget exceeded** | Log: "KillSwitch triggered" | All writes stop | Check token usage, reset if needed |
| **Bedrock timeout** | Log: "HTTP timeout" | Retry 2x, then HUMAN_REVIEW | Check AWS status |
| **Governance manifest mismatch** | Log: "GOVERNANCE INTEGRITY FAILED" | KillSwitch triggers | Verify `.opencode/` files |
| **Invalid JSON from Judge** | Log: "JSON validation failed" | Retry 2x, then HUMAN_REVIEW | Review prompt |
| **Surgeon edits wrong file** | Deviation diff detects it | Escalate to human | Review scope |
| **Replay mode active** | Log: "fallbackMode: true" | Pipeline runs with recorded responses | Verify replay file |
| **Scanner failure (trivy/checkov)** | Log: "scan failed, continuing without it" | Degrades to empty result, other scanners continue | Check scanner logs; findings from that scanner are missing for this run |

> **Trivy offline-scan note (issue #8):** the Docker image bakes a warm Maven cache
> (`target/m2-repo` → `/root/.m2/repository`, exported by `make package`) and trivy
> runs with `--offline-scan`. Because `mvn package` precedes the Docker build, the
> cache always covers the scanned PR's dependencies. Reusing this action on a
> repository without a warm cache may silently miss dependencies - drop
> `--offline-scan` in that case.

### 5.2 Escalation Path

```
Finding detected
    → Constitutional veto? → BLOCK → Comment on PR → Human review
    → Judge decides HUMAN_REVIEW? → Comment on PR → Human decides
    → Judge decides SAFE_FIX
        → Surgeon applies patch
        → Verifier checks
            → FAIL? → Comment on PR with trace → Human review
            → PASS? → PR opened
                → Human accepts or refuses
```

---

## 6. Kill Switch

### 6.1 Trigger Conditions

The KillSwitch (`YAMI_DISABLED`) activates when:

1. Environment variable `YAMI_DISABLED=true` is set
2. Token budget exceeds 1,000,000 tokens (`TokenBudget.java`)
3. Governance manifest verification fails (`GovernanceIntegrity.java`)
4. Programmatic trigger from any component

### 6.2 Behavior

- Once triggered, **all writes are blocked** for the JVM lifetime
- Agents cannot be invoked
- Publisher cannot create branches or PRs
- Audit trail is still written (read-only operations)

### 6.3 Recovery

```bash
# Check status
echo $YAMI_DISABLED

# To reset (requires new runner/container):
# Restart the GitHub Actions job
```

---

## 7. Replay Mode

### 7.1 Purpose

Replay mode allows testing the Yami pipeline **without Bedrock** by replaying recorded HTTP responses from a previous live run.

### 7.2 Usage

```yaml
- uses: ./
  with:
    github-token: ${{ secrets.GITHUB_TOKEN }}
    replay-mode: true
    replay-file: replay.jsonl
```

### 7.3 Recording

Replay files are generated during live runs by `ReplayHttpShim.java` at the Java↔OpenCode boundary. The Java pipeline runs for real; only the AI responses are replayed.

---

## 8. Self-Healing

### 8.1 How It Works

Yami can scan its own repository:

1. `Dockerfile` → outdated tool pins → `owasp-supplychain` skill
2. `.github/workflows/` → CI/CD issues → `owasp-cicd-top10` skill
3. `pom.xml` → Java dependency vulnerabilities → Trivy

### 8.2 Verification

Self-healing PRs are verified by the repository's own CI (`security-scan.yml` running on the PR), not by a local rebuild (no docker-in-docker).

### 8.3 Human Decision

The human reviewer accepts or refuses the PR. **Refusal is a feature.** It demonstrates governance.

---

## 9. Logs and Audit

### 9.1 Console Logs

Yami prints structured logs to stderr/stdout:

```
[Harness] Starting Yami pipeline
[Harness] Governance integrity verified
[Harness] Found 3 findings
[Harness] Processing finding: CKV_AWS_21 @ aws_s3_bucket.assets
[Harness] Judge decision: SAFE_FIX (confidence=0.92)
[Harness] Surgeon modified: [main.tf]
[Harness] Verification: TERRAFORM = PASS
[Harness] Opened PR: https://github.com/.../pull/123
```

### 9.2 Audit Artifact

`audit.json` is published as a GitHub Actions artifact containing:

- SHA-256 hashes of findings, skills, files (before/after)
- Exported OpenCode sessions (full agent conversations)
- Verification results
- Decision outcomes with confidence scores
- `fallbackMode` flag

### 9.3 SQLite Database

`.yami-audit.db` stores a hash-chained audit log locally in the container.

---

## 10. Troubleshooting

### 10.1 Common Issues

| Issue | Cause | Solution |
|---|---|---|
| Checkov exits with code 1 | Findings exist (normal behavior) | Not an error - Yami processes findings |
| Bedrock timeout | AWS latency or throttling | Retry 2x, then HUMAN_REVIEW |
| Terraform validate fails | Surgeon wrote invalid syntax | Patch rejected, human review triggered |
| "Governance integrity failed" | `.opencode/` files modified without regenerating manifest | Run `make manifest` |
| PR not created | Token lacks permissions | Verify `contents:write` + `pull_requests:write` |
| KillSwitch triggered | Token budget exceeded or manual trigger | Check logs, reset if needed |

### 10.2 Debug Commands

```bash
# Build locally
mvn package -DskipTests

# Run unit tests
make test-unit

# Build Docker image
make docker-build

# Generate SBOM
make sbom

# Regenerate governance manifest
make manifest
```

---

## 11. Maintenance

### 11.1 Tool Updates

| Tool | Update Method | Verification |
|---|---|---|
| Checkov | Update version in Dockerfile | `checkov --version` |
| Terraform | Update URL in Dockerfile | `terraform version` |
| Trivy | Update URL + SHA256 in Dockerfile | `trivy --version` |
| OpenCode | Update URL + SHA256 in Dockerfile | `opencode --version` |
| Actionlint | Update URL + SHA256 in Dockerfile | `actionlint --version` |

### 11.2 Security Scans

- **Trivy SCA:** Runs on every `pom.xml` or `.jar` change. Gate on HIGH/CRITICAL.
- **Semgrep SAST:** Runs on every push/PR. Audit-only.
- **SBOM:** Generated on every push/PR. Uploaded as artifact.

---

## 12. References

- Yami System Card (`docs/SYSTEM_CARD.md`)
- Yami Threat Model (`docs/THREAT_MODEL.md`)
- Yami AI Usage Statement (`docs/AI_USAGE_STATEMENT.md`)
- Yami architecture: `src/main/java/com/yami/Harness.java` (orchestration pipeline), `src/main/java/com/yami/Main.java` (entrypoint and environment)
