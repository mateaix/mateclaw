# Workspaces

**A workspace is a box around one team's stuff.**

MateClaw supports multiple teams in a single deployment by organizing every resource — agents, skills, wiki knowledge bases, conversations, memory files, tool guard rules, channels — into **workspaces**. When you're logged in, you see the workspaces you belong to and nothing else. When you switch workspace, the whole UI re-scopes: different agents, different skills, different knowledge, different channels.

The point is that one MateClaw deployment can serve a product team, an engineering team, and a research team without their data, agents, or conversations bleeding into each other.

---

## What belongs to a workspace

Almost everything. The scoped resources:

| Resource | Scoped how |
|----------|-----------|
| **Agents** | Every agent row has a `workspace_id` foreign key |
| **Skills** | Custom and MCP skills are scoped per workspace; builtin skills are global |
| **Wiki knowledge bases** | Every KB belongs to exactly one workspace |
| **Conversations and messages** | Scoped to the workspace the agent lives in |
| **Workspace memory files** | `workspace/{workspaceId}/{agentId}/...` |
| **Channels** | Each channel binds to one agent, so transitively to one workspace |
| **Tool Guard rules** | Rules can be global or scoped to a specific workspace |
| **File Guard paths** | Allowed/denied paths can be workspace-specific |
| **Cron jobs** | Scoped to the workspace of the agent they trigger |
| **Datasources** | External DB connections, scoped per workspace |
| **Audit events** | Every audit event records its `workspace_id` |

What's **not** scoped (i.e., global):

- JWT secret and auth config
- Model providers and API keys (global, with usage-tracked per workspace)
- MCP server definitions (global connections; workspace access is controlled by permissions)
- System-level settings in `mate_system_setting`
- Builtin skills

---

## Workspace roles

Each user is assigned to a workspace with one of four roles:

| Role | Can do |
|------|--------|
| **Owner** | Everything, including deleting the workspace and managing members |
| **Admin** | Everything except deleting the workspace or changing the owner |
| **Member** | Use agents, read/write wiki, create conversations, invoke tools (subject to Tool Guard) |
| **Viewer** | Read-only — see agents and KBs, read conversations, can't create or modify |

A user can belong to multiple workspaces with different roles. When they switch workspace, their effective permissions switch with them.

### Role scope

Roles control **UI visibility** and **API access**. The console hides menu items users don't have permission to use — a viewer role on a workspace doesn't see the Security menu or the workspace management page at all. The backend enforces the same rules on every API endpoint, so hitting a protected endpoint as a viewer returns `403 Forbidden`.

---

## Creating a workspace

`Settings → Workspaces → New Workspace`.

1. Name it after what the team does, not what the team is called ("Product Research" over "Alpha Team")
2. Optional description
3. Save

You become the owner of the workspace. You can now invite members.

### Via API

```bash
curl -X POST http://localhost:18088/api/v1/workspaces \
  -H "Authorization: Bearer <token>" \
  -H "Content-Type: application/json" \
  -d '{
    "name": "Product Research",
    "description": "Competitive research and product specs"
  }'
```

---

## Inviting members

`Settings → Members → Add Member`. Enter an existing MateClaw user's username, pick a role, save.

The member immediately sees the workspace in their workspace switcher on next page load. No invite email, no acceptance flow — the member's account already exists in MateClaw.

### Via API

```bash
curl -X POST http://localhost:18088/api/v1/workspaces/1/members \
  -H "Authorization: Bearer <token>" \
  -H "Content-Type: application/json" \
  -d '{
    "userId": 42,
    "role": "member"
  }'
```

---

## Switching workspace

Top-left of the admin console. Click the workspace name to open the switcher; pick another one to switch. The entire UI re-scopes:

- Sidebar menus re-render based on the new workspace's role
- The agent list refreshes to show agents in this workspace
- The Wiki list, skill list, channel list, etc. all change
- Active conversations stay open (they belong to their own workspace)

Workspace selection is persisted per user — when you log back in, you land on the last workspace you used.

---

## Security primitives that follow workspace boundaries

This is where workspace isolation earns its keep.

### File Guard

The default allowed-path list for File Guard is `workspace/{workspaceId}/...`. A tool call from an agent in workspace A cannot read or write files that belong to workspace B, regardless of path traversal tricks — the symlink check and path normalization catch it.

### Tool Guard rules

Rules can be scoped to a specific workspace. You can have:

- A **global** rule that says `ShellExecuteTool` needs approval
- A **workspace-specific** rule that says `ShellExecuteTool` is allowed if the command matches a narrow read-only pattern

Only the second rule applies inside that workspace. Other workspaces see only the global rule.

### Wiki knowledge bases

A Wiki KB's data never leaves its workspace. An agent in workspace B cannot read a KB that belongs to workspace A, even if it tries. The Wiki search and read tools resolve `kbId` from the bound agent's workspace; cross-workspace reads are rejected at the API layer.

### Memory files

Workspace memory files (PROFILE.md, MEMORY.md, daily notes) live under `workspace/{workspaceId}/{agentId}/`. File Guard enforces the workspace boundary; the memory tools scope their list/read/write operations to the caller's workspace.

### Channels

Each channel binds to exactly one agent, so transitively to exactly one workspace. A DingTalk bot configured in workspace A is completely separate from a DingTalk bot configured in workspace B, even if they're configured to connect to the same DingTalk application (you probably don't want that, but it's technically allowed).

---

## What isolation does NOT cover

- **Shared global config** — JWT secret, model provider API keys, MCP server definitions are global. A workspace admin can't change them.
- **Audit log cross-workspace access** — security admins with the right permissions can query audit events across all workspaces. This is intentional — you want to see suspicious activity regardless of which workspace it happened in.
- **Token usage reporting** — aggregated globally, broken down per-workspace, per-agent, per-model in the Dashboard.
- **Model provider costs** — one billing relationship per provider at the global level; per-workspace quotas are on the [Roadmap](./roadmap).

---

## Moving resources between workspaces

Not supported directly. You have two options:

1. **Export and import** — some resources have JSON export (agents via API, wiki KBs via API). Re-create them in the target workspace.
2. **Change ownership** — an admin or owner can directly update the `workspace_id` column in the database for simple resources. This is not officially supported; do it at your own risk and only with a backup.

We'd like to support first-class moving in a future release. If you need this, leave a note on the [GitHub issue](https://github.com/matevip/mateclaw/issues).

---

## Deleting a workspace

**Only the owner can delete a workspace.** `Settings → Workspaces → [workspace] → Delete`.

Deleting a workspace:

- Soft-deletes every resource belonging to it — agents, skills, KBs, conversations, memory files, channels
- Removes all member associations
- Records an audit event

Soft delete means the data isn't physically removed — it's marked `deleted = 1` and hidden from queries. If you delete by mistake, a database admin can restore it by flipping the flag. After the configured retention period, deleted data may be permanently purged by a cleanup job.

---

## Workspace management API

```bash
# List workspaces you belong to
curl http://localhost:18088/api/v1/workspaces \
  -H "Authorization: Bearer <token>"

# Get one workspace detail
curl http://localhost:18088/api/v1/workspaces/1 \
  -H "Authorization: Bearer <token>"

# Create
curl -X POST http://localhost:18088/api/v1/workspaces \
  -H "Authorization: Bearer <token>" \
  -H "Content-Type: application/json" \
  -d '{"name": "Product Research"}'

# Update
curl -X PUT http://localhost:18088/api/v1/workspaces/1 \
  -H "Authorization: Bearer <token>" \
  -H "Content-Type: application/json" \
  -d '{"description": "Updated description"}'

# Delete (owner only)
curl -X DELETE http://localhost:18088/api/v1/workspaces/1 \
  -H "Authorization: Bearer <token>"

# Member management
curl http://localhost:18088/api/v1/workspaces/1/members \
  -H "Authorization: Bearer <token>"

curl -X POST http://localhost:18088/api/v1/workspaces/1/members \
  -H "Authorization: Bearer <token>" \
  -H "Content-Type: application/json" \
  -d '{"userId": 42, "role": "member"}'

curl -X DELETE http://localhost:18088/api/v1/workspaces/1/members/42 \
  -H "Authorization: Bearer <token>"

curl -X PUT http://localhost:18088/api/v1/workspaces/1/members/42/role \
  -H "Authorization: Bearer <token>" \
  -H "Content-Type: application/json" \
  -d '{"role": "admin"}'
```

---

## Data model

**`mate_workspace`**

| Column | Purpose |
|--------|---------|
| `id` | Primary key |
| `name` | Workspace name |
| `description` | Short description |
| `owner_id` | User ID of the owner |
| `create_time` / `update_time` | Timestamps |
| `deleted` | Logical delete flag |

**`mate_workspace_member`**

| Column | Purpose |
|--------|---------|
| `id` | Primary key |
| `workspace_id` | FK to `mate_workspace` |
| `user_id` | FK to `mate_user` |
| `role` | `owner` / `admin` / `member` / `viewer` |
| `joined_at` | When the user joined this workspace |
| `create_time` / `update_time` | Timestamps |

---

## Next

- [Admin Console](./console) — workspace switcher and UI
- [Security & Approval](./security) — how workspace isolation interacts with Tool Guard and File Guard
- [LLM Wiki](./wiki) — workspace-scoped knowledge bases
- [Memory](./memory) — workspace memory files
