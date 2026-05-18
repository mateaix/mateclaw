---
title: Backstage — Admin Runtime Console for Live Agent Visibility
description: Backstage is the admin-only live view of every digital employee currently on the clock. State-ring avatars, saying lines, watchdog-based stuck and orphan detection, soft-stop, force-recycle, sweep-all, and per-subagent interrupt. Open one URL when a user says "the agent is stuck".
head:
  - - meta
    - name: keywords
      content: agent runtime,admin console,force recycle,stuck agent,orphan run,multi-agent observability,SSE stream cleanup,digital employee,live agent visibility
---

# Backstage — admin runtime console

**The page you open when someone says "my agent is stuck."**

A digital employee that's frozen mid-step is one of the few things in MateClaw that doesn't fix itself. Streams hang, subagents fan out into the void, the SSE buffer keeps a flux alive that nobody is reading. Backstage is the one screen that surfaces all of that and lets you reach in.

It is **admin-only** (`ROLE_ADMIN`), live (auto-refresh every 5 s, pausable), and intentionally simple — one card per running agent, four actions, no menus.

---

## Where it lives

- **Route:** `/backstage`
- **Sidebar:** top-level entry under *Operate*
- **Authorization:** the JWT must carry `ROLE_ADMIN`. Non-admins get a 403 from every `/api/v1/admin/agent-runtime/*` endpoint, and the route guard hides the link from the sidebar entirely.

---

## What you see

A grid of cards, one per running digital employee. The auto-refresh chip in the page header shows whether the live feed is active; click it to pause (e.g. when you're about to act and don't want the card to jump under your cursor mid-click).

When nothing is running you see a calm "all quiet" empty state — that's a feature, not a missing page.

### Card anatomy

| Element | What it shows |
|---|---|
| **Avatar with status ring** | The agent's avatar inside a coloured ring: **green breathing** = healthy, **slow orange** = stuck, **faint purple** = orphan |
| **Agent name + owner** | The display name plus `@username` of whoever started the run |
| **Saying line** | A human-readable status sentence (e.g. *"Reasoning about retrieved chunks…"*) — taken from the runtime's last published phase |
| **Tool chip** | A separate chip beside the saying line showing the tool the agent is currently inside, when one is active |
| **Time elapsed** | Human-readable age of the run (e.g. `2m 34s`) |
| **Orphan badge** | Shown when `orphan && !stuckReason`. Means the run is alive in memory but no client is reading the stream |
| **Progress bar** | Appears once `ageMs > 30 s`. Linear interpolation across a 5-minute window — you can spot a drifting run at a glance |
| **Subagent stack** | Up to 3 child-agent avatars; the rest collapse to `+N`. Click `+N` to expand the list |
| **Action buttons** | Stop / End / Interrupt subagent — see below |

A counter strip at the top of the page summarises the snapshot: **N running · M stuck · K orphan**. The numbers come from the same `/snapshot` call that powers the cards, so they are always consistent with the grid.

---

## Actions

Every action takes effect on the live in-memory `RunState` for that conversation, not just on the database row.

| Action | Endpoint | When to use it |
|---|---|---|
| **Stop** *(soft)* | `POST /api/v1/admin/agent-runtime/runs/{conversationId}/stop` | The agent is still progressing but you want it to stop after the current step. Cooperative — the run finishes whatever it's in the middle of, then exits cleanly. |
| **End** *(force)* | `POST /api/v1/admin/agent-runtime/runs/{conversationId}/recycle` | The agent has a `stuckReason`. Disposes the SSE flux, drops `RunState`, releases the conversation row. Button is hidden when the run isn't stuck so you don't reach for it by mistake. |
| **Tidy Up** | `POST /api/v1/admin/agent-runtime/sweep` | Force-recycle every stuck run on the page. Use after a provider outage to clean up in one click. |
| **Interrupt subagent** | `POST /api/v1/admin/agent-runtime/subagents/{subagentId}/interrupt` | Cancel one delegated child-run without touching its parent. The parent gets a `delegation_cancelled` event and decides whether to retry or give up. |

The page also exposes the read-only **snapshot endpoint** that powers everything you see:

```
GET /api/v1/admin/agent-runtime/snapshot
```

Returns running / stuck / orphan counts and the per-run detail rendered on the cards. Useful for plugging into Grafana or your own ops dashboard.

---

## What counts as stuck or orphan

Two distinct conditions, two different signals.

### Stuck — the runtime gave up waiting

`stuckReason` is non-null on the run. The runtime's watchdog sets it when a step blows past its timeout:

| Step | Default threshold | Notes |
|---|---|---|
| Short steps (reasoning chunks, status updates) | 30 s | Token-level liveness |
| Tool call | 150 s | Includes both built-in and MCP / ACP tools |
| Whole turn | 600 s | End-to-end ceiling |

All three are configurable. A typical reason string looks like `tool_call.timeout(150s)` or `reasoning.no_progress(30s)` so the card can show *why*, not just *that it died*.

When a run goes stuck the runtime stops feeding it but doesn't tear it down — that's your decision. Hit **End** to recycle, or wait and see if it recovers (some upstream APIs take 5+ minutes when degraded).

### Orphan — alive but unwatched

`orphan && !stuckReason`. The run is still progressing, but no client is reading the stream:

- The user closed the browser tab and never came back
- The desktop app crashed mid-stream
- An external-channel adapter (DingTalk, Feishu, …) lost its webhook session

Orphan runs are not automatically killed — they may finish and write a useful turn to the conversation regardless. The badge is informational. Recycle them if you need the slot back, leave them if you don't.

A run can be both stuck and orphan; the **stuck** signal wins and the orphan badge is suppressed in that case so you don't get a confusing two-pill row.

---

## A typical session

A user pings you on Slack: "my agent's been spinning for 10 minutes."

1. Open `/backstage`.
2. Find their card. The orange status ring tells you it's stuck before you read anything else.
3. Read the *saying* line and the tool chip — usually enough to see what the agent was last doing.
4. If you want to see what triggered it, click into the conversation from the card link. Otherwise, hit **End**.
5. The card disappears within the next 5-second refresh tick.

Total time: about 15 seconds. That is the whole point of this page.

If multiple users are reporting the same thing — usually means a provider went down — switch to **Tidy Up** and clear all the stuck runs in one click instead of triaging individually.

---

## Operational notes

- **Refresh cost** — the snapshot endpoint walks an in-memory map. Auto-refresh at 5 s is cheap; don't lower it without reason.
- **Audit** — every Stop / End / Tidy Up / Interrupt is logged via the standard audit pipeline. Look for `agent_runtime.stop`, `.recycle`, `.sweep`, `.interrupt_subagent` events in `mate_audit_event`.
- **Empty state** — if the page is empty during a known-busy period, your runtime probably restarted and lost the in-memory `RunState`. The conversations themselves are intact in `mate_conversation` / `mate_message`; only the live wire-up is gone.
- **Multi-replica deploys** — `RunState` lives in the JVM that's serving the SSE stream. Backstage shows you what's running *on the replica handling the snapshot request*. Behind a load balancer, refresh a couple of times to see the others, or scope by sticky session.

---

## Next

- [Admin Console](./console) — the broader SPA that hosts Backstage
- [Agents](./agents) — what's actually running inside those cards
- [Doctor](./doctor) — system-health checks (disk, queues, providers); pairs naturally with Backstage when you're triaging an incident
- [Security & Approval](./security) — the audit trail your Backstage actions land in
