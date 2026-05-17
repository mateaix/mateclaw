---
title: 主动型 AI — AI 找你，不是你找 AI
description: 定时任务 + 多渠道交付 = 主动型 AI。每天早上九点把简报推送到飞书，竞品有大动作直接 ping 你的钉钉，AI 在该出现的时候自己出现，不需要你想起它。
head:
  - - meta
    - name: keywords
      content: 主动型AI,Ambient AI,定时任务,Cron,多渠道交付,飞书简报,钉钉推送,Slack机器人,环境式AI,主动汇报
---

# 主动型 AI

**AI 找你，不是你找 AI。**

ChatGPT、Claude、Gemini——每个 AI 助手都在等你打开它。打开浏览器、登录、点开输入框、敲字、等回答。AI 是一个你必须主动走过去的东西。

MateClaw 不是。

你可以让任何一个 Agent 在任何一个时间，去任何一个聊天软件里，**主动找你**。

```
每天早上 9:00：日报 Agent → 飞书研发群
每周一 10:00：销售数据 Agent → Slack 频道
凌晨 4:00 失败的任务 → 执行助理 Agent → DingTalk 私聊
```

这是从「对话型 AI」到 **主动型 AI** 的升维。我们叫它 **Ambient AI**——环境式 AI。它不在某个浏览器标签页里等你，**它在你的工作流里**。

---

## 它在做什么

三件事串在一起：

1. **Cron Job**——一个能跑 Agent 的调度器（`mate_cron_job`、`mate_cron_job_run` 两张表）
2. **Agent 跑一遍**——触发时间到了，调度器拉起 Agent 上下文，跑一整套工具链（搜索、抓取、读 Wiki、查数据库⋯）
3. **结果通过渠道交付**——Agent 的输出走 `CronJobCompletedEvent` → `CronDeliveryListener` → `ChannelCronResultDelivery`，落到你预设的渠道

整个过程**没有人值守**。你设置完，AI 就开始按时上班。

---

## 三个典型用法

### 一、每日简报

> 每天早上 9 点，竞品监控 Agent 跑一遍：搜索昨天发了什么 release、读 5 个目标公司的官博、对比 24 小时内的差异，把最重要的三件事写成 Markdown，推送到飞书研发群。

`Cron 表达式 0 0 9 * * ?` · `Agent: 竞品监控` · `渠道: 飞书 - 研发群`

你坐进地铁，手机弹出消息，三件事看完。到工位之前你已经知道今天要追什么。

### 二、周报汇总

> 每周一上午 10 点，销售助理 Agent 查上周的订单数据库、抽出关键指标、写一份带表格的周报，推送到 Slack 频道。

数据来自 [数据源工具](./config)，Agent 自动写 SQL 并解释结果。Wiki 里如果有过往周报，会被自动引用做对比。

### 三、事件触发

> 邮件来了带「老板」标签 → 执行助理 Agent → 总结邮件主旨 + 草拟回复 → 推送到微信。

事件触发用 cron 高频轮询 + 触发条件，或者用 [MCP 工具](./mcp) 接外部 webhook。Agent 跑完通过同一条交付链路落到渠道。

---

## 怎么配

`控制台 → 定时任务 → 新建` 三步：

1. **Cron 表达式**——什么时候跑（标准 6 段 cron，UI 里有图形化编辑器）
2. **Agent**——选一个已经配好工具和系统指令的 Agent
3. **结果交付**——选一个 [渠道](./channels) 作为输出（飞书 / 钉钉 / Slack / 企业微信 / Telegram / 任意已配置的渠道）

保存。下次触发时间一到，Agent 就开始上班。

::: tip 别配 100 个 cron
和模型管理一样——你不需要 100 个定时任务，你需要**一个真的有用的**。先做"早上 9 点的简报"，跑两周，看一下哪些信息你真的会读，哪些是噪音，再加第二个。
:::

---

## 为什么这件事重要

整个 2025–2026 年，AI 圈在追同一个目标——**不需要你打开屏幕**。

- **Vision Pro** 想让 AI 在你视野里出现，没做成
- **Humane AI Pin** 想让 AI 在你身上出现，崩了
- **Echo / Alexa** 当年想让 AI 在你家里出现，停留在天气预报

它们都试图用一个新硬件去解决这件事。

MateClaw 的答案是：**你团队已经在用的所有聊天软件，就是那个"硬件"**。

飞书、钉钉、企业微信、Slack、Telegram、Discord、QQ——你早就开着。AI 出现在你已经看的地方，就足够了。**不需要新设备，不需要新习惯。**

---

## 它和别的 AI 产品有什么不一样

| | 对话型 AI | 主动型 AI（MateClaw） |
|---|---|---|
| 触发方式 | 你打开它 | 它在该出现的时间出现 |
| 在哪 | 一个浏览器 tab | 你已经在用的 IM |
| 什么时候说话 | 你问它才说 | 你需要时它就说 |
| 离线时 | 错过 | 等你上线就推 |
| 失败时 | 红色 error | 自动重连，下一次正常推 |

最右那一列只有 MateClaw 完整实现了——因为只有 MateClaw 同时有：

- **多 Agent 引擎**（ReAct + Plan-Execute）
- **Cron 调度 + 失败重试**
- **9 个 IM 渠道适配器**（每个都有指数退避重连）
- **持久化记忆**（[Memory](./memory)，Dreaming 之后越用越懂你）
- **Wiki 知识层**（[LLM Wiki](./wiki)，让调研有依据）
- **Tool Guard**（[Security](./security)，敏感操作问你一句再执行）

Cron 是最后一块拼图——把上面这堆能力**翻译成时间触发**。

---

## 安全考虑

主动 = 需要更严格的权限控制。一个 cron 触发的 Agent 跑得没人盯着，它能调什么工具就直接调，没有"我再确认一下"的机会。

所以：

- **Cron Agent 不会绕过 Tool Guard**——需要审批的工具调用照样卡在那里，等你在 IM 里点一下批准，Agent 才继续。详见 [审批工作流](./security#审批工作流-人在回路)
- 不想被打断的，把敏感工具配成 `deny` 而不是 `require_approval`——让它在权限之外的地方直接停下，不发审批通知
- **每次 cron 执行都进审计日志**（`mate_audit_event`）——哪个任务在什么时候、用什么工具做了什么，全有记录

---

## 底层数据（如果你好奇）

| 表 | 用途 |
|---|---|
| `mate_cron_job` | 每个定时任务一行——Agent ID、cron 表达式、目标渠道、超时、启用开关 |
| `mate_cron_job_run` | 每次执行一行——开始/结束时间、状态、输出摘要、错误信息（如有） |

代码组织：

- `vip.mate.cron.service.CronJobLifecycleService` —— 任务生命周期管理
- `vip.mate.cron.service.CronJobRunner` —— 单次执行
- `vip.mate.cron.delivery.ChannelCronResultDelivery` —— 把 Agent 输出落到渠道
- `vip.mate.cron.delivery.CronDeliveryListener` —— 监听 `CronJobCompletedEvent`
- `vip.mate.cron.CronChatOriginFactory` —— 构造 cron 来源标记，会话能查到这条对话来自哪个定时任务

### API

```bash
# 列出所有定时任务
curl http://localhost:18088/api/v1/cron-jobs \
  -H "Authorization: Bearer <token>"

# 立刻试跑一次（不影响下次定时触发）
curl -X POST http://localhost:18088/api/v1/cron-jobs/{id}/run-now \
  -H "Authorization: Bearer <token>"

# 看历史执行
curl http://localhost:18088/api/v1/cron-jobs/{id}/runs \
  -H "Authorization: Bearer <token>"
```

---

## 下一步

- [多渠道接入](./channels) —— 把 AI 输出送到哪一个 IM
- [Agent 引擎](./agents) —— cron 调度的就是你配好的 Agent
- [安全与审批](./security) —— 敏感操作不让 cron 自动跑
- [LLM Wiki](./wiki) —— 给 cron Agent 一座知识库做调研
