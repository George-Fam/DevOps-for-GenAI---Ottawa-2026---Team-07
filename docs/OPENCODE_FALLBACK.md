# OpenCode Fallback — Spike H+0-2 Resolution

> **Status**: Resolved — API HTTP `serve` is the correct mechanism.
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
    "model": "amazon-bedrock/anthropic.claude-3-5-sonnet-20241022-v2:0",
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

## References

- OpenCode Server API: https://opencode.ai/docs/server/
- OpenCode Agents: https://opencode.ai/docs/agents/
- OpenCode CLI: https://opencode.ai/docs/cli/
- Yami Pivot v2.2 §16 (spike plan)
