# OpenCode Fallback — Spike H+0-2 Resolution

> **Status**: **Superseded.** The original spike (below) concluded Solution A (`opencode
> serve` + HTTP API) was correct. Live testing against real AWS Bedrock later in the
> same hackathon session (see [Update: What Live Testing Found](#update-what-live-testing-found-2026-08-22),
> and [issue #4](https://github.com/George-Fam/DevOps-for-GenAI---Ottawa-2026---Team-07/issues/4))
> found Solution A does not work for the pipeline's real topology — Surgeon editing a
> file outside OpenCode's own working directory. The rest of this document is kept for
> historical record of the spike; read the update section first for the current state.
> **Date**: 2026-08-22
> **Version**: OpenCode 1.18.21

## The Problem

When running `opencode run --agent <name>` with agents configured as `mode: subagent` in their `.md` frontmatter, OpenCode warns:

```
agent "<name>" is a subagent, not a primary agent. Falling back to default agent
```

**Root cause**: The `--agent` CLI flag can only select **primary agents**. Subagents must be invoked differently.

## Solution A (Recommended): API HTTP `serve`

Start a headless OpenCode server and invoke agents via HTTP API.

### 1. Start the server

```bash
opencode serve --port 4096
```

### 2. Create a session

```bash
curl -X POST http://localhost:4096/session \
  -H "Content-Type: application/json" \
  -d '{"title": "yami-judge"}'
```

Response:
```json
{
  "id": "ses_abc123",
  "title": "yami-judge",
  "createdAt": "2026-08-22T14:30:00Z"
}
```

### 3. Send message to subagent

```bash
curl -X POST http://localhost:4096/session/ses_abc123/message \
  -H "Content-Type: application/json" \
  -d '{
    "agent": "judge",
    "model": "amazon-bedrock/us.anthropic.claude-sonnet-4-5-20250929-v1:0",
    "parts": [{"type": "text", "text": "Analyze finding CKV_AWS_21 on aws_s3_bucket.data"}]
  }'
```

### 4. Update permissions at runtime (per-finding scoping)

```bash
curl -X PATCH http://localhost:4096/config \
  -H "Content-Type: application/json" \
  -d '{
    "permission": {
      "edit": ["fixtures/safe_fix/main.tf"],
      "bash": []
    }
  }'
```

### 5. Export session for audit

```bash
opencode export ses_abc123 --output audit/judge-session.json
```

### Why this works

- Subagent name is in the **request body**, not CLI args
- Permissions can be updated via `PATCH /config` between findings
- Session ID is returned for export/audit
- Java Harness controls the full lifecycle

## Solution B (Fallback): Primary Agents in `opencode.json`

If `serve` fails to start or the HTTP API returns errors, configure agents as **primary agents**.

### Configuration

```json
{
  "$schema": "https://opencode.ai/config.json",
  "agent": {
    "judge": {
      "mode": "primary",
      "description": "Yami Judge — read-only security decision agent",
      "temperature": 0.0,
      "permission": {
        "read": ["{{scoped_dirs}}"],
        "edit": "deny",
        "bash": "deny"
      }
    },
    "surgeon": {
      "mode": "primary",
      "description": "Yami Surgeon — scoped file editor",
      "temperature": 0.0,
      "permission": {
        "read": ["{{scoped_dirs}}"],
        "edit": ["{{target_file}}"],
        "bash": "deny"
      }
    }
  }
}
```

### Usage

```bash
opencode run --auto --agent judge --format json "Analyze this finding..."
```

### Trade-offs

| Aspect | Impact |
|---|---|
| Agents appear in `Tab` cycle | Acceptable for hackathon — jury sees the agents |
| Cannot re-scope permissions per finding | All permissions must be set upfront in `opencode.json` |
| Simpler — no persistent server | Cold start per finding, but acceptable |

### When to use B

- `opencode serve` fails to start in the Docker container
- HTTP API returns 500/connection errors
- Network issues between Java Harness and OpenCode server
- Time constraints — B is faster to set up

## Decision Log

| Date | Decision | Context |
|---|---|---|
| 2026-08-22 | Solution A is primary | API HTTP verified working with subagents |
| 2026-08-22 | Solution B documented as fallback | For resilience and hackathon time constraints |
| 2026-08-22 | **Solution A found broken for the real topology** | Live test against real Bedrock: Surgeon editing a file outside `opencode serve`'s cwd hung indefinitely — see below |

## Update: What Live Testing Found (2026-08-22)

Confirmed live with real AWS Bedrock credentials, later the same day as the original spike: **the persistent-server architecture does not work for Yami's actual production topology.**

- Every earlier "successful" Judge/Auditor test happened to use a path *inside* `opencode serve`'s own working directory, so the `external_directory` permission gate was never triggered.
- The moment Surgeon was asked to edit a file *outside* that directory — the normal case, since `OpenCodeServer` was designed to run in a neutral temp cwd separate from the scanned repo, specifically so a malicious PR's own `.opencode/` couldn't override Yami's governed agents — the call hung indefinitely. `PATCH /config` (re-scoping permissions at runtime, described in Solution A above) did not change this: OpenCode appears to create/dispose a session-scoped "instance" that doesn't pick up the PATCH'd runtime state.

**What did work, live:** `opencode run --agent <name> --auto --model ... "prompt"` — a one-shot subprocess invocation, not the persistent server. Confirmed for:

- **Surgeon**: real file edit, correct Terraform resources added, in a directory outside its own cwd.
- **Publisher**: real `git checkout -b` + `git commit`, then an honest, non-hallucinated failure report when `git push`/`gh pr create` had no remote to work with.

This matches the original pivot document's own risk flag (`Yami_Pivot.md` §3.2, edge case #1, severity red) before Solution A was implemented: Candidate B (`opencode serve` + runtime permission patch) doesn't hold up; Candidate A in that doc's numbering — `opencode run` one-shot, permissions scoped per-process — does.

### Current interim state (hackathon scope)

Rather than the full rearchitecture mid-hackathon, `OpenCodeServer.java` now starts `opencode serve` **inside `repoDir`** (the scanned repo itself) instead of a neutral directory, which avoids the `external_directory` hang. This is explicitly marked in-code as a `HACKATHON WORKAROUND (issue #4)` with an accepted, documented risk: a malicious PR could in theory ship its own `.opencode/agents/*.md` for that run. The `GovernanceIntegrity` chain-linked-manifest check is the mitigation — it verifies Yami's own agent definitions haven't been tampered with before any agent is invoked, independent of this workaround. See [`docs/THREAT_MODEL.md` §3.1](THREAT_MODEL.md#31-known-gap-per-invocation-permission-scoping-is-not-yet-enforced) for the full security analysis.

### Planned fix (post-hackathon)

- `OpenCodeClient.java` — replace `createSession`/`sendMessage`/`updateConfig` HTTP calls with a `ProcessBuilder` invocation of `opencode run --agent <name> --auto --model ... <prompt>` per call, with per-invocation scoping set per-process (exact mechanism — `OPENCODE_PERMISSION` env var or equivalent — needs its own quick check).
- `OpenCodeServer.java` — likely becomes unnecessary if nothing needs a standing server, or gets repurposed.
- `Harness.java` — the server start/stop lifecycle in `run()` goes away or changes shape.
- `SessionExporter`/`ReplayHttpShim` — currently modeled around HTTP request/response pairs; a one-shot CLI invocation has a different "session" concept (stdout is the transcript, not a sequence of HTTP calls) — replay/audit export need rethinking too.

**Acceptance criteria for closing issue #4:**
- [ ] A real `Harness.run()` completes an end-to-end SAFE_FIX cycle (Judge → Surgeon → Verify → Publisher) against a repo directory that is NOT the same directory OpenCode itself runs in — the real production topology.
- [ ] No agent invocation hangs waiting for an interactive permission prompt.

## References

- OpenCode Server API: https://opencode.ai/docs/server/
- OpenCode Agents: https://opencode.ai/docs/agents/
- OpenCode CLI: https://opencode.ai/docs/cli/
- Yami Pivot v2.2 §16 (spike plan)
