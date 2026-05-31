# Architecture

**How MateClaw is put together, in one page.**

If you're using MateClaw, read [Introduction](./intro). If you're building on MateClaw — adding tools, new channels, custom memory providers, new agent graph nodes — read this page.

---

## The product in one diagram

```
┌─────────────────────────────────────────────────────────────────┐
│                         MateClaw                                 │
│                                                                  │
│  ┌──────────────┐  ┌──────────────┐  ┌─────────────────────┐   │
│  │  Web Console  │  │  Desktop App  │  │   IM Channels       │   │
│  │   Vue 3 SPA   │  │   Electron    │  │ DingTalk / Feishu / │   │
│  │  (src/static) │  │  + Bundled    │  │ WeCom / Telegram /  │   │
│  │               │  │   JRE 21      │  │ Discord / QQ / ...  │   │
│  └───────┬──────┘  └──────┬───────┘  └──────────┬──────────┘   │
│          │  HTTP/SSE       │  HTTP/SSE            │ SPI           │
│          └────────┬────────┴───────────────────────┘              │
│                   │                                               │
│  ┌────────────────▼──────────────────────────────────────────┐  │
│  │            Spring Boot Backend (vip.mate.*)                │  │
│  │                                                             │  │
│  │  ┌────────────┐  ┌────────────┐  ┌────────────────────┐   │  │
│  │  │    auth     │  │  channel    │  │      agent          │   │  │
│  │  │   (JWT)     │  │  adapters   │  │  (StateGraph        │   │  │
│  │  └────────────┘  └─────┬──────┘  │   runtime)          │   │  │
│  │                        │         │                     │   │  │
│  │  ┌────────────┐        │         │  ┌──────────────┐   │   │  │
│  │  │ workspace  │        │         │  │  ReasoningN  │   │   │  │
│  │  │ isolation  │        │         │  │  ActionN     │   │   │  │
│  │  └────────────┘        │         │  │  Observation │   │   │  │
│  │                        │         │  │  PlanGenN    │   │   │  │
│  │                        ▼         │  │  StepExecN   │   │   │  │
│  │                ┌──────────────┐  │  │  FinalAnsN   │   │   │  │
│  │                │  Message     │  │  └──────────────┘   │   │  │
│  │                │  Router      ├──▶      │               │   │  │
│  │                └──────────────┘  └──────┼─────────────┘   │  │
│  │                                          │                   │  │
│  │  ┌─────────────────────────────────────▼────────────────┐ │  │
│  │  │                    Tool Registry                        │ │  │
│  │  │                                                         │ │  │
│  │  │   built-in @Tool  +  MCP clients  +  Skill scripts     │ │  │
│  │  └─────────┬───────────────────────────────────────────┬──┘ │  │
│  │            │                                            │   │  │
│  │            ▼                                            ▼   │  │
│  │  ┌──────────────┐  ┌─────────────┐  ┌───────────────────┐  │  │
│  │  │  Tool Guard  │  │ Approval    │  │  Audit Log        │  │  │
│  │  │  (rules)     │  │ Workflow    │  │  Pipeline         │  │  │
│  │  └──────────────┘  └─────────────┘  └───────────────────┘  │  │
│  │                                                             │  │
│  │  ┌────────────┐  ┌────────────┐  ┌────────────────────┐   │  │
│  │  │   memory    │  │    wiki     │  │    skill            │   │  │
│  │  │ multi-layer │  │  (layered)  │  │  (SKILL.md runtime) │   │  │
│  │  │   SPI       │  │  digester   │  │                     │   │  │
│  │  └────────────┘  └────────────┘  └────────────────────┘   │  │
│  │                                                             │  │
│  │  ┌────────────────────────────────────────────────────┐   │  │
│  │  │           MyBatis Plus / H2 or MySQL                │   │  │
│  │  │                                                      │   │  │
│  │  │    mate_agent / mate_message / mate_wiki_* /        │   │  │
│  │  │    mate_tool_guard_* / mate_workspace / ...          │   │  │
│  │  └────────────────────────────────────────────────────┘   │  │
│  └───────────────────────────────────────────────────────────┘  │
└─────────────────────────────────────────────────────────────────┘
```

One JAR. One process. The whole widget.

---

## Repository layout

```
mateclaw/
├── mateclaw-server/          # Spring Boot backend (the heart)
│   └── src/main/java/vip/mate/
│       ├── MateClawApplication.java
│       ├── agent/            # StateGraph runtime, nodes, edges, state
│       ├── planning/         # Plan & SubPlan persistence
│       ├── workflow/         # Workflow engine (1.3.0+): DSL compiler, linear runtime, payload spill
│       ├── trigger/          # Trigger engine (1.3.0+): 6 pattern types, event governance, CronDelegationPort
│       ├── tool/             # ToolRegistry, @Tool beans, MCP (per-agent binding), guard
│       ├── approval/         # Approval workflow (also bridges the workflow await_approval pause/resume)
│       ├── skill/            # Dynamic skill packages
│       ├── wiki/             # LLM Wiki + Transformations engine (1.3.0+)
│       ├── memory/           # Memory layers + SPI
│       ├── workspace/        # Workspace entity + conversation + document
│       ├── channel/          # Channel SPI + adapters (1.3.0+: WeCom v2, reply queue, leader lease)
│       ├── llm/              # LLM provider configuration (1.3.0+: multimodal sidecar routing)
│       ├── auth/             # Spring Security + JWT
│       ├── audit/            # Audit event pipeline
│       ├── cron/             # Scheduled job engine (trigger.cron reuses via CronDelegationPort)
│       ├── task/             # Async task runtime
│       ├── dashboard/        # Metrics aggregation
│       ├── datasource/       # External DB connections
│       ├── stt/              # Speech-to-text
│       ├── tts/              # Text-to-speech
│       ├── system/           # System settings, bootstrap, onboarding
│       ├── config/           # Spring configuration
│       ├── common/           # Shared utilities
│       └── exception/        # Global exception handler
│   └── src/main/resources/
│       ├── application.yml
│       ├── db/migration/      # Flyway migration scripts (h2/ + mysql/)
│       ├── db/data.sql       # Seed data
│       ├── prompts/          # LLM prompt templates
│       ├── skills/           # Bundled skill packages
│       └── static/           # Frontend build output
├── mateclaw-ui/              # Vue 3 admin console
├── mateclaw-desktop/         # Electron desktop shell
├── mateclaw-webchat/         # Embeddable chat widget
├── matevip-sites/            # Marketing and docs sites (pnpm workspace)
├── docs/                     # This documentation (VitePress)
├── deploy/                   # Production deployment configs
├── docker-compose.yml
└── .env.example
```

The backend is a **single modular monolith**. Other projects are independent packages that don't share code with the backend.

---

## The agent runtime is a StateGraph

This is the most important thing to know if you're contributing to the backend.

**MateClaw's agent runtime is not a class hierarchy.** There's no `BaseAgent` → `ReActAgent` → `MyCustomAgent` inheritance chain. The runtime is a **StateGraph** (from `spring-ai-alibaba-graph`) composed of nodes and conditional edges, assembled at runtime by `AgentGraphBuilder`.

### Key pieces

- `agent/graph/StateGraphReActAgent.java` — assembles the ReAct loop
- `agent/graph/plan/StateGraphPlanExecuteAgent.java` — assembles the Plan-and-Execute graph
- `agent/graph/node/` — `ReasoningNode`, `ActionNode`, `ObservationNode`, `FinalAnswerNode`, `SummarizingNode`, `LimitExceededNode`, `GoalEvaluationNode`
- `agent/graph/plan/node/` — `PlanGenerationNode`, `StepExecutionNode`, `PlanSummaryNode`, `DirectAnswerNode`
- `agent/graph/edge/` + `plan/edge/` — dispatcher functions that decide the next node based on state
- `agent/graph/state/MateClawStateKeys.java` — the keys for the shared state object
- `agent/graph/state/MateClawStateAccessor.java` — typed accessor for the state map (don't touch the map directly)
- `agent/graph/lifecycle/ReActLifecycleListener.java` — node-level instrumentation hooks
- `agent/AgentGraphBuilder.java` — the builder that wires nodes and edges per agent config
- `agent/GraphEventPublisher.java` + `agent/graph/NodeStreamingChatHelper.java` — how streaming events escape the graph into the SSE stream

### How to extend

**Adding agent behavior** — create a new node in `agent/graph/node/` or a new edge dispatcher in `agent/graph/edge/`. Wire it into `AgentGraphBuilder`. Read and write state through `MateClawStateAccessor`.

**Don't** create a new `XxxAgent` class. You'll be reimplementing what the graph already does.

### Goal-evaluation node (1.4.0+)

The graph (both ReAct and Plan-Execute) now runs a `GoalEvaluationNode` after `FinalAnswerNode` has streamed the final answer: it scores how completely the goal was met and can optionally inject an auto-followup message to keep pushing any unmet goals forward.

### Other 1.4.0 runtime changes

- **Progressive tool/skill disclosure** — a tool-disclosure layer splits tools into core and extension tiers; `enable_tool` / `load_skill` let an employee activate extension tools / load skills on demand, keeping the system prompt small.
- **Multi-level subagent delegation** — parent-to-child delegation is recursive and depth-capped, forming a tree; child-graph events are relayed back to the root conversation in real time.
- **ChannelToolProvider SPI** — channels (e.g. Feishu) can expose platform capabilities directly as agent tools without a separate MCP server.
- **Workspace RBAC** — capabilities are resolved from a backend role→capability mapping that gates both REST endpoints and frontend routes/menus.

### Shared state keys

| Key | Purpose |
|-----|---------|
| `USER_MESSAGE` | Current user input |
| `MESSAGES` | Conversation messages loaded from `mate_message` |
| `OBSERVATION_HISTORY` | Tool call results in this turn |
| `CURRENT_ITERATION` | How many loops have happened |
| `MAX_ITERATIONS` | The ceiling |
| `TOOL_CALLS` | Current tool call list |
| `AWAITING_APPROVAL` | Set true when a call needs human approval |
| `FINAL_ANSWER` | The agent's response |
| `FINISH_REASON` | Why the graph ended |

---

## Data flow — a single turn

```
1. POST /api/v1/chat?agentId={id}   (or POST /api/v1/chat/stream with agentId in the body)
        ↓
2. ChatController.sendMessage()
        ↓
3. ConversationManager.loadOrCreate(conversationId)
        ↓
4. AgentGraphBuilder.build(agentEntity)   ← resolves the compiled graph
        ↓
5. graph.invoke(initialState)              ← StateGraph execution begins
        ↓
   ReasoningNode → Dispatcher → ActionNode → ObservationNode → (loop or finish)
        ↓
6. Tool calls go through:
   ToolRegistry.resolve() →
   Tool Guard rule evaluation →
   (if approval needed) mate_tool_approval row + SSE event + AWAITING_APPROVAL=true
   (if approved inline) ToolExecutionExecutor.execute() → observation
        ↓
7. Segments stream to the client via:
   GraphEventPublisher → NodeStreamingChatHelper → SSE stream
        ↓
8. On completion:
   FinalAnswerNode aggregates result
   ConversationManager persists segments to mate_message
   ConversationCompletedEvent published (async memory extraction kicks in)
        ↓
9. Response closes
```

---

## Extension points

These are the SPIs and plugin points you can build on:

### `@Tool`-annotated Spring beans

Write a `@Component` with `@Tool` methods. It's picked up by `ToolRegistry` on startup. Every `@Tool` method becomes a callable tool.

```java
@Component
public class MyCustomTool {
    @Tool(description = "What the LLM sees")
    public String doThing(@ToolParam(description = "...") String input) {
        return "result";
    }
}
```

### `ChannelAdapter` SPI

Implement `vip.mate.channel.ChannelAdapter` (or `StreamingChannelAdapter` for streaming). Register as a Spring bean. Add webhook endpoints via `ChannelWebhookController`. See [Channels](./channels).

```java
public interface ChannelAdapter {
    void onMessage(ChannelMessage message);
    void sendMessage(String channelId, String content);
    String getChannelType();
}
```

### `MemoryProvider` SPI

Implement `vip.mate.memory.spi.MemoryProvider` to plug in a custom memory backend (vector, graph, external service). Multiple providers can be stacked per agent. See [Memory](./memory).

### MCP servers

Connect external tool servers over stdio, streamable_http, or sse. Their tools appear in the tool registry automatically — Agent code doesn't know they're external. See [MCP](./mcp).

### Skill packages

Bundle instructions + tools + optional scripts in a `SKILL.md`. Upload via the UI or API. Agents can invoke them at runtime. See [Skills](./skills).

### Agent graph nodes and edges

For deeper customization, add a new node in `agent/graph/node/` or a new dispatcher in `agent/graph/edge/`. Wire it into `AgentGraphBuilder` behind a config flag. State access goes through `MateClawStateAccessor`.

---

## Persistence — one schema, two databases

MateClaw uses **MyBatis Plus** (not JPA) for database access. Conventions:

- All tables prefixed `mate_`
- `snake_case` columns, `camelCase` Java fields, auto-mapped
- Every table has `create_time`, `update_time`, `deleted` (logical delete)
- **Flyway** manages schema migrations — `db/migration/h2/` and `db/migration/mysql/` hold dialect-specific scripts, auto-selected on startup
- `FlywayRepairConfig` runs `repair()` before `migrate()` on every boot, self-healing checksum drift and partially-failed migrations
- Seed data loaded by `DatabaseBootstrapRunner` from `db/data-*.sql`, idempotent

### Table groups

**Identity & config** — `mate_user`, `mate_system_setting`, `mate_model_config`, `mate_model_provider`, `mate_datasource`, `mate_mcp_server`

**Agents & planning** — `mate_agent`, `mate_agent_skill`, `mate_agent_tool`, `mate_plan`, `mate_sub_plan`

**Conversation** — `mate_conversation`, `mate_message`, `mate_channel`, `mate_channel_session`

**Tools & approval** — `mate_tool`, `mate_tool_approval`, `mate_tool_guard_rule`, `mate_tool_guard_config`, `mate_tool_guard_audit_log`

**Skills & workspace** — `mate_skill`, `mate_workspace`, `mate_workspace_member`, `mate_workspace_file`

**Knowledge & memory** — `mate_wiki_knowledge_base`, `mate_wiki_raw_material`, `mate_wiki_page`, `mate_wiki_transformation`, `mate_wiki_transformation_run` (1.3.0+), `mate_memory_recall`

**Workflow & triggers (1.3.0+)** — `mate_workflow`, `mate_workflow_revision`, `mate_workflow_run`, `mate_workflow_step_run`, `mate_workflow_payload`, `mate_trigger`, `mate_trigger_event`

**Ops** — `mate_cron_job`, `mate_cron_job_run`, `mate_async_task`, `mate_usage_daily`, `mate_audit_event`, `mate_doctor_check`

---

## Streaming — why SSE, not WebFlux

MateClaw uses **Spring MVC**, not Spring WebFlux. WebFlux is explicitly excluded from the dependency graph.

Why: Spring MVC + SSE is sufficient for streaming LLM responses to the frontend. It's simpler to reason about, easier to debug, and doesn't force the whole stack to become reactive.

::: tip Virtual threads (JDK 21)
`spring.threads.virtual.enabled=true` is on. Tomcat request threads, `@Scheduled` tasks, and `@Async` methods all run on virtual threads. SSE long-connections no longer hold platform threads — concurrent connection count is no longer limited by thread pool size.
:::

Streaming flow:

1. Client `POST /api/v1/chat/stream` with `agentId` / `message` / `conversationId` in the JSON body and `Accept: text/event-stream` in the headers
2. Controller returns `SseEmitter`
3. Agent graph runs on a worker thread; node execution emits events to `GraphEventPublisher`
4. Events serialize into SSE format and write to the emitter
5. `ChatStreamTracker` watches for abandoned streams and cleans them up

The same SSE pattern is reused by channel adapters that support streaming (DingTalk AI Card, Web).

---

## Frontend architecture

**Vue 3 + TypeScript + Composition API + `<script setup>`** everywhere. Key pieces:

- **`src/views/`** — page components
- **`src/stores/`** — Pinia stores, domain-driven (each store owns its slice exclusively)
- **`src/composables/`** — Composition API helpers (chat streaming lives here, not in a global store)
- **`src/api/`** — Axios instance + endpoint definitions
- **`src/router/`** — Vue Router with auth guards and role-based hiding
- **`src/i18n/`** — `zh-CN.ts` and `en-US.ts` translations
- **`src/assets/main.css`** — the `--mc-*` CSS design tokens (source of truth for theming)

See [Admin Console](./console) for per-page details and [Contributing](./contributing) for frontend conventions.

---

## Three delivery surfaces

Same backend JAR, three different ways to ship it:

1. **Web** — run the JAR directly, open a browser at `http://localhost:18088`. The frontend is embedded in the JAR's `static/`.
2. **Desktop** — Electron shell in `mateclaw-desktop/` bundles JRE 21 and the JAR. Users never install Java. Auto-update via electron-updater.
3. **Docker** — `docker-compose.yml` with MySQL. Production deployment.

The webchat widget in `mateclaw-webchat/` is a fourth path — an embeddable chat UI you can drop into any website. It talks to the same backend APIs.

---

## Request layering

```
   HTTP Request
        │
        ▼
   Spring Security Filter  ── JWT validation, sliding-window renewal
        │
        ▼
   Role-based Access Filter  ── workspace permissions
        │
        ▼
   Controller                ── @RestController
        │
        ▼
   Service                   ── business logic
        │
        ▼
   Mapper (MyBatis Plus)     ── SQL
        │
        ▼
   Database (H2 / MySQL)
```

Each layer has a single responsibility. Cross-layer concerns (audit logging, metrics) are implemented as AOP aspects or Spring Boot Actuator endpoints.

::: tip Spring AI Observability
Spring AI's built-in Micrometer Observation is enabled — every LLM call automatically records `gen_ai.client.operation` (latency) and `gen_ai.client.token.usage` (input/output tokens). View via `/actuator/metrics/gen_ai.*`. Prompt content is never leaked into spans (`log-prompt=false`).
:::

---

## Next

- [Introduction](./intro) — the "why" before the "how"
- [Agents](./agents) — StateGraph deep-dive
- [Contributing](./contributing) — conventions for adding code
- [API Reference](./api) — REST surface the backend exposes
