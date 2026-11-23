# Changelog

## Unreleased

- Documentation: align contribution branches with `dev`, clarify deployment backups, Flyway validation and rollback, refresh user-guide/README links, and document verified private-to-public synchronization.
- Website quick start: explicitly build the Docker services on first startup.

## 3.1.0 — 2026-11-19

Changes after v3.0.0:

- Skills: withdraw individual lessons with durable identities, preview snapshot restoration, refresh reviews and fail closed on incomplete scans; cancellable installation and intact binary attachments.
- Memory/chat: preserve exact sections and isolate asynchronous edits, quote selected answers, show browser run status, render command intent and real shell failures, and retain stopped-run usage.
- Runtime/models: recover context overflow and oversized media, validate generation parameters, bound provider concurrency and team retries, preserve timeout overrides, plan summaries and H2 authentication state.
- Wiki/documents/channels: per-document entity extraction, consistent citations, continued reasoning after DOCX generation, correct table/newline handling, DingTalk delivery acknowledgements, Feishu filenames and unbuffered Team/A2A streams.
- DSH: optional 0.2.2-rc.1 on Node.js 24.9+ (24.x); default remains 0.2.0-rc.1. Desktop packaging/signing hardened.
- Dependencies: Spring Boot 4.1.2, Spring AI 2.0.2, AgentScope Java 2.0.5 and MyBatis Plus 3.5.18.1. Fix synchronous Anthropic final-answer/tool-call selection.

[English release and upgrade notes](mateclaw-server/src/main/resources/docs/en/changelog.md) · [中文更新与升级说明](mateclaw-server/src/main/resources/docs/zh/changelog.md)

## 3.0.0 — 2026-10-26

3.0.0 upgrades to Java 21, Spring Boot 4 and Spring AI 2. AgentScope 2 drives ReAct and plan-step execution loops; MateClaw retains approvals, permissions, Goals, SSE and persistence.

Release date: **2026-10-26**. These notes cover the 3.0.0 stable release. Source and package versions are `3.0.0`; download published packages from [GitHub Releases](https://github.com/mateaix/mateclaw/releases).

- MyBatis Plus 3.5.17 with the Boot 4 starter; MCP SDK 0.18.3.
- Native DashScope chat/embedding adapters; native speech SDK retained.
- Owned HTTP cancellation, guarded tool batches and durable Goal checkpoints prevent blind side-effect replay.
- Existing frontend interaction contracts and conversation history remain supported.
- Shared prompt budgets inherit model defaults and count effective tool schemas plus one output reserve; AgentScope remains on stable 2.0.4.
- Latest budget regression: 342 focused tests and 6,509 full-suite tests (50 skipped) with no failures/errors. Real-model acceptance: 18/20 after manual review; tool argument and progress-count failures remain documented in the detailed notes.

[English details](mateclaw-server/src/main/resources/docs/en/changelog.md) · [中文说明](mateclaw-server/src/main/resources/docs/zh/changelog.md)

## Finalization, team delivery and deployment

- Long tasks check completion before returning a final answer and can continue unfinished work within runtime limits. Requests that need user input wait for a reply instead of repeatedly starting action turns (#668).
- When reasoning consumes the response output budget, final-answer generation can retry within runtime limits. DeepSeek Flash thinking controls are applied consistently (#672).
- Team worker progress retains full result evidence and runtime failure causes. A Team Run publishes completion after final synthesis, keeping worker output, progress and the delivered summary consistent.
- DSH keeps its reviewed default SDK version; `0.2.1-rc.2` is an opt-in candidate. Docker runtime images pin Node.js 22 and include bubblewrap for Linux sandbox execution (#670). Candidate checks on macOS arm64 do not establish Linux or Windows acceptance; see [DSH setup and limitations](mateclaw-server/src/main/resources/docs/en/deepseek-harness.md).
- Every backend requires a non-empty `JWT_SECRET` of at least 32 UTF-8 bytes. Desktop local mode generates and persists its secret; source and server deployments must configure and retain theirs. See [configuration](mateclaw-server/src/main/resources/docs/en/config.md).

## Cumulative changes since 2.3.0

3.0.0 includes the former 2.4.0 development cycle; 2.4.0 was not published as a separate stable release. The groups below cover capabilities added before the architecture migration as well.

### Workspaces and conversations

- Member file isolation is an opt-in strict sandbox. With `MATECLAW_WORKSPACE_SANDBOX_MEMBER_ISOLATION_ENABLED=true`, authenticated user and workspace identities determine the file root; missing identity denies access without shared-directory fallback. Only six audited builtin file/code tools are available; DSH, MCP/plugin tools, shared skill scripts and unauthenticated channel identities are unavailable. This differs from personal memory-file fallback. (#644).
- Persistent channel conversations expire stale query context. Uploaded images resolve from conversation storage; directory uploads retain directory metadata and validate containment.
- Employee drafts accept JSON following reasoning blocks, and explicit tool opt-out is respected even for general capabilities (#648, #655).

### Structured decisions, Goals, plans and teams

- A bounded typed Choice / Boolean / Score decision pipeline adds durable audit, OFF / SHADOW / ACTIVE modes and scenario overrides. SHADOW is the default; suggestions cannot bypass domain constraints.
- Goal continuation, plan employee routing and team worker-result judgment share the decision foundation. Domain transactions commit outcomes and state; delayed decisions validate identity and fences.
- Richer routing context, worker contracts, delegated-step result checks and bounded Goal recovery retain existing completion gates and record applied follow-up suppression.

### Models and DeepSeek Harness

- DSH moves to the SDK runtime with pinned versions and controlled upgrades. Failed health checks invalidate cached health and broken settings remain editable (#650).
- Model probes validate completed responses rather than accepting connection success or empty output (#658).

### Read-aloud, chat and tool presentation

- Edge and DashScope read-aloud are restored, with provider voice lists, selection and preview (#646).
- Structured MCP results get dedicated presentation below the final answer. Ordinary tool results no longer automatically become final-answer text (#651, #656, #661).

### Channels and database operations

- Application-owned Feishu WebSocket readiness and lifecycle release owned resources on shutdown and reconnect (#659).
- Unsupported webhook channels are rejected before configuration persistence (#654).
- Flyway validation failures block startup by default; history repair requires explicit opt-in, with backup and recovery guidance (#657).
- Commercial-edition overview and links are added; commercial modules are not included in the open-source edition.
