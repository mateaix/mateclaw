---
title: MCP Integration — Model Context Protocol Tool Extension
description: MateClaw acts as an MCP client, connecting to any external tool server via Model Context Protocol. JSON-RPC dynamic discovery, SSE/stdio dual transport, seamless unification with built-in tools.
head:
  - - meta
    - name: keywords
      content: MCP,Model Context Protocol,MCP client,tool protocol,JSON-RPC,AI tool extension,Anthropic MCP
---

# MCP — Model Context Protocol

**MCP is how MateClaw talks to tools someone else built.**

Model Context Protocol is an open standard from Anthropic for connecting AI models to external tools and data. An MCP server is a process — local or remote — that advertises a set of tools over JSON-RPC. MateClaw acts as an MCP *client*: it connects, discovers tools via `tools/list`, and exposes them to your agents as if they were native. **From the agent's point of view, there's no difference between a built-in `@Tool` Spring bean and a tool coming from an MCP server.**

This is the escape hatch. If you need a capability MateClaw doesn't ship with — filesystem access for a sandboxed directory, Tavily search, a custom internal data service, a browser automation suite — there's probably already an MCP server for it, and you can plug it in without writing a line of Java.

---

## What MCP actually is

```
┌───────────────────────┐              ┌───────────────────────┐
│     MateClaw           │              │     MCP Server        │
│     (MCP Client)       │              │     (Tool Provider)   │
│                       │   JSON-RPC   │                       │
│  Agent Engine  ───────┼──────────────┼──► Tool A             │
│                       │              │    Tool B             │
│  Tool Registry ◄──────┼──────────────┼─── Tool Discovery     │
│                       │              │    (tools/list)       │
└───────────────────────┘              └───────────────────────┘
```

Core concepts:

- **MCP Client** — MateClaw, connecting to servers, discovering tools, forwarding invocations
- **MCP Server** — a third-party process declaring its available tools and executing calls
- **Tool Discovery** — the client sends `tools/list` to retrieve every tool and its parameter schema
- **Tool Invocation** — when the agent decides to call a tool, the client forwards the request to the right MCP server

New tool capabilities become available to agents **without modifying code or restarting the service**.

---

## Transport types

Three transports for different deployment scenarios:

### stdio (Standard I/O)

MateClaw spawns a local child process and exchanges JSON-RPC messages via stdin/stdout.

```
MateClaw  ── stdin ──►  MCP Server subprocess
          ◄─ stdout ──
```

**Use cases:** local Node.js/Python MCP packages (e.g., `@anthropic/mcp-filesystem`), command-line tool wrappers, development.  
**Advantages:** no network configuration, works immediately, process isolation.  
**Limitations:** local only.

### streamable_http (Streamable HTTP)

Standard HTTP POST for JSON-RPC, responses streamed back over HTTP. **Recommended for production.**

```
MateClaw  ── HTTP POST ──►  Remote MCP Server
          ◄─ HTTP Stream ──
```

**Use cases:** cloud-deployed MCP servers, deployments behind load balancers.  
**Advantages:** standard HTTP, CDN/firewall friendly, auth headers.

### sse (Server-Sent Events)

Earlier HTTP transport using SSE for server-to-client push. Legacy compatibility; new projects should prefer `streamable_http`.

### Transport comparison

| Feature | stdio | streamable_http | sse |
|---------|-------|-----------------|-----|
| Deployment | Local only | Local or remote | Local or remote |
| Network requirement | None | HTTP reachable | HTTP reachable |
| Authentication | Environment variables | HTTP Headers | HTTP Headers |
| Process management | MateClaw manages subprocess | External | External |
| Recommendation | Local tools | Remote services | Legacy compatibility |

---

## Configuration via UI

`Tools → MCP Servers → Add MCP Server`. Fill in:

- **Name** — unique identifier (letters, numbers, `_`, `-`, `.`, spaces; 1–128 chars)
- **Description** — optional
- **Transport type** — `stdio`, `streamable_http`, or `sse`
- **Command** (stdio) — `npx`, `node`, `python`, etc.
- **Arguments** (stdio) — JSON array (e.g., `["-y", "@anthropic/mcp-filesystem", "/path"]`)
- **Working directory** (stdio) — optional
- **Environment variables** (stdio) — JSON object; supports `${ENV_VAR}` references
- **URL** (streamable_http/sse) — server endpoint
- **HTTP Headers** (streamable_http/sse) — JSON object (e.g., `{"Authorization": "Bearer token"}`)
- **Connect timeout** — default 30s
- **Read timeout** — default 30s

Save. If enabled, MateClaw auto-attempts to connect and discover tools.

### Testing, enabling, status

- **Test Connection** — sends `tools/list`, returns result, latency, tool list
- **Enable/Disable toggle** — drop connection without deleting config
- **Status** — `connected` / `disconnected` / `error` with error detail

---

## Configuration via REST API

Full CRUD at `/api/v1/mcp/servers`.

### List all

```bash
curl -s http://localhost:18088/api/v1/mcp/servers \
  -H "Authorization: Bearer <token>" | jq
```

Response includes `headersJson` and `envJson` automatically **sanitized** (`sk-****abcd`).

### Create — stdio

```bash
curl -X POST http://localhost:18088/api/v1/mcp/servers \
  -H "Content-Type: application/json" \
  -H "Authorization: Bearer <token>" \
  -d '{
    "name": "filesystem",
    "transport": "stdio",
    "command": "npx",
    "argsJson": "[\"-y\", \"@anthropic/mcp-filesystem\", \"/home/user/workspace\"]",
    "enabled": true
  }'
```

### Create — streamable_http

```bash
curl -X POST http://localhost:18088/api/v1/mcp/servers \
  -H "Content-Type: application/json" \
  -H "Authorization: Bearer <token>" \
  -d '{
    "name": "remote-tools",
    "transport": "streamable_http",
    "url": "https://mcp.example.com/mcp",
    "headersJson": "{\"Authorization\": \"Bearer your-api-key\"}",
    "connectTimeoutSeconds": 15,
    "readTimeoutSeconds": 60,
    "enabled": true
  }'
```

### Update (PATCH semantics)

```bash
curl -X PUT http://localhost:18088/api/v1/mcp/servers/{id} \
  -H "Content-Type: application/json" \
  -H "Authorization: Bearer <token>" \
  -d '{"description": "Updated", "readTimeoutSeconds": 60}'
```

After update, enabled servers automatically reconnect.

### Delete / Toggle / Test / Refresh

```bash
curl -X DELETE http://localhost:18088/api/v1/mcp/servers/{id} \
  -H "Authorization: Bearer <token>"

curl -X PUT "http://localhost:18088/api/v1/mcp/servers/{id}/toggle?enabled=false" \
  -H "Authorization: Bearer <token>"

curl -X POST http://localhost:18088/api/v1/mcp/servers/{id}/test \
  -H "Authorization: Bearer <token>"

curl -X POST http://localhost:18088/api/v1/mcp/servers/refresh \
  -H "Authorization: Bearer <token>"
```

**Built-in servers** (`builtin=true`) cannot be deleted.

---

## Practical examples

### Example 1 — Filesystem MCP (stdio)

```bash
curl -X POST http://localhost:18088/api/v1/mcp/servers \
  -H "Content-Type: application/json" \
  -H "Authorization: Bearer <token>" \
  -d '{
    "name": "filesystem",
    "description": "Filesystem access (restricted to specified directory)",
    "transport": "stdio",
    "command": "npx",
    "argsJson": "[\"-y\", \"@anthropic/mcp-filesystem\", \"/home/user/workspace\"]",
    "enabled": true
  }'
```

Discovered tools: `read_file`, `write_file`, `list_directory`, `search_files`, `get_file_info`.

Security: `@anthropic/mcp-filesystem` only allows access to the specified directory and subdirectories.

### Example 2 — Remote HTTP with auth

```bash
curl -X POST http://localhost:18088/api/v1/mcp/servers \
  -H "Content-Type: application/json" \
  -H "Authorization: Bearer <token>" \
  -d '{
    "name": "internal-data-service",
    "transport": "streamable_http",
    "url": "https://mcp-api.internal.example.com/mcp",
    "headersJson": "{\"Authorization\": \"Bearer sk-your-api-key\", \"X-Team-Id\": \"engineering\"}",
    "connectTimeoutSeconds": 10,
    "readTimeoutSeconds": 120,
    "enabled": true
  }'
```

**Header values support environment variable references**: `{"Authorization": "Bearer ${MCP_API_KEY}"}` is replaced at runtime, **secrets don't land in the database**.

### Example 3 — Tavily search (stdio + env vars)

```bash
curl -X POST http://localhost:18088/api/v1/mcp/servers \
  -H "Content-Type: application/json" \
  -H "Authorization: Bearer <token>" \
  -d '{
    "name": "tavily-search",
    "transport": "stdio",
    "command": "npx",
    "argsJson": "[\"-y\", \"@anthropic/mcp-tavily\"]",
    "envJson": "{\"TAVILY_API_KEY\": \"${TAVILY_API_KEY}\"}",
    "enabled": true
  }'
```

---

## How MCP tools become available to agents

```
Application startup
   │
   ▼
Iterate enabled MCP servers
   │
   ▼
Connect by transport → initialize → list tools → cache
   │
   ▼
Tool registry (aggregates built-in tools + MCP tools)
   │
   ▼
Agent tool set
```

**Key:** the agent fetches the **latest** active tool list on every invocation, so adding or removing MCP servers takes effect **without restarting**. From the agent's perspective, **MCP tools and built-in tools are identical** — no difference.

---

## Per-agent tool binding

::: tip New in 1.3.0
Before v1.2.0, all employees could call every MCP tool by default — it was a global switch. v1.3.0 makes the binding **per-employee**, and adds dirty-state detection plus namespace collision handling.
:::

### Three problems it solves

**Problem 1: Tool namespace collisions.**
Two MCP servers both expose `read_file` — which one wins? v1.3.0 internally uses a **stable server-prefixed callback name** (`{serverName}__{toolName}`) and persists it to `mate_mcp_server.cached_tools`. The picker shows them as `serverA__read_file` and `serverB__read_file`; the agent's prompt maps them back to original names to save tokens and avoid LLM confusion.

**Problem 2: MCP server / tool rename breaks bindings.**
In v1.2.0, renaming a server orphaned every employee bound to it. v1.3.0 introduces a **persistent tool cache**: every successful list-tools writes tool metadata to a `cached_tools` JSON column on `mate_mcp_server`. When validating bindings and the server is temporarily unreachable, the cache is consulted as fallback — bindings stay marked `stale` and become live again the moment the server reconnects.

**Problem 3: Save silently accepted non-existent tool references.**
A typo'd `nonexistent-server.weird-tool` would save fine and blow up at runtime. v1.3.0 runs `AgentBindingService.validate(...)` on save:

| Status | Meaning | Save behavior |
|---|---|---|
| `connected` | Server online, tool visible | ✅ Persist normally |
| `stale` | Server temporarily offline but in cache | ✅ Persist (marked stale) |
| `unavailable` | Server disabled | ✅ Persist (marked unavailable) |
| `orphan` | Server / tool no longer exists at all | ❌ Reject save, prompt user to clear |

### Where to see tool status

`Agents → pick employee → Tools` — see [Agent tool binding](./agents#tool-binding-per-agent-tool-picker).

### Data contract

- `mate_mcp_server.cached_tools` (new column in v1.3.0): JSON array, each element `{name, description, inputSchema, lastSeenAt}`
- `mate_agent_tool.tool_name`: stores the **prefixed callback name** `{serverName}__{toolName}` rather than the raw name, so a server rename surfaces immediately as an observable join miss
- `AgentBindingService.getEffectiveToolNames(agentId)` is the single source of truth for tool dispatch — runs every turn, ensuring the editor view and the runtime view always agree

### Server-side rules

- MCP servers bridged in via ACP **cannot** be edited from the MCP server list (they're owned by the ACP server's own lifecycle)
- A tool marked `unavailable` is **not listed** in the agent's system prompt — the LLM won't reach for it, but the binding row is preserved
- `returnDirect=true` tools (whose output replaces the assistant turn) go through the same ACL — they **do not bypass** binding

---

## Connection management

### Automatic connection on startup

All `enabled=true` MCP servers connect automatically when the app starts. A single server's failure doesn't block other servers or application startup.

### Thread safety

The active-client map is concurrent, with an independent lock per server.

### Connection replacement

**"Connect new, then disconnect old"** strategy: build a new client, initialize it, swap it into the pool, close the old one. If the new client fails, the old one remains.

### Subprocess cleanup

For stdio servers, cleanup happens on: disable/delete, config replacement, application shutdown (`@PreDestroy`), connection failure.

### Status monitoring

After each connection operation, results persist:

- `last_status` — `connected` / `disconnected` / `error`
- `last_error` — error message
- `last_connected_time` — timestamp of last success
- `tool_count` — currently discovered tools

### Manual refresh

`POST /api/v1/mcp/servers/refresh` drops all existing connections and reconnects every enabled server. Useful for troubleshooting.

---

## Database storage — `mate_mcp_server`

| Column | Type | Default | Purpose |
|--------|------|---------|---------|
| `id` | BIGINT | — | Primary key |
| `name` | VARCHAR(128) | — | Unique identifier |
| `description` | TEXT | NULL | Server description |
| `transport` | VARCHAR(32) | `stdio` | `stdio` / `streamable_http` / `sse` |
| `url` | VARCHAR(512) | NULL | Remote URL |
| `headers_json` | TEXT | NULL | HTTP headers JSON |
| `command` | VARCHAR(512) | NULL | Startup command |
| `args_json` | TEXT | NULL | Command arguments JSON array |
| `env_json` | TEXT | NULL | Environment variables JSON; supports `${VAR}` |
| `cwd` | VARCHAR(512) | NULL | Working directory |
| `enabled` | BOOLEAN | TRUE | On/off |
| `connect_timeout_seconds` | INT | 30 | HTTP connect timeout |
| `read_timeout_seconds` | INT | 30 | Request response timeout |
| `last_status` | VARCHAR(32) | `disconnected` | Last connection status |
| `last_error` | TEXT | NULL | Last error message |
| `last_connected_time` | DATETIME | NULL | Last successful connection |
| `tool_count` | INT | 0 | Discovered tool count |
| `builtin` | BOOLEAN | FALSE | Whether it's a built-in server |
| `create_time` / `update_time` | DATETIME | — | Timestamps |
| `deleted` | INT | 0 | Logical delete |

### Sensitive data sanitization

`headers_json` and `env_json` values are automatically masked in API responses. `args_json` is returned as-is.

### Environment variable references

- `${VAR_NAME}` — exact match and replacement
- `$VAR_NAME` — regex match

Keeps secrets out of the database.

---

## Troubleshooting

### "Command not found" (stdio)

1. Confirm the command is in PATH of the user running MateClaw
2. Verify: `which npx` or `npx --version`
3. Docker: confirm command is installed in the container
4. Use full path: `/usr/local/bin/npx`

### Connection timeout

1. HTTP/SSE: confirm URL reachable (`curl -v <url>`)
2. Check firewall rules
3. Increase `connectTimeoutSeconds` / `readTimeoutSeconds`
4. stdio: first `npx -y` run may need to download packages

### SSL/TLS errors

1. Confirm remote SSL certificate is valid and not expired
2. Self-signed: add CA cert to JVM trust store
3. Confirm JDK supports required TLS version

### Tools not showing up

1. Check `tool_count > 0`
2. Use test connection, confirm `discoveredTools` non-empty
3. Verify MCP server implements `tools/list`
4. Check backend logs for MCP tool-discovery output

### Tool invocation failures

1. Check backend logs for specific errors
2. Confirm MCP server process is running (stdio)
3. Confirm remote server reachable (HTTP/SSE)
4. Check `readTimeoutSeconds` is sufficient
5. Try refresh connections

### Orphaned subprocesses (stdio)

Subprocesses are cleaned up on normal shutdown. If MateClaw was force-killed (`kill -9`), subprocesses may remain. `ps aux | grep mcp` and terminate.

---

## Next

- [Tools](./tools) — how MCP tools relate to built-in tools
- [Skills](./skills) — MCP-backed skills
- [Configuration](./config) — full configuration reference
