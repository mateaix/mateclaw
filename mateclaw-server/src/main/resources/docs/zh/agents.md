---
title: 多智能体引擎 — ReAct + Plan-and-Execute 双模式
description: MateClaw 的多智能体系统支持 ReAct 推理循环和 Plan-and-Execute 任务拆解两种模式。Agent 之间可以互相委派，实现真正的多智能体协作。
head:
  - - meta
    - name: keywords
      content: 多智能体,ReAct,Plan-and-Execute,Agent委派,Spring AI Alibaba,AI Agent
---

# 多智能体引擎

> **它们现在叫"数字员工"了。** 后台所有出现"智能体"的地方都改了。底层运行时还是 Agent，但 UI 上、概念上、模板上——它们是你团队里的同事。
> 命名变了，世界观也跟着变了：你给一个员工**角色（Role）**、**目标（Goal）**、**背景故事（Backstory）**，他知道自己是谁、为什么存在；你不用再写一段冷冰冰的 system prompt 让"agent"理解任务。

**一个数字员工就是一个带工具的人格。多个员工组成一支团队。**

短版本是这个。长一点的版本：一个员工是一个名字，一段定义它怎么思考的 system prompt（包含角色 / 目标 / 背景故事），一个真正在思考的模型，一组它被允许调用的工具，可选的知识库，可选的技能包，它自己的一份记忆，以及它处理难题的方式——一步一步反应式（ReAct）还是先写计划再执行（Plan-and-Execute）。

你可以有很多个员工。每一个都专门做一类事。你把不同的活交给不同的员工。

---

## 一个数字员工有什么

| 部分 | 是什么 |
|------|--------|
| **名字** | 你和你的团队找它用的 |
| **图标** | 像素艺术风格、按角色配色，一眼认出 |
| **角色 (Role)** | 一句话——"我是产品研究员" / "我是客户支持" |
| **目标 (Goal)** | 一句话——"我帮你看清楚市场怎么动" |
| **背景故事 (Backstory)** | 它来自哪、为什么存在、它在意什么；这些会自动拼到最终 system prompt |
| **员工卡片标语 (Tagline)** | 卡片上展示的一句"自我介绍" |
| **System Prompt** | 它的人格、规则、风格、优先级（角色/目标/背景故事会自动注入） |
| **类型** | `react` 或 `plan_execute` |
| **工具** | 它被允许调用的工具（来自内置工具、MCP、技能包、ACP 桥接） |
| **知识库** | 它可以读的 LLM Wiki（KB 热点会自动注入到 system prompt） |
| **工作空间记忆** | 它自己那一份 `PROFILE.md`、`MEMORY.md`、`SOUL.md`、`AGENTS.md`，以及每日笔记 |
| **最大迭代次数** | 强制收敛前允许走多少轮推理循环 |
| **启用开关** | 关掉它 |

注意一个**没有**的东西：模型。整个 MateClaw 部署里只有**一个全局默认模型**（在 `设置 → 模型` 里设置），所有 Agent 在运行时用的都是它。Agent 行上那个 `model_name` 字段是历史遗留——**被忽略**。这是刻意的：换模型是整个部署一次点击的事，不是三十次。

---

## 模板：从一个已经会工作的同事开始

你不是从一张白纸开始。`数字员工 → 新建` 打开一个模板选择器。里面有两层：

### 5 个职业模板（推荐）

每一个都自带角色、目标、背景故事、合适的工具集、像素艺术头像、专属配色——**打开就能用**：

- **产品研究员**——竞品调研、市场动态追踪、用户访谈整理
- **客户支持**——接住客户问的事、查知识库、把不能解决的升级出去
- **知识管理员**——把零散材料整理进 LLM Wiki、维护双向链接、定期归纳
- **数据分析师**——查数据源、跑 SQL、出图表、写结论
- **行政助理**——日程、邮件草稿、跨工具协调

### 通用模板（白纸或半成品）

- **通用助手**——默认的聊天员工
- **研究 / 代码 / 写作 / 知识策展 / 数据分析**——按用途分类的半成品
- **自定义**——彻底白纸一张，知道自己要什么就选这个

选一个，给它起名字、调一下角色和目标，保存。**一分钟以内就有一个能上岗的同事。** 创建之后每一项都能改。

---

## 两种思考方式

### ReAct —— 思考、行动、观察、继续

默认模式。ReAct 模式下的 Agent 跑一个循环：**推理**下一步该做什么，**行动**（可能调一个工具），**观察**结果，决定是再循环一次还是回答。

适合：
- 简单问答，需要一两次工具调用
- 对话式交互，每一轮用户输入都不大
- 需要 Agent "边学边反应"的任务

例子：*"北京今天什么天气？"* → 推理（需要实时数据）→ 行动（调 web search）→ 观察（15–26°C，晴）→ 回答。

### Plan-and-Execute —— 先计划、再执行

适合更大的任务。Agent 先生成一个**计划**——一个由 2 到 6 个步骤组成的有序列表，然后一步一步执行。完成之后，总结一下所有做过的事。

适合：
- 多步研究（"调查 X，对比 Y，写一个简报"）
- 步骤在一开始就能想清楚的任务
- 你想**看着进度跑**的任务——计划和每一步的状态会出现在对话旁边的**持久任务清单**里

例子：*"研究一下 Spring AI 框架，对比前三个，给我写个简报。"* → 计划（4 步）→ 按顺序执行 → 最终汇总。

### 怎么选

| 情境 | 用哪个 | 为什么 |
|------|--------|--------|
| 简单问答、单工具调用 | ReAct | 没有计划开销 |
| 信息检索 | ReAct | 一般 2–3 轮就结束 |
| 多步有序任务 | Plan-and-Execute | 显式计划更好看、更好 debug |
| 研究 + 对比 + 写作 | Plan-and-Execute | 每一步的结果喂给下一步 |
| "读这份文件然后告诉我 X" | ReAct | 一个工具，一个答案 |
| "给我写一份关于 X 的结构化报告" | Plan-and-Execute | 多轮收集 + 综合 |

类型可以随时改。同一份 system prompt 在两种模式下都能工作得不错。

---

## 多 Agent 并行委派

一个 Agent 不是孤军作战。一个 Agent 可以把任务委派给另一个——或者**同时委派给三个**。

- **单点委派** —— 把一个子任务交给指定 Agent，在独立会话中执行，结果流式回传
- **并行委派** —— 同时委派给多个 Agent，每个在自己的隔离会话里跑
- **子会话实时可见** —— 你在 ChatConsole 里能看到每个子任务的推理、工具调用和进度
- **路由提示** —— 系统 prompt 里内置，Agent 知道什么时候该自己做、什么时候该委派

例子：让代码 Agent 处理 Jira 工单，同时让研究 Agent 拉竞品数据，同时让写作 Agent 起草 Slack 回复。三路并行，结果汇总给编排者。

---

## 深度思考

不是所有问题都值得深度推理，但有些问题需要。MateClaw 支持按 Agent、按对话打开深度思考模式：

- **`thinkingLevel`**：`off` / `low` / `medium` / `high` / `max`
- 支持 Anthropic extended thinking、DashScope qwq 推理、OpenAI o1 `reasoning_effort=high`
- 思考块流式进入 UI，做成可折叠面板——你看得见模型怎么想，token 不会浪费在不需要推理的闲聊上

---

## 雇佣一个数字员工

`数字员工 → 新建`：

1. 选一个模板（5 个职业模板之一，或通用模板，或 Custom）
2. 起名字，挑头像（像素艺术风格的库可选，或自己上传）
3. 写**角色 (Role)**——一句话；**目标 (Goal)**——一句话；**背景故事 (Backstory)**——几句话
4. 写一句**员工卡片标语 (Tagline)**——卡片上展示的"自我介绍"
5. 选类型（`react` 或 `plan_execute`）
6. 写或改 system prompt（角色 / 目标 / 背景故事会自动拼接进来，不用重复写）
7. 勾选它能用的工具，绑定它该读的知识库
8. 设置 `max_iterations`（默认 10）
9. 保存

立刻生效。从聊天 UI 或 API 开始用。

### 工具绑定（per-agent tool picker）

::: tip 1.3.0 新增
在 v1.2.0 时，员工的工具绑定是平铺的"勾哪个就能用哪个"列表。v1.3.0 把这块重做成**分组 + 状态感知 + 命名空间感知**的 picker，专门处理 MCP 工具的脏状态。
:::

打开数字员工编辑器的"工具"标签页：

- **按来源分组**：内置工具 / 技能注入工具 / MCP 工具（按 server 名再分组） / ACP 工具
- **状态徽标**：每个工具有一个标签——
  - `connected` —— 当前可用
  - `stale` —— 这个 MCP server 现在连不上，但绑定还保留（恢复连接后立即可用）
  - `unavailable` —— server / skill 已被禁用，绑定保留但运行时不会下发给员工
  - `orphan` —— **不存在**的工具引用（server 删了或 tool 改名了）；保存时会**拒绝**保留这种引用，强制清理
- **命名空间冲突**：两个不同 MCP server 提供同名工具时（比如两个都有 `read_file`），picker 显示完整 prefixed name（`server-a__read_file` / `server-b__read_file`）；员工的 system prompt 里把它们映射回原始名以避免混淆
- **保存时校验**：勾选的每个工具会跑 `AgentBindingService.validate(...)`——任何 orphan 引用直接报错，必须先去掉
- **MCP server 重命名**：原来挂在某 server 上的绑定**自动跟随**到新名字（按持久化的 tool cache 匹配），不需要手动重新勾

UI 入口：`Agents → 选员工 → 工具`。

技术细节见 [MCP](./mcp#per-agent-工具绑定)。

### System Prompt 最佳实践

System prompt 是数字员工的声音、优先级、约束的来源。**角色 / 目标 / 背景故事**和技能指令、工作空间记忆系统会自动拼接到最终 prompt 里——这些部分你不用自己写。

**你自己**的 prompt 应该包含：

1. **它该怎么说话**——语气、风格、措辞偏好（"专业但不死板" / "面向客户保持谨慎"）
2. **它被允许做什么、被期望做什么**——任务边界
3. **不确定时怎么办**——"先搜，不要编造" / "跑危险命令前问我"
4. **输出格式**——需要结构就明说

**不要**写的东西：

- 工具描述——会自动注入
- 工作空间记忆的使用说明——从 `AGENTS.md` 来
- 框架层的行为（工具调用格式、ReAct 结构）——别跟运行时对着干

示例：

> 你是一个专业的技术文档助手。你的职责：
>
> 1. 根据用户需求搜索并整理技术资料
> 2. 用清晰、结构化的方式回答问题
> 3. 确保代码示例语法正确
> 4. 不确定的时候先搜索，不要捏造
>
> 原则：
> - 引用外部信息时注明来源
> - 涉及时效性问题，先获取当前日期再搜索

---

## 给开发者：Agent 内部是怎么转的

只是用 Agent 的话，这一节可以跳。在它上面写代码（加节点、改路由、做扩展）的话，请直接看 [架构说明](./architecture)——StateGraph 拓扑、节点列表、共享状态 key、扩展点都在那一页。

---

## 生命周期状态

| 状态 | 含义 |
|------|------|
| `IDLE` | 等待输入 |
| `PLANNING` | 正在生成计划（Plan-and-Execute 模式） |
| `EXECUTING` | 正在执行工具调用或子任务 |
| `RUNNING` | 活跃的 ReAct 循环或 Plan-Execute 图执行中 |
| `WAITING_USER_INPUT` | 暂停，等用户响应 |
| `DONE` | 完成 |
| `FAILED` | 执行失败 |
| `ERROR` | 错误状态 |

回合的结束原因：

| 值 | 含义 |
|----|------|
| `NORMAL` | LLM 给出了直接的最终答案 |
| `SUMMARIZED` | 上下文压缩之后正常完成 |
| `MAX_ITERATIONS_REACHED` | 到达迭代上限被强制收敛 |
| `ERROR_FALLBACK` | 出错后降级的答案 |

---

## 可靠性机制

这些是运行时自己在做的事，目的是让 Agent 在你不想去 debug 的那种地方不脆弱：

- **上下文修剪**——上下文窗口快满时，早期轮次由 LLM 总结、摘要替换原文。缓存 30 分钟。摘要以用户消息形式注入，不是系统消息——防止历史内容被提升成系统级指令的注入风险。
- **思考恢复**——流式中途断了，已经写出的思考和内容会持久化，会话重载时还在。
- **迭代上限处理**——到达 `max_iterations` 不会崩溃，而是强制让 LLM 用现有信息生成一个尽力而为的总结答案。
- **僵尸流清理**——后台跟踪每一个打开的 SSE 流，被遗弃的会被自动回收。
- **429 重试**——LLM 限流错误会触发带退避的自动重试。
- **重复检测**——抓住那些反复在同一个工具调用上打转的 Agent，强行把它拉出循环。
- **工具超时可配置**——一个慢工具不会冻结整个回合。
- **渠道健康监控**——失败的渠道适配器走指数退避重启。

这些没有一个是用户面的按钮。它们就自己在发生。

---

## Agent 管理 API

### 创建

```bash
curl -X POST http://localhost:18088/api/v1/agents \
  -H "Authorization: Bearer YOUR_JWT_TOKEN" \
  -H "Content-Type: application/json" \
  -d '{
    "name": "技术助手",
    "description": "专业的技术文档助手",
    "agentType": "react",
    "systemPrompt": "你是一个专业的技术文档助手...",
    "maxIterations": 10
  }'
```

### 列表 / 获取 / 更新 / 删除

```bash
curl http://localhost:18088/api/v1/agents -H "Authorization: Bearer YOUR_JWT_TOKEN"
curl http://localhost:18088/api/v1/agents/1 -H "Authorization: Bearer YOUR_JWT_TOKEN"

curl -X PUT http://localhost:18088/api/v1/agents/1 \
  -H "Content-Type: application/json" \
  -H "Authorization: Bearer YOUR_JWT_TOKEN" \
  -d '{"name":"技术助手 v2","maxIterations":15}'

curl -X DELETE http://localhost:18088/api/v1/agents/1 \
  -H "Authorization: Bearer YOUR_JWT_TOKEN"
```

### 流式对话

```bash
curl -N "http://localhost:18088/api/v1/agents/1/chat/stream?message=你好&conversationId=default" \
  -H "Authorization: Bearer YOUR_JWT_TOKEN"
```

---

## 调试

在 `application.yml` 里打开 DEBUG 日志：

```yaml
logging:
  level:
    vip.mate.agent: DEBUG
    vip.mate.agent.graph: DEBUG
```

你会看到节点一节点的执行过程：状态切换、dispatcher 路由决策、迭代数、工具调用参数和结果摘要、Tool Guard 检查结果。

### 常见问题

| 症状 | 可能原因 |
|------|----------|
| Agent 不响应或超时 | 模型配置错、API Key 无效、额度用光 |
| Agent 卡在循环里出不来 | `max_iterations` 太低，或者某个工具反复报错 |
| `MAX_ITERATIONS_REACHED` 经常触发 | 调 system prompt 或者把上限调高 |
| 工具调用悄悄失败 | Tool Guard 在拦——看 `mate_tool_guard_audit_log` |
| 等审批的图恢复不了 | `chatWithReplay` 里 `toolCallPayload` 格式对不上 |

---

## 下一步

- [工具系统](./tools)——Agent 能调用什么
- [技能系统](./skills)——怎么扩展 Agent 能做的事
- [LLM Wiki](./wiki)——知识怎么被 Agent 读到
- [记忆系统](./memory)——Agent 怎么跨会话记住东西
- [工作流](./workflow)（1.3.0+）——把多个数字员工 + 系统动作编排成一条业务流程
- [触发器](./triggers)（1.3.0+）——让事件自动启动工作流或员工对话
- [架构说明](./architecture)——StateGraph 运行时深入
