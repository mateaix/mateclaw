# Skills

**A skill is a tool that thinks in sentences.**

Tools are atomic — read a file, send an HTTP request, run a command. Skills are compositions — "research this topic and write a brief", "review this code and comment on it", "turn my git log into a standup update". A skill is a `SKILL.md` file that combines instructions, parameters, prompt templates, optional scripts, and a list of tools the skill needs. The runtime loads it, renders it with your inputs, and hands the result to the agent.

If tools are hands, skills are recipes.

---

## Five kinds of skills

| Type | Where it comes from | Who maintains it |
|------|--------------------|------------------|
| **`builtin`** | Ships with MateClaw under `skills/` in the classpath | The core team |
| **`custom`** | Created by you through the UI, API, or dropping a file into the workspace | You |
| **`dynamic`** | Auto-synthesized by agents during work | The agent + your approval |
| **`mcp`** | Backed by a tool exposed from an MCP server (a same-name `custom` skill shadows it) | The MCP server author |
| **`acp`** | Bridged from an external Agent Client Protocol endpoint (Claude Code, Codex, etc.) | The upstream agent service |

All five flow through the same runtime pipeline. Only the source differs.

---

## The SKILL.md protocol

Every skill is one Markdown file with YAML frontmatter. The frontmatter is the contract. The body is the prompt.

```markdown
---
name: web-researcher
title: Web Researcher
description: Search the web and summarize findings on a given topic
version: 1.0.0
type: custom
author: your-name
tools:
  - WebSearchTool
  - ReadFileTool
tags:
  - research
  - search
parameters:
  - name: topic
    type: string
    required: true
    description: The topic to research
  - name: depth
    type: string
    required: false
    default: brief
    description: Level of detail (brief, detailed, comprehensive)
---

# Web Researcher

You are a web research assistant. When given a topic, you should:

1. Use WebSearchTool to find relevant information about {{topic}}
2. Evaluate source credibility
3. Compile findings into a {{depth}} summary
4. Include source URLs in your response

## Output Format

Present your findings as:
- **Summary**: 2-3 sentence overview
- **Key Facts**: Bullet-point list
- **Sources**: Numbered list of URLs
```

Two things to notice. First, the body is a prompt — not a description of one. It's what the skill will say to the agent at runtime, with `{{topic}}` and `{{depth}}` filled in. Second, the `tools:` list is a contract: the runtime guarantees those tools are available when the skill runs. If the agent doesn't have them, the skill call fails early with a clear error.

### Frontmatter fields

| Field | Required | Purpose |
|-------|----------|---------|
| `name` | ✅ | Unique identifier (kebab-case) |
| `title` | ✅ | Human-readable display name |
| `description` | ✅ | One-line summary |
| `version` | ✅ | Semantic version |
| `type` | ✅ | `builtin`, `custom`, `mcp` |
| `author` | — | Skill author |
| `tools` | — | List of tool names the skill requires |
| `tags` | — | Categorization |
| `parameters` | — | Typed input parameters |

### Parameter schema

| Field | Required | Purpose |
|-------|----------|---------|
| `name` | ✅ | Parameter name (used in `{{name}}` interpolation) |
| `type` | ✅ | `string`, `number`, `boolean`, `array` |
| `required` | — | Whether it must be provided (default: false) |
| `default` | — | Fallback value if caller omits |
| `description` | ✅ | What the parameter controls |

### Typed wrapper tools for scripts (new in v1.4)

A SKILL.md can declare a `scripts:` block that turns each script entrypoint into its **own named tool** with a typed JSON Schema. Instead of one generic `runSkillScript`, the model sees `skill_<skill>_<entrypoint>` tools and fills in schema-described parameters directly.

```yaml
scripts:
  - id: summarize
    path: scripts/dispatch.py
    fixedArgs: ["summarize"]        # prepended verbatim to every call
    parameters:
      - name: url
        type: string
        required: true
  - id: translate
    path: scripts/dispatch.py
    fixedArgs: ["translate"]
    parameters:
      - name: lang
        type: string
        required: true
```

- **One typed tool per entrypoint** — the model gets typed params, not a free-form arg string.
- **`fixedArgs` lets one dispatcher script back several entrypoints** — both entries above call `dispatch.py`, distinguished by the fixed leading arg, so you don't need a separate file per command.
- **Wrappers register/deregister with the skill lifecycle** — they appear when the skill goes live and disappear when it's disabled or archived. Path traversal is blocked: only scripts under the skill's own `scripts/` directory are reachable. A database-only skill (no directory) exposes no wrappers.

---

## The runtime pipeline

```
1. RESOLVE     Look up the skill by name in mate_skill
       │
       ▼
2. VALIDATE    Check that required parameters are provided
       │
       ▼
3. RENDER      Replace {{parameter}} placeholders in the SKILL.md body
       │
       ▼
4. INJECT      Append the rendered instructions to the agent's system prompt
       │
       ▼
5. BIND TOOLS  Verify required tools are available; fail fast if missing
       │
       ▼
6. EXECUTE     The agent processes the enriched prompt with bound tools
```

Skills don't run scripts by default — they **shape the agent's behavior** for the duration of the call. The agent's next reasoning step sees the skill's rendered instructions as part of its system prompt. The exception is skills that ship with a script — `SkillScriptTool` can execute a skill's bundled script file, gated by Tool Guard.

### Template rendering

Skill bodies support `{{parameterName}}` placeholders. With `{topic: "quantum computing", depth: "detailed"}`:

```markdown
Research the topic "{{topic}}" at a {{depth}} level of detail.
```

…renders to:

```markdown
Research the topic "quantum computing" at a detailed level of detail.
```

Missing parameters fall back to defaults. Unknown placeholders are left intact.

---

## Skill storage

The database is the source of truth, the filesystem is a materialized cache. That's always been the rule for **SKILL.md**, and **as of v1.3 it applies to scripts/ and references/ too**.

### Database: `mate_skill` + `mate_skill_file`

`mate_skill` — skill identity and body:

| Column | Purpose |
|--------|---------|
| `id` | Primary key |
| `name` | Unique name |
| `title` | Display title |
| `description` | One-line summary |
| `type` | `builtin` / `custom` / `mcp` |
| `content` | Full `SKILL.md` content |
| `version` | Semantic version |
| `enabled` | On/off |
| `tags` | JSON array |
| `create_time` / `update_time` | Timestamps |

`mate_skill_file` (new in v1.3, migration `V112`) — the **canonical copy** of every bundle file:

| Column | Purpose |
|--------|---------|
| `id` | Primary key |
| `skill_id` | FK to `mate_skill` |
| `file_path` | Relative path like `scripts/run.py` or `references/cfg.md` |
| `content` | UTF-8 text (≤1 MB per file, ≤50 MB per bundle) |
| `content_size` | Byte count (so listings don't have to load the blob) |
| `sha256` | Content fingerprint, drives the syncer's idempotent diff |

### Filesystem: skill workspace

```
~/.mateclaw/skills/
├── translate/
│   ├── SKILL.md               # Skill definition
│   ├── references/            # Reference materials
│   └── scripts/               # Optional executable scripts
├── code-review/
│   ├── SKILL.md
│   └── ...
└── .archived/                 # Archived old versions
    └── translate-20260401-143000/
```

Think of it as "Maven Local Repository, but for skills" — except the local repo can now hydrate itself from the database.

### Auto-sync on startup

Two sync passes run at boot, so every node has the latest bundle:

1. `SkillWorkspaceBootstrapRunner` → `BundledSkillSyncer` scans the classpath `skills/` directory and syncs **bundled skills** into the workspace root. **Only syncs when the target directory doesn't exist**, so it never clobbers local modifications.
2. `SkillFileSyncer` diffs `mate_skill_file` (DB) against the local workspace (FS) by `sha256` and materializes anything missing or stale.

**Why this matters for multi-instance deployments**: one node accepts the upload, the DB row + file rows are written, every other node either restarts or hits `POST /api/v1/skills/{id}/sync-files` to receive the full bundle. No NFS, no scp loop, even desktop clients can hand a skill off across machines.

> Upgrade path: pre-v1.3 installs have files on disk but no `mate_skill_file` rows. The first time `SkillFileSyncer` runs on a freshly upgraded node, it **backfills from disk** into the canonical store; from then on the two stay in lockstep.

### Robust zip install

Third-party packagers package weirdly — some put `setup.sh` at the zip root, some emit `scripts/` entries before `SKILL.md`. As of v1.3, `ZipSkillFetcher`:

- **Two-pass extraction** — the entire archive is buffered in memory first (cap-protected at 50 MB), `SKILL.md` is located and the wrapper-dir prefix computed, then entries are classified. **Zip entry order no longer affects the result.**
- **Root-level extension fallback** — files sitting next to `SKILL.md` that aren't already under a known bucket get classified by extension: `.sh / .py / .js / .rb / ...` → `scripts/`, `.md / .json / .yaml / .csv / ...` → `references/`. Unknown extensions are dropped with a `WARN` line so packaging mistakes surface instead of vanishing.
- **Write-then-prune + empty-bundle guard** — reinstalls **write new files first, then prune anything in the bucket that's not in the new bundle**. If the new bundle has zero entries for a bucket (`scripts/` or `references/`), the disk copies for that bucket are **left alone** — a malformed re-extract can no longer wipe your scripts. Pass `forcePrune=true` if you really want to clear a bucket via an intentionally empty bundle.

> Real failure this catches: the official tencent-meeting-mcp zip puts `setup.sh` at the package root (not under `scripts/`). The old extractor silently dropped it; the new one auto-classifies it as `scripts/setup.sh` and the skill installs ready to run.

### Configuration

```yaml
mateclaw:
  skill:
    workspace:
      root: ${user.home}/.mateclaw/skills
      auto-init: true
      delete-policy: archive                 # `archive` or `ignore`
      bundled-skills-path: skills
```

---

## Skill Market (and ClawHub)

The **Skill Market** page (`/skills`) is where you browse, install, edit, and manage skills. Three sources:

- **Built-in** — skills that ship with MateClaw
- **Your custom skills** — the ones you created
- **ClawHub** — a community skill repository. Browse thousands of community skills, preview them, install with one click. Installed skills land as `custom` type.

ClawHub is optional — if you're offline or don't want external skills, just don't touch that tab.

---

## Skill Market API

```bash
# List all skills
curl http://localhost:18088/api/v1/skills \
  -H "Authorization: Bearer YOUR_JWT_TOKEN"

# Create a custom skill
curl -X POST http://localhost:18088/api/v1/skills \
  -H "Authorization: Bearer YOUR_JWT_TOKEN" \
  -H "Content-Type: application/json" \
  -d '{
    "name": "code-reviewer",
    "title": "Code Reviewer",
    "description": "Review code for bugs, style issues, and improvements",
    "type": "custom",
    "content": "---\nname: code-reviewer\n...",
    "tags": ["development", "review"]
  }'

# Enable / disable
curl -X PUT http://localhost:18088/api/v1/skills/1 \
  -H "Content-Type: application/json" \
  -H "Authorization: Bearer YOUR_JWT_TOKEN" \
  -d '{"enabled": true}'

# Delete
curl -X DELETE http://localhost:18088/api/v1/skills/1 \
  -H "Authorization: Bearer YOUR_JWT_TOKEN"
```

Delete policy is configurable — by default, deletion moves the skill workspace to `.archived/` rather than erasing it.

---

## Writing a custom skill — step by step

1. **Decide what the skill does.** One sentence.
2. **List the tools it needs.** Three or fewer is a good target.
3. **Write the parameters.** Required ones first, optional with defaults.
4. **Write the body.** Address the agent directly: *"You are X. When given Y, do Z."*
5. **Upload** via the Skill Market UI or API.
6. **Bind** the skill to one or more agents.
7. **Test** by sending a message that should trigger the skill.

Example — "Daily Standup" skill:

```markdown
---
name: daily-standup
title: Daily Standup Generator
description: Generate a daily standup update based on recent git activity
version: 1.0.0
type: custom
tools:
  - ShellExecuteTool
parameters:
  - name: repo_path
    type: string
    required: true
    description: Path to the git repository
---

# Daily Standup Generator

Generate a standup update by analyzing recent git activity.

## Steps

1. Run `git log --oneline --since="yesterday" --author=$(git config user.name)`
   in the directory {{repo_path}}
2. Summarize completed work
3. Identify any work-in-progress branches
4. Format as a standup update:
   - **Yesterday**: What was completed
   - **Today**: What is planned based on open branches
   - **Blockers**: Any merge conflicts or failing tests
```

---

## Workspace isolation

Each workspace gets its own copy of skills. When you enable a skill for a workspace, its files are staged under that workspace's directory, the skill's tools are scoped to that workspace, and any file the skill writes stays inside the workspace boundary. As of v1.4 the skill **catalog and runtime are scoped per workspace** too, so each workspace sees and runs only its own skills. See [Workspaces](./workspaces).

---

## Auto Skill Synthesis

Agents that work with you long enough start noticing patterns — a recurring database query, a particular report layout, the exact commands to SSH into your box. Agents can **turn those patterns into skills on their own**.

The flow:

1. The agent recognizes a reusable workflow during task execution
2. The agent proposes a new skill (create / edit / patch / delete)
3. You review in ChatConsole — check the content, rename if you want, approve or reject
4. On approval, the skill saves as `dynamic` type, ready for reuse

**Security scan runs automatically before save** — dangerous patterns (prompt injection, script injection) are blocked. Skills can migrate between agents and export as ZIP.

The agent's memory grows with you. No more repeating "remember I like tables sorted this way."

---

## Template wizard: start from a starter

Don't know how to write a SKILL.md? Open the wizard.

`Skills → Create Wizard`:

1. Pick a **starter template** (8 of them: researcher, code reviewer, writing assistant, customer-support script, data analysis, Claude Code helper, Codex helper, blank)
2. Fill in the variables — name, parameters, a few sentences of description
3. Upload any supporting files (scripts, references, prompt fragments)
4. Set secrets (API keys, etc.) — **secrets go to a vault, not into SKILL.md**
5. Save

You don't get just a SKILL.md. You get a **multi-file bundle** — SKILL.md, references/, scripts/, secret references — packaged together.

### The `skill-authoring` meta-skill (new in v1.4)

There's now a built-in `skill-authoring` skill, auto-seeded on startup, that teaches an agent (or you) how to author a SKILL.md correctly. It covers:

- **Required frontmatter** and what each field means
- **Validator limits** — name must match `^[a-z0-9][a-z0-9._-]{0,63}$`, content ≤ 100k characters
- **Built-in vs custom** authoring workflows
- **Directory placement** for scripts/ and references/
- **Common pitfalls** that fail validation or silently misbehave

Bind it to an agent and "write me a skill that…" produces a valid bundle on the first try, not after three validation round-trips.

---

## Pre-flight check before installation

A skill that's installed isn't necessarily a skill that runs — it might need an API key, a CLI tool, a MateClaw feature flag toggled on.

Used to be: install, run, fail, debug. Now:

**Pre-flight install dialog** — runs the prerequisite check automatically before the skill goes live:

- Are the required tools present?
- Are the required API keys configured?
- Are the required feature flags on?
- Are the dependent MCP / ACP endpoints reachable?

Whatever's missing is reported up front, with a one-click **`[Set Up]`** button that jumps to the right config page. **No more install-then-debug.**

---

## LESSONS.md: skills that learn from experience

Each skill can carry a `LESSONS.md` — what the skill learned during runs.

- After a run, the skill can **proactively write a lesson**: "Last time the user didn't like that format, don't do it again"
- Next time the same skill is invoked, LESSONS get auto-injected into the prompt context
- The more it's used, the better it knows **when to step in and when to stay out**

This is the first cut of skill self-evolution. Skills go from a list of instructions to something with playbooks, experience, and the capacity to grow.

LESSONS are viewable and editable in the skill detail drawer's **Memory tab**.

---

## Secrets: put the token in the right place

Lots of skills need API credentials to function — tencent-meeting needs `TENCENT_MEETING_TOKEN`, Slack needs a bot token, Linear needs a personal API key. Those values **don't belong in SKILL.md** (it goes into the prompt and leaks to the LLM), don't belong in scripts (one git push and you're sorry), and editing `~/.zshrc` requires restarting the server and won't follow the skill across machines.

As of v1.3, every skill has its own **per-skill secret store**.

### Manage it in the UI

Skill detail drawer → **Secrets** tab. One table plus a form:

```
Key                        Value         Last updated     Actions
TENCENT_MEETING_TOKEN     sk••••ef      2026-05-12       [Edit] [Delete]

[+ Add secret]
```

- **Plaintext never leaves the server** — listing returns only `preview` (`sk••••ef`-style mask); the add/edit dialog's value field starts blank, saving overwrites whatever was there.
- **Client-side validation** — keys must match `^[A-Za-z_][A-Za-z0-9_]{0,127}$`; bad keys are rejected in the browser before submission.
- **Value field is `<input type="password" autocomplete="off">`** — shoulder-surfers, screenshots, and password managers all stay out.

### How it's stored / how it's injected

| Stage | What happens |
|---|---|
| Write | `POST /api/v1/skills/{id}/secrets` `{key, value}` → AES-encrypted → `mate_skill_secret` |
| Read | Before subprocess launch, `SkillSecretService.getDecrypted(skillId)` AES-decrypts |
| Inject | `ProcessBuilder.environment().putAll(...)` — **overrides parent-process env vars of the same name** |

The injection rule is **secret-store wins, `.zshrc` is the fallback**. For multi-user / multi-machine deployments, desktop clients, and corporate accounts that don't share databases, the secret store is the more reliable source of truth.

### REST endpoints

```bash
# List (masked)
GET    /api/v1/skills/{id}/secrets
# Upsert (empty value deletes)
POST   /api/v1/skills/{id}/secrets   {"key":"...", "value":"..."}
# Delete
DELETE /api/v1/skills/{id}/secrets/{key}
```

### A full example: tencent-meeting

```
SkillMarket → tencent-meeting-mcp card → detail drawer → Secrets tab
  → + Add secret → key=TENCENT_MEETING_TOKEN, value=<paste your token>
  → Save

Then when the agent runs setup.sh or scripts/tencent_meeting.py:
  ProcessBuilder env carries $TENCENT_MEETING_TOKEN
  → mcporter / Python script calls the Tencent API → meeting ID returned
```

No `~/.zshrc` edit, no mateclaw restart needed.

---

## Discoverability: a skill installed should be a skill found

Installing a new skill used to mean the agent often couldn't find it. Three causes, three fixes, all in v1.3.

### 1) New skills are **boosted** in the prompt catalog

The agent's system prompt carries a compact Skills table. Each model gets a row cap based on its max input tokens — qwen-turbo with 8192 tokens gets only **8 entries**. A brand-new skill has zero usage history, so the existing recent / frequent / RECOMMENDED sort buries it behind ~40 older skills, well below the cutoff.

v1.3 inserts a "**installed in the last 7 days**" sort key at the front of the ranker. Install on Friday, the skill is still in the first frame on Monday — long enough to span a weekend, short enough not to occupy a slot indefinitely. Builtins and virtual MCP/ACP rows are excluded (you didn't "just install" them).

### 2) `listAvailableSkills()` teaches the LLM how to search wider

The tool description now explicitly says:

- The default page is 20 entries; if you see `Showing: 20 of 47`, **retry with `keyword=<part of name>` or `limit=50`**
- If the user mentions a specific skill name, **skip the catalog** — go straight to `readSkillFile(skillName="<exact-name>", filePath="SKILL.md")` to verify

Truncated results carry a one-line hint at the end so even small models can see how to follow up.

### 3) Calling a skill name as a tool **auto-redirects**

LLMs occasionally call a skill name as if it were a tool (`tencent-meeting-mcp({...})`). The previous behavior was a textual hint telling them to call `readSkillFile` instead — which qwen-turbo-class models often can't act on. They reply "let me get that for you" and end the turn without any further tool call, producing a dead loop.

As of v1.3, when `ToolExecutionExecutor` sees this case AND `readSkillFile` is bound to the agent, it **transparently invokes readSkillFile on the LLM's behalf** and returns the SKILL.md content (prefixed with `[auto-redirect]` and the original args echoed back) as the tool result. The model has runnable instructions in front of it on its very first attempt and goes straight to `runSkillScript`, no loop.

> This fix helps small models a lot and doesn't hurt large models (they would have followed the textual hint anyway).

---

## Progressive skill disclosure (new in v1.4)

Dumping every skill's full SKILL.md into the system prompt doesn't scale — it blows the token budget and churns the prompt cache on every turn. v1.4 flips the model: the prompt carries only a compact catalog, and the agent **pulls a skill's instructions on demand**.

**`load_skill(skillName, filePath?)`** loads a skill's SKILL.md (or any bundle file via the optional `filePath`) right when the agent decides to use it:

- **Injected via message history, not the system prompt** — the loaded content arrives as a conversation turn, so the system prompt (and its cache) stays byte-stable across the session.
- **Loaded skills get pinned** to the top of the runtime catalog on later turns, so the agent keeps seeing what it just pulled in.
- The catalog guidance tells the model to `load_skill(skillName=<name>)` before using a skill, and to call it directly when the user names a specific skill.

```yaml
mateclaw:
  skill:
    disclosure:
      load-skill-tool:
        enabled: true     # default; set false to fall back to the older readSkillFile flow
```

When disabled, the catalog guidance points at `readSkillFile` instead and `load_skill` is not registered.

---

## Skill lifecycle curator (new in v1.4)

Agents that synthesize skills accumulate cruft — a one-off skill from three weeks ago is still in the catalog, eating a slot. The **curator** is a daily sweep that ages idle, **agent-created** skills through `active → stale → archived` and gets them out of the way without deleting anything.

- Idle past `staleAfterDays` (default 30) → **stale**; idle past `archiveAfterDays` (default 90) → **archived** (workspace moved to a `.archived/` subdir). `restore` brings an archived skill back.
- **Never touched**: built-ins, pinned skills, MCP/ACP/virtual skills, and any name starting with a protected prefix (default `sys-`, `ops-`).

### Settings → Skill Curator panel

- **Preview (dry-run)** — see exactly which skills the next sweep would move, before it runs.
- **Pause / resume** the whole sweep; **activate / deactivate** an individual skill.
- **Last run / next run** timestamps and **per-state counts** (active / stale / archived).

### Configuration

```yaml
mateclaw:
  skill:
    curator:
      enabled: true
      cron: "0 0 2 * * *"        # daily at 02:00
      staleAfterDays: 30
      archiveAfterDays: 90
      scope: AGENT_CREATED       # AGENT_CREATED | ALL_DYNAMIC | OFF
      protectPrefixes: ["sys-", "ops-"]
```

`scope: AGENT_CREATED` touches only skills with a source conversation; `ALL_DYNAMIC` also sweeps manually-created dynamic skills; `OFF` disables the sweep regardless of `enabled`.

### Lifecycle in the Skill Market

The Skills page picks up the lifecycle:

- **Lifecycle tabs** — Enabled / Stale / Archived.
- Cards show a **"last used"** badge.
- The detail drawer adds **manual archive / restore / pin**.
- Manually archiving a still-bound skill triggers a **confirm handshake** — you don't silently pull a skill out from under a digital employee that's still using it.

---

## ACP bridge: plug in external coding agents

ACP (Agent Client Protocol) is a protocol that lets external agent clients (Claude Code, Codex, other compatible clients) plug into MateClaw as skills.

Once installed:

- ACP endpoints **auto-bridge into skill cards** — they show up on the Skills page with a wrapper toolset
- **Visual env editor** — every endpoint's required key, URL, CWD, configurable in the UI
- **Per-session cwd** — every ACP session has its own working directory
- **Errors translated** — upstream messages like "Request not allowed" get translated into something actionable
- **OAuth keychain hijack detection** — if your OAuth token has been hijacked by another app, you're prompted to re-authenticate

Templates: `claude-code-helper`, `codex-helper` — install and go.

A digital employee calls an ACP skill the same way it calls a built-in tool.

### Virtual SKILL.md for MCP/ACP skills (new in v1.4)

MCP- and ACP-derived skills used to be opaque tool bundles with no readable instructions. v1.4 **synthesizes a read-only virtual SKILL.md** from each MCP/ACP server's metadata (transport, command, args, env, exposed tools), so those integrations show up as **navigable skill catalogs** in the Skills page. Because they're synthesized, virtual SKILL.md rebuilds on every list call — no stale persisted copy to maintain — and `load_skill` can read it just like a real skill, giving the agent a description of what the integration can do before it calls a single tool.

---

## Detail drawer: everything in one place

Every skill card opens a drawer with eight tabs:

- **Overview** — identity fields, manifest projection, source, version
- **Body** — `SKILL.md` editor (takes over the full drawer width)
- **Tools** — which tools this skill uses (with effective tool expansion)
- **Features** — capability matrix
- **Security** — content scan results, related Tool Guard rules
- **Lessons** — `LESSONS.md` content
- **Secrets** — env-var-style credentials (new in v1.3; see the "Secrets" section below)
- **Memory** — digital employees bound to this skill

The card itself is slim — six fields and one status pill. **Clear beats comprehensive.**

---

## Security

Custom skills go through several checks before they become live:

- **Content scanning** — `SKILL.md` scanned for prompt injection and script injection on upload
- **Tool requirement check** — `tools:` list must only reference tools that exist
- **Tool Guard compliance** — skills with dangerous tools inherit Tool Guard rules
- **MCP skill constraints** — MCP-backed skills inherit the security constraints of their MCP server

Full review in [Security & Approval](./security).

---

## Next

- [Tools](./tools) — tools that skills can use
- [Agents](./agents) — how agents invoke skills during a turn
- [MCP](./mcp) — MCP-backed skills
- [Security & Approval](./security) — skill scanning details
