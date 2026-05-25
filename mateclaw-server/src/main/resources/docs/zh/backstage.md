---
title: Backstage —— Admin 运行时控制台，看见每个数字员工正在干什么
description: Backstage 是 admin 专用的实时视图，列出当前在岗的每一位数字员工。状态环头像、saying 行、watchdog 卡死 / 孤儿判定、软停 / 强停 / 一键全清、按子 Agent 中断。用户说"卡住了"的时候，你打开这一个 URL。
head:
  - - meta
    - name: keywords
      content: agent 运行时,运行时控制台,强制回收,卡死的 Agent,孤儿 run,多 Agent 可观测,SSE 流清理,数字员工,实时 Agent 可视
---

# Backstage —— Admin 运行时控制台

**有人说"我的 Agent 卡住了"，你打开的就是这个页面。**

数字员工在某一步冻住，是 MateClaw 里少数不会自我修复的事情之一。流挂着、子 Agent 散到一地、SSE buffer 还活着但没人读。Backstage 是把这些东西全部摊开给你看、并且让你伸手干预的那块屏幕。

它是 **admin 专用**（`ROLE_ADMIN`）、实时（每 5 秒自动刷新，可暂停），故意做得很简单——一个正在跑的 Agent 一张卡片，四个动作按钮，没有菜单。

---

## 怎么打开

::: tip 1.4.0：实时视图并入员工页
v1.4.0 起，这块实时运行时视图被折叠进了**员工（Employees）页**。`/backstage` 现在会**重定向到** `/agents?view=live`；员工页顶部有一个 **花名册（Roster）/ 实时（Live）** 的分段切换——"实时" 就是本文描述的运行时控制台。下面提到的"路由 `/backstage`"仍然可用，只是会落到员工页的实时视图。
:::

- **路由：** `/backstage`（重定向到 `/agents?view=live`）
- **侧边栏：** 员工页的 **实时** 分段；当有员工卡死时，侧边栏会冒出一个**橙色"卡死员工"小圆点**，点它直接跳到这个实时视图
- **权限：** JWT 必须带 `ROLE_ADMIN`。非 admin 调用 `/api/v1/admin/agent-runtime/*` 全部 403，路由守卫还会把侧边栏入口直接藏起来。

---

## 你看到的

一栅卡片，每张卡片对应一位正在干活的数字员工。页面顶部那个自动刷新 chip 显示实时流是否在跑；点一下就暂停（比如你正要点按钮，不想卡片在你光标下挪位）。

什么都没在跑的时候页面是一个安静的"all quiet"空状态——这是设计，不是缺页。

### 卡片字段一览

| 元素 | 含义 |
|---|---|
| **头像 + 状态环** | Agent 头像外面套一圈彩色环：**绿色呼吸** = 健康、**橙色慢呼吸** = 卡死、**淡紫色** = 孤儿 |
| **Agent 名 + 所有者** | 显示名 + 启动这次 run 的用户 `@username` |
| **Saying 行** | 一句人类可读的状态描述（例如 *"在分析检索到的 chunk…"*）——来自 runtime 上一次发布的阶段事件 |
| **Tool chip** | Saying 行旁边一个独立 chip，显示当前正在跑的工具（如果有的话） |
| **运行时长** | 这次 run 的人类可读年龄（例如 `2m 34s`） |
| **Orphan 徽标** | 当 `orphan && !stuckReason` 时显示。意思是 run 在内存里还活着，但没人在订阅它的流 |
| **进度条** | `ageMs > 30 秒` 时出现。按 5 分钟窗口线性插值——一眼就能看出谁在偏离正常节奏 |
| **子 Agent 堆栈** | 最多展示 3 个子 Agent 头像，多余的折叠成 `+N`。点 `+N` 展开列表 |
| **动作按钮** | Stop / End / 中断子 Agent——见下面 |

页面顶部还有一条计数：**N 个运行中 · M 个卡死 · K 个孤儿**。这些数字和卡片来自同一次 `/snapshot` 调用，所以一定和你看到的一致。

---

## 动作按钮

每一个动作都直接作用于内存里那个会话的 `RunState`，不是只动数据库行。

| 动作 | 端点 | 什么时候用 |
|---|---|---|
| **Stop**（软停） | `POST /api/v1/admin/agent-runtime/runs/{conversationId}/stop` | Agent 还在推进，但你希望它跑完当前这一步就停。协作式——它把当前在做的事情做完，然后干净退出。 |
| **End**（强停） | `POST /api/v1/admin/agent-runtime/runs/{conversationId}/recycle` | Agent 有 `stuckReason`。释放 SSE flux、丢弃 `RunState`、放掉会话行。run 没卡死时这个按钮**不显示**，避免你手滑。 |
| **Tidy Up** | `POST /api/v1/admin/agent-runtime/sweep` | 把当前页所有卡死的 run 一键回收。provider 故障之后用这个，比一个个点快得多。 |
| **中断子 Agent** | `POST /api/v1/admin/agent-runtime/subagents/{subagentId}/interrupt` | 取消一个被委派出去的子 run，不影响父 run。父 run 会收到一个 `delegation_cancelled` 事件，由它自己决定重试还是放弃。 |

页面背后那个只读的**快照端点**也是公开的：

```
GET /api/v1/admin/agent-runtime/snapshot
```

返回 running / stuck / orphan 计数和卡片渲染所需的 per-run 详情。要接到 Grafana 或者你自己的 ops dashboard 上很方便。

---

## 什么算 Stuck，什么算 Orphan

两个独立条件，两个独立信号。

### Stuck —— runtime 等不下去了

run 上的 `stuckReason` 非空。runtime 的 watchdog 在某个步骤超过超时阈值时打上：

| 步骤 | 默认阈值 | 说明 |
|---|---|---|
| 短步骤（推理 chunk、状态更新） | 30 秒 | token 级活性 |
| 工具调用 | 150 秒 | 内置工具和 MCP / ACP 工具都算 |
| 整轮 | 600 秒 | 端到端封顶 |

三者都可配置。reason 字符串典型形如 `tool_call.timeout(150s)` 或 `reasoning.no_progress(30s)`，所以卡片不光告诉你"它死了"，还告诉你"为什么死的"。

进入 stuck 之后，runtime 不再喂数据但**不会**把它直接拆掉——拆不拆是你的决定。点 **End** 就回收；想等等看上游恢复也行（个别 provider 退化时确实会卡 5 分钟以上）。

### Orphan —— 还活着但没人看

`orphan && !stuckReason`。run 还在推进，但没人在读流：

- 用户关掉浏览器标签后再没回来
- 桌面 App 中途崩了
- 外部渠道适配器（钉钉、飞书……）丢了 webhook session

孤儿 run 不会被自动回收——它有可能跑完然后写出一个有用的对话轮次。徽标是信息性的。要资源就回收，不在乎就让它跑。

一个 run 可以同时既是 stuck 又是 orphan；这种情况下 **stuck 信号胜出**，orphan 徽标会被压下，避免一行两个 pill 让人犯迷糊。

---

## 一次典型操作

用户在 Slack 上 ping 你："我的 Agent 已经转 10 分钟了。"

1. 打开 `/backstage`。
2. 找到那张卡。橙色状态环在你看任何文字之前就告诉你它卡死了。
3. 读一下 saying 行 + tool chip——通常足以看出 Agent 上一步在干什么。
4. 想知道是什么触发的，从卡片链接进会话看；不想看就直接点 **End**。
5. 卡片在下一次 5 秒刷新时消失。

总耗时大概 15 秒。这就是这个页面存在的理由。

如果多个用户同时反馈——一般是某个 provider 挂了——直接 **Tidy Up** 一键全清，比一个一个分诊快。

---

## 运维注意

- **刷新成本** —— snapshot 端点遍历的是内存 map。5 秒一次的自动刷新很便宜，没必要降频。
- **审计** —— 每一次 Stop / End / Tidy Up / 中断都走标准审计管线。`mate_audit_event` 里搜 `agent_runtime.stop` / `.recycle` / `.sweep` / `.interrupt_subagent` 即可。
- **空状态** —— 已知很忙的时段页面却空着，多半是 runtime 重启后内存里的 `RunState` 丢了。会话本身在 `mate_conversation` / `mate_message` 里完好无损，只是实时连线没了。
- **多副本部署** —— `RunState` 存在那个正在伺服 SSE 流的 JVM 里。Backstage 给你看的是**伺服了这次 snapshot 请求的那个副本**上跑的东西。负载均衡后面，多刷几次能看到其他副本，或者用 sticky session 锁。

---

## 下一步

- [控制台](./console) —— 承载 Backstage 的整个 SPA
- [Agent 引擎](./agents) —— 卡片背后真正在跑的东西
- [Doctor 健康检查](./doctor) —— 系统级健康（磁盘、队列、provider 状态），事故定位时和 Backstage 是天然配对
- [安全与审批](./security) —— 你在 Backstage 上的每一次操作最终都落到这里
