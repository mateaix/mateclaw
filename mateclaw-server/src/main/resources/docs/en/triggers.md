# Triggers

::: tip New in 1.3.0
The trigger system is available from v1.3.0. In v1.2.0 and earlier, workflows and agent conversations could only be invoked manually.
:::

**What triggers are**: a connector between "events that happen in the system" and "actions to perform". Events can be a cron schedule, a webhook arriving, a channel message, an employee finishing a conversation, or another workflow completing. Actions are either starting a workflow or sending a message to an employee for processing.

**What triggers are not**:
- Not a replacement cron-job manager — `mate_cron_job` still exists and runs independently; triggers **share** its ShedLock + scheduler base but **do not write into** `mate_cron_job`
- Not an IFTTT / n8n drag-to-edit automation builder — triggers only do "event → action" routing; complex logic belongs in [Workflow](./workflow.md)
- Not a full-feature webhook dispatcher — they handle dedup / rate-limit / bot-self filtering / pattern matching, not arbitrary business-payload parsing

::: warning v1.3.0 scope
v0 = 6 pattern types + 2 dispatch targets (agent / workflow). Event governance (dedup, per-trigger rate limit, recursion guard, bot-self filtering) is on by default.
:::

---

## One-minute overview

```jsonc
// A trigger that runs a "morning report" workflow daily at 9 AM
{
  "name": "daily-morning-report",
  "patternType": "cron",
  "patternJson": {
    "cronExpression": "0 0 9 * * *",
    "timezone": "Asia/Shanghai"
  },
  "targetType": "workflow",
  "targetId": 12345,
  "payloadTemplate": "{ \"date\": \"{{ now | date('yyyy-MM-dd') }}\" }",
  "rateLimitPerMin": 10,
  "dedupWindowSecs": 60,
  "botSelfFilter": true,
  "enabled": true
}
```

At 9 AM → backend grabs the ShedLock via `CronDelegationPort` → renders the payload → enqueues an async run of workflow `12345`. Other instances at the same moment are blocked by the lock; no double-fire.

---

## Six pattern types

Implemented in `TriggerPatternMatcher.java`. Each pattern matches its `pattern_json` block on the trigger row. **Fields not listed here are ignored** by v0's matcher.

| Pattern | When it fires | `pattern_json` fields | Reuse constraint |
|---|---|---|---|
| `cron` | On a cron expression (**does not flow through ingest**; runs from the scheduler) | `cronExpression`, `timezone` | Reuses the `cron/` module's ShedLock + Spring TaskScheduler; **does NOT write into mate_cron_job, does NOT call CronJobService** |
| `webhook` | Generic event passthrough (**v0 does no further filtering** — secret check happens at the channel layer; the trigger itself just matches `patternType=webhook`) | (none in v0) | Through the unified `POST /api/v1/triggers/events` entry + envelope wrap |
| `channel_message` | Channel receives a message | `channelType` (optional, compared against envelope `data.channelType`), `senderEquals` (optional, exact sender id match) | Side-channel through `ChannelWebhookController`; original routing unaffected |
| `agent_lifecycle` | Agent lifecycle events | `agentId` (optional), `phase` (optional: `spawned` / `terminated` / `crashed`) | Hangs off `ReActLifecycleListener` |
| `content_match` | Substring must appear in the envelope content | `substring` (**required**, case-insensitive contains-match against envelope `data.content`) | Generic content filter; the event source is whatever fed the envelope |
| `workflow_completion` | A workflow run reaches a terminal state | `sourceWorkflowId` (optional), `stateFilter` (optional: `completed` / `failed` / `any`) | Listens to `WorkflowEngine` terminal events; recursion guard below |

> **Unknown pattern types fail closed by default** — typo'd or future pattern types can't silently fire every trigger in the workspace.
>
> **Not in v1.3.0**: `schedule` (one-shot non-cron like "30 minutes from now"), external MQ listeners (Kafka / Pulsar / RocketMQ), metrics / threshold alerting triggers.

---

## Event governance (on by default)

### Bot self-msg filtering (default binding is no-op)

Some channels (Feishu / DingTalk / WeCom) surface bot-emitted messages back as `channel_message` events. The framework wires this through `BotSelfFilter` SPI + each trigger's `bot_self_filter` field (default `true`).

::: warning v0 default implementation is no-op
The default-bound `NoopBotSelfFilter` returns `false` from `isBotSelf(...)` for every sender. That means `bot_self_filter=true` on a trigger **doesn't actually filter anything in v0** until a channel adapter registers a real `BotSelfFilter` Spring Bean (which replaces the default). This is intentional — a wrong default would silently swallow all legitimate bot-to-bot messages.
:::

To exempt a single trigger from the framework filter (rare — e.g. a bot emitting a special command to trigger cleanup), set that trigger's `bot_self_filter` to `false`.

### Event dedup

When `TriggerEventIngestService` dispatches an event, the engine queries `mate_trigger_event` for the `dedup_key` within the `dedupWindowSecs` window (default 60s). Already present → **dropped**, `fire_count` not incremented.

Default `dedupWindowSecs = 60`. Raise it to absorb longer gateway re-deliveries; set to `0` to disable (**not recommended**).

### Per-trigger rate limit

Each trigger is rate-limited individually: at most `rateLimitPerMin` per minute (default 10). Events past the cap are dropped — **no retry**, **no row in `mate_trigger_event`**; instead `mate_trigger.last_error` is updated to `"rate-limited"` so ops can see it.

`channel_message` triggers usually want this raised (group bursts); `workflow_completion` triggers usually want it lowered (to slow A→B→A chains).

### Recursion guard

A `workflow_completion` trigger fires a workflow which fires another `workflow_completion`… dispatch chain length > 5 → engine cuts + alerts. Intended to break "A writes a message that triggers B, B writes a message that triggers A" loops.

### Webhook ACK timing

The HTTP entry (`POST /api/v1/triggers/events`) → envelope wrap → dedup check → bot-self check → rate-limit check → **immediate 200 ACK** → async dispatch. Implications:

- Upstream gateways (Feishu / DingTalk etc.) get 200 and stop re-delivering
- Dispatch failures → `mate_trigger.last_error` updates; same `dedup_key` on retry is still dedup'd (**no automatic retry**)

"ACK only after dispatch succeeds" semantics — **not in v0** — fire-and-forget is intentional for surge handling.

---

## Managing triggers from the UI

::: tip 1.4.0 change: merged into the Scheduler
As of v1.4.0, **Scheduled Jobs** and **Triggers** are merged into a single **Scheduler** page (`Settings → Scheduler`, route `/settings/scheduler`) with three tabs: **Scheduled Jobs** / **Event Triggers** / **Run History**. Each tab shows an item count next to its title; the top-right action button is context-aware (it's "New" on the Scheduled Jobs / Event Triggers tabs, "Refresh" on the History tab); Run History **spans both** — execution records for both scheduled jobs and triggers live here.

The old routes redirect automatically: `/cron-jobs` and `/settings/triggers` each land on the matching Scheduler tab.
:::

### Entry point

`Settings → Scheduler` (sidebar) → **Event Triggers** tab. In v1.4.0 the trigger list was redesigned from the old wide table into **rule cards** — one card per trigger, showing pattern type / target / enabled state at a glance. Click **+ New Trigger** to open the drawer.

### Creating a trigger

The drawer has structured forms per pattern type — no hand-written `pattern_json`:

- `cron` → cron expression input + timezone dropdown + next-fire preview. The expression can be typed by hand, or click the edit button beside the input to open the **visual cron editor** (see below)
- `channel_message` → channel type (optional) + sender id exact-match (optional)
- `agent_lifecycle` → agent (optional) + phase: `spawned` / `terminated` / `crashed` (optional)
- `content_match` → substring (**required**), matched case-insensitively against envelope `data.content`
- `workflow_completion` → upstream workflow (optional) + state filter: `completed` / `failed` / `any` (optional)
- `webhook` → no extra fields in v0 (transparent passthrough)

Save → trigger persists; with `enabled=true` it's registered with the right engine immediately (cron → ShedLock; others → envelope router).

### Visual cron editor (new in 1.4.0)

You don't have to hand-write the cron expression. Click the edit button beside the expression input to open a **segmented editor**: minute / hour / day / month / day-of-week each get a tab, and each segment offers "every / specific value / range / step"; a row of **presets** up top (every minute, on the hour, daily at midnight, every Monday…) fills it in with one click; at the bottom is a **live human-readable preview** that translates the current expression into plain language (e.g. "every day at 09:00").

This editor is the **same component shared by Scheduled Jobs and Triggers**:

- **Scheduled Jobs** use **5-field** cron (minute hour day month day-of-week)
- **Triggers** use **6-field** cron (with seconds: second minute hour day month day-of-week) — an extra leading seconds field

The input itself also carries a one-line readable preview, so you can confirm what your hand-typed expression parsed to without opening the editor.

---

## Scheduled-task types (task type)

Every job on the **Scheduled Jobs** tab of the Scheduler has a `task_type` that decides what it does when it runs. This is the authoritative list of cron task types (the six event-trigger pattern types are covered above):

| task type | Behavior | Binds an employee? | Notes |
|---|---|---|---|
| `text` / `agent` / `reminder` | Starts an employee conversation on the cron schedule | **Yes** (agent required) | Classic scheduled conversation; the result routes to the conversation |
| `wiki_process` | Processes a knowledge base offline on the cron schedule | **No** | New in 1.4.0 — see below |

### `wiki_process`: off-peak KB processing (new in 1.4.0)

`wiki_process` lets you schedule **knowledge-base processing** to run offline during low-traffic windows instead of saturating the processing queue the moment an upload finishes. It **binds no employee** — it's a system task: no conversation, no chat.

When creating one you only fill in:

- **cron expression** (use the visual editor above, 5-field)
- **KB selector** — which KB this job processes
- an optional **"force reprocess"** toggle — when on, already-processed raw materials are rerun too (`force`)

On each tick, the job **asynchronously queues** that KB's raw materials for processing and logs one row in Run History, of the form `queued N raw material(s)` (a `(force)` suffix is appended when force is on). **Note it does not route to any conversation** — it just hands work to the processing queue; check progress on the [LLM Wiki](./wiki.md) page.

### Payload template

The `payload_template` field is a Pebble template string; the rendered output becomes the input to the dispatch target (agent conversation or workflow run).

```jsonc
"payload_template": "{
  \"date\": \"{{ now | date('yyyy-MM-dd') }}\",
  \"trigger\": \"{{ trigger.name }}\",
  \"sourceEvent\": {{ event | toJson }}
}"
```

Variables in the template:
- `now` — current time
- `trigger.{name,id,workspaceId}` — the firing trigger
- `event` — the current event envelope (`workspaceId` / `senderId` / `data` JSON, etc.)

### Inspecting fire history

`mate_trigger_event` is **dedup metadata only** — one row per accepted event with `trigger_id` / `dedup_key` / `received_at` / `expires_at`, and **no copy of the envelope itself**. To audit the actual content of a particular event, look at channel-layer logs + the agent / workflow run records.

`mate_trigger.fire_count` honestly records dispatch count (excluding dedup'd / rate-limited events); `mate_trigger.last_error` carries the most recent failure reason.

---

## API reference

All endpoints under `/api/v1/triggers/`. **What v1.3.0 actually exposes** — the `/webhook/{slug}` / `/test-fire` / `/{id}/events` entries from the RFC are not yet implemented.

| Method | Path | Description |
|---|---|---|
| `GET` | `/api/v1/triggers` | List all triggers in the current workspace |
| `GET` | `/api/v1/triggers/{id}` | Get details |
| `POST` | `/api/v1/triggers` | Create a new trigger; with `enabled=true`, registers with scheduler / router immediately |
| `PUT` | `/api/v1/triggers/{id}` | Update (including enable / disable — flip the `enabled` field); on `pattern_json` change, `pattern_version++` and stale futures self-cancel cross-instance |
| `DELETE` | `/api/v1/triggers/{id}` | Soft delete (equivalent to disable) |
| `POST` | `/api/v1/triggers/events` | **Unified event entry** — any webhook / channel adapter / internal module hands an envelope to the engine; engine runs dedup / bot-self / rate limit / pattern match / dispatch and returns a per-trigger fire / drop summary |

---

## Relationship with the existing cron module

::: tip Reuse, not replace
Before v1.3.0 MateClaw already had a standalone cron subsystem (`mate_cron_job` table + `CronJobService`). The trigger system **does not replace it** —
- Legacy cron jobs (`task_type = text / agent / reminder`) remain on the `Cron Jobs` page
- New trigger crons live on the `Triggers` page
- Both **share** the underlying ShedLock lock table + Spring TaskScheduler thread pool
- The `mate_cron_job` list **does not show** trigger crons, and vice versa
:::

Why not merge? Because `mate_cron_job`'s legacy schema (required `task_type` / `agentId`, etc.) doesn't fit a workflow target. Forcing extra columns would break existing product invariants. `CronDelegationPort` is the v0 minimal solution — share the scheduler base, split the persistence layer. Folding `mate_cron_job` into trigger entirely is a future iteration.

---

## Cross-instance consistency (multi-replica deploy)

`CronDelegationPort` methods are **process-local** — local `ScheduledFuture` lives only in this JVM, no persisted handle. Cross-instance consistency relies on:

1. Every instance, on startup, calls `syncFromDatabase()` to scan all enabled cron triggers and register locally
2. When a trigger is updated, `pattern_version++` + cancel local future
3. Each fire re-reads the trigger row before executing; mismatched `patternVersion` → **local short-circuit + self-cancel** (means another instance modified it)
4. ShedLock key = `"mate-trigger-{triggerId}"`, mutually exclusive across instances
5. Periodic `@Scheduled(fixedDelay=60s) syncFromDatabase()` as a fallback reconciler

Practical implication: rolling-deploy multiple replicas needs no extra steps — new instances pick up automatically; old instances finish their last cycle and stop.

---

## Data model

### `mate_trigger` — trigger configuration

Key fields:

| Field | Type | Purpose |
|---|---|---|
| `pattern_type` | varchar | One of the six patterns |
| `pattern_json` | TEXT | The pattern's filter parameters as JSON |
| `target_type` | varchar | `agent` or `workflow` |
| `target_id` | bigint | Foreign key to the agent / workflow |
| `payload_template` | TEXT | Pebble render template |
| `dedup_window_secs` | int | Dedup window in seconds |
| `rate_limit_per_min` | int | Max fires per minute |
| `bot_self_filter` | bool | Enable bot-self filter (default `true`, but the default impl is no-op) |
| `pattern_version` | bigint | Optimistic-concurrency Lamport counter; auto-bumps on every `pattern_json` change; cross-instance fires compare before executing and self-cancel on mismatch |
| `fire_count` | bigint | Effective dispatch count (excluding dedup'd / rate-limited drops) |
| `last_error` | varchar | Most recent failure reason (`"rate-limited"` / exception messages) |
| `enabled` | bool | Soft on/off |
| `deleted` | int | Soft delete |

### `mate_trigger_event` — dedup metadata

Used only for dedup decisions. **Does not store envelope copies**:

| Field | Type | Purpose |
|---|---|---|
| `id` | bigint | Primary key |
| `trigger_id` | bigint | Trigger this row dedups against |
| `dedup_key` | varchar | **Unique index**; the engine consults this within `dedup_window_secs` |
| `received_at` | timestamp | Insertion time |
| `expires_at` | timestamp | Window expiry; the same key can re-enter after this point |

::: tip Design tradeoff
v0 deliberately **does not persist envelopes inside `mate_trigger_event`** — full-volume channel events would crush the DB. Event-payload audit relies on channel-layer logs + the run records on the agent / workflow side. If "event replay" becomes a real need, an envelope column gets added later.
:::

---

## Known limitations (v1.3.0)

- **No visualization of trigger → workflow chains** — multiple triggers dispatching to the same workflow appear as two independent lists in the UI
- **No inter-trigger priority / dependency** — when an event hits multiple triggers, dispatches are serialized by ascending DB id
- **No dedicated webhook entry / IP allowlist** — there's no `/webhook/{slug}` route in v0; `/events` is the unified entry. Stricter IP control belongs at the front-door nginx / gateway
- **`agent_lifecycle` granularity is `spawned` / `terminated` / `crashed`** — not "started / completed / failed" per step
- **No event replay** — `mate_trigger_event` only persists dedup metadata, not envelopes; "redispatch this event" requires the upstream source to re-emit

---

## Troubleshooting

| Symptom | Investigate |
|---|---|
| Cron trigger doesn't fire | 1) `enabled=true`? 2) Does the cron expression + timezone parse to a next-fire time? The editor previews it. 3) Is the ShedLock held by another instance? Check the `shedlock` table. |
| `POST /events` returns 200 but no dispatch happens | The response body contains a per-trigger fire / drop summary — look for `BOT_SELF` / `RATE_LIMITED` / `DEDUPED` / `PATTERN_MISMATCH` |
| `channel_message` doesn't fire | 1) Does the envelope's `data.channelType` match this trigger's `pattern_json.channelType`? 2) `bot_self_filter=true` and a non-default `BotSelfFilter` is filtering it? 3) For `content_match`, the `substring` field must actually appear in `data.content` |
| `agent_lifecycle` doesn't fire | Confirm `pattern_json.phase` is `spawned` / `terminated` / `crashed` (not `started` / `completed` / `failed`) |
| Cron trigger stops firing after restart | Look at startup log for `syncFromDatabase()` errors; common cause is corrupted `pattern_json` failing deserialization |
| `mate_trigger.last_error` reads `"rate-limited"` | Raise `rate_limit_per_min`, or split the trigger into multiple ones partitioned by group |
| `bot_self_filter=true` doesn't seem to filter | Confirm a non-noop `BotSelfFilter` Spring Bean is registered — the default `NoopBotSelfFilter` always returns `false` |

---

## Related

- [Workflow](./workflow.md) — where dispatches go when `target_type=workflow`
- [Agents](./agents.md) — where dispatches go when `target_type=agent`
- [Channels](./channels.md) — the source of `channel_message` events
- [Security & Approval](./security.md) — webhook secret + ACL backstop
