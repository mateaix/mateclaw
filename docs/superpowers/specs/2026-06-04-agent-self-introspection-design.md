# Agent 自省（Self-Introspection）设计

> 状态：**已确认方向**，待用户评审本文档后进入实现计划。
> 日期：2026-06-04

## 目标

让 Agent 能稳定、自然地回答三类用户问题：
1. "你用的是什么模型？"
2. "你是谁？基于什么实现？"
3. "你正在干什么？"

主要服务于：
- **用户透明度**（终端用户能清楚知道在与谁对话）
- **多 Agent 编排协调**（子 Agent 能向父 Agent 汇报自己的身份与状态）

## 关键发现：三块信息基本都已存在，只是模型"看不到"

| 用户问题 | 已有承载点 | 缺口 |
|---|---|---|
| 你用什么模型 | `BaseAgent.modelName` / state 里的 `RUNTIME_MODEL_NAME`、`RUNTIME_PROVIDER_ID` | 没注入到任何 prompt，模型读不到 |
| 你是谁、基于什么 | `AgentEntity.systemPrompt` + workspace `PROFILE.md`/`SOUL.md` | 没声明"基于 MateClaw / Spring AI Alibaba Graph" |
| 你正在干什么 | ReAct 的 progress ledger、Plan-Execute 的 `PlanStateKeys.CURRENT_STEP_*`，**模型已看得到** | 基本无缺口 |

并且：`RuntimeContextInjector`（`agent/context/RuntimeContextInjector.java:67`）**已经在每轮注入一条 `[system-context]` 引导 UserMessage**（时间 / 工作目录 / 渠道 / 发送者），且刻意控制在 spring-ai 的 1024 字符用户缓存阈值以下，缓存安全。这正是 OpenClaw 的 `## Runtime` 行的等价物，只是 MateClaw 用 UserMessage 承载（对 prompt cache 更友好）。

## 调研：OpenClaw 怎么做

| 问题 | OpenClaw 做法 | 代码锚点 |
|---|---|---|
| 你用啥模型 | system prompt 注入 `Current model identity: <model>. If asked what model you are, answer with this value for the current run.`（带显式指令） | `src/agents/system-prompt.ts:612-620` `buildModelIdentityPromptLine` |
| 你是谁 | 通过 `IDENTITY.md` / `SOUL.md` / `AGENTS.md` 等 workspace 文件，agent 通过 BOOTSTRAP 流程自填 | `src/agents/workspace.ts:18-25` |
| 你在干什么 | 不自动注入；agent 用 `update_plan` 工具自己声明 | `docs/concepts/system-prompt.md:103-105` |
| whoami 工具 | **无**（`/whoami` 命令只查 sender 身份，非 agent 自身） | `src/auto-reply/reply/commands-whoami.ts` |

关键启发：
- 显式 "if asked …" 指令让模型"知道"自己可以引用注入值，避免编造。
- 静态身份（不变量）走 system prompt；动态事实（模型/渠道/时间）走每轮 runtime 行。
- 不需要专门的 whoami 工具即可覆盖大部分诉求。

## 最终方案：注入即可，静态/动态分离（不加工具）

镜像 OpenClaw 的静态/动态分离思路，但复用 MateClaw 已有的两个机制。

### ① 静态身份 → 缓存的 system prompt

在 `AgentGraphBuilder.buildEnhancedPrompt(...)` 末尾追加一段固定、缓存稳定的身份块：

```
## About You
你由 MateClaw 驱动——一个基于 Spring Boot 3.5 和 Spring AI Alibaba Graph
构建的多用户 AI Agent 平台，可通过 WebChat 及 8+ 个 IM 渠道
（钉钉、飞书、企业微信、微信、Telegram、Discord、QQ、Slack）访问。
被问到"你是谁 / 基于什么实现"时，按此回答。
```

- 整段拼到 system prompt 末尾，被 spring-ai-alibaba 缓存命中，每个 Agent 一致。
- 代码统一注入，零配置（不做 IDENTITY.md 式可配置模板，避免范围蔓延；MateClaw 已有 `PROFILE.md`/`SOUL.md` 可后续按需补）。

### ② 动态事实 → 复用现有 `RuntimeContextInjector` 行

在已有的时间/渠道行上，补 `model` + `provider`：

```
[system-context] Model: gpt-4o (provider: openai)
被问到用的什么模型时，按本轮这个值回答。
```

- 值来自 `BaseAgent.modelName` / `runtimeProviderId`，由 `buildInitialState` 经 graph state（`RUNTIME_MODEL_NAME`/`RUNTIME_PROVIDER_ID`）传入，调用方在调 `buildContextMessage(...)` 时透传。
- **去掉模型行对 web/cron 的抑制**：现有 `appendSenderBlockIfPresent`（`RuntimeContextInjector.java:129-151`）对 web/cron 抑制——但那是为"发送者块"设计的。模型行应对所有 origin 显示，让网页用户也能问到模型。发送者块仍保持 IM-only。
- 注意维持 1024 字符以下的缓存纪律（模型行很短，无虞）。

### ③ "你正在干什么" → 靠已有可见状态

- ReAct：progress ledger 快照（`ProgressLedgerService` + `ReasoningNode`）已注入，模型看得到。
- Plan-Execute：`PlanStateKeys.CURRENT_STEP_INDEX/TITLE` 已在 working context 中。
- 空闲（无任务）时，诚实答"在等你的指令"。
- 不加新机制。

## 实现位置

| 组件 | 文件 | 改动 |
|---|---|---|
| 静态身份块 | `agent/AgentGraphBuilder.java`（`buildEnhancedPrompt`，约 1318-1474） | 末尾追加 `## About You` 段 |
| 动态模型行 | `agent/context/RuntimeContextInjector.java` | `buildContextMessage(...)` 增加 model/provider 参数与渲染；新增一个对所有 origin 生效的模型行；保持发送者块 IM-only |
| 注入点透传 | `agent/graph/node/ReasoningNode.java`、`agent/graph/plan/node/PlanGenerationNode.java`、`agent/graph/plan/node/StepExecutionNode.java`（现有调用 `buildContextMessage` 处） | 把 `RUNTIME_MODEL_NAME`/`RUNTIME_PROVIDER_ID` 从 state 取出后透传 |

## 为什么砍掉 whoami 工具（相对早期草案）

早期草案的"方案 B"含一个 `whoami` 工具，重新评估后认定为过度设计：
- **"你在干什么"已被现有 ledger/计划步骤覆盖**——那是工具最大的价值点。
- 工具在每次运行占 token 预算槽，**还多一次 LLM 往返**，仅换来 failover 之后"实时准确"的模型名——边缘场景，**连 OpenClaw 自己都没解决**（也只承诺 "for the current run"）。
- 注入零额外往返，复用已调好缓存的代码。
- 净对比：约 2 处小改动 vs. 新建工具类 + 注册 + 测试。

## 已接受的权衡

模型名反映**本轮开始时**的模型；mid-run failover 切换后不更新（与 OpenClaw 同样的限制）。若将来需要 failover 后实时准确的模型名，**那时**再加工具才划算——推迟到有明确需求。

## 范围

本次只做：
- 静态身份注入（`## About You` 段）
- 动态模型/provider 注入（扩展 `RuntimeContextInjector` + 透传）
- 单元测试：`RuntimeContextInjector` 渲染含 model 行；web origin 也能拿到模型行；发送者块仍 IM-only
- 一个端到端冒烟：分别问三类问题，确认回答正确

不做：
- whoami 工具（已记入权衡，按需后补）
- IDENTITY.md 式可配置身份模板
- UI 展示 runtime 信息（`ChatResult` 已有 `runtimeModel/runtimeProvider`，前端若未展示属独立任务）
- 工具/文案多语言化（先沿用现有 i18n 机制，键名复用 `context.*`）

## 上游反馈

本设计源于一个通用诉求（Agent 自省），已向上游 `matevip/mateclaw` 提交 issue 反馈该能力缺口与方案建议。
