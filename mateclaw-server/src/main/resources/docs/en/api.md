# API Reference

Every REST endpoint is prefixed `/api/v1/`. Every response follows the same envelope:

```json
{
  "code": 200,
  "message": "success",
  "data": { }
}
```

Every endpoint except `/api/v1/auth/login` requires a JWT in the `Authorization` header:

```
Authorization: Bearer <token>
```

For deep behavior, read the feature page — [Chat & Messaging](./chat), [Agents](./agents), [Tools](./tools), [Security & Approval](./security), [LLM Wiki](./wiki), [Multimodal](./multimodal), [Memory](./memory), [Channels](./channels), [Models](./models), [Workspaces](./workspaces), [Goals](./goals), [Doctor](./doctor).

---

## Authentication

```
POST /api/v1/auth/login        # Login, get JWT
GET  /api/v1/users/me          # Current user profile
PUT  /api/v1/users/me          # Update profile
PUT  /api/v1/users/me/password # Change password
```

**Login example:**

```bash
curl -X POST http://localhost:18088/api/v1/auth/login \
  -H "Content-Type: application/json" \
  -d '{"username":"admin","password":"admin123"}'
```

Response:

```json
{
  "code": 200,
  "data": {
    "token": "eyJhbGciOiJIUzI1NiJ9...",
    "tokenType": "Bearer",
    "expiresIn": 86400
  }
}
```

---

## Chat

```
POST /api/v1/chat?agentId={id}                  # Send a message (sync; agentId is a query param)
POST /api/v1/chat/stream                        # SSE streaming (POST; agentId in the JSON body)
POST /api/v1/chat/{conversationId}/stop         # Stop an in-flight stream
POST /api/v1/chat/{conversationId}/interrupt   # Interrupt the agent loop
POST /api/v1/chat/upload                        # Upload a chat attachment (multipart/form-data)
GET  /api/v1/chat/files/{conversationId}/{storedName}  # Read an uploaded attachment
GET  /api/v1/chat/{conversationId}/pending-approvals   # List waiting approvals
```

**Send message:**

```bash
curl -X POST 'http://localhost:18088/api/v1/chat?agentId=1' \
  -H "Authorization: Bearer YOUR_TOKEN" \
  -H "Content-Type: application/json" \
  -d '{"message":"Hello, what can you do?", "conversationId":"conv-abc123"}'
```

Request body fields: `message` (required), `conversationId` (optional, defaults to `default`), `contentParts` (optional structured content parts for attachments).

**SSE stream example:**

The SSE endpoint is **POST with a JSON body** — browser-native `EventSource` only supports GET, so integrators should use `fetch()` and read the response stream (see the frontend's `composables/chat/useChat.ts`).

```bash
curl -N -X POST 'http://localhost:18088/api/v1/chat/stream' \
  -H "Authorization: Bearer YOUR_TOKEN" \
  -H "Content-Type: application/json" \
  -H "Accept: text/event-stream" \
  -d '{"agentId":1, "message":"Hello", "conversationId":"conv-abc123"}'
```

Event types and schema are documented in [Chat & Messaging](./chat).

### Conversations

```
GET    /api/v1/conversations                       # List (?page&size&agentId)
GET    /api/v1/conversations/page?page=&size=&keyword=  # Paginated sessions (with keyword search)
GET    /api/v1/conversations/{id}/messages         # Get messages
PUT    /api/v1/conversations/{id}/model            # Set the model used by this conversation
DELETE /api/v1/conversations/{id}                  # Delete
DELETE /api/v1/conversations/{id}/messages         # Clear messages
GET    /api/v1/conversations/{id}/status           # Conversation status
```

---

## Agents

```
GET    /api/v1/agents                # List (paginated)
GET    /api/v1/agents/{id}            # Get
POST   /api/v1/agents                 # Create
PUT    /api/v1/agents/{id}            # Update (partial)
DELETE /api/v1/agents/{id}            # Soft delete

GET    /api/v1/agents/{id}/chat/stream?message=...&conversationId=...  # Streaming chat

GET    /api/v1/agents/{id}/workspace/files                    # List files
GET    /api/v1/agents/{id}/workspace/files/{filename}         # Get content
PUT    /api/v1/agents/{id}/workspace/files/{filename}         # Write
DELETE /api/v1/agents/{id}/workspace/files/{filename}         # Delete
GET    /api/v1/agents/{id}/workspace/prompt-files             # Which files are injected
PUT    /api/v1/agents/{id}/workspace/prompt-files             # Set prompt file list

GET    /api/v1/agents/{agentId}/workspace/memory/export           # Export memory snapshot
POST   /api/v1/agents/{agentId}/workspace/memory/import/preview   # Preview import (no writes)
POST   /api/v1/agents/{agentId}/workspace/memory/import           # Import memory snapshot

GET    /api/v1/agents/templates        # List templates
POST   /api/v1/agents/templates/{id}   # Create from template
```

### Field: `primaryKbId` (1.5.0+)

Every employee can declare a **primary knowledge base** to act as the default target for wiki tools. The field is typed `string | null` (Snowflake ID, always handled as a string on the frontend).

`PUT /api/v1/agents/{id}` is **three-state**:

| Request body has | Behavior |
|------------------|----------|
| no `primaryKbId` key | leave the current value unchanged |
| `"primaryKbId": "<kbId>"` | set to the specified KB |
| `"primaryKbId": null` | clear it (wiki tools then fall back to the workspace's default KB) |

The server distinguishes "field missing" from "explicit null" via `body.containsKey("primaryKbId")`; the entity carries `@TableField(updateStrategy = FieldStrategy.ALWAYS)` so a null actually reaches the database (MyBatis-Plus's default `NOT_NULL` strategy would otherwise silently skip it).

Design intent: **KBs are workspace-shared. `primaryKbId` only chooses the default target for *this* employee's wiki tools — it does not change KB ownership or visibility.** Multiple employees can pick the same KB as primary without interfering.

Examples:

```bash
# Set the primary KB
curl -X PUT http://localhost:18088/api/v1/agents/2055639185675730946 \
  -H "Authorization: Bearer $TOKEN" \
  -H "X-Workspace-Id: 1" \
  -H "Content-Type: application/json" \
  -d '{"primaryKbId": "2054907618529591298", ...other fields}'

# Clear it
curl -X PUT http://localhost:18088/api/v1/agents/2055639185675730946 \
  -H "Authorization: Bearer $TOKEN" \
  -H "X-Workspace-Id: 1" \
  -H "Content-Type: application/json" \
  -d '{"primaryKbId": null, ...other fields}'
```

---

## Tools

```
GET    /api/v1/tools                               # List
PUT    /api/v1/tools/{id}                          # Update
PUT    /api/v1/tools/{id}/toggle?enabled={bool}    # Toggle
PUT    /api/v1/tools/{id}/disclosure-tier          # Set disclosure tier (core / extension)
POST   /api/v1/tools/{name}/test                   # Test directly
```

---

## Skills

```
GET    /api/v1/skills                                 # List (?type=builtin|custom|mcp&tag=...)
GET    /api/v1/skills/{id}                            # Get
POST   /api/v1/skills                                 # Create
PUT    /api/v1/skills/{id}                            # Update
DELETE /api/v1/skills/{id}                            # Delete
PUT    /api/v1/skills/{id}/toggle?enabled={bool}      # Toggle
GET    /api/v1/skills/runtime/active                  # Currently active skills
GET    /api/v1/skills/runtime/status                  # Runtime status
POST   /api/v1/skills/runtime/refresh                 # Reload runtime
```

---

## MCP Servers

```
GET    /api/v1/mcp/servers                             # List
GET    /api/v1/mcp/servers/{id}                         # Get
POST   /api/v1/mcp/servers                              # Create
PUT    /api/v1/mcp/servers/{id}                         # Update (PATCH semantics)
DELETE /api/v1/mcp/servers/{id}                         # Delete
PUT    /api/v1/mcp/servers/{id}/toggle?enabled={bool}   # Toggle
POST   /api/v1/mcp/servers/{id}/test                    # Test connection
POST   /api/v1/mcp/servers/refresh                      # Refresh all
```

See [MCP](./mcp) for body schemas and examples.

---

## LLM Wiki

```
GET    /api/v1/wiki/kbs                                  # List knowledge bases
POST   /api/v1/wiki/kbs                                  # Create KB
GET    /api/v1/wiki/kbs/{id}                             # Get KB detail
PUT    /api/v1/wiki/kbs/{id}                             # Update KB
DELETE /api/v1/wiki/kbs/{id}                             # Delete KB

POST   /api/v1/wiki/kbs/{kbId}/raw                       # Upload raw material
GET    /api/v1/wiki/kbs/{kbId}/raw                       # List raw materials
DELETE /api/v1/wiki/raw/{id}                             # Delete raw material
POST   /api/v1/wiki/raw/{id}/reprocess                   # Re-digest

GET    /api/v1/wiki/kbs/{kbId}/pages                     # List pages
GET    /api/v1/wiki/pages/{id}                           # Get page
PUT    /api/v1/wiki/pages/{id}                           # Edit page
DELETE /api/v1/wiki/pages/{id}                           # Delete page
POST   /api/v1/wiki/pages/{id}/lock                      # Lock page
POST   /api/v1/wiki/pages/{id}/unlock                    # Unlock page

GET    /api/v1/wiki/kbs/{kbId}/search?q=...              # Full-text search
GET    /api/v1/wiki/pages/{id}/backlinks                 # Backlinks
```

Agent-callable wiki tools (`wiki_search`, `wiki_read`, `wiki_backlinks`) resolve `kbId` automatically.

### Per-agent primary knowledge base (1.5.0+)

PR #237 / migration V130 introduced the per-employee "primary knowledge base" mechanism. New endpoint:

```
GET /api/v1/wiki/knowledge-bases/bindable      # List KBs in the current workspace that can be picked as primary
```

This returns **every** KB in the workspace, including ones already picked as primary by other employees — the binding semantics are "which one do I default to," not "I own this one." The shape matches `GET /api/v1/wiki/knowledge-bases` (list-by-workspace); the dedicated name exists to be self-documenting in the UI.

The bind action itself **does not** go through the wiki API — it's written to the agent entity:

```
PUT /api/v1/agents/{id}    # body carries the primaryKbId field
```

Field semantics and three-state behavior: see the [`primaryKbId` section under Agents](#field-primarykbid-150) above.

::: warning Legacy `kb.agentId` field
Versions before 1.5.0 stored the binding on `mate_wiki_knowledge_base.agent_id` (one-to-one, exclusive). The V130 migration backfills those values into `agent.primary_kb_id`; the old column is kept as a read-only fallback — **`PUT /api/v1/wiki/knowledge-bases/{id}` no longer processes the `agentId` field** and silently ignores it if sent. New code should drive the binding only through `agent.primaryKbId`.
:::

---

## Multimodal

```
POST /api/v1/image/generate              # Generate image
POST /api/v1/image/edit                  # Edit image
POST /api/v1/video/generate              # Generate video
POST /api/v1/video/from-image            # Image-to-video
POST /api/v1/music/generate              # Generate music
POST /api/v1/tts/synthesize              # Text-to-speech
POST /api/v1/stt/transcribe              # Speech-to-text

GET  /api/v1/image/jobs/{id}             # Async image job status
GET  /api/v1/video/jobs/{id}             # Async video job status
```

See [Multimodal](./multimodal).

---

## Memory

```
POST /api/v1/memory/{agentId}/emergence                        # Manually trigger consolidation
POST /api/v1/memory/{agentId}/summarize/{conversationId}       # Trigger extraction
GET  /api/v1/memory/{agentId}/dreaming/status                  # Last/next run + latest DREAMS.md entry
```

---

## Security & Approval

### Tool Guard rules

```
GET    /api/v1/security/guard/config                               # Global config
PUT    /api/v1/security/guard/config                               # Update global config
GET    /api/v1/security/guard/rules                                # List custom rules
GET    /api/v1/security/guard/rules/builtin                        # List builtin rules
POST   /api/v1/security/guard/rules                                # Create rule
PUT    /api/v1/security/guard/rules/{id}                           # Update rule
DELETE /api/v1/security/guard/rules/{id}                           # Delete rule
PUT    /api/v1/security/guard/rules/{id}/toggle?enabled={bool}     # Toggle rule
```

### File Guard

```
GET /api/v1/security/guard/config/file-guard   # Get config
PUT /api/v1/security/guard/config/file-guard   # Update config
```

### Approvals

```
GET  /api/v1/approvals?status=pending          # List pending approvals
POST /api/v1/approvals/{id}/resolve            # Approve or reject
```

Body:

```json
{ "decision": "approved" }
```

or

```json
{ "decision": "rejected", "notes": "Reason" }
```

### Audit log

```
GET /api/v1/security/audit/logs   # Query (?toolName, ?decision, ?from, ?to)
GET /api/v1/security/audit/stats  # Stats
GET /api/v1/audit/events          # Full audit event query
```

---

## Models

```
GET    /api/v1/models                                              # List models
GET    /api/v1/models/enabled                                      # Enabled only
GET    /api/v1/models/default                                      # Default model
GET    /api/v1/models/active                                       # Active model
PUT    /api/v1/models/active                                       # Set active
POST   /api/v1/models                                              # Create model config
PUT    /api/v1/models/{id}                                         # Update
DELETE /api/v1/models/{id}                                         # Delete
POST   /api/v1/models/{id}/default                                 # Set as default

PUT    /api/v1/models/{providerId}/config                          # Update provider config
POST   /api/v1/models/custom-providers                             # Create custom provider
DELETE /api/v1/models/custom-providers/{providerId}                # Delete custom provider

POST   /api/v1/models/{providerId}/models                          # Add model to provider
DELETE /api/v1/models/{providerId}/models/{modelId}                # Remove model

POST   /api/v1/models/{providerId}/discover                        # Discover models
POST   /api/v1/models/{providerId}/discover/apply                  # Apply discovered
POST   /api/v1/models/{providerId}/test-connection                 # Test provider
POST   /api/v1/models/{providerId}/models/{modelId}/test           # Test a single model
```

### Legacy endpoints

```
GET    /api/v1/model-providers           # Legacy — prefer /api/v1/models
POST   /api/v1/model-providers
PUT    /api/v1/model-providers/{id}
DELETE /api/v1/model-providers/{id}

GET    /api/v1/model-configs             # Legacy — prefer /api/v1/models
POST   /api/v1/model-configs
PUT    /api/v1/model-configs/{id}
DELETE /api/v1/model-configs/{id}
```

---

## Channels

```
GET    /api/v1/channels                                          # List
POST   /api/v1/channels                                          # Create
PUT    /api/v1/channels/{id}                                     # Update
DELETE /api/v1/channels/{id}                                     # Delete
PUT    /api/v1/channels/{id}/toggle?enabled={bool}               # Toggle
GET    /api/v1/channels/status                                   # Per-channel connection status
GET    /api/v1/channels/health                                   # Aggregate health view

GET    /api/v1/channels/webhook/weixin/qrcode                    # WeChat iLink QR code
GET    /api/v1/channels/webhook/weixin/qrcode/status             # QR scan status

POST   /api/v1/channels/qrcode/qq/begin                          # Begin QQ scan-to-bind
GET    /api/v1/channels/qrcode/qq/status                         # QQ scan-to-bind status
```

### Channel webhook callbacks

| Channel | Callback URL |
|---------|--------------|
| DingTalk | `POST /api/v1/channels/webhook/dingtalk` |
| Feishu | `POST /api/v1/channels/webhook/feishu` |
| WeCom | `POST /api/v1/channels/webhook/wecom` |
| Telegram | `POST /api/v1/channels/webhook/telegram` |
| Discord | *(Gateway — no webhook)* |
| QQ | `POST /api/v1/channels/webhook/qq` |
| Slack | `POST /api/v1/channels/webhook/slack` |
| WeChat Personal | `POST /api/v1/channels/webhook/weixin` |

---

## Cron jobs

```
GET    /api/v1/cron-jobs                                # List
POST   /api/v1/cron-jobs                                # Create
PUT    /api/v1/cron-jobs/{id}                           # Update
DELETE /api/v1/cron-jobs/{id}                           # Delete
PUT    /api/v1/cron-jobs/{id}/toggle?enabled={bool}     # Toggle
POST   /api/v1/cron-jobs/{id}/run                       # Run immediately
```

---

## Workflows (1.3.0+)

Full field reference, step modes, and Pebble syntax in [Workflow](./workflow).

```
GET    /api/v1/workflows                                # List
GET    /api/v1/workflows/{id}                           # Fetch (published revision + draft)
POST   /api/v1/workflows                                # Create
PUT    /api/v1/workflows/{id}/draft                     # Save draft (graph_json)
POST   /api/v1/workflows/{id}/publish                   # Publish draft as a new revision
DELETE /api/v1/workflows/{id}                           # Delete

POST   /api/v1/workflows/draft/generate                 # Natural-language → graph_json draft
POST   /api/v1/workflows/{id}/preview-compile           # Static checks + Pebble validation, no publish

POST   /api/v1/workflows/{id}/runs                      # Start a run (async)
GET    /api/v1/workflows/{id}/runs                      # Run list
GET    /api/v1/workflows/runs/{runId}                   # Run detail + per-step input/output/tokens/duration
POST   /api/v1/workflows/runs/{runId}/resume            # Resume after await_approval
POST   /api/v1/workflows/runs/{runId}/cancel            # Cancel in-flight
```

---

## Triggers (1.3.0+)

Six pattern types, event governance, cross-instance consistency in [Triggers](./triggers).

```
GET    /api/v1/triggers                                 # List
GET    /api/v1/triggers/{id}                            # Fetch
POST   /api/v1/triggers                                 # Create
PUT    /api/v1/triggers/{id}                            # Update
DELETE /api/v1/triggers/{id}                            # Delete
PUT    /api/v1/triggers/{id}/toggle?enabled={bool}      # Toggle

POST   /api/v1/triggers/events                          # Generic event ingress (webhook / external bridge)
                                                         # ACKs 200 immediately, dispatches asynchronously
GET    /api/v1/triggers/{id}/events                     # Event history for this trigger
```

---

## Goals (1.4.0+)

Goal-completion scoring and auto-followup behavior in [Goals](./goals).

```
POST   /api/v1/goals                                   # Create goal
GET    /api/v1/goals/{id}                               # Get goal
PATCH  /api/v1/goals/{id}                               # Update goal (partial)
GET    /api/v1/goals/{id}/events                        # Evaluation event history for this goal
```

---

## Token usage

```
GET /api/v1/token-usage?startDate=&endDate=&modelName=&providerId=
```

---

## System settings

```
GET /api/v1/settings              # All settings
PUT /api/v1/settings              # Update multiple
GET /api/v1/settings/language     # Current language
PUT /api/v1/settings/language     # Update language
PUT /api/v1/settings/{key}        # Update a single key
```

---

## Dashboard

```
GET /api/v1/dashboard/summary             # Usage summary cards
GET /api/v1/dashboard/trends              # Trend charts (?range=7d|30d|90d)
GET /api/v1/dashboard/top-agents          # Top-used agents
GET /api/v1/dashboard/top-tools           # Top-used tools
```

---

## Workspaces

```
GET    /api/v1/workspaces                             # List
GET    /api/v1/workspaces/{id}                        # Get
POST   /api/v1/workspaces                             # Create
PUT    /api/v1/workspaces/{id}                        # Update
DELETE /api/v1/workspaces/{id}                        # Delete (owner only)
GET    /api/v1/workspaces/{id}/access                 # Caller's access info (see below)
```

### Members & RBAC (1.4.0+)

`/access` returns the caller's effective permissions in the workspace; the frontend uses it to render routes and menus:

```json
{
  "memberRole": "editor",
  "isGlobalAdmin": false,
  "effectiveRole": "editor",
  "capabilities": ["workspace.read", "conversation.write", "..."]
}
```

```
GET    /api/v1/workspaces/{id}/members                  # List members
POST   /api/v1/workspaces/{id}/members                  # Add member
PUT    /api/v1/workspaces/{id}/members/{memberId}       # Update member (role, etc.)
DELETE /api/v1/workspaces/{id}/members/{memberId}       # Remove member
```

---

## Doctor (health check)

```
GET /api/v1/doctor/run          # Run all checks
GET /api/v1/doctor/checks       # Cached check results
```

---

## Error responses

```json
{
  "code": 400,
  "message": "Validation failed: name is required"
}
```

### Common status codes

| Code | Meaning |
|------|---------|
| 200 | Success |
| 400 | Bad request — validation failed or missing params |
| 401 | Unauthorized — token missing, expired, or invalid |
| 403 | Forbidden — insufficient permissions |
| 404 | Not found |
| 500 | Internal server error |

---

## Pagination

List endpoints return a consistent shape:

```json
{
  "code": 200,
  "data": {
    "records": [ ],
    "total": 42,
    "current": 1,
    "size": 20,
    "pages": 3
  }
}
```

| Field | Purpose |
|-------|---------|
| `records` | Array of items on the current page |
| `total` | Total items |
| `current` | Current page (1-based) |
| `size` | Items per page |
| `pages` | Total pages |

---

## Next

- [Quick Start](./quickstart) — get the server running
- [Security & Approval](./security) — JWT + approval flow
- [Chat & Messaging](./chat) — SSE event format
- [LLM Wiki](./wiki) — wiki endpoint behaviors
