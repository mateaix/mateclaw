---
title: Team Runs — from one team request to a complete, deliverable execution
description: A MateClaw Team Run links the lead, task DAG, worker executions, final synthesis, and deliverables under one runId and one shared view across Chat, Agents, and Teams.
head:
  - - meta
    - name: keywords
      content: agent teams,task board,kanban,multi-agent collaboration,dispatch,deliverables,MateClaw
---

# Team Runs and Agent Teams (2.1.0+)

> **Before: one employee with sub-tasks. Now: a team around a shared task board.**

Sub-agent delegation (`delegateToAgent`) solves "one person temporarily calls a helper": synchronous, one-to-one, black-box. But real complex delivery looks like a project: **break down tasks, declare dependencies, run in parallel, gate on approvals, archive deliverables, and see who is doing what at any time**.

Agent Teams bring that project machinery into MateClaw: you create a **team**, assign one **lead** employee and several **members**; tell the lead a goal, it breaks the goal into tasks on a **shared task board**; the dispatch engine hands tasks to members and runs them **in parallel**; settled results are announced back to the lead, which reviews, re-dispatches, and drives the whole thing to done. You watch it all from the Teams page — or drop tasks onto the board yourself.

2.1.0 adds the first-class **Team Run** above individual tasks: one user request maps to one run, and one `runId` links the objective, task DAG, worker conversations, events, final synthesis, and deliverables. Instead of a sidebar full of “subtask” conversations, you get one outcome-first work record with progressive drill-down.

## The unified Team Run experience in 2.1.0

| Surface | Responsibility | Default view |
|---------|----------------|--------------|
| **Chat** | Delivery | One stable run card with shared status/progress, final summary, deliverables, failures or approvals; task detail expands on demand |
| **Agents · Live** | Live observation | Workers sharing a `runId` are grouped with task, phase, tool, duration, and exception state; ordinary runs remain independent |
| **Teams** | History and governance | Run history and detail, task evidence, approvals, cancellation, and worker records instead of a flat wall of historical tasks |

The server owns the Team Run projection and state machine:

```text
planning → running → awaiting_review → finalizing → completed
                                      ↘ partial / failed
planning / running / awaiting_review → cancelled
```

- **One identity per job**: events, routes, logs, tasks, and final messages carry `runId`.
- **Outcome first**: intermediate task settlement updates progress instead of manufacturing one user-facing final answer per task.
- **Worker governance**: `team_worker` conversations stay out of the normal sidebar; deep links open a read-only execution record with a path back to the Team Run.
- **Refresh-safe**: every surface consumes `TeamRunView`, so title, progress, status, summary, and files cannot drift through client-side inference.
- **Historical compatibility**: 2.0 tasks without `runId` remain readable but are never guessed into an incorrect aggregate.

The run protocol is `start_run → create* → seal_run`: the lead creates a run, creates tasks explicitly under it, and seals it before dispatch. The originating message participates in idempotency, preventing reconnects or duplicate submissions from creating a second copy.

---

## Core concepts

| Concept | Description |
|------|------|
| **Team** | A group of employees plus one task board. An employee can belong to multiple teams. |
| **Role** | `lead` / `member` / `reviewer`. The lead decomposes and summarizes, members execute, reviewers review. |
| **Task** | One work item on the board: subject, description, assignee, dependencies (`blockedBy`), progress, result, deliverables, comments, timeline. |
| **Board** | A kanban grouped by status. The state machine is guarded by conditional database updates — under concurrency the first successful writer wins, so a task can never hold two states. |

Eight task statuses:

```
pending → in_progress → completed / failed / cancelled
                ↘ in_review (tasks that require human approval)
blocked (waiting on prerequisites)   stale (lease expired, retryable)
```

`failed` and `stale` tasks can be retried; `completed` / `cancelled` release downstream tasks that depend on them.

---

## What one collaboration run looks like

1. **You give the lead a goal**: "Competitive analysis: research vendors A and B separately, then merge into one report."
2. **The lead puts tasks on the board**: three tasks via the `team_tasks` tool — "research A" and "research B" run in parallel, "merge report" declares `blockedBy` on both and enters `blocked`.
3. **The dispatch engine takes over**: a resident sweep (every 30 s, plus an immediate pass after tool/REST actions) assigns pending tasks to their members — each task gets its own child conversation where the member runs the full agent graph. Each member executes one task at a time; the rest queue.
4. **Prerequisite results are handed off automatically**: once "research A/B" complete, "merge report" is released and its dispatch envelope **automatically carries the results and deliverable links of both prerequisites** — member C doesn't need the lead to retell what member A did.
5. **Settled results wake the lead**: completions and failures are announced to the lead in debounced batches; the lead wakes up in a real new turn — reviews results, dispatches follow-ups, or declares the job done.
6. **You see everything**: the Teams board refreshes live (SSE event-driven, no polling), an activity banner streams "#3 dispatched to Content Studio"; open any task for its timeline, progress, comments, deliverables — and **jump into the member's child conversation to watch the run word by word** (a live typewriter while it's running).

---

## The lead's tool: `team_tasks`

The lead (and members) operate the board through the `team_tasks` tool, with role-gated actions:

| Action | Who | What |
|------|--------|--------|
| `list` | any member | Render the current board (the lead also gets a live board snapshot injected every turn — see below) |
| `get` | any member | Task detail |
| `create` | lead | Create a task: subject, description, assignee, `blockedBy` dependencies, `requireApproval` for a human gate |
| `complete` | assignee | Submit the result (approval-gated tasks move to `in_review`) |
| `progress` | assignee | Report percent and current step (also renews the execution lease and broadcasts to the board) |
| `comment` | any member | Leave a comment |
| `attach` | assignee | Register a **deliverable** (file name + download URL) on the task |
| `cancel` | lead | Cancel — this **actually interrupts** a running member session, not just flips a status |
| `retry` | lead | Retry a `failed` / `stale` task |

**Team context injection**: employees on a team get the team roster and collaboration playbook injected into their system prompt; the lead additionally receives a **live board snapshot every turn** — it never has to call `list` first to know what's on the board, and long conversations can't drift into duplicate task creation.

---

## Execution hardening for long-running tasks

Teams target deep research and long document work — tasks that run for tens of minutes. The execution path is hardened accordingly:

- **Execution lease + runtime heartbeat.** A dispatched task holds a 60-minute lease that is renewed automatically while the member runs; only genuinely lost tasks (process crash, restart) expire to `stale` and can be safely re-dispatched — no more "task re-dispatched while still running, two instances overwriting each other".
- **Cancel means interrupt.** Member child conversations register with the stream tracker; graph nodes check the stop flag each round, so a cancelled member stops at the next node boundary — no more burning tokens to natural completion after a cancel.
- **Human approval gates.** Declare `requireApproval` at creation; the submitted task parks at `in_review` until you approve / reject on the Teams page — sensitive output passes a human before it leaves the team.
- **Manual task creation.** Tasks don't have to come from the lead: create one directly on the Teams page, assigned to any member; the result lands on the board for you (with no lead conversation to wake, announcement gracefully no-ops).

---

## Deliverables and run visibility

The right output shape for a complex task is **files + summary**, not one truncated blob of text:

- Members produce files with the document render tools (docx / pptx / xlsx / pdf) or skills, then `attach` them to the task; the task detail renders a **downloadable attachment list** and the result announcement carries the attachments — links no longer drown in truncated text.
- Every task detail has a **"view run"** entry: the member child conversation's full transcript — dispatch envelope, round-by-round thinking, tool calls, intermediate output. Opened mid-run it's a live typewriter (reconnects replay the buffer).
- **Task timeline**: who created / dispatched / reported / attached / approved / cancelled, and when — each event lands in the `mate_team_task_event` audit table and renders as a timeline on the task detail. The collaboration has a historian.

---

## Plan-Execute leads: the plan becomes the board

Leads aren't restricted by agent type. A **ReAct lead** creates tasks one by one via `team_tasks`; a **Plan-Execute lead** goes further — the plan produced by its planning node is **handed over to the board wholesale**:

- plan steps map to board tasks, step dependencies become `blockedBy` — a formerly strictly-serial plan now **parallelizes wherever it can**;
- after hand-off the plan parks (`delegated`) and the lead's turn ends normally; the dispatch/announce loop takes over;
- once all tasks settle, the wake-up passes a **parked-plan resume gate** that deterministically routes to the plan summary node, rebuilding context from task results and deliverables — the same "park in DB, resume in a fresh turn" shape the tool-approval flow already uses, with no checkpoint machinery.

The hand-off is **all-or-nothing**: the plan goes to the board only when every step resolves to a team member; if any step can't be assigned, the whole plan falls back to the original serial delegation pipeline, behaving exactly as before.

In short: **a lead that can plan turns its planning into team orchestration.**

---

## The Teams page

The admin console gains a **Teams** page (`/teams`):

- **Team management**: create teams, add/remove members, assign roles;
- **Board**: status columns, event-driven live refresh, **paged columns** with **database-side true totals** in the headers — a thousand-task board won't drag the page down;
- **Activity banner**: streaming dispatch / completion / failure events;
- **Task detail**: timeline, progress, comments, deliverable downloads, run-transcript entry, approve / reject;
- **Manual task creation**: drop work onto the board directly.

---

## REST API

The admin API lives under `/api/v1/teams`:

| Endpoint | Description |
|------|------|
| `GET /api/v1/team-runs/{runId}` | Read the complete run projection |
| `GET /api/v1/teams/{teamId}/runs` · `GET …/runs/page` | List / cursor-page team run history |
| `GET /api/v1/conversations/{conversationId}/team-runs` · `GET …/team-runs/page` | List / cursor-page runs in a parent conversation |
| `POST /api/v1/team-runs/{runId}/cancel` | Cancel a run and its non-terminal tasks |
| `GET / POST /api/v1/teams` | List / create teams |
| `GET / PUT / DELETE /api/v1/teams/{id}` | Team detail / update / delete |
| `POST /api/v1/teams/{id}/members` · `DELETE …/members/{agentId}` | Membership |
| `GET / POST /api/v1/teams/{id}/tasks` | Task list (windowed paging) / create task |
| `GET /api/v1/teams/{id}/tasks/stats` | Per-status counts (computed database-side) |
| `GET /api/v1/teams/{id}/tasks/{taskId}` | Task detail |
| `POST …/tasks/{taskId}/approve · reject · retry · cancel` | Approve / retry / cancel |
| `POST …/tasks/{taskId}/comments` | Comment |
| `GET …/tasks/{taskId}/events` | Task timeline |
| `GET /api/v1/teams/{id}/events` | Team-level SSE event stream (what drives the live board) |

Every validation failure returns a **readable error** — never a bare 500.

The original five team tables are joined by `mate_team_run`; `mate_team_task.run_id` and worker-conversation indexes connect tasks, runs, and execution records. Snowflake ids cross the JSON boundary as strings.

---

## Teams vs. sub-agent delegation (`delegateToAgent`)

| | `delegateToAgent` | Team task board |
|---|---|---|
| Shape | One-off helper call | Standing team + shared board |
| Parallelism | Single async call | Member-level parallelism + dependency orchestration |
| Visibility | Black box until done | Timeline + live spectating + deliverables |
| Interrupt/recover | Tied to parent turn | Leases, cancel-interrupt, retry |
| Fits | Outsourcing one sub-problem | Multi-role, multi-step project delivery |

They coexist: a team member can still call `delegateToAgent` inside its own task.

---

## Read next

- [Agents](./agents) — how the ReAct and Plan-Execute graphs work
- [Persistent Goals](./goals) — cross-turn follow-through for a single employee
- [Workflow](./workflow) — deterministic step orchestration (use workflows when the process is fixed, teams when it must be decomposed on the spot)
- [Security & Approval](./security) — how tool approval relates to team task approval
