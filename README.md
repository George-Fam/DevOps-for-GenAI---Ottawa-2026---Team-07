# Yami: AI-Powered Security Gate for IaC, CI/CD, and Supply Chain

> **Hackathon:** DevOps for GenAI - Ottawa 2026
> **Team:** Team 07
> **Theme:** Track 1 - Autonomous DevOps (AI-Powered CI/CD)

---

## Elevator Pitch

Yami is an AI-assisted security gate that scans your Infrastructure-as-Code and CI/CD pipelines for vulnerabilities, evaluates findings using OWASP-aligned intelligence, and proposes verified remediation patches, all without ever auto-merging. Java governs; AI proposes; humans dispose.

## Problem Statement

DevOps teams face a critical gap: security scanners (Checkov, Trivy) detect vulnerabilities in IaC and CI/CD, but remediation is manual, slow, and error-prone. Existing tools either:

- **Block deployments** without offering fixes (friction)
- **Apply blind patches** without verification (risk)
- **Require expert knowledge** of every framework (not scalable)

Yami bridges this gap by combining deterministic governance (Java) with contextual intelligence (AI agents) to produce **verified, scoped, and human-approved** remediation.

## Architecture

Yami runs as a **single Docker container** inside a GitHub Actions runner. The container hosts two processes:

- **Java process** (`com.yami.Main`): The governor. Runs scanners (Checkov, CicdRules, Trivy), normalizes findings, hashes artifacts, orchestrates the pipeline, verifies patches deterministically, and assembles tamper-evident audit trails.
- **OpenCode process** (`opencode serve` or `opencode run`): The intelligence layer. Hosts 4 agents that communicate with Java via HTTP on `localhost:4096`:
  - **Judge** (read-only): Evaluates findings against OWASP skills, emits structured decisions
  - **Surgeon** (edit scoped): Implements remediation intent in the target file only
  - **Publisher** (git/gh only): Creates disposable branches and opens PRs. Never merges.
  - **Auditor** (read-only): Produces human-readable audit summaries

External dependencies: GitHub API (PRs), Amazon Bedrock API (AI inference), tool registries (for Docker build).

## How It Works

The pipeline executes 10 steps for each finding:

1. **Governance check:** Verify `.opencode/MANIFEST.json` integrity (chain-linked SHA-256)
2. **Scan:** Run Checkov + CicdRules on scoped paths (e.g., `**/*.tf`, `.github/workflows/**`)
3. **Context:** Build `RiskContextPacket` with findings, changed files, and git diff
4. **Route:** Map finding to OWASP skill(s) (`owasp-iac-security`, `owasp-cicd-top10`, etc.)
5. **Veto:** Constitutional veto in Java: PPE/secrets/exfiltration → BLOCK immediately (never sent to AI)
6. **Judge:** AI evaluates finding with scoped reads, emits `Decision` JSON (SAFE_FIX / HUMAN_REVIEW / BLOCK)
7. **Surgeon:** AI writes the patch in the target file only, emits `PatchReport` JSON
8. **Verify:** Deterministic verification: `terraform fmt/validate` + re-scan, or `actionlint` for workflows
9. **Deviate:** Java diff checks that changed regions intersect the finding location
10. **Publish:** If verified, open PR on disposable branch; if not, escalate to human review

All decisions, patches, and verifications are recorded in a hash-chained audit trail (`audit.json`).

## Quick Start

### Prerequisites

- Java 21 (Temurin)
- Maven 3.9+
- Docker
- GitHub repository with Actions enabled

### Local Build

```bash
# Clone
git clone <repo>
cd yami

# Build fat JAR
mvn package -DskipTests

# Build Docker image
make docker-build

# Run unit tests
make test-unit

# Generate SBOM
make sbom

# Regenerate governance manifest (after any .opencode/*.md change)
make manifest
```

### GitHub Action Integration

Add `.github/workflows/yami.yml`:

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

### Action Inputs

| Input | Required | Default | Description |
|---|---|---|---|
| `github-token` | Yes | - | GitHub token for PR creation |
| `policy-file` | No | `policies/yami.yml` | Path to policy YAML |
| `audit-db` | No | `yami-audit.db` | SQLite audit database path |
| `scope` | No | `''` | Override scan paths |
| `replay-mode` | No | `false` | Enable replay mode for testing |
| `replay-file` | No | `replay.jsonl` | Replay file path |

## Security

- **Threat Model:** [`docs/THREAT_MODEL.md`](docs/THREAT_MODEL.md) - STRIDE analysis, attack scenarios, agent security matrix
- **Constitutional Veto:** Java `PolicyEngine` blocks PPE/secrets/exfiltration before AI sees them
- **Scoped Permissions:** Judge reads only finding dirs; Surgeon edits only target file; Publisher uses git/gh only
- **No Auto-Merge:** Branch protection + scoped token prevent unilateral merges
- **SHA256 Pins:** Base image, Trivy, and OpenCode binaries are SHA256-verified
- **Audit Trail:** Hash-chained SQLite + `audit.json` artifact with full trace

## AI Governance

- **System Card:** [`docs/SYSTEM_CARD.md`](docs/SYSTEM_CARD.md) - Purpose, risk classification, human oversight, model tracking
- **AI Usage Statement:** [`docs/AI_USAGE_STATEMENT.md`](docs/AI_USAGE_STATEMENT.md) - Models used, development assistance disclosure, prompt safety
- **4-Layer Oversight:** (1) Constitutional veto, (2) Judge HUMAN_REVIEW, (3) Verifier rejection, (4) Human PR approval
- **KillSwitch:** `YAMI_DISABLED=true` or token budget exceeded (1M tokens) stops all writes

## Operations

- **Runbook:** [`docs/RUNBOOK.md`](docs/RUNBOOK.md) - Deployment, failure modes, escalation, troubleshooting
- **SBOM:** [`docs/SBOM.md`](docs/SBOM.md) - Dependency inventory, pinning policy, supply chain controls
- **Self-Healing:** Yami scans its own repo (outdated tool pins in Dockerfile → PR → human accepts/refuses)
- **Replay Mode:** Test the pipeline without Bedrock by replaying recorded HTTP responses

## Theme Alignment

Yami aligns with **Track 1 - Autonomous DevOps** by demonstrating:

- **Autonomous security remediation:** AI agents evaluate and fix vulnerabilities without human intervention for clear cases
- **Governed autonomy:** 4 layers of human oversight prevent unilateral AI action
- **CI/CD integration:** Native GitHub Action that runs on every PR
- **Operational evidence:** Tamper-evident audit trails, self-healing, and deterministic verification

## Project Structure

```
src/main/java/com/yami/
  Main.java              → Entrypoint (reads env vars, delegates to Harness)
  Harness.java           → Orchestrator: scan → veto → judge → surgeon → verify → publish → audit
  core/                  → Contracts (Finding, Decision, PatchReport, VerificationResult)
  scanner/               → CheckovAdapter, CicdRules
  policy/                → PolicyEngine (constitutional veto), PolicyConfig (scope.scan_paths)
  verifier/              → Verifier (multi-strategy), TerraformStrategy, ActionlintYamlStrategy, TrivyStrategy
  opencode/              → OpenCodeClient (HTTP API), SkillRouter, PermissionInjector, ReplayHttpShim
  github/                → GithubAdapter (git CLI + gh CLI, scoped token)
  audit/                 → AuditStore (SQLite, hash-chained), TokenBudget, KillSwitch
  governance/            → GovManifestTool (generate/verify chain-linked SHA-256 manifest)
  investigator/          → Investigator (builds RiskContextPacket)

.opencode/
  opencode.json          → Bedrock provider config (env vars only, no keys)
  agents/                → judge.md, surgeon.md, publisher.md, auditor.md, spike-test.md
  skills/                → owasp-cicd-top10, owasp-iac-security, owasp-supplychain, owasp-llm-top10
  MANIFEST.json          → Chain-linked SHA-256 of all .md files

.github/workflows/
  ci.yml                 → Unit tests on push/PR
  security-scan.yml      → Trivy (gate + SARIF), Semgrep (SARIF), SBOM artifact
  yami.yml               → Yami Security Gate on PR (self-healing)

action.yml               → GitHub Action definition
policies/yami.yml        → scope.scan_paths + rule defaults
fixtures/                → safe_fix, human_review, block, rejected_patch, acte4bis_injection, acte6_selfheal
```

## Build Commands

```bash
# Build fat jar (Maven shade)
mvn package -DskipTests

# All tests (requires checkov + terraform + trivy + actionlint on PATH)
make test

# Unit tests only (no external tools)
make test-unit

# Docker build (must run mvn package FIRST)
make docker-build

# Governance manifest (regenerate after ANY .opencode/*.md change)
make manifest

# SBOM
make sbom

# Clean
make clean
```

## Documentation

| Document | Purpose | Rubric |
|---|---|---|
| [`docs/THREAT_MODEL.md`](docs/THREAT_MODEL.md) | Security threats and mitigations | Security (15%) |
| [`docs/SYSTEM_CARD.md`](docs/SYSTEM_CARD.md) | AI governance and responsible AI | AI Governance (10%) |
| [`docs/AI_USAGE_STATEMENT.md`](docs/AI_USAGE_STATEMENT.md) | AI transparency and disclosure | AI Governance (10%) |
| [`docs/RUNBOOK.md`](docs/RUNBOOK.md) | Operations and troubleshooting | Reliability (10%) |
| [`docs/SBOM.md`](docs/SBOM.md) | Supply chain inventory | Supply chain (P-14) |

## License

TBD - Hackathon project (Team 07, DevOps for GenAI Ottawa 2026)

---

*Built with Java 21, OpenCode, and Amazon Bedrock. Governed by humans, assisted by AI.*
