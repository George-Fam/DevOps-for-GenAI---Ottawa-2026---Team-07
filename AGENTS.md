# AGENTS.md

## Repo state (real — verified in code)
Code exists and is real under `src/main/java/com/yami/`: CheckovAdapter, CicdRules,
Investigator, PolicyEngine (veto constitutionnel), AuditStore, HclBlockReplacer,
Verifier, KillSwitch, **Harness** (orchestrateur), **OpenCodeClient**,
**PermissionInjector**, **SkillRouter**, **TokenBudget**, **DeviationDiff**,
**GithubAdapter** (implémenté). `Main` est câblé au Harness.

**`.opencode/` exists** : `opencode.json` (Bedrock, env vars only) + 4 agents
(judge, surgeon, publisher, auditor) + 4 skills (owasp-cicd-top10, owasp-iac-security,
owasp-supplychain, owasp-llm-top10).

Fixtures: `safe_fix`, `human_review`, `block` (`rejected_patch` still missing).
Workflows: `security-scan.yml` (repo CI), `yami.yml` (runs the action via `uses: ./`).

## Source of truth — order matters
1. **`Yami_Pivot.md` (v2.2, French) — THE source of truth guiding technical choices,
   the plan, and the implementation. Wins over everything else.** Read it before any
   change.
2. `Yami_Tech_Plan_v4.md` / `Yami_Product_Plan_v4.md` — the original v4 spec;
   superseded by the pivot wherever they conflict.
3. The two hackathon PDFs — submission checklist + 100-pt rubric; Yami = Track 1.
- `evo/` holds earlier full-vision plans. It is **local-only, not shared** — cite
  the filenames only, never the path.

## The pivot (what changed vs v4 — do not resurrect v4 assumptions)
- **AI layer = OpenCode** (same Docker container, Bedrock-backed), not a custom Java
  Bedrock client. **Spike-gated**: the v4 Bedrock clients + `DegradedPatchGenerator`
  stay until the H+0-2 spike proves per-invocation permission scoping — "le pivot
  contient v4".
- **Agents** (`.opencode/agents/`, created — versioned + hashed):
  **Judge** (read-only, decides + emits remediation intent), **Surgeon** (edit scoped
  to the single target file, writes the fix), **Publisher** (git/gh scoped, never
  merges), **Auditor** (read-only, writes the human PR summary).
- **Permissions = two layers**: constitutional floor in the agent `.md` frontmatter
  (never edit `.opencode/**` or `policies/**`) + per-invocation precision
  (`OPENCODE_PERMISSION` on one-shot `opencode run`, or `PATCH /config` on `serve` —
  **unproven until the spike**; env vars are read at process start, so a persistent
  server cannot be re-scoped per finding without `PATCH /config`).
- **Skills** (`.opencode/skills/`, created — versioned + hashed):
  `owasp-cicd-top10`, `owasp-iac-security`, `owasp-supplychain`, `owasp-llm-top10`.
- **Java = governor**: runs scanners (fixed args), normalizes, SHA-256-hashes, routes
  findings → skills, holds the **constitutional veto floor** (PPE+secrets /
  exfiltration patterns → BLOCK, never reaches the model), injects permission
  scoping (JSON built with Jackson, never string-concatenated), verifies
  (terraform fmt/validate/re-scan; actionlint+yaml for workflows; trivy for supply
  chain), computes the deviation diff itself, enforces the per-run token budget →
  KillSwitch, assembles `audit.json`.
- **Contract change**: Judge emits `remediationIntent`; Surgeon writes the final code.
  The v4 frozen contract (Judge-produced full `replacementBlock`) is dead — Pivot §5.
- **No Java fallback for the Surgeon at runtime** (problem shape is unpredictable):
  loop control = agent `steps` + `doom_loop` + harness HTTP timeout; agent failure →
  `HUMAN_REVIEW` with the full trace (`fallbackMode: true`). The build-time fallback
  if the spike fails is v4 itself.
- **Replay = HTTP shim** at the Java↔serve boundary (recorded OpenCode responses; the
  Java pipeline runs for real). Session exports are transcripts for the audit, not
  executable fixtures.
- **Three scopes**: `scope.scan_paths` in policy; Judge reads only finding dirs;
  Surgeon edits only the target file.
- **Self-healing**: Yami scans its own repo (outdated tool pins in Dockerfile → PR;
  verification = the repo CI on that PR; human accepts or refuses — refusal is a
  feature).
- **Demo attack**: Acte 4bis = live prompt-injection fixture (hostile comment in
  `main.tf` trying to flip BLOCK→SAFE_FIX); the chain holds via scoped reads +
  LLM01 inoculation directive in skills + veto floor + Verifier.

## Current focus
Spike H+0-2 (Pivot §16): prove per-invocation permission scoping on a fixture
(one-shot `opencode run` + `OPENCODE_PERMISSION`; then `PATCH /config` on `serve`)
before building anything on top of OpenCode. Pin the tested OpenCode version.

**Spike status (2026-08-22)** : `opencode run` one-shot fonctionne mais les
subagents ne sont pas chargés correctement (warning "subagent, not primary agent").
Les permissions du frontmatter `.md` ne s'appliquent pas via `--agent <name>`.
Investigation en cours : config `opencode.json` agents vs API HTTP `serve`.

## Stack & build (verified, not aspirational)
- **Java 21** (`pom.xml`) — the tech plan and early notes said 17; superseded, do not
  downgrade. Maven shade → `target/yami.jar`.
- `Dockerfile` does `COPY target/yami.jar` — **run `mvn package` before
  `docker build`**, never build the jar inside the runner.
- Dockerfile includes **actionlint v1.7.12** + **trivy v0.54.1** + placeholder for
  OpenCode (must be pinned by version + SHA256 after spike).
- Contracts live in `com.yami.core` (tech plan says `contracts/` — code wins).
- Deps: hcl4j, Jackson (+dataformat-yaml), AWS bedrockruntime, sqlite-jdbc, JUnit 6.

## Commands
- `mvn package`, `mvn test`, `mvn -Dtest=ClassName test` (single test).
- **Unit tests** (no external tools needed): `SkillRouterTest`, `PermissionInjectorTest`,
  `TokenBudgetTest`, `DeviationDiffTest`, `PolicyEngineTest`, `DecisionTest`,
  `AuditStoreTest`, `LiveBedrockClientTest`, `CicdRulesTest`.
- **Integration tests** (`HclBlockReplacerSpikeTest`, `VerifierTest`, `InvestigatorTest`)
  need real `checkov` + `terraform` on PATH; skip them if unavailable:
  `mvn test -Dtest="!*SpikeTest,!VerifierTest,!InvestigatorTest,!CheckovAdapterTest"`
- `checkov` exits 1 when findings exist — that is normal, not a crash.

## Git flow (team agreement)
- Personal branches + cross review; `feat/investigator` is the integration branch
  (pushed to origin); `main` lagged behind at initial commit.
- **Never commit secrets.** The repo's `opencode.json` must use env vars only — the
  user's global `~/.config/opencode/opencode.json` contains a plaintext provider key;
  never copy that pattern into the repo (Gitleaks runs in CI).
- **Pre-commit is active** — do not disable it. It checks trailing whitespace,
  end of files, merge conflicts, and hardcoded secrets.
- **Commit regularly** using the `create-commits` skill. Group logically:
  `.opencode/` changes in one commit, Java contracts in another, tests in a third.

## Hard constraints (still load-bearing under the pivot)
- No auto-merge, ever. Publisher has no merge permission; branch protection holds.
- Verifier subprocess calls use fixed arg lists; no shell string is built from AI
  output.
- Patch only on a disposable branch / temp dir, never `main`.
- **Constitutional veto floor (Java, pre-Judge)**: PPE+secrets / exfiltration patterns
  → BLOCK immediately, never sent to the model. This is a short constitutional list,
  not per-finding heuristics.
- `Decision` / `PatchReport` are schema-validated; deviation is detected by the Java
  diff (changed regions must intersect the finding location) — `deviationsFromIntent`
  is a signal, never a control.
- Bedrock credentials scoped to `bedrock:InvokeModel`; OIDC preferred, scoped IAM key
  as fallback.
- Third-party actions pinned by full commit SHA (see `.github/workflows/`).
- OpenCode itself is pinned by version + SHA256 in the Dockerfile — never unpinned
  `curl | bash`, never `latest`.
- Token budget per run: hard cap triggers the KillSwitch (`YAMI_DISABLED=true` also
  gates all writes).
- `.opencode/agents/*` and `.opencode/skills/*` are governance artifacts: editing them
  = changing the security posture → cross-review required.
- **Governance manifest** (planned): `.opencode/MANIFEST.json` will contain SHA-256
  hashes of all `.md` files under `.opencode/`, chain-linked. Generated by Java
  (`com.yami.governance.GovManifestTool`), verified by CI and at runtime by the
  Harness. Any modification without regenerating the manifest breaks the build.

## Communication
Reply to the user in French. Code and identifiers in English. `Yami_Pivot.md` is
intentionally French (team alignment doc).
