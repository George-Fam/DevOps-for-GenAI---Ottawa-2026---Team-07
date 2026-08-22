# Yami — outstanding work

Status snapshot after live-testing all four agents (Judge, Surgeon, Auditor, Publisher)
against real AWS Bedrock credentials. PR #2 (`feat/investigator` → `main`) is up and its
automated-review threads are resolved; this doc is about what's left before the pipeline
is actually trustworthy end-to-end.

Judge and Surgeon both produced correct, well-reasoned output against real fixtures once
pointed at a working model. That's the good news. The bad news is finding #1 below — it
means the current `Harness`/`OpenCodeClient`/`OpenCodeServer` architecture cannot
actually complete a real run yet.

## 1. Critical — blocks every real run

### 1.1 `PATCH /config` on a persistent `opencode serve` does not scope permissions
This is the exact risk the original pivot doc flagged as unproven (§3.2, edge #1, 🔴) and
it turns out to be a real dead end, confirmed live:

- Every "successful" Judge/Auditor test earlier in this project happened to use a path
  *inside* `opencode serve`'s own working directory, so the `external_directory`
  permission gate was never triggered.
- The moment Surgeon was asked to edit a file *outside* that directory (the normal case —
  `OpenCodeServer` deliberately runs in a neutral temp cwd, separate from the scanned
  repo, specifically to stop a malicious PR's own `.opencode/` from overriding Yami's
  governed agents), the call hung for the full timeout. Server log:
  `evaluated permission=external_directory ... action.action=ask` /
  `asking id=... permission=external_directory` — it's sitting in an interactive
  confirmation prompt that nothing will ever answer in headless/HTTP mode.
- Tried the corrected config schema (`{"permission": {"external_directory": {"<glob>":
  "allow"}, "edit": {...}, "read": {...}}}` — see 1.2) via `PATCH /config` before a fresh
  session too. Still hung the same way. The server appears to create/dispose an
  "instance" per session and may not be picking up the PATCH'd runtime state at all.
- **What does work**: `opencode run --agent surgeon --auto --model ... "prompt"`
  (one-shot subprocess, not the persistent server) correctly read the file, reasoned
  about the fix, and edited it — exactly right, no hang. Confirmed for Surgeon
  (real file edit) and Publisher (real `git checkout -b` + `git commit`, then an honest,
  non-hallucinated failure report when `git push`/`gh pr create` had no remote to work
  with).

**In other words**: Candidate B from the original doc (`opencode serve` + runtime
permission patch) does not work. Candidate A (`opencode run` one-shot,
`OPENCODE_PERMISSION` scoped per-process) does.

**Action needed**: rearchitect `OpenCodeClient` around `opencode run --auto` one-shot
subprocess invocations instead of persistent `serve` + HTTP. This touches:
- `OpenCodeClient.java` — replace `createSession`/`sendMessage`/`updateConfig` HTTP calls
  with a `ProcessBuilder` invocation of `opencode run --agent <name> --auto --model ...
  <prompt>` per call, with `OPENCODE_PERMISSION` (or whatever the real env-var-based
  scoping mechanism turns out to be — needs its own quick check) set per-process.
- `OpenCodeServer.java` — likely becomes unnecessary entirely if nothing needs a
  standing server; or gets repurposed if some other reason to keep it emerges.
- `Harness.java` — the server start/stop lifecycle in `run()` goes away or changes shape.
- `SessionExporter`/`ReplayHttpShim` — currently modeled around HTTP request/response
  pairs; a one-shot CLI invocation has a different "session" concept (stdout is the
  transcript, not a sequence of HTTP calls) — replay/audit export need rethinking too.
- This is a real architecture change, not a patch. Budget for it accordingly.

### 1.2 `PermissionInjector` builds the wrong JSON schema
Separate from 1.1, but part of why it wasn't caught: `PermissionInjector.buildPermissionJson()`
emits `{"permissions": {"read": [...], "edit": [...], "bash": [...]}}` — plural key,
array values. OpenCode's real config schema (confirmed via `opencode.ai/docs/permissions`
and by testing) is `{"permission": {"read": {"<glob>": "allow"}, "edit": {...},
"external_directory": {...}}}` — singular key, glob-pattern → action map, and
`external_directory` is its own separate permission type that must be granted
independently of `read`/`edit`. `PATCH /config` accepted the wrong shape with HTTP 200
and silently did nothing with it — no validation error, which is why this went
unnoticed until a real cross-directory edit was attempted.

Needs a full rewrite of `PermissionInjector` (and `PermissionInjectorTest`) once 1.1's
architecture question is settled, since the right shape may differ between "one env var
read at process start" (Candidate A) vs. a config file/PATCH body.

### 1.3 `auditor.md` / `publisher.md` have no `model:` field
`judge.md` and `surgeon.md` pin `model: amazon-bedrock/anthropic.claude-sonnet-4-5-20250929-v1:0`.
`auditor.md` and `publisher.md` don't set one at all. Confirmed live: the Auditor call
silently fell back to `providerID=opencode modelID=mimo-v2.5-free` — a free, non-Bedrock,
non-governed model — instead of the audited/approved Claude model. It still produced a
reasonable PR summary, so this is easy to miss, but it breaks the "model fixed in
frontmatter, deterministic, governed" premise the whole audit-trail story depends on.
Add the same `model:` line to both files (and regenerate `MANIFEST.json`).

### 1.4 `GithubAdapter.escalateToHumanReview()` PR-number check is wrong
```java
String prNumber = System.getenv("GITHUB_REF_NAME");
if (prNumber != null && prNumber.matches("\\d+")) { ... }
```
For a `pull_request`-triggered workflow, `GITHUB_REF_NAME` is `"{PR}/merge"` (e.g.
`"123/merge"`), not a bare number — confirmed against GitHub's own docs. `"123/merge".matches("\\d+")`
is `false`, so this guard fails every time in real CI and **no escalation comment ever
posts** — HUMAN_REVIEW/BLOCK/veto/verification-failure paths all silently do nothing.
Fix: parse the PR number out of `GITHUB_REF_NAME` (split on `/`, take the first segment)
instead of requiring the whole string to be numeric.

### 1.5 First real CI run confirmed 1.1 empirically, then hit a real Trivy/network issue
Ran the actual `yami.yml` workflow for real (after fixing the missing `mvn package`
step - see below). Got real, valuable data:
- Docker build succeeds, `Harness` starts, governance integrity check passes,
  **`opencode serve` starts and becomes ready inside the real container** - the neutral-cwd
  design works mechanically.
- Scanning reached `TrivyAdapter`, which crashed the whole run:
  `trivy fs pom.xml` needs live Maven Central access to resolve full dependency
  metadata, hit `429 Too Many Requests`, and `TrivyAdapter.scan()` correctly detected
  empty stdout and threw - which then took down the entire pipeline before Judge was
  ever reached. Root cause: `mvn package` (which was added to the workflow to build
  `yami.jar`) populates the **host runner's** `~/.m2`, but the action runs in a
  **separate Docker container** with no access to that cache and no `~/.m2` of its own,
  so Trivy inside the container always needs a fresh live resolution. Mitigated by
  making `Harness.scan()` degrade a single scanner's failure to an empty result instead
  of aborting the run (`scanOrDegrade` in `Harness.java`) - checkov/cicd findings still
  get through even if Trivy has a bad day. The root cause (container has no Maven
  cache) is still open: either mount `~/.m2` into the container, bake a warm cache into
  the image, or find a Trivy flag that avoids live Maven Central resolution for pom.xml
  scanning.
- Because of the crash, this run never actually reached Judge - so 1.1 (permission
  scoping) is still unconfirmed *in the real container topology* specifically (it was
  confirmed via local `opencode run`/`opencode serve` testing outside the container).
  Worth re-running once the Trivy issue is fixed to see how far it gets next.

## 2. Known gaps (lower severity, not blocking a first real run)

- **`{{scoped_dirs}}` / `{{target_file}}` placeholders in `judge.md`/`surgeon.md`
  frontmatter are never substituted.** No templating mechanism exists anywhere in the
  harness or in OpenCode itself for these. They're dead text — the agent-level static
  `read`/`edit` permission grant in the `.md` frontmatter currently matches nothing real.
  Only the literal `bash: deny` / `edit: deny` keywords actually do anything. Depends on
  how 1.1 gets resolved — may become moot if per-invocation scoping moves to env vars
  read at process start (which is arguably the more "constitutional floor"-appropriate
  place for this anyway).
- **Auditor's PR-summary template references "Finding hash" / "Skill hash" that
  `Harness.buildAuditorPrompt()` never computes or passes.** Only pre/post *file* hashes
  (from `DeviationDiff`) are provided. Live-tested: the Auditor invents placeholder-looking
  values (`"CKV_AWS_21::aws_s3_bucket.data::HIGH"`, `"owasp-iac-security"`) when not given
  real ones, which weakens the tamper-evidence claim in the actual PR comment. Compute
  and pass a real SHA-256 finding hash and the loaded skill file(s)' hashes.
- **`GovManifestTool` doesn't respect `.opencode/.gitignore`.** Running `make manifest`
  after any local `opencode serve`/`opencode run` test picks up the auto-installed
  `.opencode/node_modules` (dozens of files) and bakes their hashes into
  `MANIFEST.json`. Hit this repeatedly this session — always had to `rm -rf
  .opencode/node_modules .opencode/package*.json` before regenerating. Should filter
  by `.opencode/.gitignore` (or just hardcode excluding `node_modules/`,
  `package*.json`, `bun.lock`).
- **`action.yml` outputs (`audit-json`, `findings-count`, `pr-url`) are declared but
  never written.** Nothing in `Main`/`Harness` writes to `$GITHUB_OUTPUT`. Anyone
  chaining a step off this action's outputs gets nothing.
- **Publisher is supposed to be "the only agent that touches git/gh"** per its own
  `.md` and the pivot doc's constitutional framing, but `GithubAdapter` (plain Java)
  still directly shells out to `git`/`gh` for escalation comments. Judged acceptable
  earlier this session (deterministic Java action, no AI judgment involved, so no
  governance concern) — flagging again here since it's a real tension with Publisher's
  own stated mandate and worth a second opinion.
- **`TrivyStrategy`'s rescan comparison treats every post-patch finding as "new"**
  (same convention as `ActionlintYamlStrategy`, inherited rather than introduced this
  session) rather than diffing against a real pre-patch baseline. Noted previously, still
  true.
- **No written spike-gate verdict doc.** The original plan (§16) required a written
  pass/fail before continuing past H+0-2. This TODO is the closest thing that exists —
  worth promoting the 1.1 finding into a proper `docs/SPIKE.md` once the rearchitecture
  lands, so there's a durable record of what was tried and what actually works.

## 3. What's left

### End-to-end
- **Nothing has run the full `Harness.run()` pipeline for real** (scan → veto → Judge →
  Surgeon → Verify → deviation diff → Publisher/Auditor → `audit.json`). Every agent this
  session was invoked individually with hand-built prompts via ad-hoc scripts, not
  through `Harness` itself. With 1.1 unresolved, an actual `Harness.run()` against a real
  repo would currently hang the first time Judge or Surgeon touches a path outside
  `OpenCodeServer`'s neutral cwd — which is always, in production.
- Once 1.1–1.4 are fixed: run all 6 demo acts (safe_fix, human_review, block, rejected_patch,
  acte4bis_injection, acte6_selfheal) for real, live, against actual Bedrock, and confirm
  each produces the outcome the fixture is designed to demonstrate.
- Confirm the GitHub Actions workflow itself (`.github/workflows/yami.yml`) actually
  succeeds against a real PR — it's never been triggered for real since the input-name
  fix.

### Integration tests
- No `HarnessTest` exists at all — the orchestrator itself has zero test coverage.
  Worth at least a fixture-driven test using `ReplayHttpShim` in replay mode (once its
  wire format matches whatever 1.1 lands on) so the full pipeline can be exercised in CI
  without live Bedrock calls.
- `VerifierTest` has one pre-existing failing test
  (`patchIntroducingNewCriticalFindingIsRejectedEvenThoughOriginalFindingIsFixed`) —
  confirmed present on the base branch before any of this session's changes, not caused
  by them, but still unresolved.
- `InvestigatorTest` and `HclBlockReplacerSpikeTest` weren't run at all this session
  (excluded from the fast suite, need real `terraform`/`checkov` — should confirm they
  still pass given how much else changed).

### Unit test coverage gaps
- `OpenCodeClient` has **zero** JUnit coverage — every bit of validation this session was
  live/manual (curl + ad-hoc Java smoke tests, all deleted afterward). `parseJsonBody`
  (fence-stripping, brace-extraction fallback), `extractTokens`, `extractPrUrl`, and the
  `info.error` detection in `sendMessage`/`execute` are all untested by anything that
  runs in CI. High-value target given how many real bugs were found here by hand.
- `OpenCodeServer` has zero tests (readiness polling, timeout, neutral-cwd behavior).
- `GithubAdapter` has zero tests (`changedFiles()`/`diffsFor()` degradation-to-empty
  behavior, `escalateToHumanReview`'s PR-number parsing — especially relevant given 1.4).

### Docs
- Still missing entirely (called out in the original plan, §16, never done): threat
  model, system card, runbook, AI usage statement.
- `AGENTS.md` was explicitly removed from git tracking earlier ("local documentation
  only") — if it's meant to carry pivot-state context for contributors, it currently
  doesn't exist for anyone who isn't the person who removed it.

## Suggested order of attack
1. Resolve 1.1 (the architecture question) first — everything else in section 1 is
   either downstream of it (1.2) or independent but cheap (1.3, 1.4). Fix those in the
   same pass.
2. Get one real `Harness.run()` end-to-end against `fixtures/safe_fix` — first true
   proof the whole chain works, not just individual agents.
3. Backfill `OpenCodeClient`/`Harness` test coverage while the real behavior is fresh
   (write tests against what was just learned, not against what was assumed before).
4. Run the other 5 acts.
5. Docs.
