# 配置参考

**MateClaw 有三个配置位：`application.yml`、环境变量、数据库。**

大部分设置在 `application.yml`（Spring Boot 默认配置文件）里，敏感值通过环境变量覆盖。你想要**运行时修改**的东西——模型供应商、搜索 key、功能开关——存在 `mate_system_setting` 表里，通过设置页面编辑。

深入的主题有自己的页面——Tool Guard 规则在 [安全与审批](./security)，模型供应商在 [模型配置](./models)，记忆调优在 [记忆系统](./memory)。

---

## Profile

| Profile | 数据库 | 激活方式 |
|---------|--------|----------|
| `default` | H2 文件 `./data/mateclaw` | 不用做什么 |
| `mysql` | MySQL 8.0+ | `spring.profiles.active=mysql` 或环境变量 |

Docker 部署自动激活 `mysql`。桌面版用 `default`。

---

## 核心 `application.yml` 分段

### Server

```yaml
server:
  port: 18088                    # HTTP 端口
  servlet:
    context-path: /
```

### 数据库 —— H2（开发）

```yaml
spring:
  datasource:
    url: jdbc:h2:file:./data/mateclaw;MODE=MYSQL
    username: sa
    password:
    driver-class-name: org.h2.Driver
  h2:
    console:
      enabled: true              # /h2-console 可访问（生产环境关掉）
```

### 数据库 —— MySQL（生产）

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

### AI 模型 —— 在 UI 里管，不在 YAML 里

::: tip
**模型配置 100% 通过 UI 管理。** 不要在 `application.yml` 里放 `spring.ai.*` 块——每个供应商、每个 key、每个模型配置都住在 `设置 → 模型` 里，底层存在 `mate_model_provider` 和 `mate_model_config` 表。
:::

**LLM API Key 不读取环境变量**——`DASHSCOPE_API_KEY` / `OPENAI_API_KEY` 等都不再起作用。新装的实例启动时数据库里没有供应商，登录后到「设置 → 模型 → 添加供应商」加你的第一个供应商即可。完整参考在 [模型配置](./models)。

### 虚拟线程（JDK 21）

```yaml
spring:
  threads:
    virtual:
      enabled: true
```

已默认开启。Tomcat 请求线程、`@Scheduled`、`@Async` 全部运行在虚拟线程上。SSE 长连接不再占平台线程，I/O 密集型异步任务（记忆提取、审计、技能安装等）不再排队。

### Spring AI 可观测性

```yaml
spring:
  ai:
    chat:
      observations:
        log-prompt: false       # 不把 prompt 写进 span（安全）
        log-completion: false   # 不把 completion 写进 span
```

开启后，`/actuator/metrics/gen_ai.client.operation` 和 `/actuator/metrics/gen_ai.client.token.usage` 自动记录每次 LLM 调用的延迟和 token 消耗。需要 `spring-boot-starter-actuator` 依赖（已内置）。

### 上下文窗口

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

细节在 [记忆系统](./memory)。

### 记忆提取和整合

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

八个旋钮。细节在 [LLM Wiki](./wiki)。

### Tool Guard（基于规则）

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

细节在 [安全与审批](./security)。

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

### JWT 认证

```yaml
mateclaw:
  auth:
    jwt:
      secret: ${JWT_SECRET:your-secret-key-at-least-32-characters-long}
      expiration: 86400000
      sliding-window: true
```

::: warning
生产环境必须改默认 JWT secret。至少 32 字符。用环境变量，**不要 commit**。
:::

### 技能工作空间

```yaml
mateclaw:
  skill:
    workspace:
      root: ${user.home}/.mateclaw/skills
      auto-init: true
      delete-policy: archive
      bundled-skills-path: skills
```

### 多模态默认供应商

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

细节在 [多模态创作](./multimodal)。

---

## 环境变量

::: warning LLM Key 不读环境变量
DashScope / OpenAI / Anthropic / DeepSeek / Kimi 等供应商的 API Key **不通过环境变量配置**——容器零 Key 也能起来，登录后到「设置 → 模型 → 添加供应商」里加。
:::

| 变量 | 必填 | 用途 |
|------|------|------|
| `SERPER_API_KEY` | — | Google Serper 搜索 key（搜索工具暂未迁到 UI） |
| `TAVILY_API_KEY` | — | Tavily 搜索 key（同上） |
| `JWT_SECRET` | — | JWT 签名密钥（生产推荐） |
| `MATECLAW_CORS_ALLOWED_ORIGINS` | — | CORS 白名单（生产推荐） |
| `DB_PASSWORD` / `DB_ROOT_PASSWORD` | Docker | MySQL 业务库 / root 密码 |
| `SPRING_PROFILES_ACTIVE` | — | 生产设为 `mysql` |

### 怎么设

**Linux / macOS：**

```bash
export JWT_SECRET=your-production-secret-at-least-32-chars
export SERPER_API_KEY=your-serper-key   # 可选
```

**Windows（PowerShell）：**

```powershell
$env:JWT_SECRET = "your-production-secret-at-least-32-chars"
```

**Docker（`.env` 文件）：**

```properties
DB_PASSWORD=secure-password-here
DB_ROOT_PASSWORD=different-secure-password-here
JWT_SECRET=your-production-secret-at-least-32-chars
```

启动后到 `http://localhost:18080`，`admin / admin123` 登录，「设置 → 模型 → 添加供应商」配第一家 LLM。

---

## 数据库 schema 初始化

MateClaw 用 **Flyway** 管理 schema 迁移：

1. `db/migration/h2/V*__*.sql`——H2 方言的迁移脚本
2. `db/migration/mysql/V*__*.sql`——MySQL 方言的迁移脚本
3. 迁移完成后加载种子数据（`db/data-*.sql`），幂等执行

启动时 Flyway 根据 active profile 自动选择正确的方言路径。每次启动时先做一次 `repair`，再 `migrate`——checksum 变更和部分失败的迁移自动修复（对桌面端离线升级用户尤其重要）。

### 表约定

- 所有表前缀 `mate_`
- `snake_case` 列、`camelCase` Java 字段（MyBatis Plus 自动映射）
- 每张表都有 `create_time`、`update_time`、`deleted`
- 逻辑删除：`deleted = 0` 活跃，`deleted = 1` 软删除

### 开发模式下连 H2

[http://localhost:18088/h2-console](http://localhost:18088/h2-console)：

| 字段 | 值 |
|------|----|
| JDBC URL | `jdbc:h2:file:./data/mateclaw` |
| 用户名 | `sa` |
| 密码 | *（空）* |

### 切到 MySQL

```sql
CREATE DATABASE mateclaw CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci;
```

```bash
export SPRING_PROFILES_ACTIVE=mysql
export MYSQL_ROOT_PASSWORD=your-password
mvn spring-boot:run
```

---

## 运行时设置（`mate_system_setting`）

**不想重启就能改**的东西住在这里：

| Key | 类型 | 用途 |
|-----|------|------|
| `default_agent_id` | Long | 不指定时用的 Agent |
| `default_model_config_id` | Long | 默认模型配置 |
| `max_conversation_turns` | Integer | 每次对话的最大轮数 |
| `enable_memory` | Boolean | 启用记忆提取 |
| `search_enabled` | Boolean | 网页搜索工具的全局开关 |
| `search_provider` | String | `serper` / `tavily` / `duckduckgo` / `searxng` |
| `search_fallback_enabled` | Boolean | 失败时走下一个 provider |
| `serper_api_key` | String | Serper key（UI 脱敏） |
| `tavily_api_key` | String | Tavily key（UI 脱敏） |
| `language` | String | `zh-CN` / `en-US` 默认 UI 语言 |
| `stream_enabled` | Boolean | SSE 流式输出 |
| `debug_mode` | Boolean | UI 里显示额外调试信息 |

这些全都可以在 `设置 → 系统` 里编辑。改动**立刻生效不需要重启**。

### 搜索服务配置

网页搜索配置已从 `application.yml` 迁移到**系统设置**页面。改动立刻生效。

::: tip
API key 在 UI 里**脱敏显示**。保存时，**只有填了新值才会覆盖**。留空表示"保留已有的 key"。
:::

---

## 日志

```yaml
logging:
  level:
    vip.mate: INFO
    vip.mate.agent: DEBUG
    vip.mate.agent.graph: DEBUG
    org.springframework.ai: INFO
    root: INFO
```

深度排查时把 `vip.mate` 设成 `TRACE`。日志量很大——**生产环境别开着**。

---

## CORS

开发环境：Vite 开发服务器通过 proxy 处理。生产环境：前端内嵌在 JAR 里，不需要 CORS。

单独部署前端的话：

```yaml
mateclaw:
  cors:
    allowed-origins:
      - http://localhost:5173
      - https://your-domain.com
```

---

## 配置优先级

设置按以下顺序解析（**优先级从高到低**）：

1. **环境变量**
2. **命令行参数**（`--server.port=9090`）
3. **`application-{profile}.yml`**
4. **`application.yml`**
5. **数据库 `mate_system_setting`**（运行时可编辑的值）

---

## 下一步

- [模型配置](./models)——供应商和模型配置细节
- [安全与审批](./security)——JWT、Tool Guard、File Guard、审计日志
- [记忆系统](./memory)——记忆调优参数
- [LLM Wiki](./wiki)——`mate.wiki` 配置块
- [多渠道接入](./channels)——渠道特定配置
