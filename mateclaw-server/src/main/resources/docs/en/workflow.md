# Workflow

::: tip New in 1.3.0
Workflow orchestration is available from v1.3.0. Earlier releases (v1.2.0 and below) do not ship this capability.
:::

**What workflow is**: a way to compose multiple digital employees plus system actions (approval / channel dispatch / memory write) into a linear-step business process. Each step can be gated by the previous step's output, fan out in parallel, wait for human approval, or persist results into an employee's `MEMORY.md`.

**What workflow is not**:
- Not a replacement for ReAct / Plan-and-Execute — single-agent multi-turn reasoning still lives in those engines
- Not a low-code drag-and-drop if/else builder — v0 is **JSON-first** (canvas comes in v1)
- Not a 30-node Dify-style orchestrator — MateClaw workflows stay deliberately minimal: **a linear array of steps with one `mode` field expressing the control flow**

::: warning v1.3.0 scope
v0 = internal alpha. **7 step modes + 6 trigger pattern types**. `loop` and `invoke_skill` are deferred. Run it on a flagship account / internal workspace before rolling out broadly.
:::

---

## One-minute overview

```json
{
  "schemaVersion": "1.0",
  "inputs": [
    { "name": "customer", "type": "json" }
  ],
  "steps": [
    {
      "name": "enrich",
      "agentName": "data-analyst",
      "promptTemplate": "Enrich and return strict JSON: {{ inputs.customer | toJson }}",
      "mode": { "type": "sequential" },
      "outputVar": "enriched",
      "outputContentType": "json"
    },
    {
      "name": "vip-route",
      "agentName": "enterprise-sales",
      "promptTemplate": "VIP onboarding for {{ outputs.enriched.name }}",
      "mode": {
        "type": "conditional",
        "expression": "{{ outputs.enriched.tier == 'enterprise' }}"
      }
    },
    {
      "name": "notify-feishu",
      "agentName": "ops-bot",
      "promptTemplate": "Notify feishu: {{ outputs.enriched }}",
      "mode": { "type": "fan_out" }
    },
    {
      "name": "notify-email",
      "agentName": "ops-bot",
      "promptTemplate": "Notify email: {{ outputs.enriched }}",
      "mode": { "type": "fan_out" }
    },
    {
      "name": "wait-acks",
      "mode": { "type": "collect" }
    },
    {
      "name": "record",
      "promptTemplate": "Onboarded {{ inputs.customer.name }}",
      "mode": {
        "type": "write_memory",
        "employeeId": "{{ outputs.enriched.assignedEmployeeId }}",
        "file": "MEMORY.md",
        "mergeStrategy": "append"
      }
    }
  ]
}
```

How it reads:
1. `enrich` asks the data analyst to structure the customer info as JSON
2. If `tier == enterprise`, route to the enterprise-sales employee for VIP onboarding
3. In parallel (fan_out), notify Feishu and notify email
4. `collect` waits for both notifications
5. Append the result to the employee's `MEMORY.md`

---

## Core concepts

### Seven step modes (v1.3.0)

| Mode | Behavior | Required fields | Key semantics |
|---|---|---|---|
| `sequential` | Run after the previous step; previous output → `{{input}}` | — | Default mode |
| `fan_out` | Runs in parallel with consecutive `fan_out` steps; all receive the same `{{input}}` | — | Boundary detected at compile time: from this step onward, the first non-`fan_out` / non-`collect` step terminates the group |
| `collect` | Joins the most recent `fan_out` group's outputs with `\n\n---\n\n` into `{{input}}` | — | At least 2 consecutive `fan_out` steps must precede; compile-time check |
| `conditional` | Runs only if the Pebble expression is true | `expression` | When false, skipped; `{{input}}` is preserved (carries over previous step's) |
| `await_approval` | Pauses the run; sends an approval | `approvalKind`, `approverChannels[]` | Resumes to next step on approval; timeout follows workspace policy |
| `dispatch_channel` | Multi-channel delivery of `{{input}}` | `channels[]` | Per-channel failure follows `errorMode` |
| `write_memory` | Writes employee memory file | `employeeId`, `file`, `mergeStrategy` | Four strategies: `append` / `replace_section` / `upsert_kv` / `overwrite` |

> **Not in v1.3.0**: `loop` (iterate N times or per-item over an array) and `invoke_skill` (call a skill without going through an employee). Coming based on user feedback.

### Expressions: a Pebble subset

Workflow does **not** use a full template engine — it supports the same Pebble subset as Kestra, just enough to gate conditionals and reference variables, with no code execution.

| Category | Syntax |
|---|---|
| Variable references | `inputs.X` / `outputs.varname.field` / `vars.X` / `now` / `flow.id` |
| Operators | `==` `!=` `<` `<=` `>` `>=` `and` `or` `not` `+` `-` |
| Built-in filters | `length` / `lower` / `upper` / `default('x')` / `toJson` / `fromJson` / `date(format)` |
| JSONPath | `\| jq('.field.subfield')` |
| String tests | `\| contains('x')` / `\| startsWith('x')` / `\| matches('regex')` |

**Not supported** (rejected at compile time):
- User-defined functions / macros
- `include` / `extends`
- File I/O / network I/O
- Any side-effecting operations

### Output type: text vs json

Each step's `outputContentType` decides how downstream steps can access it:

| outputContentType | Default | Pebble access rules |
|---|---|---|
| `text` | ✅ | `outputs.X` is a string; `outputs.X.field` **fails at compile time**; `\| jq(...)` **fails at runtime** |
| `json` | — | Runtime `JSON.parse`; failure follows `errorMode`; field access / `jq(...)` are valid |

**Agent steps default to `outputContentType=text`** — LLM natural-language output isn't structured JSON. To do conditionals or field access, you must:
1. **Explicitly** request strict JSON in the `promptTemplate` ("return strict JSON: {...}")
2. Set that step's `outputContentType` to `json`

### Compile-time illegal combinations (publish rejects)

| Combination | Reason |
|---|---|
| Multiple consecutive `fan_out` with no `collect` to terminate | `{{input}}` for the next step is ambiguous |
| `collect` without preceding `fan_out` | Nothing to collect |
| `await_approval` mixed inside a `fan_out` group | Multiple concurrent approvals fired with no aggregation UX |
| `agentName` references a non-existent / disabled / cross-workspace employee | ACL fail |
| Pebble expression references an undeclared variable | Compile-time |
| `outputs.X.field` but step X is `text` | Compile-time type error |
| `dispatch_channel` references a channel not in the workspace allowlist | ACL |
| `write_memory` references an employeeId outside the workspace | ACL |
| Step count > 200 (default cap) | Runaway-config guard |

Publish runs `WorkflowCompiler.validate(graphJson) → List<CompileError>`. Each error points at a step name + field path; the Monaco editor highlights them inline.

---

## Using workflow from the UI

### Entry point

`Workflows` (sidebar) → list → **+ New**.

::: tip
The Workflows list is empty on a fresh install. That's intentional — v0 ships no built-in templates; flagship accounts co-author them.
:::

### Editor (v1.3.0 = JSON only)

- **Monaco editor**: JSON-schema validation, autocomplete, static Pebble checking
- **Template dropdown**: built-in skeletons fetched from `GET /api/v1/workflows/draft/templates`
- **Pre-compile**: `POST /api/v1/workflows/{id}/compile` returns compile diagnostics — **does not write a revision, does not actually run**
- **Publish**: compile → ACL validate → write a new `mate_workflow_revision` row (integer revision +1)

::: warning Canvas comes in v1
The `@vue-flow/core` canvas has a UI shell in v1.3.0, but it renders the step array as a node chain — **not** drag-to-edit. Double-click a node to open its field form; the primary edit path is still JSON. Full visual editing lands in v1.4+.
:::

### Natural language → workflow draft (v1.3.0)

`POST /api/v1/workflows/draft/generate` takes a free-form description ("I want a customer ticket triage flow with a Feishu entry, routing by tier — enterprise / pro / standard — to different handlers"), runs an internal agent to emit the corresponding `graph_json`, and **immediately compiles + returns** with diagnostics attached.

Use cases:
- Authors who don't know the JSON DSL get a publishable first draft to refine in Monaco
- Bulk-feeding old SOP docs through the generator to get candidate workflow templates
- During customer co-creation, turn "how I want this to work" into something visualizable fast

Response shape:
```json
{
  "graphJson": "...",          // can be PUT directly into a draft
  "compileErrors": [...],      // same diagnostics as /compile
  "modelUsed": "qwen-plus",
  "tokenUsage": { ... }
}
```

::: tip Doesn't replace Monaco editing
The generator **never publishes directly** — it only emits a draft (via `saveDraft`); a human still has to review → compile → publish. The generated JSON may carry compile errors; the author cleans them up before publishing.
:::

### Run history

Every run persists as `mate_workflow_run` + `mate_workflow_run_step`. Detail view shows:
- Per-step input / output (payload URI references)
- Per-step duration + token usage
- Cross-step failure chain highlight
- For paused `await_approval` steps: who's approving, how long it's been waiting

### Trigger sources

A workflow run can only start through [Triggers](./triggers.md) or via `await_approval` resume — v0 has no "fire one now" endpoint. See API reference above for details.

::: tip 1.4.0: triggers now live in the Scheduler
As of v1.4.0, **Scheduled Jobs** and **Triggers** are merged into a single **Scheduler** page (`Settings → Scheduler`, route `/settings/scheduler`) with three tabs: **Scheduled Jobs / Event Triggers / Run History**. To attach a trigger to a workflow, create a `target_type=workflow` rule on the Scheduler's **Event Triggers** tab. See [Triggers](./triggers.md).
:::

---

## API reference

All endpoints live under `/api/v1/workflows/`. Requests must carry the `X-Workspace-Id` header.

### CRUD

| Method | Path | Description |
|---|---|---|
| `GET` | `/api/v1/workflows` | List all workflows in the current workspace |
| `POST` | `/api/v1/workflows` | Create a new workflow (draft starts empty) |
| `GET` | `/api/v1/workflows/{id}` | Fetch workflow metadata + inline draft |
| `PUT` | `/api/v1/workflows/{id}` | Update workflow metadata (name / description / enabled) |
| `PUT` | `/api/v1/workflows/{id}/draft` | Save the inline draft graph_json (does not compile) |
| `DELETE` | `/api/v1/workflows/{id}` | Soft-delete |

### Compile / Publish

| Method | Path | Description |
|---|---|---|
| `POST` | `/api/v1/workflows/{id}/compile` | Compile the current draft and return diagnostics — **does not persist a revision** |
| `POST` | `/api/v1/workflows/{id}/publish` | Compile + persist a new revision; updates `latest_revision_id` |

### Draft generator (built-in in v1.3.0)

| Method | Path | Description |
|---|---|---|
| `GET` | `/api/v1/workflows/draft/templates` | List built-in draft templates |
| `POST` | `/api/v1/workflows/draft/preview-compile` | Compile arbitrary graph_json — surfaces real diagnostics before a workflow row exists |
| `POST` | `/api/v1/workflows/draft/generate` | **Natural language → workflow draft** — describe the flow, an agent emits graph_json + compile diagnostics |

### Run inspection / resume

| Method | Path | Description |
|---|---|---|
| `GET` | `/api/v1/workflows/{id}/runs?limit=...` | Recent runs of a workflow (default 50) |
| `GET` | `/api/v1/workflows/runs/paused?limit=...` | All paused runs across the workspace (operator entry point) |
| `GET` | `/api/v1/workflows/runs/{runId}` | One run's detail + all step rows (input / output / duration) |
| `POST` | `/api/v1/workflows/runs/{runId}/resume` | Resume from `await_approval` pause (called automatically when an approval lands; not for manual use) |

::: warning v0 has no standalone "start run" endpoint
There are only two paths to actually start a workflow run:

1. **Via a trigger** — configure a trigger in [Triggers](./triggers.md) pointing at this workflow (`target_type=workflow`); when an event arrives the engine starts the run
2. **Via `await_approval` resume** — the resume endpoint pushes a paused run forward

There is **no** `POST /api/v1/workflows/{id}/runs` "fire one now" endpoint in v0. For a dry run, use `/draft/preview-compile` to get compile output (**compile only — no persist, no real run**), or attach a temporary webhook trigger. A manual run-start endpoint is on the RFC but lands in a later release.
:::

---

## Security model

### Three-layer ACL

| Role | Capabilities |
|---|---|
| `workflow:author` | Edit drafts, read own runs |
| `workflow:publisher` | Publish revisions; static ACL checks fire here |
| `workflow:operator` | Start/stop triggers, cancel runs, view other people's runs |

### Per-step execution identity

Every step carries in its ExecutionContext:
- `workspaceId`: must equal the workflow's workspace
- `actingAgentId`: for `sequential` and the three MateClaw modes → that step's agent; for other modes → publisher as fallback
- `triggeredBy` / `workflowId` / `revisionId` / `runId`: for audit traceability

### Cross-workspace isolation

At publish time `WorkflowAclValidator.checkAll(graphJson)` runs:
- `agentName` references must point to an employee in the current workspace
- `dispatch_channel` channels must be in the workspace allowlist
- `write_memory` employeeIds must be inside the current workspace

Any failure → publish fails, transaction rolls back, **no revision row written, no `latest_revision_id` update**.

### Relationship with [MCP per-agent tool binding](./mcp.md)

Workflow **cannot** grant employees additional tools. When an agent step calls a tool, it goes through the same `AgentBindingService.getEffectiveToolNames(agentId)` ACL — what an employee can do inside a workflow is exactly what it can do in normal chat.

---

## Internal storage URI for payloads

Workflow inputs / outputs / intermediate artifacts above the 4KB default threshold are auto-spilled to the `mate_workflow_payload` table (v1.3.0: same-DB storage) or local filesystem fallback, and replaced inline with a `payload://` URI. This avoids large contexts blowing out the message column — see commit `9c81dba0 feat(workflow): payload fs fallback for medium-size payloads`.

```text
payload://run/abc123/step/enrich/output → resolved by the backend at access time
```

The UI lazy-loads on demand.

---

## Data model

The workflow subsystem touches 8 tables:

| Table | Purpose |
|---|---|
| `mate_workflow` | Workflow root (id / name / workspace) |
| `mate_workflow_revision` | Published revisions (integer revision; full graph_json snapshot; immutable) |
| `mate_workflow_run` | One execution (runId / triggerSource / status / startedAt / endedAt) |
| `mate_workflow_run_step` | Per-step input/output/duration inside a run |
| `mate_workflow_run_pause` | Persistent `await_approval` pause state (survives restart) |
| `mate_workflow_payload` | Large-payload internal storage (target for `payload://` URI) |
| `mate_trigger` | Trigger configurations (with cron `pattern_version`) |
| `mate_trigger_event` | Event dedup + rate-limit history |

---

## Known limitations (v1.3.0)

- **No drag-to-edit canvas** — the canvas is read-only chain rendering; primary edit path is JSON
- **No `loop` step** — can't iterate per-item or retry N times. Workaround: a fixed number of `fan_out` branches, or higher-level scheduling of multiple runs
- **No `invoke_skill` step** — skills must be attached to an agent and invoked through the agent
- **No cross-workspace sharing** — to reuse a workflow template across workspaces, copy it
- **No realtime collaborative editing** — concurrent edits to the same draft: **last write wins**
- **No per-step retry policy** — `errorMode.retry` is step-wide; finer-grained retry is deferred

---

## Related

- [Triggers](./triggers.md) — workflow's event entry point
- [Approval & security](./security.md) — what `await_approval` plugs into
- [Agents](./agents.md) — what `agentName` references
- [Channels](./channels.md) — what `dispatch_channel` can reach
- [Memory](./memory.md) — which file `write_memory` writes to
