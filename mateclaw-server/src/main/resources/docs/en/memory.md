---
title: AI Memory System — 4-Layer Memory Lifecycle (Extract, Consolidate, Dream, Recall)
description: MateClaw's 4-layer memory lifecycle — in-conversation context, post-chat extraction, workspace persistence (PROFILE.md/MEMORY.md), and scheduled Dreaming consolidation. Your AI gets smarter every day.
head:
  - - meta
    - name: keywords
      content: AI memory,memory system,Dreaming,PROFILE.md,MEMORY.md,memory lifecycle,long-term memory,memory extraction,memory consolidation
---

# AI Memory System

**Memory is how the system gets better at knowing you.**

Everything else in MateClaw is static the moment you configure it. Agents, tools, knowledge bases — they change when you change them. Memory is the one part that changes on its own, as a byproduct of actual use. That's the whole point.

::: tip Your AI dreams about you while you sleep
That's not a marketing line. It's literal code in the `memory/dreaming/` package.

Every night at 2 AM (default; configurable) a scheduled job runs — its name is **Dreaming**. It walks every agent's conversation trail from the day, consolidates scattered signals into a coherent understanding of you, filters out one-offs and contradictions and stale facts, promotes recurring patterns into `MEMORY.md`, and appends "what it saw, what it concluded, what it rewrote" to `DREAMS.md` — a human-readable audit trail of how memory got to where it is today.

When you open MateClaw the next morning, it **picks up where yesterday left off** — not from zero.

> Every other AI starts each day from scratch. MateClaw continues from where yesterday ended.
:::

This page covers the four layers that make up memory, the files the system writes for each agent, and how agents themselves read and write those files during a conversation.

---

## The four layers

```
  ┌────────────────────────────────────────────────────────────┐
  │  1. This turn                                                │
  │     What you're saying, what was just said, auto-trimmed     │
  │     to the model's token budget                              │
  │     Updated: every turn                                      │
  └────────────────────────────────────────────────────────────┘
                            │
                            ▼ (after conversation completes)
  ┌────────────────────────────────────────────────────────────┐
  │  2. Post-chat extraction                                     │
  │     Pulls the worth-keeping bits out of the conversation,    │
  │     writes them into PROFILE.md / MEMORY.md / today's note   │
  │     Updated: asynchronously, after each meaningful chat      │
  └────────────────────────────────────────────────────────────┘
                            │
                            ▼ (daily at 2:00 AM, configurable)
  ┌────────────────────────────────────────────────────────────┐
  │  3. Nightly consolidation (Dreaming)                         │
  │     Scans recent daily notes, finds recurring patterns,      │
  │     merges them into MEMORY.md, logs the run in DREAMS.md    │
  │     Updated: scheduled; manual trigger available             │
  └────────────────────────────────────────────────────────────┘
                            │
                            ▼ (next conversation picks up the latest)
  ┌────────────────────────────────────────────────────────────┐
  │  4. Workspace files as system prompt                         │
  │     The four markdown files are injected every turn          │
  │     Updated: file changes take effect on the next turn       │
  └────────────────────────────────────────────────────────────┘
```

Each layer operates at a different timescale. Short-term is *this turn*. Extraction is *after each conversation*. Consolidation is *nightly*. Workspace file injection is *every turn uses whatever's current*. Together they form a loop — what you say becomes context, context becomes files, files become system prompt, system prompt becomes what the agent knows tomorrow.

---

## Multi-layer memory with pluggable providers

The memory layer is not one hard-coded implementation. It's an **interface** — the multi-layer architecture lets you stack providers:

- The **default provider** is the workspace-file-based memory described in the rest of this page. It ships with MateClaw, and for most people it's all they'll ever need.
- **Custom providers** can be dropped in for specialized retrieval — vector-based long-term memory, graph memory, external memory services.
- **Layering** means a single agent can talk to multiple providers at once. A short-term provider returns recent context; a semantic provider returns related memories; a Wiki provider returns authoritative references. They compose at read time.

For most agents, **default is enough** and you should ignore this section. If you're building something specialized — an agent that needs to remember thousands of facts with vector search, an agent that needs graph-structured memory — this is where you plug in. See [Architecture](./architecture).

---

## The four files every agent has

Every agent has its own workspace. Four markdown files form the backbone of long-term memory:

```
workspace/{agentId}/
├── AGENTS.md          # How the agent uses memory — behavior guide
├── SOUL.md            # Who the agent is — core identity, personality, boundaries
├── PROFILE.md         # Who you are — user profile, preferences, background
├── MEMORY.md          # What matters — key decisions, project context, todos
└── memory/
    ├── 2026-04-09.md  # Daily notes — what happened today, append-only
    ├── 2026-04-10.md
    └── 2026-04-11.md
```

The first four are **injected into the system prompt on every turn** (if `enabled=true`). Daily notes are not — they feed consolidation instead.

### What each file is *for*

- **AGENTS.md** — the agent's user manual for itself. When to write memory, what goes where, what tools are available. Seed: `enabled=true`, `sort_order=0`.
- **SOUL.md** — who the agent fundamentally is. Self-awareness, evolution guidance, privacy and boundary principles. Edit when you want to change the agent's character at a deep level. Seed: `enabled=true`, `sort_order=1`.
- **PROFILE.md** — what the agent has learned about you. Name, occupation, tech stack, communication preferences. Updated by the extractor when conversations reveal something durable. Full-replace writes. Seed: `enabled=true`, `sort_order=2`.
- **MEMORY.md** — what the agent has decided matters enough to keep. Active projects, unresolved decisions, open threads, things you asked it to remember. Updated by both the extractor and the consolidator. Seed: `enabled=true`, `sort_order=3`.

::: tip New in 1.3.0: workflows can write memory
From v1.3.0, the [workflow](./workflow) `write_memory` step can write the run's output directly into an employee's `MEMORY.md` (or any enabled memory file) when the flow completes. Four merge strategies: `append` / `replace_section` / `upsert_kv` / `overwrite`. Memory is no longer written exclusively by the conversation extractor or the Dreaming consolidator — a business-process outcome can be persisted too.
:::

### Daily notes

Conversation highlights archived by date, in append mode — multiple conversations in one day concatenate into the same file. Not injected into the system prompt (`enabled=false`). They exist so the consolidator has something to scan at 2 AM.

---

## Short-term: the context window

Before every LLM call, MateClaw builds the prompt that actually gets sent:

```
[System Prompt]                        ← Always first
[Workspace file injection]             ← AGENTS / SOUL / PROFILE / MEMORY
[Conversation context summary]         ← Only if earlier turns got compressed
[Message 1: user]
[Message 2: assistant]
...
[Current user message]                 ← Always last
```

Workspace files are injected sorted by `sort_order`, formatted as:

```
--- AGENTS.md ---
(content)

--- SOUL.md ---
(content)

--- PROFILE.md ---
(content)

--- MEMORY.md ---
(content)
```

Only files with `enabled=true` are included.

### When context gets too big

Three-stage defense:

**Stage 1 — proactive compression.** When estimated total exceeds 75% of the budget (default window 128k tokens), the system calls the LLM to summarize earlier turns. The most recent 2 turns (4 messages) survive verbatim. The summary is cached for 30 minutes.

**Stage 2 — emergency recovery.** If the LLM still returns context-too-large, the system stops calling the LLM. It discards older messages, keeps the last 2 turns, and retries once.

**Stage 3 — hard trim.** If tokens are *still* over budget, messages drop from the front until the prompt fits. The last 2 messages are always preserved.

> **Security design** — the summary is injected as a **user message**, not a system message. Deliberate: preventing compressed historical user input from being elevated into system-level instructions eliminates an injection vector.

### Configuration

```yaml
mate:
  agent:
    conversation:
      window:
        default-max-input-tokens: 128000   # Global max
        compact-trigger-ratio: 0.75        # Compression trigger
        preserve-recent-pairs: 2           # Turns preserved verbatim
        summary-max-tokens: 300            # Compression budget
```

---

## Post-chat extraction

After a conversation ends, the system asynchronously pulls out what's memorable and writes it to PROFILE.md, MEMORY.md, and the day's daily note. This happens off the user-response path — it never blocks the next turn.

### What triggers it

After a turn completes, the system handles extraction on a background thread. A few preconditions must pass before it actually runs:

- Auto-summarize is on
- The conversation wasn't itself triggered by the consolidation cron job (avoids recursion)
- Message count meets the minimum (default 4)
- The last user message is long enough (default at least 10 chars)

All pass — extraction begins.

### Concurrency control

- **Cooldown** — same agent won't extract twice within 5 minutes (default)
- **Per-agent lock** — if an extraction is already running for this agent, the new request is skipped

### What the LLM actually does

1. Load conversation messages
2. Read current PROFILE.md, MEMORY.md, today's daily note
3. Build a transcript: up to 30 messages, each truncated to 2000 chars
4. Call the LLM with the memory-summarize prompt templates
5. Parse the JSON response
6. Apply writes

### LLM response schema

| Field | Type | What it does |
|-------|------|--------------|
| `should_update` | boolean | Whether memory needs updating |
| `reason` | string | Why (for audit) |
| `daily_entry` | string | Content to append to today's daily note |
| `memory_update` | string | Full new content for MEMORY.md |
| `profile_update` | string | Full new content for PROFILE.md |

### File write rules

- **PROFILE.md** — full replace, only if `profile_update` is non-empty
- **MEMORY.md** — full replace, only if `memory_update` is non-empty
- **memory/YYYY-MM-DD.md** — append, created with date heading if missing

---

## Consolidation and dreaming

The third layer runs on a schedule. Its job is to watch daily notes pile up and periodically ask: *what's the pattern here, what should be promoted into core memory, what's stale and should be forgotten?*

### What it does

1. Lists the agent's `memory/*.md` files, takes the most recent 7 days
2. Reads those + the current MEMORY.md
3. Calls the LLM with the consolidation prompt templates
4. The LLM returns `{should_update, reason, memory_content}`
5. If `should_update` is true, MEMORY.md is fully replaced

### Trigger methods

- **Automatic** — every agent has a row in the system's scheduled jobs, set to run nightly at 2 AM
- **Manual** — `POST /api/v1/memory/{agentId}/emergence`

### Why it's not recursive

Consolidation triggers a "conversation" through the agent. Without protection, that conversation would re-trigger the post-chat extraction listener, which would trigger another conversation, ad infinitum.

The event carries a trigger-source flag. The extraction listener sees that the conversation was started by the consolidation job and skips it.

### DREAMS.md — the consolidation diary

Each consolidation run appends a short entry to `workspace/{agentId}/DREAMS.md`:

- what it looked at
- what patterns it found
- what changed in MEMORY.md
- the date

Human-readable audit trail — open DREAMS.md and see *how* the memory got to its current state. Caps its own growth; old entries get summarized when the file exceeds a threshold.

### Scored emergence and recall tracking

Consolidation tracks:

- **Which memory entries were actively recalled** in recent conversations — read patterns feed back into importance
- **Scored emergence** — candidate patterns ranked by frequency + recency + explicit recall, only high-scoring ones make it into MEMORY.md
- **Multi-gate filtering** — low-signal extractions (one-off mentions, contradictions, things the user later corrected) get filtered before becoming memory
- **Dreaming status API** — `GET /api/v1/memory/{agentId}/dreaming/status`

### Full lifecycle (opt-in via flag)

Memory grows from "dream nightly" to a complete turn-by-turn lifecycle. This behavior lands behind feature flags — default off in the open-source build, on in production builds.

What it does:

- **Every turn is bookkept** — the system takes notes at the start and end of every turn, not just at nightly consolidation
- **Fact projection** — conversations are projected into structured "fact" rows the agent can query. Trust scoring + decay built in.
- **Structured nightly report** — consolidation produces a full report; you can re-consolidate by topic on demand
- **Morning card** — the first conversation of the day surfaces yesterday's report; you Confirm / Edit / Forget each fact
- **Contradiction inbox** — when new facts conflict with old ones, you get a queue instead of silent overwrites
- **Explicit forget** — say "forget that," and it actually forgets, everywhere
- **Feedback scoring** — thumbs up/down on retrieved facts feeds back into trust
- **SOUL auto-evolution** — the agent's persona file rewrites itself from accumulated facts
- **Monthly archive** — old reports roll into a compressed monthly archive, browsable in the timeline
- **Memory Browser** — timeline, facts, contradictions, diff viewer, and a trust bar across the top

Enable in `application.yml`:

```yaml
mateclaw:
  memory:
    dream-v2:
      enabled: true
      fact-projection: true
      contradictions: true
      morning-card: true
```

---

## Agents reading and writing their own memory

Memory isn't just something that *happens to* an agent. The agent itself can actively read and write its own files during a conversation, through a set of workspace memory tools:

| Method | What it does |
|--------|--------------|
| `list_workspace_memory_files` | List files, optional filename prefix filter, sorted by `sort_order` |
| `read_workspace_memory_file` | Read a specific file's content |
| `write_workspace_memory_file` | Create or overwrite a file (full replace) |
| `edit_workspace_memory_file` | Find-and-replace edit (incremental, `replaceAll` supported) |

### Keyword search over its own memory

::: tip New in 1.4.0
An employee can do more than read whole files — during a conversation it can **search all of its workspace memory files by keyword** and jump straight to the line.
:::

This is an agent runtime capability: the employee supplies a keyword and the system searches across its own workspace memory files:

- **Tokenization** — CJK is split into 2-character sliding windows, Latin text on whitespace, so both languages match
- **Per-file weighted scoring** — hits in core files like `AGENTS.md` / `MEMORY.md` / `PROFILE.md` rank above hits in the daily ledger
- **What comes back** — each hit gives a filename + line number + an 80-char context snippet (matched term highlighted) + a relevance score
- **Scan scope** — up to ~50 candidate files, sorted by score, highest first

Use it when the employee wants to confirm "did I note this before?" or recover a specific decision spread across many days of notes — without pulling whole files into context.

### Examples

**List:**

```json
// in
{"agentId": 1, "filenamePrefix": "memory/"}
// out
{"agentId": 1, "count": 3, "files": [
  {"filename": "memory/2026-04-09.md", "enabled": false, "fileSize": 512},
  ...
]}
```

**Read:**

```json
// in
{"agentId": 1, "filename": "MEMORY.md"}
// out
{"agentId": 1, "filename": "MEMORY.md", "enabled": true, "content": "..."}
```

**Edit:**

```json
// in
{"agentId": 1, "filename": "MEMORY.md", "oldText": "old", "newText": "new"}
// out
{"agentId": 1, "filename": "MEMORY.md", "replacements": 1}
```

### Safety rules

- `.md` files only
- No absolute paths, no `..` directory traversal
- `write` is a full overwrite — read first if you care about existing content
- Newly created files have `enabled=false` by default

---

## Memory snapshot export / import

::: tip New in 1.4.0
An employee's entire accumulated memory can be packaged into a ZIP and taken with you — for backup, migration to another deployment, or cloning a coworker who "already knows you."
:::

A snapshot packages an employee's core memory into a single ZIP:

- `AGENTS.md` / `MEMORY.md` / `PROFILE.md` / `SOUL.md` / `KNOWLEDGE.md`
- daily ledger files (`memory/YYYY-MM-DD.md`)
- a `manifest.json` (what's in the package, and which employee it came from)

### Three endpoints

| Method | Path | Role | What it does |
|--------|------|------|--------------|
| GET | `/api/v1/agents/{agentId}/workspace/memory/export` | Viewer | Export the ZIP — even read-only access can take a backup |
| POST | `.../workspace/memory/import/preview` | Member | **Dry run**: parse the ZIP, classify each file as create / update / skip, write nothing |
| POST | `.../workspace/memory/import` | Member | Apply the import, written **atomically** |

Preview to see the diff, confirm, then import — you always know what will change before it does.

### Safety guards

- **Whitelist** — only the file types listed above are accepted; everything else is ignored
- **Zip-bomb guards** — ≤ 500 entries, ≤ 1 MB each (uncompressed), ≤ 16 MB total; anything over is rejected
- **UI toggle state is not serialized** — `enabled` / `sortOrder` are kept out of the snapshot; on import into a new employee the target decides them by seed rules, rather than forcing the source's toggle state

### UI

- The **Agent Context page right panel** has **Export / Import** buttons
- Import shows a **diff** first (what's created, overwritten, skipped) and only writes after you confirm

---

## Configuration reference

### Memory extraction & consolidation

```yaml
mate:
  memory:
    # --- Automatic extraction ---
    auto-summarize-enabled: true
    min-messages-for-summarize: 4
    min-user-message-length: 10
    skip-cron-conversations: true
    summary-max-tokens: 1000
    max-transcript-messages: 30

    # --- Concurrency ---
    cooldown-minutes: 5

    # --- Consolidation / dreaming ---
    emergence-enabled: true
    emergence-day-range: 7
```

Prefix: `mate.memory`.

### Context window

```yaml
mate:
  agent:
    conversation:
      window:
        default-max-input-tokens: 128000
        compact-trigger-ratio: 0.75
        preserve-recent-pairs: 2
        summary-max-tokens: 300
```

---

## API endpoints

| Method | Path | Purpose |
|--------|------|---------|
| POST | `/api/v1/memory/{agentId}/emergence` | Manually trigger consolidation |
| POST | `/api/v1/memory/{agentId}/summarize/{conversationId}` | Manually trigger extraction |
| GET | `/api/v1/memory/{agentId}/dreaming/status` | Last run, next run, latest DREAMS.md entry |

---

For developers extending the memory layer, see [Architecture](./architecture).

---

## Next

- [Agents](./agents) — how agents use memory during a turn
- [LLM Wiki](./wiki) — the *deliberate* knowledge layer, contrasted with passive memory
- [Tools](./tools) — the workspace memory tool is one of many
- [Configuration](./config) — full config reference
- [Architecture](./architecture) — backend code organization, SPI extension points
