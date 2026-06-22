---
title: Persistent Goals — lock in across turns, let the worker follow up
description: MateClaw's Goal system lets a digital worker lock a multi-turn task as a goal, self-evaluate progress, and optionally drive itself forward until done or out of budget.
head:
  - - meta
    - name: keywords
      content: Goal,Agent,multi-turn,auto-evaluation,auto-followup,persistent,MateClaw
---

# Persistent Goals

> **You used to repeat the context every turn. Now you set a goal once, the worker follows.**

You say "deploy this blog to fly.io" in one turn, the worker answers, and stops. Next turn you have to remember to ask "is DNS set? cert signed? tests run?" — you're keeping the goal in your head, not the worker.

Goals flip that. **You say it once, the worker locks the goal and self-checks every turn: what's still missing? Should I take the next step myself?**

It is not a new tab or a new feature. It is a **state** the worker has. A ring appears around the assistant avatar. How filled the ring is, is how close you are to done. When done, the ring goes away.

---

## What it looks like

Not a banner. Not a dialog. Not a separate page.

A **ring around the assistant avatar**.

| State | Visual | Meaning |
|---|---|---|
| No goal | Plain avatar | This conversation has no goal — same as before |
| Active | Avatar + orange ring | Goal in flight, ring fills to progress |
| Evaluating | Avatar + sand breathing halo | Backend is judging this turn's answer |
| Completed | Avatar + green ring (briefly) | Goal reached; ring fades, conversation continues |
| Exhausted | Avatar + red-orange ring | Budget used up — your call to extend or let go |

**Hover the avatar** to see the full tooltip — title + what's still missing. Don't hover, don't get bothered. That's the design.

---

## Three ways to set a goal

In increasing order of how much you have to spell out:

### Way 1 — Let the worker decide

State the multi-turn nature of the task plus an explicit setGoal request:

> I want to do a complete project: translate the README to English, open a PR, address review feedback, merge. This spans many turns. **Please use setGoal to lock it in**, self-evaluate each turn, turnBudget=8, autoFollowup on.

The worker picks up the two signals ("long task" + "setGoal requested") and creates the goal, auto-summarizing the title from context. You see a ring next to its avatar — goal is locked.

### Way 2 — Direct tool command

Tell the worker exactly which tool to call with which params:

> Please call setGoal immediately, title="Deploy blog to fly.io", turnBudget=10, autoFollowup=true. Do not ask any clarifying questions.

The "do not ask clarifying questions" clause matters — otherwise the worker's instinct is to ask "where's the code? what domain?" first.

### Way 3 — Programmatic via the REST API

For automation and external scripts, the endpoint is direct:

```
POST /api/v1/goals
{
  "conversationId": "conv-xxx",
  "title": "Deploy blog to fly.io",
  "description": "...",
  "exitCriteria": "DNS + SSL + healthcheck + tests pass",
  "turnBudget": 10,
  "llmCallBudget": 200,
  "autoFollowupEnabled": false
}
```

> `agentId` / `workspaceId` are derived server-side from `conversationId` — **don't send them** (they're ignored if you do). Full surface in the [API reference](./api).

---

## What a goal carries

Four required:

| Field | Meaning |
|---|---|
| **title** | Short label, shown on avatar hover |
| **description** | Full statement of what you want |
| **exitCriteria** | LLM-readable bar the evaluator scores against (e.g. "tests pass + deployed") |
| **budgets (turnBudget + llmCallBudget)** | Failsafes against runaway iteration |

Optional:

- **autoFollowupEnabled** — when on, the worker may continue itself if it judges the goal incomplete, without waiting for your next message
- **followupCooldownSeconds** — minimum delay between two consecutive auto-followups

---

## How it runs

After every turn, a backend evaluator node runs:

1. Reads the worker's final answer + last few messages of context
2. Calls a lightweight evaluator model (point this at a cheap one) asking: completion 0–1? what's the gap? continue or done?
3. Writes the result into the `mate_agent_goal_event` timeline
4. Decides next step: complete / exhaust budget / continue / auto-followup

**Key invariant**: evaluation runs *after* the final answer has streamed to your screen — it never blocks you seeing the reply. You see the answer appear → the ring updates a moment later.

### Auto-followup

When `autoFollowupEnabled=true` and this turn's evaluator decision is "continue", the backend:

1. Writes a `followup_injected` event to the timeline
2. APPENDs a user message to the conversation. **Since 1.5.0, if the goal has a checklist, that message explicitly lists the criteria still open** — *"5/8 done, remaining: ① … ② …, take the next step on these"*; with no checklist it falls back to the generic *"Continue working on the goal. Still missing: {gap}."*
3. Re-enters the reasoning loop — the next assistant reply lands right after the first

Feels like: the worker answers a segment → pauses a beat → **keeps going** — like a person who finished one step, thought for a second, and continued.

---

## Getting unstuck

::: tip New
Long tasks don't stall because they're hard — they stall because they **get stuck**: hitting the iteration cap with nothing left to show, spinning on a broken tool until the budget is gone, or crashing a plan at a bad step and stopping dead. This group of mechanisms lets the worker pick itself back up, route around failures, and keep going without waiting for your next message.
:::

### Hard continuation on the iteration cap

Previously, if a ReAct loop ran out of `max_iterations` (finish reason `MAX_ITERATIONS_REACHED`), the goal subsystem **skipped** that run entirely — no evaluation, no continuation, the task just stopped there. Now it takes a **hard-continuation** path: it resets the iteration counter, clears the "over-limit draft", and gives the worker a **fresh full iteration budget** to carry on.

This is different from the auto-followup described above. Auto-followup triggers when the evaluator decides "not yet done." Hard continuation triggers specifically when the worker **hits the iteration cap** — it resets the iteration budget itself. Each hard continuation consumes one full iteration quota, so there is a limit: by default, at most **1** per run (compile-time hard ceiling of 3). Set to `0` to disable and restore the old behaviour (hitting the cap ends that run).

### Stall detection and re-planning

In Plan-Execute mode, an individual step may **throw an exception** or fall into a **stall** — repeating the same tool call that keeps failing, or getting back identical "no new information" results each time, burning through the tool budget and then "completing" with an empty result that poisons every downstream step that depended on it.

The runtime signs each tool response and runs a two-level check:

- **WARN**: after the same call fails several times in a row, a system hint is injected telling the model to try a different approach (each unique call gets at most one warning).
- **HALT**: if the call continues to fail after the warning, the step is marked stuck and the inner loop exits.

When a step is HALTed or throws an exception, the runtime triggers **re-planning**: the current plan is cleared, and a "completed-steps summary + failure reason + skip the bad step" context is passed back to the planning node to generate a new plan. Re-planning happens at most **1 time per run**. The UI receives a `plan_replan` event carrying the failed step index and the reason.

### Meta-tool turns don't count (iteration refunds)

Progressive disclosure tools such as `load_skill` / `enable_tool` are **configuration actions**, not real work. When every tool call in a ReAct turn is one of these meta-tools, that turn's iteration counter **is not incremented** (the iteration is refunded), preventing a model focused on loading skills from burning through its entire budget on setup steps alone. At most 3 refunds per run.

### Auto-deriving a goal from a multi-step plan

When a Plan-Execute plan has **two or more steps** and the current conversation has no active goal, the planning node **automatically creates a goal**, using the plan's steps as exit criteria, and broadcasts a `goal_created` event so the UI's goal panel refreshes. This means long plans are naturally held under the goal system's "follow-through to completion" semantics. Controlled by `mateclaw.goal.auto-goal-from-plan` (on by default).

---

## A goal is a checklist (1.5.0+)

In 1.4.0 the evaluator gave a completion score (0–1) and a one-line "what's missing" each turn. The problem: **what does 0.8 mean** — which boxes are done, which aren't? You couldn't see it.

1.5.0 replaces that with a **checklist**: a goal = a set of **independently verifiable** criteria.

**The evaluator has two modes:**

| Mode | When | What it does |
|---|---|---|
| **bootstrap** | No criteria yet | Decomposes the goal into a checklist; each starts "not passed" |
| **verdict** | Criteria exist | Judges each one: satisfied? with evidence |

Both modes use **structured output** — the evaluator returns a typed object (criterion `id` + `passed` + `evidence`), not free text we have to parse.

**Completion is deterministic.** Only when **every criterion passes** is the goal done. 19 of 20 passed (a 0.95 score) is still "continue" — miss one and one is missing, no fuzzy threshold.

**Three ways to add a checklist:**

- **At creation** — pass `criteria: ["DNS resolves", "SSL valid", "tests green"]` to the `setGoal` tool, or `criteria` to `POST /api/v1/goals`. Skips the bootstrap round.
- **Let the evaluator decompose** — pass no criteria and the first evaluation bootstraps the checklist.
- **Append at runtime** — the `addGoalCriterion` tool or `POST /api/v1/goals/{id}/criteria` adds one to a live goal without restarting.

**What a criterion looks like:**

```json
{ "id": "C1", "text": "DNS resolves to fly.io", "passed": false, "evidence": "" }
```

`id` is server-assigned (C1, C2…), `text` is a sentence a human reads and an LLM judges, `passed` is the evaluator's verdict, `evidence` is the justification it gives. The checklist lives in the `mate_agent_goal.criteria` column (JSON) and is delivered parsed as `GoalResponse.criteria`, never as a raw JSON string.

### The ring, on hover, is a checklist card

- **No checklist** — a one-line tooltip: title + the gap text the evaluator wrote.
- **With a checklist** — a card: title + `X/Y` progress, then each criterion prefixed by `○` (open) or `✓` (green, done, struck through).

While evaluating, a sand-gold breathing halo surrounds the avatar; on completion a green ring shows briefly then disappears; on budget exhaustion the ring turns rust.

### Evaluator SPI

The evaluation logic implements Spring AI's `Evaluator` interface: it does goal-specific checklist verdicts (bootstrap / verdict) and can be reused as a generic evaluator (wrapping a single objective as one criterion in verdict mode). Failed evaluator calls **still count against the LLM budget**, so the accounting stays honest.

> The 1.4.0 goal was "the worker remembers what it's doing." The 1.5.0 goal is "the worker knows **exactly which boxes are still open**." From a score to a checklist you can tick.

---

## Four built-in tools (worker-callable)

These four ship as agent-wide system tools — no binding setup needed:

| Tool | Purpose | Prompt example |
|---|---|---|
| **setGoal** | Create a goal | "Use setGoal to lock in this task, title=..." |
| **addGoalCriterion** | Append a sub-criterion to the active goal | "Add: must support IPv6" |
| **completeGoal** | Explicitly mark done | "All items done — call completeGoal" |
| **getGoalStatus** | Inspect current state | "How are we doing?" |

On completion (`completeGoal`, or the evaluator judging **every criterion passed**), the worker forwards a summary to its [long-term memory](./memory) so future conversations can recall it.

---

## Sub-agents cannot mutate the parent's goal

In [multi-agent collaboration](./agents) a parent worker can delegate to a child worker. Children **don't see** the four goal tools — the goal is the parent conversation's state, the child is a stateless executor.

> This is intentional. Children do work for the parent, but the goal stays owned by the parent.

---

## When the budget runs out

```
turnsUsed >= turnBudget  OR  (agentLlmCallsUsed + evalLlmCallsUsed) >= llmCallBudget
```

Either one hit → goal status flips to **exhausted**, no more evaluations, no more follow-ups, ring turns red-orange. The last turn's assistant reply still goes through.

Your options:

- **Raise the budget and resume** — `PATCH /api/v1/goals/{id}` to widen budgets then resume (no UI button in v1 — use the API or abandon and re-create)
- **Let it go** — call abandon; the conversation slot is freed for a new goal

---

## State machine

```
   create
     ↓
   active
   ↓   ↑
 paused

 active ──all criteria passed / completeGoal──→ completed (terminal)
   ↓
 active ──turns_used / llm_calls exhausted ────→ exhausted (terminal)
   ↓
 active ──user abandon ────────────────────────→ abandoned (terminal)
```

Terminal states (completed / exhausted / abandoned) cannot revive. To keep going, create a fresh goal — intentional simplicity, avoids messy "resurrect with what budget" semantics.

**One active goal per conversation**: at most one active row at any time. Terminal rows stay in history, don't count against the slot. Enforced at the DB layer with a generated column + unique index (H2 / MySQL), plus service-level precheck and audit — defense in depth.

---

## What this system does not do

A few deliberate non-features:

- **No nested goals / goal trees** — one goal per conversation, no OKR stack
- **No "goal templates"** — every goal is hand-written
- **No cross-conversation goal migration** — use a [workflow](./workflow) for that
- **No completion score in the UI** — `completionScore` is an internal engineering protocol, not user vocabulary. The UI speaks via a ring; on hover it shows the box-by-box checklist card when there's a checklist, or the natural-language gap text the evaluator wrote when there isn't. The numeric score stays in logs and the API for debugging

---

## Full event timeline (drawer view)

Each goal has an append-only event log, newest first:

| Event | Trigger |
|---|---|
| `created` | setGoal tool or REST POST |
| `evaluated` | every turn after evaluator runs |
| `followup_injected` | autoFollowup fired and injected a prompt |
| `completed` | evaluator concluded done, or completeGoal tool |
| `exhausted` | budget hit |
| `paused` / `resumed` / `abandoned` | user actions |
| `criterion_added` | addGoalCriterion tool |

Pull via `GET /api/v1/goals/{id}/events`. See [API reference](./api).

---

## Configuration

`application.yml`:

```yaml
mateclaw:
  goal:
    # Master switch; when off, the graph node passes through for every call.
    enabled: true
    # Create-time default for autoFollowupEnabled when the caller leaves it unset.
    default-auto-followup: true
    # Runtime master switch; when off, no goal injects a followup regardless of its per-goal flag.
    allow-auto-followup: true
    # Default turn budget when the user doesn't override.
    default-turn-budget: 20
    # Default combined (agent + evaluator) LLM call budget.
    default-llm-call-budget: 200
    # Minimum seconds between two consecutive auto-followups.
    auto-followup-cooldown-seconds: 0
    # Hard cap on auto-followups within a single graph run (per-message safety net; overall budget is turnBudget).
    max-followups-per-run: 8
    # Max hard continuations when the iteration cap is hit per run (0 = disabled; compile-time ceiling is 3).
    max-hard-continuations-per-run: 1
    # Automatically derive a goal from a multi-step Plan-Execute plan when no active goal exists.
    auto-goal-from-plan: true
    # Model used by the evaluator. Empty = same model as the chat agent.
    # Recommended: a cheap model like qwen-turbo / glm-4-flash.
    evaluator-model: ""
    # Max recent messages included in the evaluator prompt.
    evaluator-context-messages: 8
```

---

## Database

Two tables, all `mate_`-prefixed:

| Table | Purpose |
|---|---|
| `mate_agent_goal` | Goal itself; status / budgets / dual LLM counters / auto-followup config |
| `mate_agent_goal_event` | Append-only event log; powers the timeline view |

Flyway migration `V120__agent_goal.sql` (H2 / MySQL / KingbaseES dialects).

---

## One-liner

**A goal isn't a new feature on the worker. It's a state change.**

Before, the worker forgot the moment it answered. Goals make a worker remember one thing across many turns — what it's working on, what's still missing, when it counts as done. You say it once. The ring next to the avatar tracks the rest.
