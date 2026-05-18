# Admin Console

The admin console is the Vue 3 SPA that ships with every MateClaw deployment. It runs in your browser (or inside the Electron desktop window), talks to the Spring Boot backend over REST + SSE, and exposes every capability MateClaw has — chat, agents, knowledge, tools, skills, channels, security, cron jobs, usage analytics — behind one login.

This page is the map. It walks the sidebar group by group, page by page, and points at the API endpoints each page uses so you can automate anything the UI lets you click.

---

## Tech stack

- **Framework** — Vue 3 + Composition API + TypeScript
- **State** — Pinia (domain-driven)
- **UI** — Element Plus + TailwindCSS 4 + `--mc-*` CSS variables
- **Build** — Vite 6 (vue-tsc type check → static output into the backend JAR's `static/`)
- **Routing** — Vue Router, history mode
- **i18n** — vue-i18n (`zh-CN` / `en-US`)
- **HTTP** — Axios for REST, native `fetch` for SSE
- **Auth** — JWT with automatic injection and sliding-window renewal

---

## Layout

Left sidebar + right content area (`MainLayout.vue`). Sidebar collapses, state persists to `localStorage`. Bottom of the sidebar holds theme toggles (light / dark / system) and current user info.

### Sidebar groups

Six groups matching the intent-based information architecture:

| Group | Pages |
|-------|-------|
| **Chat** | Chat Console |
| **Use** | Agents, Wiki, Memory Explorer, Multimodal Studio, Sessions |
| **Extend** | Tools, Skills, MCP Servers |
| **Operate** | Channels, Cron Jobs, Token Usage, Dashboard, Datasources |
| **Workspace** | Current workspace overview, Members, Activity |
| **System** | Settings, Security & Approval, Doctor, Onboarding |

Pages you don't have permission to see (based on workspace role) are hidden.

### Auth guard

Every route except `/login` is protected by a `beforeEach` route guard checking for a valid JWT in `localStorage`. Set `VITE_SKIP_AUTH=true` in development to bypass.

---

## Pages

### 1. Login

**Route:** `/login`

Username/password form with password visibility toggle.

- On success, stores token + username + role + active workspace in `localStorage`
- Redirects to the chat console (or to the onboarding wizard on first login)

**API:** `POST /api/v1/auth/login`

**Default credentials:** `admin` / `admin123` — change immediately.

---

### 2. Onboarding wizard

**Route:** `/onboarding`

Shown automatically on first login. Four-step wizard:

1. **Welcome** — short product overview
2. **Configure a model** — pick a provider and paste an API key (or OAuth into ChatGPT Plus, or auto-detect Ollama)
3. **Pick an agent template** — seeds a default agent based on your choice
4. **Send the first message** — a test prompt so you can see streaming work

Skipping drops you on the chat console.

---

### 3. Chat Console

**Route:** `/chat`

The primary interaction surface. Conversations on the left, active chat on the right.

Features:

- **Agent selector** — each agent has its own conversation history
- **Conversation list** — grouped by date (Today / Yesterday / Last 7 Days / Earlier)
- **Model switcher** — pick any configured model for this conversation
- **Grouped model dropdown** — groups by provider and tag, with search
- **Message bubbles** — Markdown + code highlighting
- **Segmented messages** — thinking / tool_call / tool_result / content progressively loaded and persisted in real time
- **Thinking panel** — expand/collapse to see reasoning chain
- **Tool call visualization** — tool name, arguments, result inline
- **Phase status indicator** — current phase shown above the stream
- **Persistent task list** — Plan-and-Execute plans + step statuses in a side panel that survives refresh
- **Tool approval cards** — inline approve/reject buttons
- **File upload** — click / paste / drag
- **Stop generation** — interrupt streaming mid-flight
- **Suggestions** — prompt chips when a conversation is empty

**API:**

- `POST /api/v1/chat/stream` — SSE streaming (native fetch)
- `POST /api/v1/chat/upload`
- `POST /api/v1/chat/{conversationId}/stop`
- `POST /api/v1/approvals/{id}/resolve`
- `GET /api/v1/chat/{conversationId}/pending-approvals`
- `GET /api/v1/conversations` — list
- `GET /api/v1/conversations/{id}/messages`
- `DELETE /api/v1/conversations/{id}`
- `GET /api/v1/agents`
- `GET /api/v1/models/enabled`
- `GET/PUT /api/v1/models/active`

---

### 4. Agents

**Route:** `/agents`

CRUD for agents, shown as a table.

- Search and filter by type (All / ReAct / Plan-Execute)
- Create from template picker
- Edit system prompt, tools, knowledge bindings, max iterations, icon, tags
- Enable/disable toggle, soft delete
- **Agent context page** (`/agents/{id}/context`) — deep view of injected prompt, bound tools, bound KBs, memory files, recent activity

**API:** `/api/v1/agents`

---

### 5. LLM Wiki

**Route:** `/wiki`

Manage knowledge bases and Wiki pages. See [LLM Wiki](./wiki).

- **KB list** — card grid
- **KB detail** — Raw Material, Wiki Pages, Search tabs
- **Raw material management** — upload, scan directories, paste text, re-digest, delete
- **Page browser** — full-text search, backlinks, lock/unlock, edit in place
- **Page editor** — markdown with live preview, source panel
- **Agent bindings** — which agents can read this KB

---

### 6. Multimodal Studio

**Route:** `/multimodal`

Generate media interactively without going through an agent.

- Image generation, video generation, music generation
- TTS playground, STT playground
- Gallery of results — download, share, drop into a conversation

---

### 7. Sessions

**Route:** `/sessions`

Browse conversations across every agent and channel.

- Search by keyword
- Session title, ID, agent, message count, status, last active
- Channel source icon
- Jump to chat console with session open
- Delete historical sessions

---

### 8. Tools

**Route:** `/tools`

Table of every registered tool.

- Name, description, type (`builtin` / `mcp` / `custom`), dangerous flag, enabled
- Register custom tool, edit, toggle, delete
- Test button for provider-backed tools

---

### 9. Skills

**Route:** `/skills`

Card grid of skill packages.

- Category tabs (All / Builtin / Custom / MCP)
- Each card shows name, icon, type badge, version, description, runtime status, security scan summary
- Create / edit / toggle / delete
- **Install from ClawHub** — browse community skills, preview, install
- Refresh runtime status

---

### 10. MCP Servers

**Route:** `/mcp-servers`

- Table: name, description, transport, connection status, tool count, enabled
- Add (stdio / streamable_http / sse)
- Test connection (latency + discovered tools)
- Refresh all, edit, delete, toggle

---

### 11. Channels

**Route:** `/channels`

Card grid for eight IM channels plus web.

- Each card: icon, name, type, description, enabled, real-time connection indicator
- Add channel (DingTalk / Feishu / WeCom / WeChat / Telegram / Discord / QQ / Slack)
- Edit, toggle, delete
- Health view — channel health monitor results

---

### 12. Cron Jobs

**Route:** `/cron-jobs`

Scheduled tasks that trigger agent conversations.

- Name, agent, task type, cron expression (with human-readable translation), next run, last run, enabled
- Create, edit, delete
- **Run now** — trigger immediate execution
- Execution history per job

---

### 13. Datasources

**Route:** `/datasources`

External database connections agents can query through the SQL query skill.

- Name, type (MySQL / PostgreSQL / SQLite / ...), host/port, enabled
- Create, edit, test connection, delete
- Per-datasource permission scoping

---

### 14. Token Usage

**Route:** `/token-usage`

- Date range picker
- Summary cards — total prompt/completion tokens, message count, estimated cost
- Per-model breakdown

---

### 15. Dashboard

**Route:** `/dashboard`

- Summary cards — active agents, conversations today, tool calls today, pending approvals
- **Trend chart** — messages / tool calls / token usage over 7 / 30 / 90 days
- **Top agents / top tools** — ranked by usage
- Recent approval activity

---

### 16. Doctor

**Route:** `/doctor`

System health checks. Backend reachability, database, model providers, channels, MCP, wiki, memory cron, disk usage. Each check reports `ok` / `warning` / `error` with a short diagnostic and a "Fix" link when actionable. See [Doctor](./doctor).

---

### 17. Settings

Sub-route layout with four child pages.

#### 17.1 Models

**Route:** `/settings/models` (default Settings page)

Manage model providers and model configs. Card grid.

- Provider cards — name, icon, ID, builtin/custom badge, active status, base URL, masked API key, model count
- Add custom provider (OpenAI-compatible)
- Manage models under a provider
- Test connection
- **Discover models** — auto-fetch
- Per-model test button
- Delete custom provider

See [Models](./models).

#### 17.2 System

**Route:** `/settings/system`

Global system parameters.

- Language, stream response, debug mode
- **Search service** — provider chain, fallback, API keys (masked)
- **Default agent**

**API:** `/api/v1/settings`

#### 17.3 Workspaces, Members, Activity

**Route:** `/settings/workspaces`, `/settings/members`, `/settings/activity`

- Create / rename / delete workspaces
- Invite / remove members, assign roles (owner / admin / member / viewer)
- Activity feed — recent workspace events

See [Workspaces](./workspaces).

#### 17.4 Feature Flags

**Route:** `/settings/feature-flags`

Runtime-toggleable feature flags. Each row is one flag — flip a switch and the backend honors it without a restart.

What a row exposes:

- **Key** (monospace) — e.g. `wiki.ocr.enabled`, `wiki.hot_cache.enabled`, `wiki.compile.4stage.enabled`
- **Description** — short human-facing copy
- **Toggle** — enabled / disabled
- **Scope** — optional `whitelist_kb_ids` (CSV), `whitelist_user_ids` (CSV), `rollout_percent` (0–100). Set any of these and the flag becomes scoped: only listed KBs / users / a deterministic-hash percentage see it on.
- **Wired badge** — flags whose backend consumer hasn't been hooked up yet render greyed out with a "Not yet implemented" badge so you don't try to toggle something that won't take effect.

**API:** `/api/v1/feature-flags`

| Method | Path | Description |
|---|---|---|
| `GET` | `/` | List all flags |
| `PUT` | `/{flagKey}` | Patch `enabled`, `description`, whitelists, or `rollout_percent` |

Evaluation order at runtime: `enabled=false` → off; whitelist match → on; `rollout_percent` → deterministic `floorMod(id, 100) < rolloutPercent`; otherwise on. The store caches reads for 30 s; admin writes invalidate the cache immediately.

#### 17.5 About

**Route:** `/settings/about`

Version info, tech stack, credits.

---

### 18. Security & Approval

Sub-route layout with four child pages.

#### 18.1 Tool Guard

**Route:** `/security/tool-guard`

Rule-based Tool Guard configuration.

- **Global config** — enabled, default policy, approval timeout, notifications
- **Rules table** — name, severity, category, decision, builtin flag, enabled, priority
- Create / edit / delete / reorder / toggle

See [Security & Approval](./security).

#### 18.2 File Guard

**Route:** `/security/file-guard`

- Global enable/disable
- Allowed paths, denied paths, workspace-scoped overrides

#### 18.3 Approvals

**Route:** `/security/approvals`

Pending and historical approvals.

- Filter by status (pending / approved / rejected / expired)
- Tool name, agent, workspace, requested time, requesting user
- Resolve inline with note
- **Placeholder-substituted argument preview** — see exactly what the agent will execute

#### 18.4 Audit Logs

**Route:** `/security/audit-logs`

- Stats cards — total, blocked, approval-required, allowed
- Filters — tool, decision, date, user, workspace
- Row expand — matched rule, raw arguments, conversation snippet
- Export to CSV

---

### 19. Backstage — admin runtime console

**Route:** `/backstage`  ·  **Requires:** `ROLE_ADMIN`

A live view of every digital employee currently on the clock — status-ring avatars, watchdog-based stuck/orphan detection, soft stop, force recycle, sweep-all, per-subagent interrupt. The page you open when someone says "the agent is stuck."

Full guide: [Backstage](./backstage).

---

## Pinia stores

MateClaw uses domain-driven Pinia stores. Each store owns its slice of state exclusively.

| Store | File | Owns |
|-------|------|------|
| `useAgentStore` | `stores/useAgentStore.ts` | Agent list + CRUD |
| `useWorkspaceStore` | `stores/useWorkspaceStore.ts` | Current workspace + membership |
| `useWikiStore` | `stores/useWikiStore.ts` | KBs, pages, raw materials |
| `useCronJobStore` | `stores/useCronJobStore.ts` | Cron jobs |
| `useThemeStore` | `stores/useThemeStore.ts` | Theme mode, persistence |

Chat state is **not** in a global store — it's managed by the `useChat` composable (`composables/chat/useChat.ts`), scoped to the chat component's lifecycle.

### State ownership

```typescript
// Correct — go through store actions
agentStore.fetchAgents()
themeStore.setMode('dark')

// Wrong — never mutate directly
agentStore.agents = []         // Don't
```

---

## Dark mode

Three modes: **Light**, **Dark**, **System**. Toggle in the sidebar footer.

- `useThemeStore` persists mode to `localStorage`
- Toggling adds/removes `dark` class on `<html>`
- TailwindCSS 4 uses `dark:` prefix
- Element Plus themes switched via `--mc-*` CSS variables
- System mode uses `matchMedia('(prefers-color-scheme: dark)')`

For complex styling, use the `--mc-*` design tokens so transitions are automatic.

---

## Internationalization

- `zh-CN` — Simplified Chinese (default)
- `en-US` — English

Files in `src/i18n/`. Switching: `Settings → System → Language`. Takes effect immediately.

---

## API layer

### Axios instance

```typescript
const http = axios.create({
  baseURL: '/api/v1',
  timeout: 30000,
})
```

**Request interceptor** — reads JWT from `localStorage`, injects into `Authorization` header.

**Response interceptor** — unwraps `R<T>: { code, msg, data }`, picks up `X-New-Token` for sliding-window renewal, handles 401/403 by clearing token and redirecting to login.

### SSE streaming

Chat streaming uses native `fetch`, not Axios:

```typescript
fetch('/api/v1/chat/stream', {
  method: 'POST',
  headers: {
    Accept: 'text/event-stream',
    Authorization: `Bearer ${token}`,
    'Content-Type': 'application/json',
  },
  body: JSON.stringify(data),
})
```

Response is read incrementally via `ReadableStream` and parsed segment by segment.

---

## Route table

```
/login                     — Login
/onboarding                — First-login wizard
/                          — Redirects to /chat (or /onboarding)

/chat                      — Chat Console
/agents                    — Agents
/agents/:id/context        — Agent context deep view
/wiki                      — LLM Wiki
/multimodal                — Multimodal Studio
/sessions                  — Sessions

/tools                     — Tools
/skills                    — Skills
/mcp-servers               — MCP Servers

/channels                  — Channels
/cron-jobs                 — Cron Jobs
/datasources               — Datasources
/token-usage               — Token Usage
/dashboard                 — Dashboard
/doctor                    — Doctor

/settings                  — Redirects to /settings/models
/settings/models           — Model Settings
/settings/system           — System Settings
/settings/workspaces       — Workspaces
/settings/members          — Members
/settings/activity         — Activity
/settings/about            — About

/security                  — Redirects to /security/tool-guard
/security/tool-guard       — Tool Guard
/security/file-guard       — File Guard
/security/approvals        — Approvals
/security/audit-logs       — Audit Logs
```

Unmatched paths redirect to `/chat`.

---

## Build and development

```bash
cd mateclaw-ui
pnpm install
pnpm dev          # Port 5173, proxies /api to :18088
pnpm build        # vue-tsc + vite build into ../mateclaw-server/.../static
pnpm lint         # ESLint
```

Build artifacts embed in the Spring Boot JAR.

Tips:

- **Hot reload** — HMR enabled in dev
- **Path alias** — `@` → `src/`
- **Auto-import** — Element Plus components auto-imported
- **Styling priority** — TailwindCSS utility classes first
- **Skip auth** — `VITE_SKIP_AUTH=true` bypasses login

---

## Next

- [Quick Start](./quickstart) — get the backend + frontend running
- [Contributing](./contributing) — frontend conventions
- [Configuration](./config) — runtime settings
