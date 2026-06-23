---
title: Multi-Agent Engine — ReAct + Plan-and-Execute
description: MateClaw's multi-agent system runs in two modes — ReAct for real-time reasoning and Plan-and-Execute for complex task decomposition. Agents can delegate to one another for true multi-agent collaboration.
head:
  - - meta
    - name: keywords
      content: multi-agent,ReAct,Plan-and-Execute,agent delegation,Spring AI Alibaba,AI agent
---

# Multi-Agent Engine

> **They're called "digital employees" now.** The back office uses that term throughout. The runtime is still an Agent under the hood, but the UI, the mental model, and the templates treat each one as a coworker on your team.
> The renaming brings a worldview shift with it: you give an employee a **Role**, a **Goal**, and a **Backstory** — they know who they are and why they exist. You don't have to write a cold system prompt asking an "agent" to please understand the task.

An employee is a personality with tools. Multiple employees form a team.

That's the short version. The longer one: an employee is a name, a system prompt that defines how it thinks (built from role / goal / backstory), a model that actually thinks, a set of tools it's allowed to reach for, optional knowledge bases it can read, optional skills that extend what it can do, its own slice of memory, and a choice of how to approach hard problems — incrementally (ReAct) or with a plan (Plan-and-Execute).

You can have many employees. Each one is specialized. You give them different jobs.

---

## What a digital employee has

| Piece | What it is |
|-------|-----------|
| **Name** | How you and your team find them |
| **Icon** | Pixel-art style, color coded by role |
| **Role** | One sentence — "I'm the product researcher" / "I'm customer support" |
| **Goal** | One sentence — "I help you see how the market is moving" |
| **Backstory** | Where they came from, why they exist, what they care about; auto-spliced into the final system prompt |
| **Employee-card tagline** | The "self-introduction" shown on the card |
| **System prompt** | Their personality, rules, style, priorities (role/goal/backstory inject automatically) |
| **Type** | `react` or `plan_execute` |
| **Tools** | Which tools they're allowed to call (built-in, MCP, skills, ACP-bridged) |
| **Knowledge bases** | LLM Wikis they can read from (KB hot cache auto-injects into the system prompt) |
| **Workspace memory** | Their own `PROFILE.md`, `MEMORY.md`, `SOUL.md`, `AGENTS.md`, and daily notes |
| **Max iterations** | How many reasoning loops are allowed before forced convergence |
| **Enabled flag** | Off switch |

Notice what's *not* here: the model. A single global default model (set in `Settings → Models`) is used for every agent at runtime. The `model_name` field on the agent row is a legacy artifact — it's ignored. This is intentional: swapping models across your whole deployment is one click, not thirty.

---

## Templates: hire a coworker who already knows the job

You don't start from scratch. `Digital Employees → New` opens a two-tier template picker.

### 5 career templates (recommended)

Each one ships with a role, goal, backstory, the right toolset, a pixel-art avatar, and a color that belongs to the role. **Open one, it works:**

- **Product Researcher** — competitive scans, market tracking, interview synthesis
- **Customer Support** — catch every question, look it up in the KB, escalate what they can't resolve
- **Knowledge Curator** — feed scattered material into the LLM Wiki, maintain bidirectional links, periodic consolidation
- **Data Analyst** — query datasources, run SQL, build charts, write conclusions
- **Executive Assistant** — calendar, email drafts, cross-tool coordination

### Generic templates (blank or half-finished)

- **General Assistant** — the default chat employee
- **Research / Code / Writing / Knowledge Curator / Data Analyst** — semi-finished, organized by purpose
- **Custom** — fully blank, if you know exactly what you want

Pick one, give them a name, adjust the role and goal, save. **Working coworker in under a minute.** Every field is editable after creation.

---

## Two ways of thinking

### ReAct — think, act, observe, continue

The default. An agent in ReAct mode runs a loop: **reason** about what to do next, **act** (maybe by calling a tool), **observe** the result, decide whether to loop again or answer.

Use it for:
- simple Q&A that might need one or two tool calls
- conversational interaction where each user turn is small
- tasks where the agent needs to react to what it learns along the way

Example: *"What's the weather in Beijing today?"* → reason (need current data), act (call web search), observe (15–26°C, sunny), answer.

### Plan-and-Execute — plan first, execute second

For larger tasks. The agent starts by generating a **plan** — an ordered list of 2 to 6 steps. Then it executes each step, one at a time. When done, it summarizes everything it did.

Use it for:
- multi-step research ("investigate X, compare Y, write a brief")
- anything where the steps are knowable up front
- anything where you want to **watch progress** — the plan and each step's status show up in a persistent task list next to the conversation

Example: *"Research Spring AI frameworks, compare the top three, write me a brief."* → plan (4 steps) → execute in order → summarize.

### How to choose

| Situation | Use | Why |
|-----------|-----|-----|
| Simple Q&A, single-tool calls | ReAct | No planning overhead |
| Information retrieval | ReAct | Usually done in 2–3 cycles |
| Multi-step ordered work | Plan-and-Execute | Explicit plan is easier to watch and debug |
| Research + comparison + writing | Plan-and-Execute | Each step feeds the next |
| "Read this file and tell me X" | ReAct | One tool, one answer |
| "Build me a structured report on X" | Plan-and-Execute | Multiple gathering + synthesis steps |

Change an agent's type at any time. Same system prompt works reasonably in both modes.

---

## Multi-agent parallel delegation

An agent doesn't work alone. One agent can delegate to another — or to **multiple agents at once** (up to 8).

- **Single delegation** — hand a sub-task to a specific agent; it runs in an isolated session, results stream back
- **Parallel delegation** — fan out to multiple agents at once, each in its own session
- **Live child visibility** — see reasoning, tool calls, and progress for each child in the ChatConsole as it happens
- **Routing hints** — built into the system prompt, so agents know when to handle it themselves vs. when to delegate

Example: coding agent takes the Jira ticket, research agent pulls competitor data, writing agent drafts the Slack reply. Three in parallel, results flow back to the orchestrator.

### Multi-level subagent delegation tree

::: tip New in 1.4.0
Delegation is no longer flat. A parent employee can delegate to children, and those children can delegate further — **recursively, up to 3 levels deep**. A temporary team can grow its own hierarchy for a specific task.
:::

Three delegation tools, one per cadence:

- **`delegateToAgent`** — synchronous. Hand a sub-task to a specific employee, wait for it to finish, and return only after the child's final result. Optional `inheritParentContext` carries the parent conversation's recent context to the child, so you don't have to re-explain the background.
- **`delegateParallel`** — fan out. Delegate to several children at once; each runs in its own isolated session and the results are collected together.
- **`delegateAsync`** — background. Returns a `task_id` immediately while the child runs in the background; fetch the result later with **`taskOutput`**. `taskOutput` has an **attribution gate** — only the **same conversation + the same user** that spawned the task can read its result, preventing cross-conversation / cross-user leakage.

Children deny a default set of tools so the tree can't run away:

- `delegateToAgent` / `delegateParallel` / `listAvailableAgents` (recursion guard — children can't launch their own synchronous/parallel delegations and can't enumerate sibling agents)
- `setGoal` / `addGoalCriterion` / `completeGoal` / `getGoalStatus` (goal ownership stays with the parent)
- `remember` / `remember_structured` / `forget_structured` (children can't write into the parent's long-term memory)
- `create_employee` (children can't conjure new employees)

This default deny list is tunable via `mateclaw.delegation.child-denied-tools`.

Delegation pairs with the [Goals](./goals) system — the parent sets goals, breaks the work down, and delegates sub-tasks; children focus on execution.

### UI — nested subagent timeline + always-on plan panel

The ChatConsole draws the whole delegation tree, not a flat log:

- **Delegation start** is marked clearly
- Each child shows its **name / depth / task excerpt**
- **Completion badges**: success / timeout / error, plus duration and content length
- Every subagent has a stable **id + parentId + depth**, so the nesting is legible in the timeline — you can see exactly who delegated to whom
- **The plan panel is always on** — no longer Plan-and-Execute only; delegation-tree progress folds into the same panel

---

## Plan Kanban

::: tip New
The `Digital Employees` page now has a three-way toggle at the top: **Roster / Live / Plan Kanban**. The Kanban surfaces every plan produced by every employee in the workspace, sorted by status into a single board so you can see at a glance who's doing what and where things are stuck. (Visible to admins only.)
:::

The board is a global view of **Plan-and-Execute plans**, with four columns that plans fall into automatically:

| Column | Meaning |
|--------|---------|
| **Pending** | Plan generated, first step hasn't started yet |
| **Running** | First step has started |
| **Done** | All steps completed |
| **Failed** | A step failed and won't be retried |

The layout is **swimlane-style**: each employee that has plans gets its own row, ordered by most-recent activity, with a top-of-page dropdown to filter to a single employee. Multiple re-plans for the same goal collapse into **one card + ×N badge** — no stacking. Each card shows the goal text, a progress bar (completed / total steps), and step-distribution chips (N pending / M running / K done).

The board is **read-only** — state is driven by execution, not drag-and-drop. Click a card and a **plan detail panel** slides in from the right: assigned employee, status, KPIs (step count / progress / creation date), execution output (Markdown rendered), and an expandable step timeline. A "Goals" button at the top links directly to the active [Goals](./goals) list.

REST: `GET /api/v1/plans?limit=N` (most-recent N plans across all employees), `GET /api/v1/plans?agentId=...` (by employee), `GET /api/v1/plans/{id}` (with step detail).

### Per-step delegation to specialist employees

::: tip New
A multi-step plan doesn't have to be run by a single employee from start to finish. When generating a plan, the planner can assign **individual steps** to more-specialized employees in the workspace.
:::

The mechanism is **automatic** — no manual wiring required. During planning, the system shows the planner every other enabled employee in the workspace (name and description included); the planner marks a step for a specialist employee when that step clearly falls within the specialist's domain, leaving the remaining steps to itself. Most steps typically need no delegation.

- Delegation is recorded in `mate_sub_plan.assigned_agent_id`; a blue badge — **"Delegated to &lt;employee name&gt;"** — appears below the step in the plan detail panel
- Delegated steps execute in a **sub-conversation** scoped to the parent plan's conversation — they do **not** leak into the top-level conversation list as independent sessions
- Step-level delegation shares the same semantics as the [Goals](./goals) system and the [multi-level delegation tree](#multi-level-subagent-delegation-tree) above: the parent breaks up the work, specialists do their part

---

## Build a team from one sentence: the digital-employee builder skill

::: tip New in 1.4.0
Don't want to create employees one at a time? Give it a sentence and let the "digital-employee builder" skill assemble the whole team for you.
:::

The skill starts from your one sentence and runs the full chain:

1. **Clarify the requirement** — it pins down the vague sentence first, confirming the problem you're actually trying to solve
2. **Design the roles** — breaks it into **2 to 6** complementary roles
3. **Create each one** — calls `create_employee` per role to produce real, usable employees
4. **Chain them into a workflow draft** — links the employees into a [workflow](./workflow) draft you can tweak right away

The companion tool **`list_capability_catalog`** lets the skill survey which tools / skills / knowledge bases the deployment has available before assigning capabilities to roles. Created employees are **enabled on creation** — no extra toggle to flip.

---

## Single-employee creation wizard

::: tip New
The team-builder skill above creates a whole team in one shot. If you only need **one** employee and don't want to fill in every field by hand, use the **Create Wizard** button in the top-right corner of the employee list — describe what you want in a sentence, and the AI drafts the employee for you to tweak before saving.
:::

This is a separate three-step UI wizard (`Digital Employees → Create Wizard`), distinct from the team-builder skill: the skill outputs a team through a chat interface; the wizard outputs a single employee through a dedicated page.

1. **Describe** — type a natural-language sentence in the input box ("an operations assistant that tracks competitor news and writes a daily brief"). Example chips below the box let you fill one in with a single click
2. **Review** — the AI returns a draft: name, avatar emoji, role, goal, system prompt, type (`react` / `plan_execute`), suggested opening question, tags, and **recommended tool / skill / knowledge-base bindings**. Every field is editable; the capabilities list uses a searchable picker
3. **Publish** — confirm and the employee is created along with all tool / skill / KB bindings in one go; you're offered "Start chatting / Create another / Back to list"

**Hallucination prevention** is the key design decision here: the AI can only suggest tools, skills, and KBs that **actually exist** in your deployment — anything the model invents that doesn't match a real capability is verified and discarded server-side during generation, before the draft ever reaches the wizard. Every binding shown in the draft is immediately usable.

Backend endpoint: `POST /api/v1/agents/generate`, request body `{ "requirement": "your one-sentence description" }`, response is a validated draft.

---

## Deep thinking

Not every question deserves deep reasoning, but some do. MateClaw lets you turn on deep thinking per agent, per conversation:

- **`thinkingLevel`**: `off` / `low` / `medium` / `high` / `max`
- Supports Anthropic extended thinking, DashScope qwq reasoning, OpenAI o1 `reasoning_effort=high`
- The thinking block streams into the UI as a collapsible panel — you see the model reason, tokens don't get wasted on tasks that don't need it

---

## Hiring a digital employee

`Digital Employees → New`:

1. Pick a template (one of the 5 career templates, a generic template, or Custom)
2. Name them, choose an avatar (pixel-art library, or upload your own)
3. Write a one-sentence **Role**, a one-sentence **Goal**, a few-sentence **Backstory**
4. Write a one-line **employee-card tagline** — the self-introduction shown on the card
5. Choose the type (`react` or `plan_execute`)
6. Write (or edit) the system prompt (role / goal / backstory get auto-appended — don't repeat them)
7. Pick which tools they can use, bind any knowledge bases they should read
8. Set `max_iterations` (default 100)
9. Save

Live immediately. Call them from chat or via API.

### Tool binding (per-agent tool picker)

::: tip New in 1.3.0
In v1.2.0 the employee's tool binding was a flat "check what you want" list. v1.3.0 reworks this into a **grouped + status-aware + namespace-aware** picker, specifically to handle MCP tool grime.
:::

Open the digital-employee editor's Tools tab and you get:

- **Grouped by source**: built-in tools / skill-injected tools / MCP tools (further grouped per server) / ACP tools
- **Status badges**: each tool carries a tag —
  - `connected` — currently usable
  - `stale` — this MCP server is currently unreachable, but the binding is preserved (it'll work as soon as the server is back)
  - `unavailable` — server / skill has been disabled; binding is preserved but the runtime won't surface it to the employee
  - `orphan` — references a tool that **no longer exists** (server removed, tool renamed); the save action **rejects** orphan references and forces cleanup
- **Namespace collisions**: when two different MCP servers expose the same tool name (e.g. both have `read_file`), the picker shows the fully prefixed names (`server-a__read_file` / `server-b__read_file`); the employee's system prompt maps them back to the originals so the LLM doesn't get confused
- **Validation on save**: every checked tool runs through `AgentBindingService.validate(...)` — any orphan reference fails save and must be cleared
- **MCP server rename**: bindings tied to a renamed server **follow automatically** (matched via persisted tool cache) — no need to re-tick

UI: `Agents → pick employee → Tools`.

Implementation details: see [MCP](./mcp#per-agent-tool-binding).

### Knowledge base binding (per-agent primary KB)

::: tip New in 1.5.0
The employee editor has a new "Knowledge Base" tab where you can pick a **primary KB** for each employee. Knowledge bases stay workspace-shared — binding only declares "this is the one I default to," it doesn't restrict other employees' access.
:::

**Short version: each employee can pick one knowledge base as their "primary KB" — the default they query. Or pick none.**

The model (worth reading once so it doesn't surprise you later):

- **Knowledge bases are workspace-shared.** A KB belongs to the workspace it was created in; every employee in that workspace can see it. Binding a KB to an employee does **not** make it exclusive — other employees can still use it
- **The "primary KB" is just a default.** It tells the wiki tools (`wiki_search` / `wiki_read` / `wiki_backlinks` / ...): "when the caller doesn't specify `kbName` / `kbId`, use this one"
- **Multiple employees can pick the same KB as primary.** They don't interfere — each one's binding is its own, the KB itself isn't mutated
- **Not binding is fine.** With no primary set, the runtime falls back to the most-recently-updated KB in the workspace

UI: `Employees → pick employee → Edit → Knowledge Base`.

| Option | Behavior |
|--------|----------|
| **🚫 No primary KB** | Clear the binding; the next time the employee's wiki tools omit `kbName`, the runtime falls back to the workspace's most-recently-active KB |
| **📚 &lt;KB name&gt;** | Set this KB as primary; wiki tools default to it. The row also shows the KB's page count |

Each row shows: icon, name, description, page count. The list is the **full set** of KBs in the current workspace — including ones already picked as primary by other employees.

#### How the runtime decides "which KB to read"

When an employee invokes a wiki tool, the resolution order is:

1. The tool call explicitly carried `kbName` / `kbId` — use that
2. No explicit target → check the employee's `primaryKbId`; if it points to a workspace-visible KB, use that
3. No `primaryKbId` either → pick the most-recently-updated KB from the workspace's visible set
4. The workspace has zero KBs → tool returns empty, the LLM decides what to do next

Migration note: early versions persisted the binding on `mate_wiki_knowledge_base.agent_id` (one-to-one, exclusive semantics). Starting with the V130 migration, every legacy `kb.agent_id` is backfilled into the corresponding `agent.primary_kb_id`; the old column stays around as a read-only fallback, but new writes only touch `agent.primary_kb_id`. If you relied on `kb.agent_id` to isolate a KB to a specific agent, revisit those bindings in the editor — KBs are now visible to every employee in the workspace.

#### Disable knowledge bases entirely for an employee

::: tip New
The top of the "Knowledge Base" tab now has a toggle: **This employee does not use any knowledge base**. It is the symmetric counterpart to the tool-disable and skill-disable opt-out switches.
:::

There are two distinct meanings of "no KB selected":

- **Selector left empty** = "I haven't specified one" → at runtime, the employee **inherits all workspace KBs** (the default behavior)
- **Toggle switched on** = "I explicitly want zero KBs" → at runtime, the employee's visible KB set is treated as **empty**

After saving with the toggle on, the employee's KB binding is cleared and marked as "explicitly KB-free"; a **Disabled** badge appears on the tab. The effect:

- `wiki_read_page` / `wiki_search_pages` / `wiki_semantic_search` and every other wiki tool return `"no knowledge base"` — the tools are still in the toolset, they just produce no results
- The webchat `/wiki/pages` endpoint returns an empty list for this employee
- All KB injection and grounding is off

**Off by default** — all existing employees are unaffected. The toggle can be removed at any time: selecting at least one KB in the picker and saving automatically clears the flag (a non-empty binding takes precedence over the opt-out, preventing contradictory state).

The flag lives in `mate_agent.wiki_disabled` (V154 migration, covering H2 / MySQL / KingbaseES).

### System prompt best practices

The system prompt is the employee's voice, priorities, and constraints. **Role / Goal / Backstory**, skill instructions, and workspace memory all get automatically appended to the final prompt — you don't write those yourself.

Your part should cover:

1. **How they should speak** — tone, style, phrasing preferences ("professional but not stiff" / "stay cautious in customer-facing replies")
2. **What they're allowed and expected to do** — the task boundary
3. **How to behave when uncertain** — "search first, don't make things up" / "ask before running a dangerous command"
4. **Output format** — if you need structure, say so

Leave out:

- Tool descriptions — auto-injected
- Workspace memory instructions — they come from `AGENTS.md`
- Framework-specific behavior (tool call format, ReAct structure) — don't fight the runtime

Example:

> You are a professional technical documentation assistant. Your responsibilities:
>
> 1. Search and organize technical materials based on user needs
> 2. Answer questions using clear, structured formatting
> 3. Ensure code examples are syntactically correct
> 4. When unsure, search first rather than fabricating information
>
> Guidelines:
> - Cite sources when referencing external information
> - For time-sensitive questions, get the current date before searching

---

## For developers: how the agent actually runs

If you're just using agents, skip this section. If you're building on top of them — adding nodes, customizing routing, plugging in extensions — go straight to [Architecture](./architecture). The graph topologies, node lists, shared state keys, and extension points all live there.

---

## Lifecycle states

| State | Meaning |
|-------|---------|
| `IDLE` | Ready for input |
| `PLANNING` | Generating a plan (Plan-and-Execute mode) |
| `EXECUTING` | Running tool calls or sub-tasks |
| `RUNNING` | Active ReAct loop or Plan-Execute graph execution |
| `WAITING_USER_INPUT` | Paused for user response |
| `DONE` | Completed |
| `FAILED` | Execution failed |
| `ERROR` | Error state |

Why the turn ended:

| Value | Meaning |
|-------|---------|
| `NORMAL` | LLM gave a direct final answer |
| `SUMMARIZED` | Completed after a context-compression pass |
| `MAX_ITERATIONS_REACHED` | Forced convergence at iteration limit |
| `ERROR_FALLBACK` | Degraded answer after an error |
| `INCOMPLETE` | Response did not finish; needs retry or continuation |
| `EVIDENCE_INSUFFICIENT` | Final answer cited facts not verified by any tool result |
| `STOPPED` | User actively stopped the turn |
| `RETURN_DIRECT` | A tool with `returnDirect=true` short-circuited the loop; result delivered without re-entering the LLM |

---

## Reliability features

These are things the runtime does so agents don't fail in ways you'd have to debug:

- **Context pruning** — when the context window gets too full, earlier turns get summarized by the LLM and the summary replaces them. Cached for 30 minutes. Injected as a user message, not a system message, to prevent prompt injection from historical content.
- **Structured compaction (on prompt-too-long)** — when the model returns "prompt too long," the runtime walks a four-stage escalation: **soft trim → hard clear → pre-prune → LLM structured summary**. At every stage it **always preserves the prefix** — the system prompt + the goal anchor stay intact — and injects the final summary as a UserMessage. Delegation tool results are **never compacted** (they're a child's hard-won output; lose them and they're gone). After a PTL-triggered compaction there's a **1-minute cooldown**, so the runtime won't keep hammering the LLM inside the same over-budget turn.
- **Thinking recovery** — if a stream breaks mid-response, the partial thinking and content persist and show up when the conversation reloads.
- **Iteration limit handler** — instead of crashing when `max_iterations` is hit, the runtime forces a best-effort summary answer.
- **Stale stream cleanup** — every open SSE stream is tracked, abandoned ones are reaped automatically.
- **429 retry** — LLM rate-limit errors trigger automatic retries with backoff.
- **Repetition detection** — agents looping on the same tool call get forced out.
- **Stall detection + re-planning** — in Plan-and-Execute mode, when a step throws an exception or repeatedly fails inside a tool loop, the runtime discards the current plan, carries the failure reason back to the planning node, and **re-plans** to route around the broken step — rather than pushing a garbage result forward. See [Goals · Stall detection and re-planning](./goals#stall-detection-and-re-planning).
- **Hard continuation on the iteration cap** — an employee with an active goal that hits its iteration limit can **resume with a full fresh iteration budget** instead of stopping and waiting for you to send another message. See [Goals · Hard continuation](./goals#hard-continuation-on-the-iteration-cap).
- **Configurable tool timeouts** — one slow tool can't freeze a turn.
- **Channel health monitor** — failing channel adapters restart with exponential backoff.

None of these are user-facing buttons. They just happen.

---

## Agent management API

### Create

```bash
curl -X POST http://localhost:18088/api/v1/agents \
  -H "Authorization: Bearer YOUR_JWT_TOKEN" \
  -H "Content-Type: application/json" \
  -d '{
    "name": "Tech Assistant",
    "description": "A professional technical documentation assistant",
    "agentType": "react",
    "systemPrompt": "You are a professional technical documentation assistant...",
    "maxIterations": 10
  }'
```

### List / Get / Update / Delete

```bash
curl http://localhost:18088/api/v1/agents -H "Authorization: Bearer YOUR_JWT_TOKEN"
curl http://localhost:18088/api/v1/agents/1 -H "Authorization: Bearer YOUR_JWT_TOKEN"

curl -X PUT http://localhost:18088/api/v1/agents/1 \
  -H "Content-Type: application/json" \
  -H "Authorization: Bearer YOUR_JWT_TOKEN" \
  -d '{"name":"Tech Assistant v2","maxIterations":15}'

curl -X DELETE http://localhost:18088/api/v1/agents/1 \
  -H "Authorization: Bearer YOUR_JWT_TOKEN"
```

### Streaming chat

```bash
curl -N "http://localhost:18088/api/v1/agents/1/chat/stream?message=hello&conversationId=default" \
  -H "Authorization: Bearer YOUR_JWT_TOKEN"
```

---

## Debugging

DEBUG logging in `application.yml`:

```yaml
logging:
  level:
    vip.mate.agent: DEBUG
    vip.mate.agent.graph: DEBUG
```

You'll see node-by-node execution: state transitions, dispatcher routing, iteration counts, tool call arguments and results, Tool Guard check results.

### Common issues

| Symptom | Likely cause |
|---------|--------------|
| Agent doesn't respond or times out | Model config wrong, API key invalid, quota exhausted |
| Agent stuck in a loop | `max_iterations` too low, or a tool returning errors repeatedly |
| `MAX_ITERATIONS_REACHED` happening often | Refine the system prompt or raise the limit |
| Tool calls silently failing | Tool Guard is blocking — check `mate_tool_guard_audit_log` |
| Approval-waiting graph won't resume | `toolCallPayload` format mismatch in `chatWithReplay` |

---

## Next

- [Tools](./tools) — what agents can call
- [Skills](./skills) — how to extend what agents can do
- [LLM Wiki](./wiki) — how knowledge gets read by agents
- [Memory](./memory) — how agents remember across conversations
- [Workflow](./workflow) (1.3.0+) — orchestrate multiple digital employees and system actions into a business process
- [Triggers](./triggers) (1.3.0+) — let events automatically start workflows or agent conversations
- [Architecture](./architecture) — the StateGraph runtime in depth
