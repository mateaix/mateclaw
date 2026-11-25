# Changelog

## Unreleased: dev / 3.2.0-SNAPSHOT

These development-branch additions do not change the published 3.1.0 scope or declare a new stable release:

- **Task briefings and member mentions:** stable objective/description/acceptance-criteria/constraints snapshots for new tasks and retries, manual briefing fields, and a lead-chat member strip with scoped `@` preferences. Existing tasks remain compatible; no new database migration.
- **Reusable employee configuration:** same-workspace native snapshots, selected shared configuration documents, recruitment under a new name and template deletion preserve three-state bindings. Custom recruitment validates dependencies strictly; builtin compatibility is unchanged. DSH snapshots and cross-workspace export are not supported.
- **Coordinator mode:** opt-in native lead restrictions cover normal tools, dynamic bridges, direct delegation from persisted plans, and primary/fallback/summary hosted search. Team-board orchestration and saved bindings are retained; mode changes wait for active lead turns to finish.
- **Team access and roster governance:** direct team chat, single-team membership, unfinished-task deletion protection, lead deletion protection and assignee validation when retrying failed history.
- **Upgrade:** V205 widens task status for `awaiting_approval`; V206 adds custom employee snapshot storage. H2, MySQL and Kingbase migrations are included. Back up database and configuration first; do not bypass validation by editing migration history manually.

[Employee templates](./agents) · [Teams and coordination](./teams) · [FAQ](./faq)

## 3.1.0: skill lesson governance and execution reliability

Version: **3.1.0**. Release date: **2026-11-19**. These notes cover features and fixes after `v3.0.0`; the [3.0.0 execution architecture upgrade](https://claw.mate.vip/docs/en/releases/3.0.0) remains a historical record. Published packages are listed on [GitHub Releases](https://github.com/mateaix/mateclaw/releases).

3.1.0 makes learned skill lessons easier to inspect and withdraw, presents chat, tool and channel outcomes more accurately, and improves long-context, historical-media and failure recovery.

### Skill lessons and memory

- **Withdraw individual lessons:** durable identities identify the exact lesson being withdrawn instead of relying on its display position. Market listings and lesson panels refresh after changes.
- **Preview snapshot restoration:** inspect the skill changes and their impact before confirming a restore.
- **Trustworthy review results:** incomplete scans cannot be treated as successful reviews; results refresh after review actions.
- **Cancellable installation:** skill installation runs asynchronously with cancellation and completion managed by task lifecycle.
- **Attachment integrity:** binary attachments survive bundle imports. The `x-intel` installation source is pinned and remote-shell installation is removed (#685).
- **Precise memory edits:** untouched sections remain intact. Asynchronous saves and deletions stay bound to the memory object selected when the operation began, preventing edits from leaking across selections or conversations.

Reflection and routine mining remain disabled by default and require explicit workspace-policy configuration. Lesson governance changes do not automatically expand skill execution permissions.

### Chat status and tool results

- Quote selected answer text into follow-up drafts. Browser titles and favicons reflect conversation and run status.
- Shell tools show short intent titles without changing command arguments. Failed commands use actual execution outcomes, including correct rendering of historical failures.
- Native-loop tool events publish once. Fragment message lists retain a DOM container for rendering and positioning (#683).
- Output-limited answers retain incomplete status instead of appearing successful. Stopped native streams preserve token usage already reported by the provider.
- Usage lists show recent dates first, and language guidance stays consistent across user-visible responses (#679).

### Models, context and execution recovery

- Validate generation parameters before saving model/provider configuration. Support Azure unified OpenAI endpoints while preserving required endpoint query parameters.
- Bound concurrent requests through shared provider limits and clarify busy/cooldown failures. Failover uses the actual fallback model identity and provider telemetry (#676).
- Unify context assembly and preserve compacted history. Attempt recovery from local context overflow before rejecting history (#684).
- Downscale oversized images and filter empty media in request copies. Oversized requests can omit historical media while persisted original attachments remain intact.
- Bound skill overviews/discovery and retain verification evidence. Raise the default iteration budget from 10 to 100 for newly created employees, retain existing employee settings, and explain capped runs (#672, #674).
- Honor tool timeout overrides and abort unfinished plan steps. Recover plan summaries and surface finalization failures (#672, #681).
- Enforce cumulative team-dispatch budgets across Lead retries rather than granting a fresh full budget on every retry (#688).
- Harden H2 interrupted-write recovery and authentication-session persistence (#681).

### Wiki, documents and channels

- **Per-source entity extraction:** extract entities from a selected document in the Wiki raw-material panel (#673).
- **Consistent answers and citations:** preserve answers and reconcile citations across retrievals. Recover interrupted document analysis and keep empty child sessions out of ordinary history (#683, #686).
- **Document delivery:** continue reasoning after DOCX generation instead of treating tool completion as task completion (#687). Preserve Markdown table values, handle export edge cases, and retain file newline styles during text edits.
- **Message delivery:** split long DingTalk Markdown and validate delivery acknowledgements. Preserve readable Feishu attachment names. Prevent proxy buffering on Team and A2A event streams.

### DeepSeek Harness, desktop and deployment

DSH adds the optional **`0.2.2-rc.1`** candidate, requiring **Node.js 24.9+ (24.x)**. The managed-install default remains **`0.2.0-rc.1`**. Updating MateClaw does not automatically switch an existing DSH configuration to the candidate.

Candidate upgrades retain runtime homes, configuration and rollback generations, restore file observation guards, and bind configured model capacities. Installation, SDK handshake, real chat and Bash checks passed in native mode on macOS arm64. These checks do not cover Linux/Windows, persisted V3→V4 history migration, multi-hour endurance or production rollback. The macOS Docker shared-directory Landlock `EACCES` limitation remains; see [DSH integration](./deepseek-harness).

Desktop fixes harden multi-platform packaging and macOS signing/notarization of native binaries inside the server JAR. Publishing signed installers still depends on the corresponding platform artifacts, certificates and validation results.

Existing managed deployments gain offline-upgrade preflight backups, trial migrations and search-health checks, with backup-symlink and documentation-route fixes. These are deployment-specific operational improvements; availability and migration scope follow the deployment plan.

### Dependency versions

| Component | 3.0.0 | 3.1.0 |
| --- | --- | --- |
| Java / desktop JRE | 21 | 21 |
| Spring Boot | 4.1.1 | **4.1.2** |
| Spring AI | 2.0.1 | **2.0.2** |
| AgentScope Java | 2.0.4 | **2.0.5** |
| MyBatis Plus | 3.5.17 | **3.5.18.1** |

AgentScope 2.0.5 supplies stable core fixes with regression checks for trailing-JSON rejection and string round trips. The synchronous Anthropic adapter also selects the correct generation so final text and tool calls are retained. Application code still owns model requests, approvals, permissions, memory, compaction and persistence. No beta Harness is introduced, and unused SDK queues/JDBC/Redis stores require no migration.

### Upgrade and validation

1. Back up the database, workspace, plugins, `.env` / `.env.local`, and DSH runtime directories. Retain the JWT secret to keep existing login tokens valid.
2. For source development, install `mateclaw-plugin-api` first, then start the backend from the repository root with explicit root `data/` database/workspace paths as shown in [Contributing](./contributing). Start the frontend in `mateclaw-ui/`.
3. Keep Flyway validation enabled and do not edit applied migrations. This dependency upgrade does not require changing database engines. An existing MySQL deployment cannot use PostgreSQL Compose as an automatic data migration.
4. Verify model readiness, text answers, tool outcomes, history, cancellation/recovery and the channels you use. Roll back with the pre-upgrade backup and its matching application version.

Dependency-upgrade acceptance completed **6,994 backend/Mem0 tests with zero failures and 64 conditional skips**; **518 frontend tests** and the production build passed. Real-model checks covered text, one Bash execution, history continuity, stream cancellation, same-session recovery and message persistence. Skips require external databases, dedicated DSH environments or platform conditions and do not certify those environments.
