# Configuration

**Three places to configure MateClaw: `application.yml`, environment variables, and the database.**

Most settings live in `application.yml` (Spring Boot's default config file), with sensitive values overridden by environment variables. Anything you want to change at runtime — model providers, search keys, feature toggles — lives in the `mate_system_setting` table and is edited through the Settings page.

Deep-dive topics have their own pages — Tool Guard rules in [Security & Approval](./security), model providers in [Models](./models), memory tuning in [Memory](./memory).

---

## Profiles

| Profile | Database | Activated by |
|---------|----------|--------------|
| `default` | H2 file at `./data/mateclaw` | No action needed |
| `mysql` | MySQL 8.0+ | `spring.profiles.active=mysql` or `SPRING_PROFILES_ACTIVE=mysql` |

Docker deployments activate `mysql` automatically. Desktop builds use `default`.

---

## Core `application.yml` sections

### Server

```yaml
server:
  port: 18088                    # HTTP port
  servlet:
    context-path: /
```

### Database — H2 (development)

```yaml
spring:
  datasource:
    url: jdbc:h2:file:./data/mateclaw;MODE=MYSQL
    username: sa
    password:
    driver-class-name: org.h2.Driver
  h2:
    console:
      enabled: true              # Available at /h2-console (disable in production)
```

### Database — MySQL (production)

```yaml
spring:
  profiles:
    active: mysql
  datasource:
    url: jdbc:mysql://localhost:3306/mateclaw?useSSL=false&serverTimezone=UTC
    username: root
    password: ${MYSQL_ROOT_PASSWORD}
    driver-class-name: com.mysql.cj.jdbc.Driver
```

### AI model — managed in the UI, not YAML

::: tip
**Model configuration is 100% UI-driven.** Don't put `spring.ai.*` blocks in `application.yml` — every provider, key, and model config lives in `Settings → Models`, backed by the `mate_model_provider` and `mate_model_config` tables.
:::

**LLM API keys are not read from environment variables** — `DASHSCOPE_API_KEY` / `OPENAI_API_KEY` / etc. have no effect. A fresh install starts with no providers configured; log in and add your first one under `Settings → Models → Add Provider`. Full reference in [Models](./models).

### Virtual threads (JDK 21)

```yaml
spring:
  threads:
    virtual:
      enabled: true
```

Enabled by default. Tomcat request threads, `@Scheduled` tasks, and `@Async` methods all run on virtual threads. SSE long-connections no longer hold platform threads, and I/O-bound async tasks (memory extraction, auditing, skill install, etc.) no longer queue behind a 16-thread pool.

### Spring AI Observability

```yaml
spring:
  ai:
    chat:
      observations:
        log-prompt: false       # don't write prompt content into spans (security)
        log-completion: false   # don't write completion content into spans
```

When enabled, `/actuator/metrics/gen_ai.client.operation` and `/actuator/metrics/gen_ai.client.token.usage` automatically record latency and token usage for every LLM call. Requires `spring-boot-starter-actuator` (already included).

### Context window

```yaml
mate:
  agent:
    conversation:
      window:
        default-max-input-tokens: 128000
        compact-trigger-ratio: 0.75
        preserve-recent-pairs: 2
        summary-max-tokens: 300
```

Details in [Memory](./memory).

### Memory extraction and consolidation

```yaml
mate:
  memory:
    auto-summarize-enabled: true
    min-messages-for-summarize: 4
    min-user-message-length: 10
    skip-cron-conversations: true
    summary-max-tokens: 1000
    max-transcript-messages: 30
    cooldown-minutes: 5
    emergence-enabled: true
    emergence-day-range: 7
```

### LLM Wiki

```yaml
mate:
  wiki:
    chunk-size: 1200
    chunk-overlap: 200
    digestion-concurrency: 2
    llm-model-config-id: 1
    min-concept-occurrences: 2
    max-page-backlinks: 50
    lock-on-manual-edit: true
    rebuild-sources-on-update: true
```

Eight knobs. Details in [LLM Wiki](./wiki).

### Tool Guard (rule-based)

```yaml
mateclaw:
  tool:
    guard:
      enabled: true
      default-policy: require_approval    # `allow` / `deny` / `require_approval`
      approval-timeout-seconds: 600
      rules:
        - tool: ShellExecuteTool
          arg-pattern: "^(ls|cat|grep|find)\\s"
          action: allow
          priority: 100
        - tool: ShellExecuteTool
          action: require_approval
          priority: 50
```

Details in [Security & Approval](./security).

### File Guard

```yaml
mateclaw:
  security:
    file-guard:
      enabled: true
      allowed-paths:
        - "${user.dir}/workspace"
        - "${java.io.tmpdir}/mateclaw"
      denied-paths:
        - "/etc"
        - "/usr"
        - "${user.home}/.ssh"
        - "${user.home}/.config"
```

### JWT authentication

```yaml
mateclaw:
  auth:
    jwt:
      secret: ${JWT_SECRET:your-secret-key-at-least-32-characters-long}
      expiration: 86400000
      sliding-window: true
```

::: warning
Change the default JWT secret in production. Must be at least 32 characters. Use an environment variable; never commit it.
:::

### Skill workspace

```yaml
mateclaw:
  skill:
    workspace:
      root: ${user.home}/.mateclaw/skills
      auto-init: true
      delete-policy: archive
      bundled-skills-path: skills
```

### Multimodal default providers

```yaml
mate:
  image:
    default-provider: dashscope
  video:
    default-provider: dashscope
  tts:
    default-provider: cosyvoice
  stt:
    default-provider: paraformer
  music:
    default-provider: dashscope
```

Details in [Multimodal](./multimodal).

---

## Environment variables

::: warning LLM keys are not read from env
DashScope / OpenAI / Anthropic / DeepSeek / Kimi and other provider API keys are **not configured via environment variables**. The container starts with zero LLM keys; after login, add your first provider under `Settings → Models → Add Provider`.
:::

| Variable | Required | Purpose |
|----------|----------|---------|
| `SERPER_API_KEY` | — | Google Serper search key (search tools not yet UI-managed) |
| `TAVILY_API_KEY` | — | Tavily search key (same as above) |
| `JWT_SECRET` | — | JWT signing secret (recommended in production) |
| `MATECLAW_CORS_ALLOWED_ORIGINS` | — | CORS allowlist (recommended in production) |
| `DB_PASSWORD` / `DB_ROOT_PASSWORD` | Docker | MySQL app user / root password |
| `SPRING_PROFILES_ACTIVE` | — | Set to `mysql` for production |

### Setting them

**Linux / macOS:**

```bash
export JWT_SECRET=your-production-secret-at-least-32-chars
export SERPER_API_KEY=your-serper-key   # optional
```

**Windows (PowerShell):**

```powershell
$env:JWT_SECRET = "your-production-secret-at-least-32-chars"
```

**Docker (`.env` file):**

```properties
DB_PASSWORD=secure-password-here
DB_ROOT_PASSWORD=different-secure-password-here
JWT_SECRET=your-production-secret-at-least-32-chars
```

After startup, open `http://localhost:18080`, sign in as `admin / admin123`, and add your first LLM provider under `Settings → Models → Add Provider`.

---

## Database schema init

MateClaw uses **Flyway** for schema migrations:

1. `db/migration/h2/V*__*.sql` — H2-dialect migration scripts
2. `db/migration/mysql/V*__*.sql` — MySQL-dialect migration scripts
3. After migrations, seed data is loaded from `db/data-*.sql` — idempotent

Flyway auto-selects the correct dialect path based on the active Spring profile. Every startup runs a `repair` before `migrate`, self-healing checksum drift and partially-failed migrations (especially important for desktop users upgrading offline).

### Table conventions

- All tables prefixed `mate_`
- `snake_case` columns, `camelCase` Java fields (auto-mapped by MyBatis Plus)
- Every table has `create_time`, `update_time`, `deleted`
- Logical delete: `deleted = 0` active, `deleted = 1` soft-deleted

### H2 console in dev

[http://localhost:18088/h2-console](http://localhost:18088/h2-console):

| Field | Value |
|-------|-------|
| JDBC URL | `jdbc:h2:file:./data/mateclaw` |
| Username | `sa` |
| Password | *(empty)* |

### Switching to MySQL

```sql
CREATE DATABASE mateclaw CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci;
```

```bash
export SPRING_PROFILES_ACTIVE=mysql
export MYSQL_ROOT_PASSWORD=your-password
mvn spring-boot:run
```

---

## Runtime settings (`mate_system_setting`)

Things you want to change without restarting:

| Key | Type | Purpose |
|-----|------|---------|
| `default_agent_id` | Long | Agent used when none specified |
| `default_model_config_id` | Long | Default model configuration |
| `max_conversation_turns` | Integer | Max turns per conversation |
| `enable_memory` | Boolean | Enable memory extraction |
| `search_enabled` | Boolean | Global web search toggle |
| `search_provider` | String | `serper` / `tavily` / `duckduckgo` / `searxng` |
| `search_fallback_enabled` | Boolean | Fall through to next provider on failure |
| `serper_api_key` | String | Serper key (masked in UI) |
| `tavily_api_key` | String | Tavily key (masked in UI) |
| `language` | String | `zh-CN` / `en-US` default UI language |
| `stream_enabled` | Boolean | SSE streaming output |
| `debug_mode` | Boolean | Show extra debug info in UI |

All editable from `Settings → System`. Changes take effect immediately.

### Search service configuration

Web search config has been migrated from `application.yml` to the **System Settings** page. Changes take effect immediately without restart.

::: tip
API keys are displayed masked. When saving, keys are only overwritten when a new value is entered. Blank input means "keep existing key".
:::

---

## Logging

```yaml
logging:
  level:
    vip.mate: INFO
    vip.mate.agent: DEBUG
    vip.mate.agent.graph: DEBUG
    org.springframework.ai: INFO
    root: INFO
```

For deep debugging, set `vip.mate: TRACE`. Log volume is high — don't leave it on in production.

---

## CORS

Development: Vite's dev server handles CORS via its proxy. Production: frontend is embedded in the JAR, no CORS needed.

If you deploy the frontend separately:

```yaml
mateclaw:
  cors:
    allowed-origins:
      - http://localhost:5173
      - https://your-domain.com
```

---

## Configuration precedence

Settings resolve in this order (highest priority first):

1. **Environment variables**
2. **Command-line arguments** (`--server.port=9090`)
3. **`application-{profile}.yml`**
4. **`application.yml`**
5. **Database `mate_system_setting`** (for runtime-configurable values)

---

## Next

- [Models](./models) — provider and model config in detail
- [Security & Approval](./security) — JWT, Tool Guard, File Guard, audit log
- [Memory](./memory) — memory tuning parameters
- [LLM Wiki](./wiki) — `mate.wiki` block explained
- [Channels](./channels) — channel-specific configuration
