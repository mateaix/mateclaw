---
title: 持久化目标 — 跨多轮锁定，让员工自己跟进
description: MateClaw 的 Goal 系统让数字员工把跨多轮的任务锁成一个目标，自己评估进度、自己续命，直到完成或耗尽预算。
head:
  - - meta
    - name: keywords
      content: Goal,目标管理,Agent,多轮对话,自动评估,auto-followup,持久化,MateClaw
---

# 持久化目标

> **以前你每轮都要把上下文重复一遍。现在你定一个目标，员工自己跟。**

一次对话里你说"帮我把这个博客部署到 fly.io"，员工答完一轮就停了。下一轮你要再问"DNS 配好没？证书呢？测试跑了吗？"——你在替它记目标。

Goal 把这件事翻过来。**你说一次，员工锁住目标，自己每轮自检：还差什么？要不要自己再做一步？**

它不是聊天里的一个新功能。它是员工的一种**状态**。员工头像周围多了一圈光，光填多少就是离完成多远。完成了，光消失。

---

## 它在视觉上长什么样

不是一个 banner。不是一个 dialog。不是一个独立的标签页。

是 **assistant 头像周围的一圈环**。

| 状态 | 视觉 | 含义 |
|------|------|------|
| 无目标 | 头像就是头像 | 这条对话没绑目标，跟过去一样 |
| 进行中 | 头像 + 橙色环 | 有目标在跟，光填到进度处 |
| 评估中 | 头像 + 沙金呼吸光晕 | 后台正在判断这轮答案 |
| 已完成 | 头像 + 绿色环（短暂出现） | 目标达成，环随后消失，对话继续 |
| 预算耗尽 | 头像 + 红橙色环 | 用完 budget，需要你决定加预算还是放手 |

**hover 头像**才显示完整 tooltip — 标题 + 还差什么。不 hover 就不打扰你。这是设计意图。

---

## 怎么定一个目标

三种方式，按门槛从低到高：

### 方式 1 — 让员工自己定

你只要在第一次描述任务时让员工知道这是个长任务：

> 我要做一个完整的项目：把 README 翻译成英文、提 PR、走 review、合并。这跨多轮，**请你用 setGoal 锁定**，每轮自我评估，turnBudget=8，autoFollowup 开启。

员工识别到"长任务"+"明确要求 setGoal"两条信号，会自动调用工具创建目标，title 从对话上下文自动归纳。你只需点开它的回答，看见头像旁边多了一圈光，就知道目标已锁。

### 方式 2 — 直接命令工具

不想让员工判断，你直接告诉它调哪个工具、传什么参数：

> 请立刻调用 setGoal 工具，title="部署博客到 fly.io"，turnBudget=10，autoFollowup=true。不要问任何前置确认。

"不要问前置确认"这一句很重要 — 否则员工会先问"代码在哪？域名是什么？" 它的本能就是先澄清。

### 方式 3 — 通过 API 程序化创建

对自动化、外部脚本，REST 端点直接可用：

```
POST /api/v1/goals
{
  "conversationId": "conv-xxx",
  "agentId": "1000000001",
  "workspaceId": 1,
  "title": "部署博客到 fly.io",
  "description": "...",
  "exitCriteria": "DNS+SSL+健康检查+测试通过",
  "turnBudget": 10,
  "llmCallBudget": 200,
  "autoFollowupEnabled": false
}
```

完整接口列表见 [API 参考](./api)。

---

## 一个目标里有什么

最少四样：

| 字段 | 含义 |
|---|---|
| **标题 (title)** | 短句，光环 hover 时显示 |
| **描述 (description)** | 完整诉求 |
| **退出判据 (exitCriteria)** | LLM 可读的判据，evaluator 按这个打分（比如 "DNS 配好+测试通过"） |
| **预算 (turnBudget + llmCallBudget)** | 防失控上限 |

可选：

- **自动延续 (autoFollowupEnabled)**：开了之后，员工答完一轮如果觉得"还没完成"，会自己接着做下一步，不等你催
- **冷却 (followupCooldownSeconds)**：两次自动延续之间至少隔多久

---

## 它在后台是怎么运转的

每次员工回答完一轮，后台会跑一个评估节点。这个节点：

1. 取员工这一轮的最终回答 + 最近几条消息上下文
2. 调一个轻量 evaluator（建议指向便宜的小模型）问：完成度多少（0~1）？还差什么？该继续还是已完成？
3. 把答案写到 `mate_agent_goal_event` 时间线表里
4. 决定下一步：完成 / 预算耗尽 / 继续 / 自动延续

**关键不变量**：评估发生在 final answer 已经串给你看完之后 — **不阻塞用户看回答**。你看到回答出现 → 短暂后头像旁边的光环进度变化。

### 自动延续是怎么发生的

如果 `autoFollowupEnabled=true` 且这一轮 evaluator 判 "continue"，后台会：

1. 写一条 `followup_injected` 事件到时间线
2. 给对话末尾 APPEND 一条用户消息："Continue working on the goal. Still missing: {gap}. Take the next concrete step."
3. 让员工再跑一轮 reasoning，**这一轮的回答就直接接在第一轮后面**

你的体感是：员工答完一段 → 停半拍 → **继续往下做** — 就像一个人做完一步停了一下想了想然后继续。

---

## 4 个内置工具（员工可用）

员工的工具集里默认包含这 4 个（无需手动绑定，是 agent-wide 系统级工具）：

| 工具 | 用途 | 触发提示词示例 |
|---|---|---|
| **setGoal** | 创建目标 | "请用 setGoal 锁定本次目标，title=..." |
| **addGoalCriterion** | 追加子准则到已有目标 | "再加一条准则：必须支持 IPv6" |
| **completeGoal** | 显式标记完成 | "所有事项已做完，请 completeGoal" |
| **getGoalStatus** | 查询当前 goal 状态 | "我们现在进展到哪了？" |

完成时 (`completeGoal` 或 evaluator 判 score≥0.95)，员工会把这个目标的总结同步到[长期记忆](./memory)，后续对话能查得回来。

---

## 子员工不能改父员工的目标

[多员工协作](./agents)里 parent 员工可以委派 child 员工干活。Child **看不到**这 4 个 goal 工具 — 目标是 parent 会话的状态，child 是无状态的执行体。

> 这一条是设计意图，不是 bug。child 帮 parent 做事，但目标的"所有权"留在 parent 那。

---

## 预算耗尽时

```
turnsUsed >= turnBudget  或  (agentLlmCallsUsed + evalLlmCallsUsed) >= llmCallBudget
```

任一条命中 → 目标状态翻为 **exhausted**，不再触发评估、不再注入 follow-up，光环变橙红色。员工的最后一轮回答会正常发送给你。

你的选择：

- **加预算 + 恢复** — 通过 `PATCH /api/v1/goals/{id}` 改 budget 后 resume（v1 暂未给 UI 提供按钮，可以走 API 或先 abandon 重新创建）
- **放手** — 调 abandon，conversation 上释放槽位，可以重设新目标

---

## 状态机

```
   create
     ↓
   active
   ↓    ↑
 paused 
   
 active ──evaluator score≥0.95 / completeGoal──→ completed (终态)
   ↓
 active ──turns_used/llm_calls 用完 ─────────→ exhausted (终态)
   ↓
 active ──user abandon ─────────────────────→ abandoned (终态)
```

终态 (completed / exhausted / abandoned) 不能复活。要继续就开新 goal — 这是有意保留的简单约束，避免 "重启" 带来的预算账目混乱。

**一会话一目标**：每个 conversation 同一时刻最多一个 active goal。终态 goal 留在历史里不占名额。底层用 H2 / MySQL 的生成列 + 唯一索引保证并发安全，service 层 + DB 层双重防御。

---

## 这套系统不做什么

按设计原则保留了几个"不做"：

- **不做嵌套目标 / 目标树** — 一个 conversation 一个目标，不堆 OKR
- **不做"目标模板"** — 每个目标是手写的，不是从库里挑的
- **不做跨 conversation 迁移目标** — 想要那效果，请用[工作流](./workflow)
- **不暴露评估分数给用户** — 那个 `completionScore` 是工程内部协议，不是用户语言。UI 用一圈光说话，hover 显示 evaluator 写的 gap 文本（自然语言）。后端日志和 API 里仍可见数值，方便调试

---

## 完整事件时间线（drawer 抽屉视图）

每个目标都有一份只增不删的事件时间线，按时间倒序展示：

| 事件 | 触发 |
|---|---|
| `created` | setGoal 工具或 REST POST |
| `evaluated` | 每轮答完，evaluator 跑完一次 |
| `followup_injected` | autoFollowup 触发，注入了 prompt |
| `completed` | evaluator 判完成或 completeGoal 工具 |
| `exhausted` | budget 用尽 |
| `paused` / `resumed` / `abandoned` | 用户手动操作 |
| `criterion_added` | addGoalCriterion 工具 |

通过 `GET /api/v1/goals/{id}/events` 拉取（详见 [API 参考](./api)）。

---

## 配置项

`application.yml`：

```yaml
mateclaw:
  goal:
    # 主开关；关闭后图节点对所有调用 pass-through
    enabled: true
    # 默认 turn 预算
    default-turn-budget: 20
    # 默认 LLM 调用预算（agent + evaluator 之和）
    default-llm-call-budget: 200
    # 自动延续之间至少隔多久（秒）
    auto-followup-cooldown-seconds: 0
    # 评估器使用的模型；空字符串 = 沿用对话当前模型（便宜的小模型推荐：qwen-turbo / glm-4-flash）
    evaluator-model: ""
    # 评估 prompt 携带的历史消息条数上限
    evaluator-context-messages: 8
```

---

## 数据库

两张表，都用 `mate_` 前缀：

| 表 | 用途 |
|---|---|
| `mate_agent_goal` | 目标本体；含 status / budget / 双 LLM 计数器 / 自动延续配置 |
| `mate_agent_goal_event` | 目标的事件追加日志，drawer 时间线读它 |

迁移由 Flyway 跑 `V120__agent_goal.sql`（H2 + MySQL 双方言）。

---

## 一句话总结

**Goal 不是给员工加一个功能。是改它的状态。**

以前的员工"答完就忘"。Goal 让员工跨多轮记住一件事 — 它在干什么、还差什么、什么时候算完。你只用说一次。剩下的，让头像旁边那圈光替你跟。
