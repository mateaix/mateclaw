---
title: ACP Integration — Plug External Coding Agents Into MateClaw
description: MateClaw acts as an ACP host. Delegate prompts to Claude Code, Codex, OpenCode, Qwen Code or any Agent Client Protocol agent over stdio. Built-in endpoints, visual env editor, auto-bridged skill cards, trust model, error translation.
head:
  - - meta
    - name: keywords
      content: ACP,Agent Client Protocol,Claude Code,Codex,OpenCode,Qwen Code,external agent,stdio JSON-RPC,coding agent integration
---

# ACP — Agent Client Protocol

**ACP is how MateClaw hands a prompt to an agent someone else built.**

Agent Client Protocol is an open spec for agent clients to talk to agent servers over JSON-RPC. MateClaw acts as the **host**: it spawns an external CLI (Claude Code, Codex, OpenCode, Qwen Code, …), runs the `initialize` → `session/new` → `session/prompt` handshake on stdio, streams the response back into the conversation, and closes the process.

If MCP is "plug in a tool", ACP is **"plug in a whole agent"**. From inside a MateClaw turn, calling Claude Code looks the same as calling any built-in tool — your agent just asks for `acp_claude-code_prompt` and reads the answer.

---

## ACP vs MCP at a glance

| | **MCP** | **ACP** |
|---|---|---|
| What you connect | A tool server | An agent |
| Granularity | Per-tool (`tools/list`) | Per-prompt (one shot) |
| Transport in MateClaw | stdio / streamable_http / sse | stdio |
| Session model | Long-lived, multi-call | Stateless: spawn → prompt → close |
| Typical use | Filesystem, search, custom data API | Delegate a coding task to Claude Code / Codex |
| Surface in MateClaw | Tool catalog | Skill catalog (auto-bridged) + tool wrapper |

You can mix both in the same agent.

---

## Built-in endpoints

The Flyway migration that ships with MateClaw seeds four endpoints, all **disabled by default** — turn them on after you install the matching CLI.

| Slug | Display name | Command | Notes |
|---|---|---|---|
| `claude-code` | Claude Code | `npx -y @zed-industries/claude-agent-acp` | Anthropic's Claude Code; reads `ANTHROPIC_API_KEY` |
| `codex` | OpenAI Codex CLI | `npx -y @zed-industries/codex-acp` | OpenAI's coding agent; reads `OPENAI_API_KEY` |
| `opencode` | OpenCode | `opencode acp` | Multi-model agent; binary must be on `PATH` |
| `qwen-code` | Qwen Code | `qwen --acp` | Alibaba's coding agent; reads `DASHSCOPE_API_KEY` |

Built-in rows are write-protected — you can edit `args_json` / `env_json` / `description` / `trusted` / `enabled`, but you can't change the slug, replace the command, or delete the row. To run an unrelated agent, **add a custom endpoint** instead.

---

## Configure via the admin console

`Settings → ACP Endpoints` is the full CRUD surface.

### Add or edit an endpoint

- **Slug** — lowercase identifier (e.g. `claude-code`). Immutable after create. Skills reference endpoints by this slug.
- **Display name** — human label shown on the Skills page.
- **Description** — operator notes.
- **Command** — the executable (`npx`, `opencode`, …). Locked on built-in rows.
- **Args (JSON array)** — CLI arguments, e.g. `["-y","@zed-industries/claude-agent-acp"]`.
- **Env (JSON object)** — extra env vars merged into the child process. The visual editor masks values whose key matches `*API_KEY*`, `*TOKEN*`, `*SECRET*`, or `*PASS*`.
- **Tool parse mode** — `call_title` / `call_detail` / `update_detail`. Controls how upstream tool-call events render into the streamed transcript.
- **Trusted** — when ON, MateClaw auto-allows any `session/request_permission` the upstream agent asks for. When OFF, every permission request is denied (use this for non-interactive contexts).
- **Enabled** — gate flag. Disabled endpoints don't bridge into the skill catalog.

### Test the connection

Click **Test** to spawn the process, run `initialize` + `session/new`, and tear down. The result panel shows protocol version, agent capabilities, elapsed time, and — on failure — a translated error hint (see [Trust & error translation](#trust-error-translation)). Status persists on the row as `last_status` / `last_tested_at` / `last_error`.

### Enable / disable / delete

- **Toggle** — drops the endpoint from the catalog without deleting it.
- **Delete** — only available on custom rows. Built-in rows can't be deleted.

Any change publishes an `AcpEndpointChangedEvent` and the skill catalog re-syncs immediately — no restart needed.

---

## REST API

Base path: `/api/v1/acp/endpoints`. JWT required.

| Method | Path | What it does |
|---|---|---|
| `GET`    | `/`              | List all endpoints |
| `GET`    | `/{id}`          | Fetch one |
| `POST`   | `/`              | Create custom endpoint |
| `PUT`    | `/{id}`          | Patch fields (built-in `command` is locked) |
| `DELETE` | `/{id}`          | Delete custom endpoint (built-ins refuse) |
| `PUT`    | `/{id}/toggle?enabled=true\|false` | Enable / disable |
| `POST`   | `/{id}/test`     | Run connection test |

### Create a custom endpoint

```bash
curl -X POST http://localhost:18088/api/v1/acp/endpoints \
  -H "Content-Type: application/json" \
  -H "Authorization: Bearer <token>" \
  -d '{
    "name": "my-coder",
    "displayName": "My Custom Coder",
    "description": "Internal coding agent",
    "command": "npx",
    "argsJson": "[\"-y\",\"@my-org/my-acp-agent\"]",
    "envJson": "{\"MY_API_KEY\":\"sk-...\"}",
    "toolParseMode": "call_detail",
    "trusted": true,
    "enabled": true
  }'
```

### Test an endpoint

```bash
curl -X POST http://localhost:18088/api/v1/acp/endpoints/9100002/test \
  -H "Authorization: Bearer <token>"
```

Response shape:

```json
{
  "name": "claude-code",
  "command": "npx",
  "args": ["-y", "@zed-industries/claude-agent-acp"],
  "agentCapabilities": { "loadSession": false, "promptCapabilities": { "image": true } },
  "status": "OK",
  "elapsedMs": 1842
}
```

On failure `status` is `ERROR` and `error` carries the translated hint.

---

## How endpoints reach your agents

There are two paths:

### 1. Auto-bridged virtual skill (zero config)

For every enabled endpoint, MateClaw registers a virtual skill card and a wrapper tool named `acp_<slug>_prompt`. The tool takes a single `prompt` string and returns the upstream agent's accumulated text reply. Any agent can call it the same way it calls a built-in tool — no skill manifest required.

```
Settings → ACP Endpoints (toggle on)
   ↓
AcpEndpointChangedEvent
   ↓
Skill catalog gains card "Claude Code"
Tool registry gains "acp_claude-code_prompt"
   ↓
Agent calls the tool → AcpDelegationService.prompt()
   ↓
spawn → initialize → session/new → session/prompt
   ↓
accumulate agent-message-chunk notifications
   ↓
return text to the agent's turn
```

### 2. Hand-authored skills (full control)

A skill manifest can declare `type: acp` and pin to an endpoint. The skill gets its own wrapper tool (`acp_<endpoint>_<skill>_prompt`), can inject a `systemPrefix` ahead of every prompt, and can override `cwd` per session.

```yaml
# SKILL.md frontmatter
type: acp
acp:
  endpoint: claude-code
  systemPrefix: |
    You are working inside the MateClaw repo. Always run `mvn test` before reporting done.
  cwd: /workspaces/mateclaw
```

This is how the `claude-code-helper` and `codex-helper` skill templates ship.

---

## Trust & error translation {#trust-error-translation}

### Trust flag

ACP servers can pause and ask the host for permission (`session/request_permission`) before doing something sensitive — writing files, running shell commands, etc. MateClaw does **not** prompt the user mid-stream; instead, the per-endpoint `trusted` flag decides:

- `trusted: true` — auto-allow the first option the agent offered. Best for installed CLIs you control.
- `trusted: false` — cancel every permission request. Use for sandboxed or untrusted endpoints; the upstream agent will gracefully back off.

### Error translation

Upstream errors from coding agents are notoriously cryptic. `AcpRuntimeSupport.translateAuthError()` recognises common 401 / 403 / "Request not allowed" patterns and rewrites them into something actionable:

- Missing key → "Set `ANTHROPIC_API_KEY`" / `OPENAI_API_KEY` / `DASHSCOPE_API_KEY` / `GOOGLE_API_KEY`, picked per endpoint.
- Claude Code OAuth keychain hijack → suggests `claude logout` to clear a stale OAuth token from `~/.claude/` that's shadowing your env var.

Hints surface in the test panel and in the streamed error message your agent receives.

### Timeouts and limits

- `initialize` handshake: 15s
- `session/new`: 10s
- Whole `session/prompt` round-trip: 5 min
- Stdio buffer cap: 50 MiB per call (configurable on the row via `stdio_buffer_limit_bytes`)

---

## Database — `mate_acp_endpoint`

| Column | Type | Default | Purpose |
|---|---|---|---|
| `id` | BIGINT | — | Primary key. Built-ins use `9100001`–`9100004` |
| `name` | VARCHAR(64) | — | Unique slug. Skills reference this |
| `display_name` | VARCHAR(128) | NULL | Label |
| `description` | TEXT | NULL | Operator notes |
| `command` | VARCHAR(256) | — | Process command |
| `args_json` | TEXT | NULL | CLI args (JSON array) |
| `env_json` | TEXT | NULL | Env overrides (JSON object) |
| `tool_parse_mode` | VARCHAR(32) | `call_title` | `call_title` / `call_detail` / `update_detail` |
| `builtin` | BOOLEAN | FALSE | Built-in rows are write-protected |
| `trusted` | BOOLEAN | TRUE | Auto-allow permission requests |
| `enabled` | BOOLEAN | FALSE | Off until you opt in |
| `stdio_buffer_limit_bytes` | BIGINT | 52428800 | 50 MiB cap on accumulated stdio |
| `last_status` | VARCHAR(32) | NULL | `OK` / `ERROR` |
| `last_tested_at` | DATETIME | NULL | Last test timestamp |
| `last_error` | TEXT | NULL | Last test error |
| `workspace_id` | BIGINT | 1 | Bound workspace |
| `create_time` / `update_time` | DATETIME | — | Timestamps |
| `deleted` | INT | 0 | Logical delete |

Schema lives at `db/migration/{h2,mysql}/V68__add_acp_endpoints.sql`.

---

## Troubleshooting

### "Command not found"

The `command` must be on the `PATH` of the user running MateClaw. Verify with `which npx` (or `which opencode`, `which qwen`). On Docker, install the CLI in the image. As a last resort, set `command` to the full absolute path.

### "Request not allowed" / 403 from Claude Code

You probably have an OAuth token cached in `~/.claude/` that's overriding the `ANTHROPIC_API_KEY` you set in the env editor. Run `claude logout`, then click **Test** again. The test panel will tell you when it detects this case.

### Hangs on `session/new`

Usually means the upstream CLI is downloading dependencies on first run (`npx -y` does this). Either pre-warm by running the CLI once outside MateClaw, or just retry — subsequent calls are fast.

### "Subprocess output exceeded buffer"

The agent emitted more than 50 MiB of stdio in a single call. Bump `stdioBufferLimitBytes` on the endpoint, or split the prompt into smaller turns.

### Tool doesn't show up on the Skills page

- Confirm `enabled: true`.
- Confirm the test passes (`last_status: OK`).
- Open the agent's tool binding — auto-bridged tools are available to every agent unless explicitly excluded.

---

## Next

- [Skills](./skills) — including hand-authored ACP-typed skills
- [Tools](./tools) — how the wrapper tool plugs into the registry
- [MCP](./mcp) — the sibling protocol for tool servers
- [Security & Approval](./security) — pairing the trust flag with Tool Guard
