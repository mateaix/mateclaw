# 触发器（Triggers）

::: tip 1.3.0 新增
触发器系统自 v1.3.0 起提供。在 v1.2.0 及更早版本里，工作流和员工对话只能被手动调起。
:::

**触发器是什么**：把"系统里发生的事件"和"要执行的动作"连起来。事件可以是定时（cron）、是 webhook 来了、是某个渠道收到消息、是某个员工跑完了某次对话、是另一个工作流跑完了。动作可以是启动某个工作流，也可以是直接给某个员工发消息让它处理。

**触发器不是什么**：
- 不是 cron 任务管理器替代品——`mate_cron_job` 仍然存在并独立运作；触发器**复用**它的 ShedLock + 调度器底座，但**不写入** `mate_cron_job`
- 不是 IFTTT / n8n 风格的可拖拉自动化——触发器只负责"事件 → 动作"的路由；复杂逻辑放到 [工作流](./workflow.md) 里
- 不是 webhook 的全功能 dispatcher——它只做去重 / 限流 / bot self-msg 过滤 / pattern 匹配，不替你解析复杂业务报文

::: warning v1.3.0 范围
v0 = 6 种 pattern type + 2 种 dispatch target（agent / workflow）。安全治理（事件去重、per-trigger rate limit、循环保护、bot 自消息过滤）是默认开的。
:::

---

## 一分钟看懂

```jsonc
// 触发器：每天早上 9 点跑一次"晨报工作流"
{
  "name": "daily-morning-report",
  "patternType": "cron",
  "patternJson": {
    "cronExpression": "0 0 9 * * *",
    "timezone": "Asia/Shanghai"
  },
  "targetType": "workflow",
  "targetId": 12345,
  "payloadTemplate": "{ \"date\": \"{{ now | date('yyyy-MM-dd') }}\" }",
  "rateLimitPerMin": 10,
  "dedupWindowSecs": 60,
  "botSelfFilter": true,
  "enabled": true
}
```

每天 9 点 → 后端通过 `CronDelegationPort` 抢到 ShedLock 锁 → 渲染 payload → 调起 workflow `12345` 异步运行。其它实例同一时刻被锁挡住，不会重复触发。

---

## 6 种 pattern type

实现在 `TriggerPatternMatcher.java`。每个 pattern 对应 trigger 行的 `pattern_json` 列里一段 JSON。**未列出的字段表示 v0 不识别**——matcher 对未知字段直接忽略。

| Pattern | 触发时机 | `pattern_json` 字段 | 复用约束 |
|---|---|---|---|
| `cron` | 按 cron 表达式定时（**不进 ingest 管道**，由 scheduler 直跑） | `cronExpression`、`timezone` | 复用 `cron/` 模块的 ShedLock + Spring TaskScheduler；**不写 mate_cron_job 实体、不调 CronJobService** |
| `webhook` | 通用事件入口透传（**v0 不做更细过滤**——secret 校验在 channel 层；trigger 这边只看 `patternType=webhook` 命中） | （v0 无字段） | 通过 `POST /api/v1/triggers/events` 入口 + envelope wrap |
| `channel_message` | 渠道收到消息 | `channelType`（可选，按 envelope `data.channelType` 比对）、`senderEquals`（可选，按 sender id 精确比对） | 旁路 `ChannelWebhookController`，原路由不变 |
| `agent_lifecycle` | 员工生命周期事件 | `agentId`（可选）、`phase`（可选，取值 `spawned` / `terminated` / `crashed`） | 挂在 `ReActLifecycleListener` 上 |
| `content_match` | 内容包含 substring 才命中 | `substring`（**必填**，envelope 的 `data.content` 字段大小写不敏感包含匹配） | 通用过滤层，事件源由 envelope 决定 |
| `workflow_completion` | 工作流跑完进入终态 | `sourceWorkflowId`（可选）、`stateFilter`（可选，取值 `completed` / `failed` / `any`） | 监听 `WorkflowEngine` 终态事件；A→B→A 递归保护见下文 |

> **未知 pattern type 默认 fail-closed**——typo 或将来加的 pattern 不会偷偷把 workspace 内所有 trigger 都点燃。
>
> **不在 v1.3.0 里**：`schedule`（不带 cron 的定时如"30 分钟后"）、外部 MQ 监听（Kafka / Pulsar / RocketMQ）、metrics / threshold 告警触发。

---

## 事件治理（默认开）

### Bot self-msg 过滤（默认绑定为 noop）

某些渠道（飞书 / 钉钉 / 企微）会把 bot 自己发的消息也回流为 `channel_message` 事件。**框架层**通过 trigger 行的 `bot_self_filter` 字段（默认 `true`）+ `BotSelfFilter` SPI 协作过滤。

::: warning v0 默认实现是 noop
开箱默认绑的是 `NoopBotSelfFilter`——`isBotSelf(...)` **永远返回 false**。这意味着 `bot_self_filter=true` 的 trigger 现在**不真过滤任何事件**。要让过滤真正生效，需要 channel 适配器侧注册一个真正能识别自己 bot id 的 `BotSelfFilter` Spring Bean（替换默认实现）。这是有意设计的——避免一个错误的 default 实现把所有合法的 bot 间通讯都误杀。
:::

要单独让一条 trigger 接受自己 bot 的消息（极少见，比如 bot 发特殊命令触发清理流程），把这条 trigger 的 `bot_self_filter` 设 `false`。

### 事件去重

事件经 `TriggerEventIngestService` 派发时，引擎在 `mate_trigger_event` 表上查 `dedup_key` 是否已经在 `dedupWindowSecs`（默认 60s）时窗内入过库。已经在 → **直接丢弃**，连 `fire_count` 都不++。

默认 `dedupWindowSecs = 60`。提高这个值可以扛更长时间的网关重投递；调到 `0` 关闭去重（**不推荐**）。

### Per-trigger rate limit

每个 trigger 单独限速：1 分钟最多 `rateLimitPerMin` 次（默认 10）。命中限速的事件被丢弃，**不**重试，**不**写 `mate_trigger_event` 行；`mate_trigger.last_error` 字段会被刷成 `"rate-limited"` 便于运维查。

`channel_message` 类 trigger 通常要调高（瞬时群发）；`workflow_completion` 类通常调低（防止 A→B→A 链路加速）。

### 递归循环保护

`workflow_completion` trigger 启动的 workflow 又触发另一个 `workflow_completion`……dispatch 链超过 5 层 → 引擎切断 + 告警。这是防止"A 写消息触发 B，B 写消息又触发 A"递归。

### Webhook ACK 时序

HTTP 入口（`POST /api/v1/triggers/events`）收到事件 → envelope wrap → dedup check → bot-self check → rate limit check → **立即 ACK 200** → 异步 dispatch。这意味着：

- 上游网关（飞书 / 钉钉 等）拿到 200 就不再重投
- 实际 dispatch 失败 → `mate_trigger.last_error` 被刷新；同 `dedup_key` 再来仍然被去重挡掉，**不重试**

如果你需要"dispatch 成功才 ACK"语义，**目前没有**——v0 故意设计为 fire-and-forget 扛峰值。

---

## 在 UI 里管理触发器

### 入口

`Triggers`（侧栏）→ 列表 + **+ 新建** 抽屉。

### 创建 trigger

抽屉里按 6 种 pattern type 各自结构化表单填字段——不需要手写 `pattern_json`：

- 选 `cron` → 给 cron 表达式输入框 + 时区下拉 + 试运行下一次触发时间预览
- 选 `channel_message` → 渠道类型可选 + （可选）按 sender id 精确匹配
- 选 `agent_lifecycle` → agent 可选 + phase（spawned / terminated / crashed）可选
- 选 `content_match` → substring 输入（**必填**），匹配 envelope 的 `data.content`
- 选 `workflow_completion` → 上游 workflow 可选 + state filter（completed / failed / any）可选
- 选 `webhook` → v0 没有额外字段（透传一切）

填完保存 → trigger 入库；`enabled=true` 时立即注册到对应引擎（cron 注册到 ShedLock；其它走 envelope 路由）。

### Payload template

`payload_template` 字段是 Pebble 模板字符串，渲染后作为 dispatch target（agent 对话或 workflow run）的输入。

```jsonc
"payload_template": "{
  \"date\": \"{{ now | date('yyyy-MM-dd') }}\",
  \"trigger\": \"{{ trigger.name }}\",
  \"sourceEvent\": {{ event | toJson }}
}"
```

模板可访问的变量：
- `now` —— 当前时间
- `trigger.{name,id,workspaceId}` —— 当前触发器
- `event` —— 当前事件 envelope（`workspaceId` / `senderId` / `data` JSON 等）

### 查看触发历史

`mate_trigger_event` 表存的是**去重元数据**——一行记录含 `trigger_id` / `dedup_key` / `received_at` / `expires_at`，不存 envelope 副本本身。要审计具体一次事件的内容，查 `mate_trigger.last_error` + dispatch 日志。

`mate_trigger.fire_count` 诚实记录有效 dispatch 次数（不计被去重 / 限速过滤掉的）；`mate_trigger.last_error` 记录最近一次失败原因。

---

## API 参考

所有 endpoint 在 `/api/v1/triggers/` 下。`v1.3.0` 实际暴露的就这些——RFC 里规划的 `/webhook/{slug}` / `/test-fire` / `/{id}/events` 暂未实装。

| 方法 | 路径 | 说明 |
|---|---|---|
| `GET` | `/api/v1/triggers` | 列当前 workspace 所有 trigger |
| `GET` | `/api/v1/triggers/{id}` | 获取详情 |
| `POST` | `/api/v1/triggers` | 新建 trigger；若 `enabled=true` 立即注册到 scheduler / 路由 |
| `PUT` | `/api/v1/triggers/{id}` | 更新（包括启用 / 禁用——改 `enabled` 字段即可）；`pattern_json` 改动时 `pattern_version++`，跨实例自取消旧 future |
| `DELETE` | `/api/v1/triggers/{id}` | 软删（等同禁用） |
| `POST` | `/api/v1/triggers/events` | **统一事件入口**——任何 webhook / channel adapter / 内部模块送一份 envelope 进来；引擎做 dedup / bot-self / rate limit / pattern match / dispatch；返回 per-trigger 命中 / 丢弃汇总 |

---

## 跟现有 cron 模块的关系

::: tip 不取代，只复用
v1.3.0 之前 MateClaw 已经有一个独立的 cron 子系统（`mate_cron_job` 表 + `CronJobService`）。Trigger 系统**不取代它**——
- 老的 cron 任务（task_type = `text` / `agent` / `reminder`）仍然在 `Cron Jobs` 页面管理
- 新的 trigger cron 在 `Triggers` 页面管理
- 两者**共享**底层 ShedLock 锁表 + Spring TaskScheduler 线程池
- `mate_cron_job` 列表**不会**显示 trigger cron；反过来也是
:::

为什么不合并？因为 `mate_cron_job` 老表的 `task_type` / `agentId` 必填等字段不适合 workflow target。强行扩列会破坏既有 product 约束。`CronDelegationPort` 是 v0 的最小化解——共享调度底座，分离持久层。`mate_cron_job` 整体收敛到 trigger 是后续版本的工作。

---

## 跨实例一致性（多副本部署）

`CronDelegationPort` 的所有方法是**进程局部**的——本地 ScheduledFuture 只在本 JVM 注册，不持久化 handle。跨实例靠：

1. 每个实例启动时调 `syncFromDatabase()` 扫所有 enabled cron trigger 注册本地
2. 修改 trigger 时 `pattern_version++` + 取消本地 future
3. 每次 fire 前重新读 trigger 行，`patternVersion` 不匹配则**本地短路自取消**（说明被别的实例改过）
4. ShedLock 锁名 = `"mate-trigger-{triggerId}"`，跨实例互斥
5. 周期 `@Scheduled(fixedDelay=60s) syncFromDatabase()` 兜底收敛

实战意义：你正常 rolling-deploy 多副本不需要做任何额外动作——新实例起来自动接管，老实例本地 future 走完最后一轮就停。

---

## 数据模型

### `mate_trigger` —— 触发器配置

主要字段：

| 字段 | 类型 | 用途 |
|---|---|---|
| `pattern_type` | varchar | 6 种 pattern 之一 |
| `pattern_json` | TEXT | 该 pattern 的过滤参数 JSON |
| `target_type` | varchar | `agent` 或 `workflow` |
| `target_id` | bigint | 对应 agent / workflow 主键 |
| `payload_template` | TEXT | Pebble 渲染模板 |
| `dedup_window_secs` | int | 去重窗口（秒） |
| `rate_limit_per_min` | int | 每分钟最大 fire 次数 |
| `bot_self_filter` | bool | 是否启用 bot self 过滤（默认 true，但默认实现是 noop） |
| `pattern_version` | bigint | 乐观并发 lamport 计数器，**每次 `pattern_json` 改动 +1**；跨实例 fire 前比对自取消 |
| `fire_count` | bigint | 有效 dispatch 次数（不计去重 / 限速过滤掉的） |
| `last_error` | varchar | 最近一次失败原因（含 `"rate-limited"` / 异常 message） |
| `enabled` | bool | 软启停开关 |
| `deleted` | int | 软删标志 |

### `mate_trigger_event` —— 去重元数据

仅用于去重判定，**不存 envelope 副本本身**：

| 字段 | 类型 | 用途 |
|---|---|---|
| `id` | bigint | 主键 |
| `trigger_id` | bigint | 关联 trigger |
| `dedup_key` | varchar | **唯一索引**，引擎按此 key 在 `dedup_window_secs` 时窗内做去重判定 |
| `received_at` | timestamp | 入库时间 |
| `expires_at` | timestamp | 去重窗口过期时间，超过此点同 key 可以重新入库 |

::: tip 设计取舍
v0 故意**不把 envelope 全文写进 `mate_trigger_event`**——大体量渠道事件全量持久化撑不住库。事件正文的审计依赖 channel 层日志 + agent / workflow 层的 run 记录。如果未来需要"事件回放"等能力，再加 envelope 持久化列。
:::

---

## 已知限制（v1.3.0）

- **没有可视化 trigger → workflow 串联图**——多 trigger 投递到同 workflow 在 UI 上看是两个独立列表
- **没有 trigger 间优先级 / 依赖**——同一事件命中多 trigger 时按数据库 id 升序串行 dispatch
- **Webhook 入口没鉴权 IP allowlist**——只有 secret header；如果你需要更强的 IP 限制，前置 nginx / 网关
- **`agent_lifecycle` 不区分会话级和 step 级**——员工一次对话内多次 step 失败只会触发一次 `failed`
- **没有事件回放**——`mate_trigger_event` 是只读历史，没有"重新派发这条事件"的按钮（v1 加）

---

## 故障排查

| 现象 | 排查 |
|---|---|
| Cron trigger 没触发 | 1) `enabled=true`？ 2) cron 表达式 + 时区是否解析为下次时间？UI 编辑器有预览； 3) ShedLock 锁是否被另一实例长持？查 `shedlock` 表 |
| 事件 `POST /events` 返回 200 但 dispatch 没发生 | 返回体里有 per-trigger fire / drop 汇总——看是否被 `BOT_SELF` / `RATE_LIMITED` / `DEDUPED` / `PATTERN_MISMATCH` 标了原因 |
| `channel_message` 触发不起来 | 1) envelope 的 `data.channelType` 拼写大小写是否和 trigger 的 `pattern_json.channelType` 匹配？2) `bot_self_filter=true` 但有自定义 `BotSelfFilter` 实现把它过掉了？3) `content_match` 的 `substring` 是否真的出现在 envelope 的 `data.content` 里 |
| `agent_lifecycle` 没触发 | 检查 `pattern_json.phase` 是 `spawned` / `terminated` / `crashed` 之一（不是 `started` / `completed` / `failed`） |
| 重启后 cron trigger 不再触发 | 看启动日志 `syncFromDatabase()` 是否报错；常见是表损坏 / `pattern_json` 反序列化失败 |
| `mate_trigger.last_error` 是 `"rate-limited"` | 调高 `rate_limit_per_min` 或者把 trigger 拆成多条按 group 分流 |
| `bot_self_filter=true` 没起作用 | 确认 `BotSelfFilter` 是否真有非 noop 实现——默认 `NoopBotSelfFilter` 永远返回 false |

---

## 相关链接

- [工作流（Workflow）](./workflow.md) —— `target_type=workflow` 时 dispatch 到这里
- [数字员工](./agents.md) —— `target_type=agent` 时 dispatch 到这里
- [多渠道接入](./channels.md) —— `channel_message` pattern 监听的事件来源
- [审批与安全](./security.md) —— webhook secret + ACL 兜底
