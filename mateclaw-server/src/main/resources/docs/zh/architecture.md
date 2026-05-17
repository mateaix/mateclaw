# 架构说明

**MateClaw 是怎么拼起来的，一页讲完。**

**用** MateClaw 的人看 [项目介绍](./intro)。**在 MateClaw 上面建东西**的人——加工具、新渠道、自定义记忆 provider、新的 Agent 图节点——看这一页。

---

## 一张图的产品

```
┌─────────────────────────────────────────────────────────────────┐
│                         MateClaw                                 │
│                                                                  │
│  ┌──────────────┐  ┌──────────────┐  ┌─────────────────────┐   │
│  │  Web 控制台  │  │   桌面端     │  │     IM 渠道          │   │
│  │   Vue 3 SPA  │  │  Electron    │  │ 钉钉 / 飞书 /        │   │
│  │ (src/static) │  │  + 内置       │  │ 企业微信 / Telegram/ │   │
│  │              │  │   JRE 21     │  │ Discord / QQ / ...   │   │
│  └──────┬──────┘  └──────┬───────┘  └──────────┬──────────┘   │
│         │  HTTP/SSE       │  HTTP/SSE            │ SPI           │
│         └────────┬────────┴───────────────────────┘              │
│                  │                                               │
│  ┌───────────────▼──────────────────────────────────────────┐  │
│  │          Spring Boot 后端（vip.mate.*）                    │  │
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
│  │  │   内置 @Tool  +  MCP client  +  技能脚本                │ │  │
│  │  └─────────┬───────────────────────────────────────────┬──┘ │  │
│  │            │                                            │   │  │
│  │            ▼                                            ▼   │  │
│  │  ┌──────────────┐  ┌─────────────┐  ┌───────────────────┐  │  │
│  │  │  Tool Guard  │  │  审批       │  │   审计日志         │  │  │
│  │  │  （规则）    │  │  工作流     │  │   管道            │  │  │
│  │  └──────────────┘  └─────────────┘  └───────────────────┘  │  │
│  │                                                             │  │
│  │  ┌────────────┐  ┌────────────┐  ┌────────────────────┐   │  │
│  │  │   memory    │  │    wiki     │  │    skill            │   │  │
│  │  │   多层      │  │  （分层）    │  │  (SKILL.md 运行时)  │   │  │
│  │  │   SPI       │  │   digester  │  │                     │   │  │
│  │  └────────────┘  └────────────┘  └────────────────────┘   │  │
│  │                                                             │  │
│  │  ┌────────────────────────────────────────────────────┐   │  │
│  │  │           MyBatis Plus / H2 或 MySQL               │   │  │
│  │  │                                                      │   │  │
│  │  │    mate_agent / mate_message / mate_wiki_* /        │   │  │
│  │  │    mate_tool_guard_* / mate_workspace / ...          │   │  │
│  │  └────────────────────────────────────────────────────┘   │  │
│  └───────────────────────────────────────────────────────────┘  │
└─────────────────────────────────────────────────────────────────┘
```

**一个 JAR。一个进程。The whole widget。**

---

## 仓库布局

```
mateclaw/
├── mateclaw-server/          # Spring Boot 后端（心脏）
│   └── src/main/java/vip/mate/
│       ├── MateClawApplication.java
│       ├── agent/            # StateGraph 运行时、节点、边、状态
│       ├── planning/         # Plan 和 SubPlan 持久化
│       ├── workflow/         # 工作流引擎（1.3.0+）：DSL 编译、线性 runtime、payload spill
│       ├── trigger/          # 触发器引擎（1.3.0+）：6 种 pattern、事件治理、CronDelegationPort
│       ├── tool/             # ToolRegistry、@Tool bean、MCP（per-agent 绑定）、guard
│       ├── approval/         # 审批工作流（兼任 workflow 的 await_approval 暂停/恢复桥接）
│       ├── skill/            # 动态技能包
│       ├── wiki/             # LLM Wiki + Transformations 引擎（1.3.0+）
│       ├── memory/           # 记忆层 + SPI
│       ├── workspace/        # 工作空间实体 + 会话 + 文档
│       ├── channel/          # 渠道 SPI + 适配器（1.3.0+: WeCom v2、reply queue、leader lease）
│       ├── llm/              # LLM provider 配置（1.3.0+: 多模态 sidecar 路由）
│       ├── auth/             # Spring Security + JWT
│       ├── audit/            # 审计事件管道
│       ├── cron/             # 定时任务引擎（trigger.cron 通过 CronDelegationPort 复用）
│       ├── task/             # 异步任务运行时
│       ├── dashboard/        # 指标聚合
│       ├── datasource/       # 外部 DB 连接
│       ├── stt/              # 语音转文字
│       ├── tts/              # 文字转语音
│       ├── system/           # 系统设置、bootstrap、引导
│       ├── config/           # Spring 配置
│       ├── common/           # 共享工具
│       └── exception/        # 全局异常处理器
│   └── src/main/resources/
│       ├── application.yml
│       ├── db/migration/      # Flyway 迁移脚本（h2/ + mysql/）
│       ├── db/data.sql       # 种子数据
│       ├── prompts/          # LLM prompt 模板
│       ├── skills/           # 捆绑的技能包
│       └── static/           # 前端构建产物
├── mateclaw-ui/              # Vue 3 管理控制台
├── mateclaw-desktop/         # Electron 桌面壳
├── mateclaw-webchat/         # 可嵌入的聊天小部件
├── matevip-sites/            # 营销和文档站点（pnpm workspace）
├── docs/                     # 这份文档（VitePress）
├── deploy/                   # 生产部署配置
├── docker-compose.yml
└── .env.example
```

后端是**一个模块化的单体**。其他项目是不和后端共享代码的独立包。

---

## Agent 运行时是一张 StateGraph

**这是你给后端贡献代码时最重要的事。**

**MateClaw 的 Agent 运行时不是一个类层次。** 没有 `BaseAgent` → `ReActAgent` → `MyCustomAgent` 的继承链。运行时是一张**由节点和条件边组成的 StateGraph**（来自 `spring-ai-alibaba-graph`），在运行时由 `AgentGraphBuilder` 装配。

### 关键文件

- `agent/graph/StateGraphReActAgent.java`——装配 ReAct 循环
- `agent/graph/plan/StateGraphPlanExecuteAgent.java`——装配 Plan-and-Execute 图
- `agent/graph/node/`——`ReasoningNode`、`ActionNode`、`ObservationNode`、`FinalAnswerNode`、`SummarizingNode`、`LimitExceededNode`
- `agent/graph/plan/node/`——`PlanGenerationNode`、`StepExecutionNode`、`PlanSummaryNode`、`DirectAnswerNode`
- `agent/graph/edge/` + `plan/edge/`——基于状态决定下一个节点的 dispatcher 函数
- `agent/graph/state/MateClawStateKeys.java`——共享 state 对象的 key
- `agent/graph/state/MateClawStateAccessor.java`——state map 的类型化访问器（**别直接动 map**）
- `agent/graph/lifecycle/ReActLifecycleListener.java`——节点级插桩 hook
- `agent/AgentGraphBuilder.java`——按 Agent 配置拼装节点和边的 builder
- `agent/GraphEventPublisher.java` + `agent/graph/NodeStreamingChatHelper.java`——流式事件怎么从图里逃到 SSE 流里

### 怎么扩展

**加 Agent 行为**——在 `agent/graph/node/` 创建新节点，或在 `agent/graph/edge/` 创建新边 dispatcher。把它接进 `AgentGraphBuilder`。通过 `MateClawStateAccessor` 读写 state。

**不要**创建新的 `XxxAgent` 类。你会把图已经在做的事情重新实现一遍。

### 共享 state key

| Key | 用途 |
|-----|------|
| `USER_MESSAGE` | 当前用户输入 |
| `MESSAGES` | 从 `mate_message` 加载的会话消息 |
| `OBSERVATION_HISTORY` | 本回合的工具调用结果 |
| `CURRENT_ITERATION` | 已经循环过多少次 |
| `MAX_ITERATIONS` | 上限 |
| `TOOL_CALLS` | 当前工具调用列表 |
| `AWAITING_APPROVAL` | 有调用需要人工审批时置 true |
| `FINAL_ANSWER` | Agent 的响应 |
| `FINISH_REASON` | 图为什么结束 |

---

## 数据流 —— 单次回合

```
1. POST /api/v1/chat/{agentId}/message
        ↓
2. ChatController.sendMessage()
        ↓
3. ConversationManager.loadOrCreate(conversationId)
        ↓
4. AgentGraphBuilder.build(agentEntity)   ← 解析出编译好的图
        ↓
5. graph.invoke(initialState)              ← StateGraph 执行开始
        ↓
   ReasoningNode → Dispatcher → ActionNode → ObservationNode →（循环或结束）
        ↓
6. 工具调用走：
   ToolRegistry.resolve() →
   Tool Guard 规则评估 →
   （需要审批时）mate_tool_approval 行 + SSE 事件 + AWAITING_APPROVAL=true
   （内联允许时）ToolExecutionExecutor.execute() → observation
        ↓
7. Segment 通过以下方式流到客户端：
   GraphEventPublisher → NodeStreamingChatHelper → SSE 流
        ↓
8. 结束时：
   FinalAnswerNode 聚合结果
   ConversationManager 把 segment 持久化到 mate_message
   ConversationCompletedEvent 发出（异步记忆提取启动）
        ↓
9. 响应关闭
```

---

## 扩展点

这些是你可以在上面建东西的 SPI 和插件点：

### `@Tool` 标注的 Spring bean

写一个带 `@Tool` 方法的 `@Component`。启动时被 `ToolRegistry` 捡起来。每个 `@Tool` 方法都成为一个可调用工具。

```java
@Component
public class MyCustomTool {
    @Tool(description = "LLM 看到的描述")
    public String doThing(@ToolParam(description = "...") String input) {
        return "result";
    }
}
```

### `ChannelAdapter` SPI

实现 `vip.mate.channel.ChannelAdapter`（或支持流式的 `StreamingChannelAdapter`）。注册成 Spring bean。通过 `ChannelWebhookController` 加 webhook 端点。见 [多渠道接入](./channels)。

```java
public interface ChannelAdapter {
    void onMessage(ChannelMessage message);
    void sendMessage(String channelId, String content);
    String getChannelType();
}
```

### `MemoryProvider` SPI

实现 `vip.mate.memory.spi.MemoryProvider` 来插入自定义记忆后端（向量、图、外部服务）。**多个 provider 可以在 Agent 上堆叠**。见 [记忆系统](./memory)。

### MCP 服务

通过 stdio、streamable_http、sse 连接外部工具服务。它们的工具自动出现在工具注册表里——Agent 代码**不知道它们是外部的**。见 [MCP 协议](./mcp)。

### 技能包

把指令 + 工具 + 可选脚本打包进一个 `SKILL.md`。通过 UI 或 API 上传。Agent 在运行时可以调用它们。见 [技能系统](./skills)。

### Agent 图节点和边

更深的定制——在 `agent/graph/node/` 加新节点或在 `agent/graph/edge/` 加新 dispatcher。在配置 flag 后面接进 `AgentGraphBuilder`。State 访问走 `MateClawStateAccessor`。

---

## 持久化 —— 一份 schema，两种数据库

MateClaw 用 **MyBatis Plus**（不是 JPA）做数据库访问。约定：

- 所有表前缀 `mate_`
- `snake_case` 列、`camelCase` Java 字段、自动映射
- 每张表有 `create_time`、`update_time`、`deleted`（逻辑删除）
- **Flyway** 管理 schema 迁移——`db/migration/h2/` 和 `db/migration/mysql/` 各有一套方言脚本，启动时自动选择
- `FlywayRepairConfig` 在每次启动时先 `repair()` 再 `migrate()`，checksum 变更和部分失败的迁移自动修复
- 种子数据由 `DatabaseBootstrapRunner` 从 `db/data-*.sql` 加载，幂等执行

### 表分组

**身份和配置**——`mate_user`、`mate_system_setting`、`mate_model_config`、`mate_model_provider`、`mate_datasource`、`mate_mcp_server`

**Agent 和计划**——`mate_agent`、`mate_agent_skill`、`mate_agent_tool`、`mate_plan`、`mate_sub_plan`

**会话**——`mate_conversation`、`mate_message`、`mate_channel`、`mate_channel_session`

**工具和审批**——`mate_tool`、`mate_tool_approval`、`mate_tool_guard_rule`、`mate_tool_guard_config`、`mate_tool_guard_audit_log`

**技能和工作空间**——`mate_skill`、`mate_workspace`、`mate_workspace_member`、`mate_workspace_file`

**知识和记忆**——`mate_wiki_knowledge_base`、`mate_wiki_raw_material`、`mate_wiki_page`、`mate_wiki_transformation`、`mate_wiki_transformation_run`（1.3.0+）、`mate_memory_recall`

**工作流和触发器（1.3.0+）**——`mate_workflow`、`mate_workflow_revision`、`mate_workflow_run`、`mate_workflow_step_run`、`mate_workflow_payload`、`mate_trigger`、`mate_trigger_event`

**运维**——`mate_cron_job`、`mate_cron_job_run`、`mate_async_task`、`mate_usage_daily`、`mate_audit_event`、`mate_doctor_check`

---

## 流式 —— 为什么用 SSE 不用 WebFlux

MateClaw 用 **Spring MVC**，不是 Spring WebFlux。**WebFlux 在依赖图里被明确排除。**

为什么：Spring MVC + SSE 足以把 LLM 响应流式到前端。它更容易推理、更容易调试、不强迫整个栈变成响应式。

::: tip 虚拟线程（JDK 21）
`spring.threads.virtual.enabled=true` 已开启。Tomcat 请求线程、`@Scheduled` 任务和 `@Async` 方法全部运行在虚拟线程上。SSE 长连接不再占用平台线程——并发连接数不再受线程池大小约束。
:::

流式流程：

1. 客户端打开 `GET /api/v1/chat/{agentId}/stream`，带 `Accept: text/event-stream`
2. Controller 返回 `SseEmitter`
3. Agent 图在工作线程上运行；节点执行把事件发给 `GraphEventPublisher`
4. 事件序列化成 SSE 格式写进 emitter
5. `ChatStreamTracker` 监视被遗弃的流并清理它们

同样的 SSE 模式被支持流式的渠道适配器复用（钉钉 AI Card、Web）。

---

## 前端架构

**Vue 3 + TypeScript + Composition API + `<script setup>`** 到处都是。关键部件：

- **`src/views/`**——页面组件
- **`src/stores/`**——Pinia store，领域驱动（每个 store 独占自己的切片）
- **`src/composables/`**——Composition API 辅助（**聊天流式住在这里**，不在全局 store 里）
- **`src/api/`**——Axios 实例 + 端点定义
- **`src/router/`**——带认证守卫和基于角色隐藏的 Vue Router
- **`src/i18n/`**——`zh-CN.ts` 和 `en-US.ts` 翻译
- **`src/assets/main.css`**——`--mc-*` CSS 设计 token（主题化的**单一事实源**）

按页细节见 [控制台](./console)，前端约定见 [贡献指南](./contributing)。

---

## 三个交付面

同一份后端 JAR，三种发布方式：

1. **Web**——直接跑 JAR，浏览器打开 `http://localhost:18088`。前端嵌入 JAR 的 `static/`。
2. **桌面**——`mateclaw-desktop/` 里的 Electron 壳捆绑 JRE 21 和 JAR。**用户永远不装 Java**。通过 electron-updater 自动更新。
3. **Docker**——`docker-compose.yml` 带 MySQL。生产部署。

`mateclaw-webchat/` 里的 webchat widget 是第四条路——一个你可以丢进任何网站的可嵌入聊天 UI。它和同样的后端 API 对话。

---

## 请求分层

```
   HTTP 请求
        │
        ▼
   Spring Security Filter  ── JWT 校验、滑动窗口续签
        │
        ▼
   基于角色的访问 Filter   ── 工作空间权限
        │
        ▼
   Controller                ── @RestController
        │
        ▼
   Service                   ── 业务逻辑
        │
        ▼
   Mapper（MyBatis Plus）    ── SQL
        │
        ▼
   数据库（H2 / MySQL）
```

每一层**单一职责**。跨层的关注点（审计日志、指标）用 AOP 切面或 Spring Boot Actuator 端点实现。

::: tip Spring AI 可观测性
Spring AI 内置的 Micrometer Observation 已开启——每次 LLM 调用自动记录 `gen_ai.client.operation`（延迟）和 `gen_ai.client.token.usage`（input/output token）。通过 `/actuator/metrics/gen_ai.*` 端点查看。prompt 内容不会泄露到 span 中（`log-prompt=false`）。
:::

---

## 下一步

- [项目介绍](./intro)——在"怎么做"之前先看"为什么"
- [Agent 引擎](./agents)——StateGraph 深入
- [贡献指南](./contributing)——加代码的约定
- [API 参考](./api)——后端暴露的 REST 表面
