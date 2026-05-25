# 工作流（Workflow）

::: tip 1.3.0 新增
工作流编排自 v1.3.0 起提供。在 v1.2.0 及更早版本里没有这个能力。
:::

**工作流是什么**：把多个数字员工 + 系统操作（审批 / 渠道分发 / 写记忆）按线性 step 编排成一条业务流程。每一步可以被前一步的输出条件控制，可以并行扇出，可以等待人工审批，可以把结果写进员工的 MEMORY.md。

**工作流不是什么**：
- 不是 ReAct / Plan-and-Execute 的替代品——单 agent 的多轮推理仍然在那两条引擎里
- 不是可视化拖拉框框生成 if/else 的低代码工具——v0 是 **JSON 优先**（v1 才上画布）
- 不是 30+ 节点的 Dify-style 编排——MateClaw 工作流刻意保持极简：**线性 step 数组 + 一个 mode 字段表达控制流**

::: warning v1.3.0 范围
v0 = internal alpha。**7 种 step mode + 6 种 trigger pattern**。`loop` / `invoke_skill` 留给后续版本。生产场景请先在标杆账号 / 内部 workspace 跑通再推广。
:::

---

## 一分钟看懂

```json
{
  "schemaVersion": "1.0",
  "inputs": [
    { "name": "customer", "type": "json" }
  ],
  "steps": [
    {
      "name": "enrich",
      "agentName": "data-analyst",
      "promptTemplate": "Enrich and return strict JSON: {{ inputs.customer | toJson }}",
      "mode": { "type": "sequential" },
      "outputVar": "enriched",
      "outputContentType": "json"
    },
    {
      "name": "vip-route",
      "agentName": "enterprise-sales",
      "promptTemplate": "VIP onboarding for {{ outputs.enriched.name }}",
      "mode": {
        "type": "conditional",
        "expression": "{{ outputs.enriched.tier == 'enterprise' }}"
      }
    },
    {
      "name": "notify-feishu",
      "agentName": "ops-bot",
      "promptTemplate": "Notify feishu: {{ outputs.enriched }}",
      "mode": { "type": "fan_out" }
    },
    {
      "name": "notify-email",
      "agentName": "ops-bot",
      "promptTemplate": "Notify email: {{ outputs.enriched }}",
      "mode": { "type": "fan_out" }
    },
    {
      "name": "wait-acks",
      "mode": { "type": "collect" }
    },
    {
      "name": "record",
      "promptTemplate": "Onboarded {{ inputs.customer.name }}",
      "mode": {
        "type": "write_memory",
        "employeeId": "{{ outputs.enriched.assignedEmployeeId }}",
        "file": "MEMORY.md",
        "mergeStrategy": "append"
      }
    }
  ]
}
```

读法：
1. `enrich` 让数据员工把客户信息结构化为 JSON
2. 如果客户层级是 `enterprise`，让企业销售员工跑 VIP onboarding
3. 同时（fan_out）扇出到飞书通知 + 邮件通知 step
4. `collect` 等两个通知都返回再继续
5. 把结果追加写到员工的 `MEMORY.md`

---

## 核心概念

### Step 七种 mode（v1.3.0）

| Mode | 行为 | 必填字段 | 关键语义 |
|---|---|---|---|
| `sequential` | 顺序执行；上一步 output → `{{input}}` | — | 默认 mode |
| `fan_out` | 与连续后续 fan_out 并行；都收到同一 `{{input}}` | — | 边界由编译期检测：从此 step 开始，遇到第一个非 fan_out / 非 collect 的 step 即停 |
| `collect` | 把前面**最近一组** fan_out 的输出按 `\n\n---\n\n` 拼接为 `{{input}}` | — | 前面必须存在至少 2 个连续 fan_out；编译期校验 |
| `conditional` | Pebble 表达式 true 才执行 | `expression` | false 时跳过；`{{input}}` 不变（保留上一步） |
| `await_approval` | 暂停 run，发审批 | `approvalKind`、`approverChannels[]` | resume 后继续下一步；超时按 workspace 政策处理 |
| `dispatch_channel` | 把 `{{input}}` 多渠道分发 | `channels[]` | 单渠道失败按 errorMode 处理 |
| `write_memory` | 写员工记忆文件 | `employeeId`、`file`、`mergeStrategy` | 4 种策略：`append` / `replace_section` / `upsert_kv` / `overwrite` |

> **不在 v1.3.0 里**：`loop`（迭代 N 次或对数组逐项处理）、`invoke_skill`（直接调用 skill 不经过员工）。等用户反馈再加。

### 表达式：Pebble 子集

工作流**不**用全功能模板引擎——它支持的是 Kestra 同款 Pebble 子集，只够做条件判断和变量引用，不能跑代码。

| 类别 | 语法 |
|---|---|
| 变量引用 | `inputs.X` / `outputs.varname.field` / `vars.X` / `now` / `flow.id` |
| 操作符 | `==` `!=` `<` `<=` `>` `>=` `and` `or` `not` `+` `-` |
| 内建过滤器 | `length` / `lower` / `upper` / `default('x')` / `toJson` / `fromJson` / `date(format)` |
| JSONPath | `\| jq('.field.subfield')` |
| 字符串测试 | `\| contains('x')` / `\| startsWith('x')` / `\| matches('regex')` |

**不支持**（编译期拒）：
- 自定义函数 / 宏定义
- include / extends
- 文件 I/O / 网络 I/O
- 任何副作用操作

### 输出类型：text vs json

每一步的 `outputContentType` 决定下游怎么访问它：

| outputContentType | 默认 | Pebble 访问规则 |
|---|---|---|
| `text` | ✅ | `outputs.X` 是字符串；`outputs.X.field` **编译期错**；`\| jq(...)` **运行时错** |
| `json` | — | 运行时 `JSON.parse` 解析；失败按 `errorMode` 处理；字段访问 / `jq(...)` 合法 |

**Agent step 默认 `outputContentType=text`**——LLM 自然语言输出本来就不是 JSON。要做条件分支或字段访问，必须：
1. 在 `promptTemplate` 里**明确**让 LLM 输出严格 JSON（"return strict JSON: {...}"）
2. 把这一步的 `outputContentType` 设为 `json`

### 编译期非法组合（发布时拒）

| 组合 | 拒原因 |
|---|---|
| 连续多个 `fan_out` 后没 `collect` | `{{input}}` 进入下一步歧义 |
| `collect` 没有前置 `fan_out` | 没东西可 collect |
| `fan_out` 组里混入 `await_approval` | 多审批并发同时触发，没法聚合 |
| `agentName` 指向员工不存在 / 已禁用 / 跨 workspace | ACL 失败 |
| Pebble 表达式引用未声明的变量 | 编译期 |
| `outputs.X.field` 但 step X 是 `text` 类型 | 编译期类型错 |
| `dispatch_channel` 引用的 channel 不在 workspace allowlist | ACL |
| `write_memory` 引用的 employeeId 跨 workspace | ACL |
| step 数 > 200（默认上限） | 防失控配置 |

发布时跑 `WorkflowCompiler.validate(graphJson) → List<CompileError>`，每个错误指到 step name + 字段路径，UI 上 Monaco 编辑器直接标红。

---

## 在 UI 里用工作流

### 入口

`Workflows`（侧栏）→ 列表 → 点 **+ 新建**。

::: tip
新装的实例 Workflows 列表是空的。这是故意的——v0 不内置模板，由用户和标杆客户共建。
:::

### 编辑器（v1.3.0 = JSON only）

- **Monaco 编辑器**：JSON schema 校验 + 自动补全 + Pebble 表达式静态检查
- **模板下拉**：从 `GET /api/v1/workflows/draft/templates` 拉的内置骨架
- **预编译**：`POST /api/v1/workflows/{id}/compile` 拿 compile diagnostics，**不写 revision、不真跑**
- **发布**：跑编译 → ACL 校验 → 写一个新的 `mate_workflow_revision` 行（整数版本号 +1）

::: warning v1 才上画布
`@vue-flow/core` 画布在 v1.3.0 里**已经有 UI 雏形**，但它把 step 数组渲染为节点链，**不是**可拖拉编辑——双击节点弹出对应字段表单，主编辑路径仍是 JSON。完整的可视化拖拉编辑推到 v1.4+。
:::

### 自然语言 → 工作流草稿（v1.3.0）

`POST /api/v1/workflows/draft/generate` 接受一段自然语言描述（"我要一条客户工单分流流程，飞书入口，按客户层级路由 enterprise / pro / standard 分别走不同处理人"），让一个内置 agent 生成对应 graph_json，并**立即编译返回**——附带 compile diagnostics。

适用场景：
- 不熟 JSON DSL 的用户先生成一稿可发布的草稿，再去 Monaco 里调
- 老 SOP 文档批量灌进生成器，快速得到候选工作流模板
- 客户共建时，把"做完这件事我希望它如何运转"的口语描述快速可视化

返回结构：
```json
{
  "graphJson": "...",          // 可直接 PUT 进 draft 的 JSON
  "compileErrors": [...],      // 同 /compile 一致的诊断
  "modelUsed": "qwen-plus",
  "tokenUsage": { ... }
}
```

::: tip 不替代 Monaco 编辑
生成器**不会**直接发布——它只生成草稿（`saveDraft`），仍要走人工审阅 → 编译 → 发布。生成的 JSON 会带编译错误也不奇怪，作者修完再发布。
:::

### 运行历史

每条 run 都持久化为 `mate_workflow_run` + `mate_workflow_run_step`。详情页：
- 每个 step 的 input / output（payload URI 引用）
- 每步耗时 + token 消耗
- 跨 step 失败链路高亮
- await_approval 暂停时显示等谁审批 + 等了多久

### 触发方式

工作流的实际启动只能通过 [触发器（Triggers）](./triggers.md) 或 `await_approval` 恢复——v0 没有"立即手动跑一次"的 endpoint。详见上方 API 参考。

::: tip 1.4.0：触发器现在在调度中心里
v1.4.0 起，**定时任务**和**触发器**合并为单个**调度中心**页面（`设置 → 调度中心`，路由 `/settings/scheduler`），分**计划任务 / 事件触发器 / 运行历史**三个 tab。要给工作流挂触发器，去调度中心的**事件触发器** tab 新建一条 `target_type=workflow` 的规则。详见 [触发器](./triggers.md)。
:::

---

## API 参考

所有 endpoint 都在 `/api/v1/workflows/` 下，请求要带 `X-Workspace-Id` header。

### CRUD

| 方法 | 路径 | 说明 |
|---|---|---|
| `GET` | `/api/v1/workflows` | 列出当前 workspace 的所有工作流 |
| `POST` | `/api/v1/workflows` | 新建工作流（草稿初始为空） |
| `GET` | `/api/v1/workflows/{id}` | 获取工作流元信息 + 内联草稿 |
| `PUT` | `/api/v1/workflows/{id}` | 更新工作流元数据（name / description / enabled） |
| `PUT` | `/api/v1/workflows/{id}/draft` | 保存内联草稿 graph_json（不编译） |
| `DELETE` | `/api/v1/workflows/{id}` | 软删工作流 |

### 编译 / 发布

| 方法 | 路径 | 说明 |
|---|---|---|
| `POST` | `/api/v1/workflows/{id}/compile` | 编译当前草稿，返回 compile diagnostics，**不写 revision** |
| `POST` | `/api/v1/workflows/{id}/publish` | 编译 + 写新 revision；自动指向 `latest_revision_id` |

### 草稿生成器（v1.3.0 内置）

| 方法 | 路径 | 说明 |
|---|---|---|
| `GET` | `/api/v1/workflows/draft/templates` | 列出内置草稿模板 |
| `POST` | `/api/v1/workflows/draft/preview-compile` | 任意 graph_json 试编译——不需要先建 workflow 行就能拿到诊断 |
| `POST` | `/api/v1/workflows/draft/generate` | **自然语言 → 工作流草稿**——你描述需求，agent 生成 graph_json + 编译诊断 |

### 运行查询 / 恢复

| 方法 | 路径 | 说明 |
|---|---|---|
| `GET` | `/api/v1/workflows/{id}/runs?limit=...` | 列某 workflow 最近 run（默认 50 条） |
| `GET` | `/api/v1/workflows/runs/paused?limit=...` | 列当前 workspace **所有 paused run**（运维入口） |
| `GET` | `/api/v1/workflows/runs/{runId}` | 单个 run 详情 + 全部 step 行（input / output / duration） |
| `POST` | `/api/v1/workflows/runs/{runId}/resume` | 从 `await_approval` 暂停恢复（系统在审批通过时自动调用，不需要前端手动调） |

::: warning v0 没有"启动 run"的独立 endpoint
工作流 run 的实际启动路径只有两条：

1. **通过 trigger**——在 [Triggers](./triggers.md) 配一条 trigger 指向这个 workflow（`target_type=workflow`），事件到了引擎自动启动 run
2. **通过 `await_approval` resume**——`/runs/{runId}/resume` 把 paused run 推下去

v0 **没有** `POST /api/v1/workflows/{id}/runs` 这种"立即手动跑一次"的 API。要做"试运行"，用 `/draft/preview-compile` 拿编译结果（**只编译、不入库、不真跑**），或者临时挂一条 webhook trigger 触发。手动启动 run 在 RFC 里已规划，会在后续版本加。
:::

---

## 安全模型

### 三层 ACL 角色

| 角色 | 能干什么 |
|---|---|
| `workflow:author` | 编辑 draft、读自己的 run |
| `workflow:publisher` | 发布 revision；发布期跑静态 ACL 检查 |
| `workflow:operator` | 启停 trigger、cancel run、查看其它人的 run |

### Step 执行身份

每个 step 在 ExecutionContext 携带：
- `workspaceId`：必须等于 workflow 的 workspace
- `actingAgentId`：sequential / 3 个 MateClaw mode → 该 step 的 agent；其它 mode → 发布者作为 fallback
- `triggeredBy` / `workflowId` / `revisionId` / `runId`：审计追溯

### 跨 workspace 隔离

发布期跑 `WorkflowAclValidator.checkAll(graphJson)`：
- `agentName` 引用的员工必须在当前 workspace 内
- `dispatch_channel` 的 channel 必须在 workspace allowlist 内
- `write_memory` 的 employeeId 必须在当前 workspace 内

任一不通过 → 发布失败，事务回滚，**不写 revision、不更新 latest_revision_id**。

### 与 [MCP 每 agent 工具绑定](./mcp.md) 的关系

工作流**不能**给员工额外的工具。Agent step 调工具时会跑 `AgentBindingService.getEffectiveToolNames(agentId)` 算出的同一套 ACL——员工在工作流里能用什么工具，跟它在普通对话里能用什么工具完全一致。

---

## 内置存储 URI（payload）

工作流的输入 / 输出 / 中间产物如果超过 4KB 默认阈值，会被自动写入 `mate_workflow_payload` 表（v1.3.0：单库存储）或本地文件系统兜底，并在 graph 里以 `payload://` URI 替代。这避免了大上下文撑爆 message column 的问题——参考 commit `9c81dba0 feat(workflow): payload fs fallback for medium-size payloads`。

```text
payload://run/abc123/step/enrich/output → 实际存储位置由后端解析
```

UI 渲染时按需 lazy-load。

---

## 数据模型

工作流系统涉及 8 张表：

| 表 | 用途 |
|---|---|
| `mate_workflow` | 工作流主体（id / name / workspace） |
| `mate_workflow_revision` | 已发布版本（整数 revision；graph_json 整体快照；不可变） |
| `mate_workflow_run` | 一次执行（runId / triggerSource / status / startedAt / endedAt） |
| `mate_workflow_run_step` | run 内每一步的 input/output/duration |
| `mate_workflow_run_pause` | await_approval 持久化暂停状态（重启后能恢复） |
| `mate_workflow_payload` | 大 payload 内置存储（payload URI 解析目标） |
| `mate_trigger` | 触发器配置（含 cron pattern_version） |
| `mate_trigger_event` | 事件去重 + rate limit 历史 |

---

## 已知限制（v1.3.0）

- **没有可视化拖拉编辑**——画布是只读的链式渲染，主编辑路径是 JSON
- **没有 loop step**——做不了"对数组逐项处理"或"重试 N 次"。变通：用 `fan_out` 数个固定分支，或上层调度多 run
- **没有 invoke_skill step**——skill 必须挂在 agent 上由 agent 调用
- **没有跨 workspace 共享**——同一份工作流模板要复用到多个 workspace 需要复制
- **没有实时协作编辑**——同时间多人编辑同一草稿，**后写覆盖**
- **没有 step 级 retry policy**——`errorMode.retry` 是 step-wide 的；细粒度 retry 推到后续

---

## 相关链接

- [触发器（Triggers）](./triggers.md) —— 工作流的事件入口
- [审批与安全](./security.md) —— `await_approval` 走的就是这条审批通道
- [数字员工](./agents.md) —— Step 里 `agentName` 引用的就是它们
- [多渠道接入](./channels.md) —— `dispatch_channel` 能投递到哪些渠道
- [记忆系统](./memory.md) —— `write_memory` 写到员工的哪份文件
