---
title: AI 记忆系统 — 四层记忆生命周期（提取-整合-Dreaming-召回）
description: MateClaw 的四层记忆生命周期：即时上下文、对话后提取、工作空间持久化（PROFILE.md/MEMORY.md）、定时 Dreaming 整合。让 AI 越用越懂你，不再每天从零开始。
head:
  - - meta
    - name: keywords
      content: AI记忆,记忆系��,Dreaming,PROFILE.md,MEMORY.md,记忆生命周期,长期记忆,记忆提取,记忆整合
---

# AI 记忆系统

**记忆是系统越用越懂你的机制。**

MateClaw 里其他所有东西，在你配置完之后就静止了。Agent、工具、知识库——你改它们的时候才改。记忆是**唯一一个**会自己改变的部分，变化是实际使用过程的副产品。这就是整个设计的核心意图。

::: tip 它在你睡着的时候做了一个关于你的梦
不是营销词。是 `memory/dreaming/` 包里真实跑的代码。

每天凌晨 2 点（默认时间，可改），系统跑一次调度任务，名字就叫 **Dreaming**：扫一遍今天和你聊天的每个 Agent 的对话痕迹，把零散的线索整合成对你的理解，过滤掉一次性的、矛盾的、过期的，把高频出现的提升进 `MEMORY.md`，整个"看见了什么、得出了什么、改写了什么"的过程追加进 `DREAMS.md`——一条人类可读的审计线。

第二天早上你打开它，它**从昨天结束的地方继续**，不是从零开始。

> 别的 AI 每天从零开始。MateClaw 从昨天结束的地方继续。
:::

这一页讲组成记忆的四个层、每个 Agent 的记忆文件、以及 Agent 自己怎么在对话中读写这些文件。

---

## 四个层

```
  ┌────────────────────────────────────────────────────────────┐
  │  1. 当下这一回合                                              │
  │     正在说的话、刚刚说过的话、按 token 预算自动裁剪           │
  │     更新时机：每一回合                                        │
  └────────────────────────────────────────────────────────────┘
                            │
                            ▼（对话完成之后）
  ┌────────────────────────────────────────────────────────────┐
  │  2. 对话结束后的提取                                          │
  │     从对话里挑出值得记住的事，写进 PROFILE.md / MEMORY.md     │
  │     和当天的日常笔记                                         │
  │     更新时机：每次有意义的对话结束后异步跑                     │
  └────────────────────────────────────────────────────────────┘
                            │
                            ▼（默认每天凌晨 2 点，可调）
  ┌────────────────────────────────────────────────────────────┐
  │  3. 夜里整合（Dreaming）                                     │
  │     扫一遍最近的日常笔记，找出反复出现的模式，               │
  │     合并进 MEMORY.md，把过程记到 DREAMS.md                   │
  │     更新时机：定时触发，可手动                                │
  └────────────────────────────────────────────────────────────┘
                            │
                            ▼（下一次对话直接用最新版本）
  ┌────────────────────────────────────────────────────────────┐
  │  4. 工作空间文件进入 system prompt                            │
  │     四个 markdown 文件每一回合都被注入                        │
  │     更新时机：底下文件一变，下一回合就生效                     │
  └────────────────────────────────────────────────────────────┘
```

每一层跑在不同的时间尺度上。当下是**这一回合**。提取是**每一次对话之后**。整合是**每天夜里**。文件注入是**每一回合都用当前最新版本**。加在一起它们形成一个循环——你说的话变成上下文，上下文变成文件，文件变成 system prompt，system prompt 变成 Agent 明天知道的东西。

---

## 多层记忆 + 可插拔 Provider

记忆这一层不是一个硬编码的实现。它是一个**接口**——多层架构允许你**堆叠 provider**：

- **默认 Provider** 就是这页后面讲的基于工作空间文件的记忆。MateClaw 出厂就带这个，对大多数人来说这一个就够了。
- **自定义 Provider** 可以插入用于专用检索——基于向量的长期记忆、图结构记忆、外部记忆服务。
- **分层**意味着同一个 Agent 可以同时和多个 provider 对话。短期 provider 返回最近上下文；语义 provider 返回相关记忆；Wiki provider 返回权威引用。它们在读取时组合。

对大多数 Agent 来说，**默认的就够了**，这一节可以跳过。如果你在做某种专用的东西——需要记住上千条事实并用向量搜索、需要图结构记忆——这里就是插入点。开发细节看 [架构说明](./architecture)。

---

## 每个 Agent 都有的四个文件

每个 Agent 都有自己的工作空间。四个 markdown 文件是长期记忆的骨架：

```
workspace/{agentId}/
├── AGENTS.md          # Agent 怎么用记忆 —— 行为指南
├── SOUL.md            # Agent 是谁 —— 核心身份、人格、边界
├── PROFILE.md         # 你是谁 —— 用户画像、偏好、背景
├── MEMORY.md          # 什么重要 —— 关键决策、项目上下文、待办
└── memory/
    ├── 2026-04-09.md  # 日常笔记 —— 今天发生了什么，追加模式
    ├── 2026-04-10.md
    └── 2026-04-11.md
```

前四个会在**每一回合注入到 system prompt**（只要 `enabled=true`）。每日笔记不会——它们喂给整合服务用。

### 每个文件是干什么的

- **AGENTS.md**——Agent 自己的使用说明书。什么时候该写记忆、每个地方放什么、有哪些工具可以操作记忆。种子：`enabled=true`，`sort_order=0`。
- **SOUL.md**——Agent 从根上是谁。自我意识、演化指引、隐私与边界原则。想在深层修改 Agent 的性格时编辑它。种子：`enabled=true`，`sort_order=1`。
- **PROFILE.md**——Agent 学到的关于你的东西。名字、职业、技术栈、沟通偏好。对话里出现值得保留的东西时记忆提取器会更新它。全覆盖写入。种子：`enabled=true`，`sort_order=2`。
- **MEMORY.md**——Agent 认为重要到值得留下的东西。活跃项目、未决定的事、打开的线索、你让它记住的东西。提取器和整合器都会更新它。种子：`enabled=true`，`sort_order=3`。

::: tip 1.3.0 新增：工作流可以写记忆
v1.3.0 起，[工作流](./workflow) 的 `write_memory` step 可以在流程跑完时把结果直接写进某位员工的 `MEMORY.md`（或任意启用的 memory 文件），支持 4 种合并策略：`append` / `replace_section` / `upsert_kv` / `overwrite`。这意味着记忆不再只能由对话提取或 Dreaming 写入——一条业务流程的产物也可以被沉淀。
:::

### 每日笔记

对话亮点按日期归档，**追加模式**——同一天里的多次对话全部累加到同一个文件。这些不会注入到 system prompt（`enabled=false`）。它们存在是为了让整合器凌晨两点跑的时候有东西可扫。

---

## 短期：上下文窗口

每一次 LLM 调用之前，MateClaw 都会构造真正送出去的那个 prompt：

```
[System Prompt]                        ← 永远在最前
[工作空间文件注入]                      ← AGENTS / SOUL / PROFILE / MEMORY
[对话上下文摘要]                        ← 只有在早期轮次被压缩过时才有
[Message 1: user]
[Message 2: assistant]
...
[当前用户消息]                          ← 永远在最后
```

工作空间文件按 `sort_order` 排序拼进 system prompt，格式：

```
--- AGENTS.md ---
（内容）

--- SOUL.md ---
（内容）

--- PROFILE.md ---
（内容）

--- MEMORY.md ---
（内容）
```

只注入 `enabled=true` 的文件。

### 上下文爆了怎么办

三层防御：

**第一层：主动压缩。** 估算总 token 超过预算的 75%（默认窗口 12.8 万 token），系统让 LLM 总结早期轮次。最近 2 轮（4 条消息）保留原文。结果缓存 30 分钟。

**第二层：紧急恢复。** 如果 LLM 仍然返回上下文超限，系统不再调 LLM，直接丢掉更早的消息、保留最后 2 轮、重试一次。

**第三层：硬截断。** 总结之后还是超，从前往后继续丢消息直到 prompt 装得下。最近 2 条永远不动。

> **安全设计**——摘要以**用户消息**形式注入，**不是**系统消息。刻意的：防止早期用户输入的压缩版本被提升成系统级指令，关掉一条注入攻击路径。

### 配置

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

---

## 对话后提取

一次对话结束之后，系统会异步地把值得记住的东西提取出来、写进 PROFILE.md、MEMORY.md、当天的日常笔记。发生在用户响应路径之外——**永远不会阻塞下一个回合**。

### 什么时候触发

一个回合完成之后，系统在后台线程处理这次对话。需要满足几个条件才会真的跑提取：

- 自动总结开关打开
- 不是定时任务自己触发的对话（防止递归）
- 消息数达到下限（默认 4 条）
- 最后一条用户消息够长（默认至少 10 字符）

全部通过，开始提取。

### 并发控制

- **冷却**——同一个 Agent 在默认 5 分钟内不会重复提取
- **按 Agent 加锁**——同一个 Agent 已经有一个提取任务在跑，新任务直接跳过

### LLM 实际在做什么

1. 从对话历史里加载消息
2. 读当前的 PROFILE.md、MEMORY.md、今天的日常笔记
3. 构造 transcript：最多 30 条消息，每条截断到 2000 字符
4. 用记忆总结的 prompt 模板调 LLM
5. 解析 JSON 响应
6. 执行写入

### LLM 响应 schema

| 字段 | 类型 | 作用 |
|------|------|------|
| `should_update` | boolean | 记忆是否需要更新 |
| `reason` | string | 原因（用于审计） |
| `daily_entry` | string | 追加到今天日常笔记的内容 |
| `memory_update` | string | MEMORY.md 的全新全量内容 |
| `profile_update` | string | PROFILE.md 的全新全量内容 |

### 文件写入规则

- **PROFILE.md**——全覆盖，只在 `profile_update` 非空时写
- **MEMORY.md**——全覆盖，只在 `memory_update` 非空时写
- **memory/YYYY-MM-DD.md**——追加，文件不存在时用日期标题新建

---

## 整合与 Dreaming

第三层按计划跑。它的工作是看着日常笔记堆起来，周期性地问自己：*这里的模式是什么？哪些东西应该被提升进核心记忆？哪些东西过期了应该被遗忘？*

### 它做什么

1. 列出 Agent 所有 `memory/*.md` 文件，取最近 7 天
2. 读这些日常笔记 + 当前 MEMORY.md
3. 用整合 prompt 模板调 LLM
4. LLM 返回 `{should_update, reason, memory_content}`
5. 如果 `should_update` 为 true，MEMORY.md 被 `memory_content` 全覆盖

### 触发方式

- **自动**——每个 Agent 在系统定时任务里有一行，每天凌晨 2 点跑一次
- **手动**——`POST /api/v1/memory/{agentId}/emergence`

### 为什么不会递归

整合跑起来的时候会通过 Agent 触发一次"对话"。没有保护的话，那次对话会再触发对话后记忆提取监听器，循环下去。

事件上带触发源标记，提取监听器看到是定时任务触发的就直接跳过。

### DREAMS.md —— 整合日记

每次整合跑完会往 `workspace/{agentId}/DREAMS.md` 追加一条短记录：

- 它看了什么
- 它找到了什么模式
- 因此 MEMORY.md 变了什么
- 日期

这给你一条人类可读的审计线——你可以打开 DREAMS.md，看**记忆是怎么一步步走到当前状态的**。这个文件也有自我增长的上限，超过阈值会对旧记录做总结。

### 打分式 Emergence + 召回追踪

整合不是盲目地总结。它会追踪：

- **哪些记忆条目在最近的对话里真的被主动召回**——Agent 的读取模式反过来影响整合对"什么重要"的判断
- **打分式 emergence**——候选模式按频率 + 近期性 + 显式召回打分，只有高分的才能进 MEMORY.md
- **多闸门过滤**——低信号的提取（一次性提及、矛盾、用户后来主动纠正过的）会在变成记忆之前被过滤掉
- **Dreaming 状态 API**——`GET /api/v1/memory/{agentId}/dreaming/status`

### 完整生命周期（开关控制）

记忆从"夜里梦一次"升级成完整的逐轮生命周期。这套行为落在开关后面——开源版默认关，生产构建打开。

它做的事：

- **每一轮都被记账** —— 每一回合开始和结束时系统都在记笔记，不只是夜里整合的时候
- **事实投影** —— 对话被拆成结构化的"事实"行，Agent 可以查询。带信任度评分 + 衰减。
- **结构化的夜间报告** —— 整合产出一份完整报告，可以按主题手动重做
- **晨报卡片** —— 第二天第一次对话浮出昨天的报告；逐条 Confirm / Edit / Forget
- **矛盾收件箱** —— 新事实和老事实冲突时给一个决策队列，而不是悄悄覆盖
- **显式遗忘** —— 你说"忘掉"，它就真的忘掉，从所有地方
- **反馈打分** —— 检索到的事实点👍/👎，反馈进入信任度评分
- **SOUL 自动演化** —— Agent 的人格档会从累积的事实里自我重写
- **月度归档** —— 老报告滚进压缩的月度归档，时间线里能查
- **记忆浏览器** —— 时间线、事实、矛盾、变更对比、信任度面板

`application.yml` 启用：

```yaml
mateclaw:
  memory:
    dream-v2:
      enabled: true
      fact-projection: true
      contradictions: true
      morning-card: true
```

---

## Agent 自己读写自己的记忆

记忆不是单向地"发生在 Agent 身上"的事。Agent 自己在对话过程中可以主动读写自己的文件——通过一组工作空间记忆工具：

| 方法 | 作用 |
|------|------|
| `list_workspace_memory_files` | 列出 Agent 的文件，可按文件名前缀过滤，按 `sort_order` 排序 |
| `read_workspace_memory_file` | 读某个文件的内容 |
| `write_workspace_memory_file` | 创建或覆盖一个文件（全覆盖） |
| `edit_workspace_memory_file` | 按精确查找替换编辑（增量更新，支持 `replaceAll`） |

### 示例

**列表：**

```json
// 输入
{"agentId": 1, "filenamePrefix": "memory/"}
// 输出
{"agentId": 1, "count": 3, "files": [
  {"filename": "memory/2026-04-09.md", "enabled": false, "fileSize": 512},
  ...
]}
```

**读取：**

```json
// 输入
{"agentId": 1, "filename": "MEMORY.md"}
// 输出
{"agentId": 1, "filename": "MEMORY.md", "enabled": true, "content": "..."}
```

**编辑：**

```json
// 输入
{"agentId": 1, "filename": "MEMORY.md", "oldText": "旧内容", "newText": "新内容"}
// 输出
{"agentId": 1, "filename": "MEMORY.md", "replacements": 1}
```

### 安全约束

- 只允许 `.md` 文件
- 不允许绝对路径，不允许 `..` 目录穿越
- `write` 是全覆盖——在乎已有内容就先 `read`
- 新建的文件默认 `enabled=false`

---

## 配置参考

### 记忆提取 & 整合

```yaml
mate:
  memory:
    # --- 自动提取 ---
    auto-summarize-enabled: true
    min-messages-for-summarize: 4
    min-user-message-length: 10
    skip-cron-conversations: true
    summary-max-tokens: 1000
    max-transcript-messages: 30

    # --- 并发 ---
    cooldown-minutes: 5

    # --- 整合 / dreaming ---
    emergence-enabled: true
    emergence-day-range: 7
```

配置前缀：`mate.memory`。

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

---

## API 接口

| 方法 | 路径 | 用途 |
|------|------|------|
| POST | `/api/v1/memory/{agentId}/emergence` | 手动触发整合 |
| POST | `/api/v1/memory/{agentId}/summarize/{conversationId}` | 对某次对话手动触发提取 |
| GET | `/api/v1/memory/{agentId}/dreaming/status` | 查询上次运行、下次计划、最新 DREAMS.md 条目 |

---

## 下一步

- [Agent 引擎](./agents)——Agent 在一个回合里怎么用记忆
- [LLM Wiki](./wiki)——**刻意的**知识层，和被动的记忆对照
- [工具系统](./tools)——记忆读写工具是众多工具之一
- [配置说明](./config)——完整配置参考
- [架构说明](./architecture)——后端代码组织、SPI 扩展点
