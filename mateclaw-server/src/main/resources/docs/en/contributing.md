# Contributing

**Code you write here runs on other people's machines.**

That's the only thing to remember. MateClaw is Apache 2.0, self-hosted, and shipped as a single JAR. Every line you add gets downloaded, unpacked, and executed by someone you'll never meet. Write the code you'd be happy to find yourself, six months from now, in someone else's logs at 2 AM.

---

## Getting started

### 1. Fork and clone

```bash
git clone https://github.com/YOUR_USERNAME/mateclaw.git
cd mateclaw
```

### 2. Start the backend

```bash
# Java 21 + Maven; run from the repository root.
mvn -pl mateclaw-plugin-api -am install -DskipTests
[ -f .env.local ] || (umask 077; printf 'JWT_SECRET=%s\n' "$(openssl rand -hex 48)" > .env.local)
set -a
source .env.local
set +a
# Use the configured root database and workspace
SPRING_DATASOURCE_URL="jdbc:h2:file:$PWD/data/mateclaw;MODE=MySQL;DATABASE_TO_LOWER=TRUE;CASE_INSENSITIVE_IDENTIFIERS=TRUE" \
MATECLAW_WORKSPACE_SANDBOX_ROOT="$PWD/data/workspace" \
mvn -pl mateclaw-server spring-boot:run
```

Use the repository-root `data/` directory; starting against `mateclaw-server/data/` creates a separate database without your configured models. Backend starts on port 18088. H2 console at `/h2-console`, Swagger UI at `/swagger-ui.html`.

::: tip
Model configuration is **UI-driven** — no need to set `DASHSCOPE_API_KEY` as an env var to start. Log in, go to `Settings → Models`, add a provider there.
:::

### 3. Start the frontend

```bash
cd mateclaw-ui
npm install
npm run dev -- --host 127.0.0.1
```

Frontend on port 5173, proxies `/api` to the backend. Keep the JWT secret in `.env.local` across restarts and never commit that file.

### 4. Verify

Open [http://localhost:5173](http://localhost:5173). Log in with `admin` / `admin123`. Add a model in `Settings → Models`. Send a test message. If tokens stream back, you're ready.

---

## Development workflow

```bash
# 1. Make a feature branch from main
git checkout -b feat/your-feature-name

# 2. Work in small, meaningful commits
git add path/to/changed-file
git commit -m "feat(scope): what you changed"

# 3. Keep up to date with upstream
git fetch upstream
git rebase upstream/main

# 4. Push and open a PR
git push origin feat/your-feature-name
```

---

## Branch naming

| Prefix | Purpose | Example |
|--------|---------|---------|
| `feat/` | New feature | `feat/voice-input` |
| `fix/` | Bug fix | `fix/sse-reconnect` |
| `docs/` | Documentation | `docs/api-reference` |
| `refactor/` | Refactoring | `refactor/agent-state` |
| `chore/` | Build, deps, tooling | `chore/upgrade-spring-boot` |
| `test/` | Test-only changes | `test/add-approval-coverage` |

---

## Commit messages

[Conventional Commits](https://www.conventionalcommits.org/) format:

```
type(scope): brief description

Optional longer description explaining what and why.
```

Examples:

```
feat(agent): add max iteration limit to ReAct loop
fix(channel): handle DingTalk message encoding correctly
docs(tools): add examples for WebSearchTool
chore(deps): upgrade Spring Boot to 3.5.1
```

**Write commit messages about the change, not the code.** "add retry logic to WebSearchTool" is useful. "update WebSearchTool.java" is not.

---

## Backend conventions

### Package layout

Put new code in the right `vip.mate.*` package:

| You're adding... | Package |
|------------------|---------|
| A new tool | `vip.mate.tool` |
| A new channel adapter | `vip.mate.channel` |
| A new agent graph node | `vip.mate.agent.graph.node` |
| A memory provider | `vip.mate.memory.spi` |
| A new Wiki feature | `vip.mate.wiki` |
| Utility code | `vip.mate.common` |

### Code style

- **Java 21 features encouraged** — records, sealed classes, text blocks, pattern matching, `var` for obvious local types
- **Constructor injection**, not field injection
- **Naming**: `XxxService`, `XxxController`, `XxxMapper`, `XxxEntity`
- **Database**: MyBatis Plus, not JPA. `mate_` prefix. camelCase Java fields → snake_case columns.
- **Logical delete** via `deleted` column
- **Every table** needs `create_time`, `update_time`, `deleted`

### Native loop, application policies

AgentScope owns the outer ReAct loop, while `ExplicitPlanRuntime` owns plan phases. Business policy remains in application callbacks wired by `AgentGraphBuilder`. When adding behavior, work with:

- **A node** (reasoning, action, observation, plan generation) — in `vip.mate.agent.graph.node` or `vip.mate.agent.graph.plan.node`
- **An edge** or **dispatcher** — in `vip.mate.agent.graph.edge` or `vip.mate.agent.graph.plan.edge`
- **A state key** — in `vip.mate.agent.graph.state.MateClawStateKeys`

The builder that wires it up is `AgentGraphBuilder`. Streaming events from nodes go through `GraphEventPublisher` and `NodeStreamingChatHelper`.

### Adding a new tool

```java
@Component
public class MyNewTool {

    @Tool(description = "Clear description for the LLM")
    public String myMethod(
            @ToolParam(description = "What this parameter controls") String input) {
        // Implementation
        return "result";
    }
}
```

- Spring `@Component`
- Every `@Tool` method becomes a callable tool
- Use `@ToolParam` on every parameter — this is the LLM description
- **If the tool is dangerous, add a Tool Guard rule for it**

### Adding a new channel

1. Create a class in `vip.mate.channel` implementing `ChannelAdapter` (or `StreamingChannelAdapter` for streaming)
2. Register webhook endpoint in `ChannelWebhookController`
3. Add config properties
4. Update `docs/en/channels.md` and `docs/zh/channels.md` with integration steps

### Adding a new memory provider

1. Create a class in `vip.mate.memory.spi` implementing `MemoryProvider`
2. Register as a Spring bean
3. Add configuration under `mate.memory.providers.{name}`
4. Add tests

### External plugins after the runtime upgrade

Rebuild external plugins against the current `mateclaw-plugin-api` and Java 21. Plugins that call Spring AI provider APIs must migrate those calls to Spring AI 2 before rebuilding; removed provider DTOs and mutable setters are not binary compatible. Keep host-provided MateClaw API, Spring Boot and Spring AI libraries out of the plugin JAR. The compatibility bridge preserves defaults from legacy `ChatModel.getDefaultOptions()` implementations, but does not guarantee compatibility for older provider SDK bytecode. Test plugin loading, tool registration and a real invocation against the upgraded host before deployment.

MyBatis Plus uses the Spring Boot 4 starter at 3.5.17. Its Spring integration classes moved from `com.baomidou.mybatisplus.extension.spring` to `com.baomidou.mybatisplus.spring`; update imports such as `MybatisSqlSessionFactoryBean` when rebuilding code that uses those classes.

### SQL schema changes

Schema is managed by **Flyway**. New DDL goes in a fresh `V{next}__description.sql` file under `db/migration/h2/`, `db/migration/mysql/` and `db/migration/kingbase/` (shared by PostgreSQL and KingbaseES profiles). Each file must be compatible with its dialect (MySQL doesn't support `ADD COLUMN IF NOT EXISTS` — use an `INFORMATION_SCHEMA` guard; H2 supports it natively).

Migration startup validates applied checksums by default. The runtime upgrade corrects MySQL V192 to update its temporary merge table through one JOIN; MySQL 8 cannot reopen that table across several subqueries. Back up existing databases before upgrading. An already-applied V192 requires a reviewed checksum repair; a failed nontransactional migration may also retain cleanup changes and a failed history record. `mateclaw.flyway.auto-repair=true` is an explicit operator opt-in for repair before migration. Inspect the affected history and script first, then return the setting to its default `false`; never enable repair routinely to hide drift. Test both a fresh database and an upgraded copy. Application rollback requires the matching prior database backup when migration changes cannot be reversed.

Before switching runtime versions, stop scheduled Goal/Cron dispatch and new conversation turns, drain active callbacks, and back up the database and workspace together. Reconcile attempts with `tool_outcome_uncertain` or an unfinished tool-entry checkpoint before enabling the prior runtime: older settlement code does not enforce the new unknown-outcome guard. Restoring a database backup does not undo external tool effects. Keep uncertain work blocked until its external result is established; do not resume it by clearing checkpoint fields. Validate the restored copy, approval records and conversation history before reopening dispatch.

Seed data is loaded by `DatabaseBootstrapRunner` from `db/data-*.sql` — idempotent (`INSERT ... ON DUPLICATE KEY UPDATE` / `MERGE INTO`).

---

## Frontend conventions

### Code style

- **Composition API with `<script setup>`** for all new components
- **TypeScript required** — no `any` unless absolutely necessary
- **Pinia stores** for shared state, local `ref`/`reactive` for component state
- **Element Plus** preferred over custom implementations
- **TailwindCSS** utility classes; avoid inline styles
- **Path alias** `@` → `src/`
- **Design tokens** in `src/assets/main.css` (`--mc-*` CSS variables) — don't hardcode colors

### State ownership

Each Pinia store owns its domain's state **exclusively**. External code calls store actions — it does not mutate state directly.

```typescript
// Correct
agentStore.fetchAgents()
themeStore.setMode('dark')

// Wrong
agentStore.agents = []         // Don't
```

### Adding a new page

1. Create the view in `src/views/`
2. Register the route in `src/router/index.ts`
3. Add translations in both `src/i18n/zh-CN.ts` and `src/i18n/en-US.ts`
4. Create a Pinia store in `src/stores/` if you need shared state
5. Add the page to the sidebar in `src/views/layout/MainLayout.vue`

### Component structure

```vue
<script setup lang="ts">
import { ref, onMounted } from 'vue'
import { useAgentStore } from '@/stores/useAgentStore'

const agentStore = useAgentStore()
const loading = ref(false)

onMounted(async () => {
  loading.value = true
  await agentStore.fetchAgents()
  loading.value = false
})
</script>

<template>
  <div class="p-4">
    <el-table :data="agentStore.agents" v-loading="loading">
      <!-- columns -->
    </el-table>
  </div>
</template>
```

---

## Testing

### Backend tests

```bash
cd mateclaw-server
mvn test                                  # All tests
mvn test -Dtest=NativePolicyDriverTest  # Single class
mvn test -Dtest=NativePolicyDriverTest#toolRoundUsesOneBatchAndDetachedApplicationHistory  # Single method
```

### Frontend type check and lint

```bash
cd mateclaw-ui
npm run build       # vue-tsc type check + vite build
npm run lint        # ESLint with auto-fix
```

### Manual test checklist

- [ ] Backend starts without errors
- [ ] Frontend builds without type errors (`npm run build`)
- [ ] Login works with default credentials
- [ ] Model configured via UI
- [ ] Chat streams a response back
- [ ] New feature works as described
- [ ] No console errors
- [ ] Docs updated if user-facing behavior changed

---

## Documentation changes

If your PR changes user-facing behavior — a new feature, a renamed endpoint, a changed config key — **update the docs in the same PR**.

The docs live in `docs/`. Pick the relevant page and update both `docs/en/` and `docs/zh/`. The Chinese and English versions are **independently written**, not translations — match tone and style with the existing page.

```bash
cd docs
npm run build
```

Build must succeed with zero errors before you open the PR.

---

## Pull request process

1. **Title** — conventional commit format
2. **Description** — what, why, how; link issues
3. **Screenshots** — for UI changes, before/after
4. **Testing** — describe how you tested
5. **Breaking changes** — note clearly at the top

### PR template

```markdown
## What

Brief description of the change.

## Why

Why this change is needed (link issue).

## How

Technical approach.

## Testing

How this was tested.

## Screenshots (if UI changes)

Before / After.
```

---

## Reporting issues

When filing a bug:

- MateClaw version (or commit hash)
- Java version and OS
- Exact steps to reproduce
- Expected vs. actual behavior
- Relevant log output

Good bug reports get good fixes.

---

## Next

- [Quick Start](./quickstart) — setup walkthrough
- [Introduction](./intro) — architecture overview
- [Architecture](./architecture) — native runtime guide for developers
- [Roadmap](./roadmap) — what we're working on next
