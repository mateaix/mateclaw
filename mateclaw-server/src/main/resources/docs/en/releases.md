# Changelog

Release notes for every MateClaw version. The latest documentation always lives in `docs/en/` as the single source of truth — these notes are about what *changed* in each version.

For historical diffs, check the corresponding git tag. For the "why" behind a feature, follow the link into the full release note.

---

## Releases

| Version | Date | Highlights |
|---------|------|------------|
| [v1.7.0](./releases/1.7.0) | 2026-07-04 | Productionization pass — all three approval paths close the loop (workflow approval channel notify + resolve→resume bridge · WebChat/API-Key channel approve+replay · Feishu/WeCom card-click resolves workflow approvals) · Long tasks are visible ("Run Overview" rail + per-turn token breakdown incl. cache hit/miss/write + sub-agent cost rolled up + one-click generated-file download) · Fits the real model window (local-model context-window probing + unified token budget for prefix injection + small-context degradation + tool-schema budget gate) · Opens up (KB / Deep Research open API with API-key+rate-limit+SSE · pluggable search Provider SPI · MCP identity forwarding) · Desktop remote-server connection + `mateclaw-desktop` source open-sourced + LAN deployment mode · One-click operational data export (Dashboard 9-sheet Excel + CLI) · Wiki processing-failure visibility · Per-employee model chain · Debuggable OpenAPI/Swagger |
| [v1.6.0](./releases/1.6.0) | 2026-06-22 | Runs on domestic databases — KingbaseES (人大金仓) + PostgreSQL (one shared PostgreSQL-family migration tree · opt-in Kingbase driver · least-privilege Docker roles) · New senses & hands (image kept in context across turns + `image_analyze` · `execute_code` runs agent-authored code) · You shape the employee (AGENTS.md editor + About You identity + runtime model identity + KB-scope binding + roster tags) · Wiki Sources tab (raw materials + watcher unified, per-KB auto-sync, multi-path/glob, pageType form editor) · Global outbound HTTP/SOCKS proxy · Deterministic Markdown answers · Claude Fable 5 |
| [v1.5.0](./releases/1.5.0) | 2026-06-04 | Goals grew a checklist — from "a score" to "ticked boxes" (checklist + Evaluator SPI + deterministic completion) · The Wiki learned to maintain itself (`[[wikilinks]]` + cascade rename/delete link-fix + broken-link lint · fact/experience layers + staleness propagation · pageType profiles & per-agent permissions · processing pipelines · local-directory knowledge source with scheduled incremental sync) · Per-owner memory isolation (owner_key + personal/team/global scopes + third-party endUserId passthrough) · Each employee binds a primary KB · Preferred provider drives the primary model + Claude Opus 4.8 |
| [v1.4.0](./releases/1.4.0) | 2026-05-23 | Persistent Goals — an employee locks a goal and follows it to done on its own · Subagent delegation became a tree (recursive 3 levels + async + digital-employee builder) · Progressive tool/skill disclosure (`enable_tool` + `load_skill`) · Workspace RBAC (4 roles + capability gating) · Feishu as a first-class citizen (interactive / approval / streaming cards + voice / file / audio / video + channel-native tools) |
| [v1.3.0](./releases/1.3.0) | 2026-05-13 | Year one of workflow — 7 step modes assemble employees into business processes · 6 trigger patterns make events drive workflows · Wiki promoted from search index to processing pipeline (user templates + cross-material aggregator + reverse citations) · Per-agent MCP tool binding + multimodal sidecar routing · 4 JVM-native document generation tools + image edit |
| [v1.2.0](./releases/1.2.0) | 2026-05-05 | Agents renamed "digital employees" (role / goal / backstory + 5 career templates) · Skills became the skeleton (manifest + template wizard + LESSONS self-evolution) · ACP integration: Claude Code / Codex now show up as your employees · Admin Runtime Console lets you see every employee working in real time |
| [v1.1.137](./releases/1.1.137) | 2026-04-29 | It learns from yesterday now · One bad model doesn't take the whole thing down · The "almost good" parts are good now · The knowledge base became a library you can open |
| [v1.1.0](./releases/1.1.0) | 2026-04-17 | Auto skill synthesis, multi-agent parallel delegation, Wiki semantic search + two-phase digest, deep thinking, Anthropic prompt caching, declarative hooks, plugin SDK, voice for every channel, ChatConsole multi-channel realtime sync, WeChat stability rebuild |
| [v1.0.418](./releases/1.0.418) | 2026-04-11 | Backend i18n, Flyway migration framework, WorkspacePathGuard sandbox, CronJobTool, Skill ZIP import, security hardening |
| [v1.0.314](./releases/1.0.314) | 2026-04-08 | LLM Wiki knowledge base, TTS/STT, music generation, image/video upgrades, search system with keyless fallback, ChatGPT OAuth login, agent runtime enhancements, database schema unification |
| [v1.0.108](./releases/1.0.108) | 2026-04-06 | Datasource SQL query, multimodal enhancements, desktop dynamic port, OpenRouter free models |
| [v1.0.101](./releases/1.0.101) | 2026-04-05 | Mobile layout, Ollama auto-detection on startup, model grouping, GitHub MCP, drag-and-drop file upload, multi-agent collaboration |
| [v1.0.0](./releases/1.0.0) | 2026-03-20 | Initial release — ReAct + Plan-Execute agents, 12 built-in tools, MCP protocol, 6 channel adapters, Vue 3 admin console |

---

## What to read next

- [Roadmap](./roadmap) — what's planned, what's in progress, what's done
- [Introduction](./intro) — why MateClaw exists
- [Contributing](./contributing) — how to help ship the next release
