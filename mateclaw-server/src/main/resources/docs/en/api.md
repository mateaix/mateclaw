# API Reference

This page is source-aligned with the Spring MVC controllers under `mateclaw-server/src/main/java`. The route inventory below was rebuilt from controller annotations; when it conflicts with an older feature page, this page and the source code are the contract.

## Contract

All application REST endpoints use the `/api/v1` prefix unless explicitly noted. Most JSON responses use the project envelope:

```json
{
  "code": 200,
  "msg": "success",
  "data": {}
}
```

Important exceptions:

- Streaming endpoints (`text/event-stream`) send SSE frames instead of the JSON envelope.
- Download endpoints such as `/api/v1/files/generated/{id}`, chat uploads, and wiki raw downloads return bytes or `ResponseEntity` bodies.
- A few conflict/error flows may return a small structured object outside `R<T>` when the client must branch on the HTTP status.

IDs are Snowflake `Long` values serialized as JSON strings by the backend. Frontends and third-party clients should keep IDs as strings.

## Authentication

`POST /api/v1/auth/login` returns the JWT. Send protected requests with:

```text
Authorization: Bearer <token>
```

Public routes from `SecurityConfig` include login, first-run setup, webhook/webchat callbacks, chat stream/stop routes, agent stream route, talk WebSocket, `GET /api/v1/settings/language`, and `/api/v1/files/generated/**` one-time generated-file downloads. Role annotations such as `@RequireWorkspaceRole` and `@RequireGlobalAdmin` still apply after authentication.

Workspace-scoped APIs usually accept `X-Workspace-Id`. If omitted, many handlers fall back to workspace `1` for desktop/local compatibility.

## Frequently Used APIs

### Login

```bash
curl -X POST http://localhost:18088/api/v1/auth/login \
  -H "Content-Type: application/json" \
  -d '{"username":"admin","password":"admin123"}'
```

### Chat

```bash
curl -N -X POST http://localhost:18088/api/v1/chat/stream \
  -H "Authorization: Bearer $TOKEN" \
  -H "Content-Type: application/json" \
  -H "Accept: text/event-stream" \
  -d '{"agentId":"1","message":"Hello","conversationId":"conv-abc123"}'
```

Use `fetch()` with a streaming reader for `/chat/stream`; browser `EventSource` cannot send POST bodies.

### Tool Approval

There is no `POST /api/v1/approvals/{id}/resolve` REST endpoint. Web approval and denial go through the chat stream by sending `/approve` or `/deny` in the waiting conversation. Read-only hydration remains `GET /api/v1/chat/{conversationId}/pending-approvals`. Auto-approval policies are managed under `/api/v1/approval/grants`.

### Doctor / Health

The current backend health surface is `GET /api/v1/system/health`. The old `/api/v1/doctor/*` endpoints are not implemented in the current source tree.

### Multimodal Generation

Image, video, music, and 3D generation are agent tools (`image_generate`, `video_generate`, `music_generate`, `model3d_generate`), not standalone `/api/v1/image`, `/api/v1/video`, or `/api/v1/music` REST controllers. REST surfaces that do exist here are TTS/STT and generated-file download.

### Non-REST Endpoint

`/api/v1/talk/ws` is registered by `WebSocketConfig` for Talk Mode. It is intentionally listed in `SecurityConfig` as a public WebSocket route, but it is not counted in the controller route inventory below.

## Source-Aligned Route Inventory

Total routes extracted: 406.

### Authentication

| Method | Path | Purpose / handler |
|---|---|---|
| `POST` | `/api/v1/auth/login` | `Login` |
| `GET` | `/api/v1/auth/tokens` | `List my PATs (metadata only — plaintext is never returned after creation)` |
| `POST` | `/api/v1/auth/tokens` | `Mint a new PAT — returned plaintext is shown once and cannot be recovered` |
| `DELETE` | `/api/v1/auth/tokens/{id}` | `Revoke a PAT — soft-delete; further auth attempts with this token will fail` |
| `GET` | `/api/v1/auth/users` | `List Users` |
| `POST` | `/api/v1/auth/users` | `Create User` |
| `PUT` | `/api/v1/auth/users/{id}/password` | `Change Password` |

### Chat

| Method | Path | Purpose / handler |
|---|---|---|
| `POST` | `/api/v1/chat` | `Chat` |
| `GET` | `/api/v1/chat/files/{conversationId}/{storedName:.+}` | `Read Uploaded File` |
| `POST` | `/api/v1/chat/stream` | `Chat Stream` |
| `POST` | `/api/v1/chat/upload` | `Upload` |
| `POST` | `/api/v1/chat/{conversationId}/interrupt` | `Interrupt Stream` |
| `GET` | `/api/v1/chat/{conversationId}/pending-approvals` | `Get Pending Approvals` |
| `POST` | `/api/v1/chat/{conversationId}/stop` | `Stop Stream` |

### Conversations

| Method | Path | Purpose / handler |
|---|---|---|
| `GET` | `/api/v1/conversations` | `List` |
| `POST` | `/api/v1/conversations/batch-delete` | `Batch Delete` |
| `GET` | `/api/v1/conversations/page` | `Page` |
| `DELETE` | `/api/v1/conversations/{conversationId}` | `Delete` |
| `DELETE` | `/api/v1/conversations/{conversationId}/messages` | `Clear Messages` |
| `GET` | `/api/v1/conversations/{conversationId}/messages` | `List Messages` |
| `PUT` | `/api/v1/conversations/{conversationId}/model` | `Set Model` |
| `PUT` | `/api/v1/conversations/{conversationId}/pin` | `Set Pinned` |
| `GET` | `/api/v1/conversations/{conversationId}/status` | `Get Stream Status` |
| `PUT` | `/api/v1/conversations/{conversationId}/title` | `Rename` |

### Agents

| Method | Path | Purpose / handler |
|---|---|---|
| `GET` | `/api/v1/agents` | `List` |
| `POST` | `/api/v1/agents` | `Create` |
| `GET` | `/api/v1/agents/{agentId}/provider-preferences` | `List Provider Preferences` |
| `PUT` | `/api/v1/agents/{agentId}/provider-preferences` | `Set Provider Preferences` |
| `GET` | `/api/v1/agents/{agentId}/skills` | `List Skills` |
| `PUT` | `/api/v1/agents/{agentId}/skills` | `Set Skills` |
| `DELETE` | `/api/v1/agents/{agentId}/skills/{skillId}` | `Unbind Skill` |
| `POST` | `/api/v1/agents/{agentId}/skills/{skillId}` | `Bind Skill` |
| `GET` | `/api/v1/agents/{agentId}/tools` | `List Tools` |
| `PUT` | `/api/v1/agents/{agentId}/tools` | `Set Tools` |
| `GET` | `/api/v1/agents/{agentId}/workspace/files` | `List Files` |
| `DELETE` | `/api/v1/agents/{agentId}/workspace/files/**` | `Delete File` |
| `GET` | `/api/v1/agents/{agentId}/workspace/files/**` | `Get File` |
| `PUT` | `/api/v1/agents/{agentId}/workspace/files/**` | `Save File` |
| `GET` | `/api/v1/agents/{agentId}/workspace/memory/export` | `Export Memory` |
| `POST` | `/api/v1/agents/{agentId}/workspace/memory/import` | `Import Memory` |
| `POST` | `/api/v1/agents/{agentId}/workspace/memory/import/preview` | `Preview Import Memory` |
| `GET` | `/api/v1/agents/{agentId}/workspace/prompt-files` | `Get Prompt Files` |
| `PUT` | `/api/v1/agents/{agentId}/workspace/prompt-files` | `Set Prompt Files` |
| `DELETE` | `/api/v1/agents/{id}` | `Delete` |
| `GET` | `/api/v1/agents/{id}` | `Get` |
| `PUT` | `/api/v1/agents/{id}` | `Update` |
| `GET` | `/api/v1/agents/{id}/capabilities` | `Capabilities` |
| `POST` | `/api/v1/agents/{id}/chat` | `Chat` |
| `GET` | `/api/v1/agents/{id}/chat/stream` | `Chat Stream` |
| `POST` | `/api/v1/agents/{id}/execute` | `Execute` |
| `GET` | `/api/v1/agents/{id}/state` | `Get State` |

### Agent Templates

| Method | Path | Purpose / handler |
|---|---|---|
| `GET` | `/api/v1/templates` | `List` |
| `POST` | `/api/v1/templates/{id}/apply` | `Apply` |

### Sub-agents

| Method | Path | Purpose / handler |
|---|---|---|
| `GET` | `/api/v1/subagents/active` | `List active sub-agents in a conversation's delegation tree` |
| `POST` | `/api/v1/subagents/spawn-pause` | `Set sub-agent spawn-pause for a conversation` |
| `POST` | `/api/v1/subagents/{subagentId}/interrupt` | `Interrupt a running sub-agent` |

### Admin Runtime

| Method | Path | Purpose / handler |
|---|---|---|
| `POST` | `/api/v1/admin/agent-runtime/runs/{conversationId}/recycle` | `Force recycle — dispose flux + drop RunState; use after friendly stop ignored` |
| `POST` | `/api/v1/admin/agent-runtime/runs/{conversationId}/stop` | `Friendly stop — request the run to wind down at its next checkpoint` |
| `GET` | `/api/v1/admin/agent-runtime/snapshot` | `Snapshot of every in-flight agent turn` |
| `POST` | `/api/v1/admin/agent-runtime/subagents/{subagentId}/interrupt` | `Interrupt one sub-agent (admin override of ownership check)` |
| `POST` | `/api/v1/admin/agent-runtime/sweep` | `Recycle every run currently flagged as stuck` |

### Approval Grants

| Method | Path | Purpose / handler |
|---|---|---|
| `GET` | `/api/v1/approval/grants` | `List` |
| `POST` | `/api/v1/approval/grants` | `Create` |
| `GET` | `/api/v1/approval/grants/active` | `Active Summary` |
| `DELETE` | `/api/v1/approval/grants/{id}` | `Revoke` |
| `GET` | `/api/v1/approval/resolutions` | `List Resolutions` |

### Security and Tool Guard

| Method | Path | Purpose / handler |
|---|---|---|
| `GET` | `/api/v1/security/approvals` | `List Approvals` |
| `GET` | `/api/v1/security/audit/logs` | `List Audit Logs` |
| `GET` | `/api/v1/security/audit/stats` | `Get Audit Stats` |
| `GET` | `/api/v1/security/guard/config` | `Get Guard Config` |
| `PUT` | `/api/v1/security/guard/config` | `Update Guard Config` |
| `GET` | `/api/v1/security/guard/config/file-guard` | `Get File Guard Config` |
| `PUT` | `/api/v1/security/guard/config/file-guard` | `Update File Guard Config` |
| `GET` | `/api/v1/security/guard/rules` | `List Rules` |
| `POST` | `/api/v1/security/guard/rules` | `Create Rule` |
| `GET` | `/api/v1/security/guard/rules/builtin` | `List Builtin Rules` |
| `DELETE` | `/api/v1/security/guard/rules/by-id/{id}` | `Delete Rule By Pk` |
| `GET` | `/api/v1/security/guard/rules/export` | `Export Rules` |
| `POST` | `/api/v1/security/guard/rules/import` | `Import Rules` |
| `DELETE` | `/api/v1/security/guard/rules/{ruleId}` | `Delete Rule` |
| `PUT` | `/api/v1/security/guard/rules/{ruleId}` | `Update Rule` |
| `PUT` | `/api/v1/security/guard/rules/{ruleId}/toggle` | `Toggle Rule` |

### Audit

| Method | Path | Purpose / handler |
|---|---|---|
| `GET` | `/api/v1/audit/events` | `List Events` |

### Activity

| Method | Path | Purpose / handler |
|---|---|---|
| `GET` | `/api/v1/activity/feed` | `Unified activity feed (audit + approval + tool calls)` |

### Notifications

| Method | Path | Purpose / handler |
|---|---|---|
| `GET` | `/api/v1/notifications/summary` | `Aggregated counts for the sidebar attention badges` |

### Workspaces

| Method | Path | Purpose / handler |
|---|---|---|
| `GET` | `/api/v1/workspaces` | `List` |
| `POST` | `/api/v1/workspaces` | `Create` |
| `DELETE` | `/api/v1/workspaces/{id}` | `Delete` |
| `GET` | `/api/v1/workspaces/{id}` | `Get` |
| `PUT` | `/api/v1/workspaces/{id}` | `Update` |
| `GET` | `/api/v1/workspaces/{id}/access` | `Get Access` |
| `GET` | `/api/v1/workspaces/{id}/members` | `List Members` |
| `POST` | `/api/v1/workspaces/{id}/members` | `Add Member` |
| `DELETE` | `/api/v1/workspaces/{id}/members/{targetUserId}` | `Remove Member` |
| `PUT` | `/api/v1/workspaces/{id}/members/{targetUserId}` | `Update Member Role` |

### Settings

| Method | Path | Purpose / handler |
|---|---|---|
| `GET` | `/api/v1/settings` | `Get Settings` |
| `PUT` | `/api/v1/settings` | `Save Settings` |
| `GET` | `/api/v1/settings/language` | `Get Language` |
| `PUT` | `/api/v1/settings/language` | `Save Language` |
| `PUT` | `/api/v1/settings/sidecar` | `Save Sidecar` |

### First-run Setup

| Method | Path | Purpose / handler |
|---|---|---|
| `POST` | `/api/v1/setup/init` | `Init` |
| `GET` | `/api/v1/setup/onboarding-status` | `Get Onboarding Status` |
| `GET` | `/api/v1/setup/status` | `Get Status` |

### System Health

| Method | Path | Purpose / handler |
|---|---|---|
| `GET` | `/api/v1/system/browser-health` | `Browser launch diagnostics` |
| `GET` | `/api/v1/system/health` | `System health check` |

### Dashboard

| Method | Path | Purpose / handler |
|---|---|---|
| `GET` | `/api/v1/dashboard/cron-runs` | `Recent Runs` |
| `GET` | `/api/v1/dashboard/cron-runs/{cronJobId}` | `Cron Job Runs` |
| `GET` | `/api/v1/dashboard/overview` | `Overview` |
| `GET` | `/api/v1/dashboard/trend` | `Trend` |

### Token Usage

| Method | Path | Purpose / handler |
|---|---|---|
| `GET` | `/api/v1/token-usage` | `Get Summary` |

### Models

| Method | Path | Purpose / handler |
|---|---|---|
| `GET` | `/api/v1/models` | `List` |
| `POST` | `/api/v1/models` | `Create` |
| `GET` | `/api/v1/models/active` | `Get Active Model` |
| `PUT` | `/api/v1/models/active` | `Set Active Model` |
| `GET` | `/api/v1/models/by-type` | `List By Type` |
| `GET` | `/api/v1/models/catalog` | `Catalog` |
| `DELETE` | `/api/v1/models/custom-providers` | `Delete Custom Provider By Query` |
| `POST` | `/api/v1/models/custom-providers` | `Create Custom Provider` |
| `DELETE` | `/api/v1/models/custom-providers/{providerId}` | `Delete Custom Provider` |
| `GET` | `/api/v1/models/default` | `Get Default Model` |
| `GET` | `/api/v1/models/embedding/default` | `Get Default Embedding` |
| `POST` | `/api/v1/models/embedding/default` | `Set Default Embedding` |
| `POST` | `/api/v1/models/embedding/{modelId}/test` | `Test Embedding` |
| `GET` | `/api/v1/models/enabled` | `List Enabled` |
| `DELETE` | `/api/v1/models/{id}` | `Delete` |
| `GET` | `/api/v1/models/{id}` | `Get` |
| `PUT` | `/api/v1/models/{id}` | `Update` |
| `POST` | `/api/v1/models/{id}/default` | `Set Default` |
| `PUT` | `/api/v1/models/{providerId}/config` | `Update Provider Config` |
| `POST` | `/api/v1/models/{providerId}/disable` | `Disable Provider` |
| `POST` | `/api/v1/models/{providerId}/discover` | `Discover Models` |
| `POST` | `/api/v1/models/{providerId}/discover/apply` | `Apply Discovered Models` |
| `POST` | `/api/v1/models/{providerId}/enable` | `Enable Provider` |
| `DELETE` | `/api/v1/models/{providerId}/models` | `Remove Provider Model` |
| `POST` | `/api/v1/models/{providerId}/models` | `Add Provider Model` |
| `POST` | `/api/v1/models/{providerId}/models/test` | `Test Model` |
| `POST` | `/api/v1/models/{providerId}/test-connection` | `Test Connection` |

### OAuth

| Method | Path | Purpose / handler |
|---|---|---|
| `POST` | `/api/v1/oauth/anthropic/reload` | `Force re-detect credentials and refresh if near expiry` |
| `GET` | `/api/v1/oauth/anthropic/status` | `Read current Claude Code OAuth credential status from local disk` |
| `GET` | `/api/v1/oauth/openai/authorize` | `Authorize` |
| `POST` | `/api/v1/oauth/openai/callback-paste` | `Callback Paste` |
| `POST` | `/api/v1/oauth/openai/device/cancel` | `Device flow: cancel a pending session` |
| `POST` | `/api/v1/oauth/openai/device/poll` | `Device flow: poll for completion` |
| `POST` | `/api/v1/oauth/openai/device/start` | `Device flow: start — request user_code` |
| `POST` | `/api/v1/oauth/openai/refresh` | `Refresh` |
| `DELETE` | `/api/v1/oauth/openai/revoke` | `Revoke` |
| `GET` | `/api/v1/oauth/openai/status` | `Status` |

### LLM Runtime

| Method | Path | Purpose / handler |
|---|---|---|
| `GET` | `/api/v1/llm/provider-pool` | `Snapshot` |
| `POST` | `/api/v1/llm/provider-pool/{providerId}/reprobe` | `Reprobe` |

### Tools

| Method | Path | Purpose / handler |
|---|---|---|
| `GET` | `/api/v1/tools` | `List` |
| `POST` | `/api/v1/tools` | `Create` |
| `GET` | `/api/v1/tools/available` | `List Available` |
| `GET` | `/api/v1/tools/enabled` | `List Enabled` |
| `DELETE` | `/api/v1/tools/{id}` | `Delete` |
| `GET` | `/api/v1/tools/{id}` | `Get` |
| `PUT` | `/api/v1/tools/{id}` | `Update` |
| `PUT` | `/api/v1/tools/{id}/disclosure-tier` | `Set Disclosure Tier` |
| `PUT` | `/api/v1/tools/{id}/toggle` | `Toggle` |

### MCP Servers

| Method | Path | Purpose / handler |
|---|---|---|
| `GET` | `/api/v1/mcp/servers` | `List` |
| `POST` | `/api/v1/mcp/servers` | `Create` |
| `POST` | `/api/v1/mcp/servers/refresh` | `Refresh` |
| `DELETE` | `/api/v1/mcp/servers/{id}` | `Delete` |
| `GET` | `/api/v1/mcp/servers/{id}` | `Get` |
| `PUT` | `/api/v1/mcp/servers/{id}` | `Update` |
| `PUT` | `/api/v1/mcp/servers/{id}/disclosure-tier` | `Set Disclosure Tier` |
| `POST` | `/api/v1/mcp/servers/{id}/test` | `Test` |
| `PUT` | `/api/v1/mcp/servers/{id}/toggle` | `Toggle` |
| `GET` | `/api/v1/mcp/servers/{id}/tools` | `List Tools` |

### ACP Endpoints

| Method | Path | Purpose / handler |
|---|---|---|
| `GET` | `/api/v1/acp/endpoints` | `List ACP endpoints` |
| `POST` | `/api/v1/acp/endpoints` | `Create a custom ACP endpoint` |
| `DELETE` | `/api/v1/acp/endpoints/{id}` | `Delete an ACP endpoint (builtins are protected)` |
| `GET` | `/api/v1/acp/endpoints/{id}` | `Get ACP endpoint by id` |
| `PUT` | `/api/v1/acp/endpoints/{id}` | `Update an ACP endpoint` |
| `POST` | `/api/v1/acp/endpoints/{id}/test` | `Test ACP endpoint connection (initialize handshake)` |
| `PUT` | `/api/v1/acp/endpoints/{id}/toggle` | `Enable / disable an ACP endpoint` |

### Skills

| Method | Path | Purpose / handler |
|---|---|---|
| `GET` | `/api/v1/skills` | `List` |
| `POST` | `/api/v1/skills` | `Create` |
| `GET` | `/api/v1/skills/counts` | `Counts` |
| `POST` | `/api/v1/skills/curator/activate` | `Curator Activate` |
| `POST` | `/api/v1/skills/curator/dry-run` | `Curator Dry Run` |
| `POST` | `/api/v1/skills/curator/pause` | `Curator Pause` |
| `GET` | `/api/v1/skills/curator/reports` | `Curator Reports` |
| `GET` | `/api/v1/skills/curator/reports/{runId}` | `Curator Report` |
| `POST` | `/api/v1/skills/curator/resume` | `Curator Resume` |
| `GET` | `/api/v1/skills/curator/status` | `Curator Status` |
| `GET` | `/api/v1/skills/enabled` | `List Enabled` |
| `POST` | `/api/v1/skills/install/cancel/{taskId}` | `Cancel` |
| `GET` | `/api/v1/skills/install/hub/search` | `Search Hub` |
| `POST` | `/api/v1/skills/install/start` | `Start Install` |
| `GET` | `/api/v1/skills/install/status/{taskId}` | `Get Status` |
| `POST` | `/api/v1/skills/install/upload` | `Upload Zip` |
| `DELETE` | `/api/v1/skills/install/{skillName}` | `Uninstall` |
| `GET` | `/api/v1/skills/prompt-preview` | `Prompt Preview` |
| `GET` | `/api/v1/skills/runtime/active` | `Get Active Skills` |
| `POST` | `/api/v1/skills/runtime/refresh` | `Refresh Runtime` |
| `GET` | `/api/v1/skills/runtime/status` | `Get Runtime Status` |
| `GET` | `/api/v1/skills/summary` | `Summary` |
| `POST` | `/api/v1/skills/sync-files` | `Re-sync every skill's bundle files (admin)` |
| `POST` | `/api/v1/skills/synthesize-from-conversation` | `Synthesize From Conversation` |
| `GET` | `/api/v1/skills/type/{skillType}` | `List By Type` |
| `DELETE` | `/api/v1/skills/{id}` | `Delete` |
| `GET` | `/api/v1/skills/{id}` | `Get` |
| `PUT` | `/api/v1/skills/{id}` | `Update` |
| `POST` | `/api/v1/skills/{id}/archive` | `Archive` |
| `GET` | `/api/v1/skills/{id}/employees` | `List agents that can use this skill (RFC-090 §14.2)` |
| `POST` | `/api/v1/skills/{id}/export-workspace` | `Export To Workspace` |
| `GET` | `/api/v1/skills/{id}/lessons` | `Read per-skill LESSONS.md (RFC-090 §11.4)` |
| `POST` | `/api/v1/skills/{id}/lessons/clear` | `Clear all lessons for a skill (RFC-090 §11.4)` |
| `POST` | `/api/v1/skills/{id}/pin` | `Pin` |
| `GET` | `/api/v1/skills/{id}/requirements` | `Pre-flight requirement statuses for a skill (RFC-090)` |
| `POST` | `/api/v1/skills/{id}/rescan` | `Rescan` |
| `POST` | `/api/v1/skills/{id}/restore` | `Restore` |
| `POST` | `/api/v1/skills/{id}/sync-files` | `Re-sync this skill's bundle files from DB → local workspace cache` |
| `PUT` | `/api/v1/skills/{id}/toggle` | `Toggle` |
| `GET` | `/api/v1/skills/{id}/workspace` | `Get Workspace Info` |
| `GET` | `/api/v1/skills/{skillId}/secrets` | `List secret keys + masked previews for a skill` |
| `POST` | `/api/v1/skills/{skillId}/secrets` | `Upsert a secret value (empty value deletes it)` |
| `DELETE` | `/api/v1/skills/{skillId}/secrets/{key}` | `Delete a single secret by key` |

### Skill Templates

| Method | Path | Purpose / handler |
|---|---|---|
| `GET` | `/api/v1/skill-templates` | `List skill templates (RFC-091)` |
| `GET` | `/api/v1/skill-templates/{id}` | `Get a single skill template` |
| `POST` | `/api/v1/skill-templates/{id}/instantiate` | `Instantiate a template into a skill` |

### Plugins

| Method | Path | Purpose / handler |
|---|---|---|
| `GET` | `/api/v1/plugins` | `List all plugins` |
| `GET` | `/api/v1/plugins/{name}` | `Get plugin detail` |
| `PUT` | `/api/v1/plugins/{name}/config` | `Update plugin configuration` |
| `POST` | `/api/v1/plugins/{name}/disable` | `Disable a plugin` |
| `POST` | `/api/v1/plugins/{name}/enable` | `Enable a plugin` |

### LLM Wiki

| Method | Path | Purpose / handler |
|---|---|---|
| `POST` | `/api/v1/wiki/admin/backfill-tokens` | `Force-run the token-count backfill batch now` |
| `POST` | `/api/v1/wiki/admin/kb/{kbId}/rebuild-overview` | `Ensure overview/log scaffold + rebuild overview stats now` |
| `GET` | `/api/v1/wiki/chunks/{chunkId}/pages` | `Pages By Chunk Id` |
| `DELETE` | `/api/v1/wiki/hot-cache/{kbId}` | `Soft-delete the hot cache row` |
| `GET` | `/api/v1/wiki/hot-cache/{kbId}` | `Get the current hot cache snapshot for a KB` |
| `POST` | `/api/v1/wiki/hot-cache/{kbId}/regenerate` | `Schedule a manual rebuild of the hot cache` |
| `GET` | `/api/v1/wiki/kb/{kbId}/jobs` | `Get Jobs` |
| `GET` | `/api/v1/wiki/kb/{kbId}/pages/{pageId}/citations` | `Page Citations` |
| `GET` | `/api/v1/wiki/kb/{kbId}/pages/{slugA}/relation/{slugB}` | `Explain Relation` |
| `POST` | `/api/v1/wiki/kb/{kbId}/pages/{slug}/enrich` | `Enrich Page` |
| `GET` | `/api/v1/wiki/kb/{kbId}/pages/{slug}/related` | `Related Pages` |
| `POST` | `/api/v1/wiki/kb/{kbId}/pages/{slug}/repair` | `Repair Page` |
| `POST` | `/api/v1/wiki/kb/{kbId}/search-preview` | `Search Preview` |
| `GET` | `/api/v1/wiki/kb/{kbId}/stats` | `Kb Stats` |
| `GET` | `/api/v1/wiki/knowledge-bases` | `List KBs` |
| `POST` | `/api/v1/wiki/knowledge-bases` | `Create KB` |
| `GET` | `/api/v1/wiki/knowledge-bases/agent/{agentId}` | `List KBs By Agent` |
| `GET` | `/api/v1/wiki/knowledge-bases/bindable` | `List Bindable KBs` |
| `DELETE` | `/api/v1/wiki/knowledge-bases/{id}` | `Delete KB` |
| `GET` | `/api/v1/wiki/knowledge-bases/{id}` | `Get KB` |
| `PUT` | `/api/v1/wiki/knowledge-bases/{id}` | `Update KB` |
| `GET` | `/api/v1/wiki/knowledge-bases/{id}/config` | `Get Config` |
| `PUT` | `/api/v1/wiki/knowledge-bases/{id}/config` | `Update Config` |
| `GET` | `/api/v1/wiki/knowledge-bases/{id}/page-type-profile` | `Get Page Type Profile` |
| `PUT` | `/api/v1/wiki/knowledge-bases/{id}/page-type-profile` | `Save Page Type Profile` |
| `POST` | `/api/v1/wiki/knowledge-bases/{id}/page-type-profile/reset-default` | `Reset Page Type Profile` |
| `POST` | `/api/v1/wiki/knowledge-bases/{id}/page-type-profile/validate` | `Validate Page Type Profile` |
| `POST` | `/api/v1/wiki/knowledge-bases/{id}/scan` | `Scan Directory` |
| `PUT` | `/api/v1/wiki/knowledge-bases/{id}/source-directory` | `Set Source Directory` |
| `GET` | `/api/v1/wiki/knowledge-bases/{id}/source-watcher` | `Get Source Watcher` |
| `POST` | `/api/v1/wiki/knowledge-bases/{id}/source-watcher/scan` | `Trigger Source Watcher` |
| `GET` | `/api/v1/wiki/knowledge-bases/{kbId}/agents/{agentId}/page-type-permissions` | `List Page Type Permissions` |
| `POST` | `/api/v1/wiki/knowledge-bases/{kbId}/agents/{agentId}/page-type-permissions` | `Save Page Type Permission` |
| `DELETE` | `/api/v1/wiki/knowledge-bases/{kbId}/agents/{agentId}/page-type-permissions/{id}` | `Delete Page Type Permission` |
| `GET` | `/api/v1/wiki/knowledge-bases/{kbId}/lint/broken-links` | `Get Broken Links Report` |
| `POST` | `/api/v1/wiki/knowledge-bases/{kbId}/lint/broken-links` | `Start Broken Links Scan` |
| `GET` | `/api/v1/wiki/knowledge-bases/{kbId}/lint/broken-links/jobs/{jobId}` | `Get Broken Links Job` |
| `GET` | `/api/v1/wiki/knowledge-bases/{kbId}/pages` | `List Pages` |
| `GET` | `/api/v1/wiki/knowledge-bases/{kbId}/pages/archived` | `List Archived Pages` |
| `DELETE` | `/api/v1/wiki/knowledge-bases/{kbId}/pages/batch` | `Batch Delete Pages` |
| `GET` | `/api/v1/wiki/knowledge-bases/{kbId}/pages/refs` | `List Page Refs` |
| `DELETE` | `/api/v1/wiki/knowledge-bases/{kbId}/pages/{slug}` | `Delete Page` |
| `GET` | `/api/v1/wiki/knowledge-bases/{kbId}/pages/{slug}` | `Get Page` |
| `PUT` | `/api/v1/wiki/knowledge-bases/{kbId}/pages/{slug}` | `Update Page` |
| `POST` | `/api/v1/wiki/knowledge-bases/{kbId}/pages/{slug}/archive` | `Archive Page` |
| `GET` | `/api/v1/wiki/knowledge-bases/{kbId}/pages/{slug}/backlinks` | `Get Backlinks` |
| `POST` | `/api/v1/wiki/knowledge-bases/{kbId}/pages/{slug}/rename` | `Rename Page` |
| `POST` | `/api/v1/wiki/knowledge-bases/{kbId}/pages/{slug}/unarchive` | `Unarchive Page` |
| `GET` | `/api/v1/wiki/knowledge-bases/{kbId}/pipeline-runs/{runId}` | `Get Pipeline Run` |
| `GET` | `/api/v1/wiki/knowledge-bases/{kbId}/pipelines` | `List Pipelines` |
| `POST` | `/api/v1/wiki/knowledge-bases/{kbId}/pipelines` | `Save Pipeline` |
| `POST` | `/api/v1/wiki/knowledge-bases/{kbId}/pipelines/validate` | `Validate Pipeline` |
| `DELETE` | `/api/v1/wiki/knowledge-bases/{kbId}/pipelines/{id}` | `Delete Pipeline` |
| `GET` | `/api/v1/wiki/knowledge-bases/{kbId}/pipelines/{id}/runs` | `List Pipeline Runs` |
| `POST` | `/api/v1/wiki/knowledge-bases/{kbId}/process` | `Process KB` |
| `GET` | `/api/v1/wiki/knowledge-bases/{kbId}/processing-status` | `Get Processing Status` |
| `GET` | `/api/v1/wiki/knowledge-bases/{kbId}/progress` | `Subscribe Progress` |
| `GET` | `/api/v1/wiki/knowledge-bases/{kbId}/raw` | `List Raw` |
| `POST` | `/api/v1/wiki/knowledge-bases/{kbId}/raw/text` | `Add Raw Text` |
| `POST` | `/api/v1/wiki/knowledge-bases/{kbId}/raw/upload` | `Upload Raw` |
| `DELETE` | `/api/v1/wiki/knowledge-bases/{kbId}/raw/{rawId}` | `Delete Raw` |
| `POST` | `/api/v1/wiki/knowledge-bases/{kbId}/raw/{rawId}/cancel` | `Cancel Raw` |
| `GET` | `/api/v1/wiki/knowledge-bases/{kbId}/raw/{rawId}/download` | `Download Raw` |
| `POST` | `/api/v1/wiki/knowledge-bases/{kbId}/raw/{rawId}/reprocess` | `Reprocess Raw` |
| `GET` | `/api/v1/wiki/pages/lookup` | `Lookup Pages` |
| `GET` | `/api/v1/wiki/raw/{rawId}/pages` | `Pages By Raw Id` |
| `POST` | `/api/v1/wiki/research/start` | `Start Research` |
| `GET` | `/api/v1/wiki/research/stream/{sessionId}` | `Stream` |
| `GET` | `/api/v1/wiki/transformations` | `List transformations available to a KB` |
| `POST` | `/api/v1/wiki/transformations` | `Create` |
| `GET` | `/api/v1/wiki/transformations/runs` | `List Runs` |
| `DELETE` | `/api/v1/wiki/transformations/runs/{runId}` | `Delete Run` |
| `GET` | `/api/v1/wiki/transformations/runs/{runId}` | `Get Run` |
| `POST` | `/api/v1/wiki/transformations/runs/{runId}/cancel` | `Cancel a still-running transformation run` |
| `POST` | `/api/v1/wiki/transformations/runs/{runId}/save-as-page` | `Save a completed run's output as a synthesis wiki page` |
| `DELETE` | `/api/v1/wiki/transformations/{id}` | `Delete` |
| `GET` | `/api/v1/wiki/transformations/{id}` | `Get` |
| `PUT` | `/api/v1/wiki/transformations/{id}` | `Update` |
| `POST` | `/api/v1/wiki/transformations/{id}/aggregate` | `Aggregate all completed runs of a template into one KB-level synthesis page` |
| `POST` | `/api/v1/wiki/transformations/{id}/apply` | `Run a transformation against a raw material or wiki page` |

### Memory

| Method | Path | Purpose / handler |
|---|---|---|
| `GET` | `/api/v1/memory/{agentId}/dream/events` | `Subscribe to dream events (SSE)` |
| `GET` | `/api/v1/memory/{agentId}/dream/morning-card` | `Get morning card for current user + agent` |
| `POST` | `/api/v1/memory/{agentId}/dream/morning-card/seen` | `Mark morning card as seen` |
| `GET` | `/api/v1/memory/{agentId}/dream/reports` | `List dream reports (paginated, newest first)` |
| `GET` | `/api/v1/memory/{agentId}/dream/reports/{reportId}` | `Get a single dream report by ID` |
| `POST` | `/api/v1/memory/{agentId}/dream/reports/{reportId}/entries/{key}/confirm` | `Confirm a memory entry (no-op acknowledgment)` |
| `POST` | `/api/v1/memory/{agentId}/dream/reports/{reportId}/entries/{key}/edit` | `Edit a memory entry — writes back to the target memory file with user-edited metadata` |
| `GET` | `/api/v1/memory/{agentId}/dreaming/candidates` | `Get Dreaming Candidates` |
| `GET` | `/api/v1/memory/{agentId}/dreaming/dreams` | `Get Dreams` |
| `POST` | `/api/v1/memory/{agentId}/dreaming/focused` | `Trigger Focused Dream` |
| `GET` | `/api/v1/memory/{agentId}/dreaming/status` | `Get Dreaming Status` |
| `POST` | `/api/v1/memory/{agentId}/emergence` | `Trigger Emergence` |
| `GET` | `/api/v1/memory/{agentId}/facts` | `List facts for an agent` |
| `GET` | `/api/v1/memory/{agentId}/facts/contradictions` | `List unresolved contradictions` |
| `POST` | `/api/v1/memory/{agentId}/facts/contradictions/{contradictionId}/resolve` | `Resolve a contradiction (KEEP_A / KEEP_B / MERGE / IGNORE)` |
| `POST` | `/api/v1/memory/{agentId}/facts/{factId}/feedback` | `Submit feedback on a fact (HELPFUL/UNHELPFUL)` |
| `POST` | `/api/v1/memory/{agentId}/facts/{factId}/forget` | `Forget a fact — writes canonical metadata, rebuilds projection` |
| `POST` | `/api/v1/memory/{agentId}/summarize/{conversationId}` | `Trigger Summarize` |

### Goals

| Method | Path | Purpose / handler |
|---|---|---|
| `GET` | `/api/v1/goals` | `List goals (optionally filtered by status)` |
| `POST` | `/api/v1/goals` | `Create a persistent goal for a conversation` |
| `GET` | `/api/v1/goals/by-conversation/{conversationId}` | `Get the active goal bound to a conversation (or null)` |
| `GET` | `/api/v1/goals/{id}` | `Get goal detail by id` |
| `PATCH` | `/api/v1/goals/{id}` | `Sparse update of a non-terminal goal` |
| `POST` | `/api/v1/goals/{id}/abandon` | `Abandon a goal (terminal)` |
| `POST` | `/api/v1/goals/{id}/criteria` | `Append a sub-criterion to an active goal` |
| `GET` | `/api/v1/goals/{id}/events` | `Get the event timeline for a goal` |
| `POST` | `/api/v1/goals/{id}/pause` | `Pause an active goal` |
| `POST` | `/api/v1/goals/{id}/resume` | `Resume a paused goal` |

### Cron Jobs

| Method | Path | Purpose / handler |
|---|---|---|
| `GET` | `/api/v1/cron-jobs` | `List` |
| `POST` | `/api/v1/cron-jobs` | `Create` |
| `GET` | `/api/v1/cron-jobs/active-runs` | `Active Runs` |
| `DELETE` | `/api/v1/cron-jobs/{id}` | `Delete` |
| `GET` | `/api/v1/cron-jobs/{id}` | `Get` |
| `PUT` | `/api/v1/cron-jobs/{id}` | `Update` |
| `POST` | `/api/v1/cron-jobs/{id}/run` | `Run Now` |
| `PUT` | `/api/v1/cron-jobs/{id}/toggle` | `Toggle` |

### Triggers

| Method | Path | Purpose / handler |
|---|---|---|
| `GET` | `/api/v1/triggers` | `List triggers in the caller's workspace.` |
| `POST` | `/api/v1/triggers` | `Create a trigger; if enabled, registers it with the scheduler.` |
| `POST` | `/api/v1/triggers/events` | `Ingest one event envelope; returns per-trigger fire / drop summary.` |
| `DELETE` | `/api/v1/triggers/{id}` | `Delete a trigger and unregister its schedule.` |
| `GET` | `/api/v1/triggers/{id}` | `Get a trigger by id, scoped to the caller's workspace.` |
| `PUT` | `/api/v1/triggers/{id}` | `Update a trigger; pattern_version bumps when the cron expression changes.` |

### Workflows

| Method | Path | Purpose / handler |
|---|---|---|
| `GET` | `/api/v1/workflows` | `List workflows in the workspace` |
| `POST` | `/api/v1/workflows` | `Create a workflow row (draft starts empty).` |
| `POST` | `/api/v1/workflows/draft/generate` | `Generate a workflow draft from a natural-language description.` |
| `POST` | `/api/v1/workflows/draft/preview-compile` | `Compile arbitrary draft JSON without persisting — used by the template picker / generator preview to surface real ACL + schema diagnostics before a workflow row exists.` |
| `GET` | `/api/v1/workflows/draft/templates` | `List the canonical workflow templates the generator can apply directly.` |
| `GET` | `/api/v1/workflows/runs/paused` | `List paused runs across the workspace so operators can resume them.` |
| `GET` | `/api/v1/workflows/runs/{runId}` | `Inspect a single run with its step rows for replay / debugging.` |
| `POST` | `/api/v1/workflows/runs/{runId}/resume` | `Resume a paused workflow run with the given outcome.` |
| `DELETE` | `/api/v1/workflows/{id}` | `Soft-delete a workflow row.` |
| `GET` | `/api/v1/workflows/{id}` | `Get a workflow by id (includes inline draft + latest published graph).` |
| `PUT` | `/api/v1/workflows/{id}` | `Update workflow metadata (name / description / enabled).` |
| `POST` | `/api/v1/workflows/{id}/compile` | `Compile the draft and surface diagnostics without persisting a revision.` |
| `PUT` | `/api/v1/workflows/{id}/draft` | `Save the inline draft graph_json without compiling.` |
| `POST` | `/api/v1/workflows/{id}/publish` | `Compile the draft and persist a new revision pointed at by latest_revision_id.` |
| `GET` | `/api/v1/workflows/{id}/runs` | `List the most recent runs for a workflow.` |

### Channels

| Method | Path | Purpose / handler |
|---|---|---|
| `GET` | `/api/v1/channels` | `List` |
| `POST` | `/api/v1/channels` | `Create` |
| `GET` | `/api/v1/channels/health` | `Health All` |
| `POST` | `/api/v1/channels/preflight` | `Pre-flight: validate draft channel config without persisting` |
| `POST` | `/api/v1/channels/qrcode/{channelType}/begin` | `Begin` |
| `GET` | `/api/v1/channels/qrcode/{channelType}/status` | `Status` |
| `GET` | `/api/v1/channels/status` | `Status` |
| `GET` | `/api/v1/channels/type/{channelType}` | `List By Type` |
| `GET` | `/api/v1/channels/webchat/config` | `Get Config` |
| `POST` | `/api/v1/channels/webchat/stream` | `Chat Stream` |
| `POST` | `/api/v1/channels/webhook/dingtalk` | `Dingtalk Webhook` |
| `POST` | `/api/v1/channels/webhook/dingtalk/register/begin` | `Dingtalk Register Begin` |
| `GET` | `/api/v1/channels/webhook/dingtalk/register/status` | `Dingtalk Register Status` |
| `POST` | `/api/v1/channels/webhook/discord` | `Discord Webhook` |
| `POST` | `/api/v1/channels/webhook/feishu` | `Feishu Webhook` |
| `POST` | `/api/v1/channels/webhook/feishu/register/begin` | `Feishu Register Begin` |
| `GET` | `/api/v1/channels/webhook/feishu/register/status` | `Feishu Register Status` |
| `POST` | `/api/v1/channels/webhook/slack` | `Slack Webhook` |
| `GET` | `/api/v1/channels/webhook/status` | `Status` |
| `POST` | `/api/v1/channels/webhook/telegram` | `Telegram Webhook` |
| `POST` | `/api/v1/channels/webhook/wecom` | `Wecom Webhook` |
| `GET` | `/api/v1/channels/webhook/weixin/qrcode` | `Weixin Qrcode` |
| `GET` | `/api/v1/channels/webhook/weixin/qrcode/status` | `Weixin Qrcode Status` |
| `DELETE` | `/api/v1/channels/{id}` | `Delete` |
| `GET` | `/api/v1/channels/{id}` | `Get` |
| `PUT` | `/api/v1/channels/{id}` | `Update` |
| `GET` | `/api/v1/channels/{id}/health` | `Health` |
| `PUT` | `/api/v1/channels/{id}/toggle` | `Toggle` |

### Datasources

| Method | Path | Purpose / handler |
|---|---|---|
| `GET` | `/api/v1/datasources` | `List` |
| `POST` | `/api/v1/datasources` | `Create` |
| `DELETE` | `/api/v1/datasources/{id}` | `Delete` |
| `GET` | `/api/v1/datasources/{id}` | `Get` |
| `PUT` | `/api/v1/datasources/{id}` | `Update` |
| `POST` | `/api/v1/datasources/{id}/test` | `Test Connection` |
| `PUT` | `/api/v1/datasources/{id}/toggle` | `Toggle` |

### Speech to Text

| Method | Path | Purpose / handler |
|---|---|---|
| `POST` | `/api/v1/stt/transcribe` | `Transcribe` |

### Text to Speech

| Method | Path | Purpose / handler |
|---|---|---|
| `POST` | `/api/v1/tts/synthesize` | `Synthesize` |
| `GET` | `/api/v1/tts/voices` | `List Voices` |

### Generated Files

| Method | Path | Purpose / handler |
|---|---|---|
| `GET` | `/api/v1/files/generated/{id}` | `Download a tool-generated file by its one-time id` |

### Plans

| Method | Path | Purpose / handler |
|---|---|---|
| `GET` | `/api/v1/plans` | `List By Agent` |
| `GET` | `/api/v1/plans/{id}` | `Get Plan` |

### Feature Flags

| Method | Path | Purpose / handler |
|---|---|---|
| `GET` | `/api/v1/feature-flags` | `List` |
| `PUT` | `/api/v1/feature-flags/{flagKey}` | `Update` |
