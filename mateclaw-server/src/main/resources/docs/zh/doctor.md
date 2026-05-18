# Doctor

**Doctor 页面回答一个问题：这个东西现在是不是真的在正常工作？**

MateClaw 有很多活动部件——后端、数据库、模型供应商、MCP 服务、IM 渠道、cron 任务、记忆整合、wiki 消化。出问题时，**症状**（"我的 Agent 不响应"）通常有一个**具体的原因**（"DashScope API Key 昨天过期了"）埋在离你能看到的地方好几层远的地方。Doctor 是一个单页，**一次性跑所有检查**，告诉你哪些是绿的、哪些是黄的、哪些是红的。

通过 `设置 → Doctor` 打开，或者直接跳 `/doctor`。

---

## 它检查什么

每一项检查独立运行，报告三种状态之一：

- **✅ OK**——一切按预期工作
- **⚠️ 警告**——在工作但降级了（例如在用 fallback provider、接近配额、一个非关键的 cron 任务暂停了）
- **❌ 错误**——以一种你需要修的方式坏了

### 核心基础设施

| 检查 | 验证什么 |
|------|----------|
| **后端版本** | MateClaw 在跑，报告它的版本 |
| **数据库连接** | 配置的数据源可达，查询成功 |
| **数据库 schema** | 所有预期的 `mate_*` 表存在；迁移状态干净 |
| **磁盘使用** | 数据目录有足够空闲空间（低于 20% 警告，低于 5% 错误） |
| **H2 console 暴露** | 生产 profile 里启用了 H2 console 会警告 |
| **JWT secret 强度** | 还在用默认 JWT secret 会警告 |

### 模型

| 检查 | 验证什么 |
|------|----------|
| **活跃模型** | 默认模型配置存在且启用 |
| **供应商连通性** | 每个启用的供应商最近通过了连接测试 |
| **API Key 存在** | 每个标记为启用的云供应商都配了 key |
| **Ollama 可达** | 如果配了 Ollama，本地实例可达 |

### Agent 和工具

| 检查 | 验证什么 |
|------|----------|
| **工具注册表** | 内置工具和 MCP 工具加载无错 |
| **Tool Guard 配置** | 至少存在一条 Tool Guard 规则（用 `default-policy: allow` 会警告） |
| **默认 Agent** | 默认 Agent 存在且启用 |
| **Agent 模板** | 内置模板存在且可加载 |

### 记忆和 Wiki

| 检查 | 验证什么 |
|------|----------|
| **记忆整合 cron** | 每个 Agent 的整合 cron 任务存在且启用 |
| **上次整合运行** | 过去 7 天没有跑过整合会警告 |
| **Wiki 消化队列** | 没有卡住的 `pending` 或 `processing` 原始材料 |
| **Wiki schema** | `mate_wiki_*` 表存在且可查询 |

### 渠道

| 检查 | 验证什么 |
|------|----------|
| **渠道健康监控** | 每个启用的渠道报告 `connected` 或正在主动重连 |
| **每渠道状态** | 每个 IM 渠道的连接状态和上次错误 |
| **Webhook URL 可达** | 生产环境下 webhook 模式的渠道没配公网 URL 会警告 |

### MCP

| 检查 | 验证什么 |
|------|----------|
| **启用的 MCP 服务** | 每个启用的 MCP 服务是 `connected` |
| **工具数** | 每个连接成功的服务报告至少一个工具 |
| **孤儿子进程** | 没有超过它父 client 存活的 stdio 子进程 |

### Cron 和异步

| 检查 | 验证什么 |
|------|----------|
| **Cron 引擎** | 计划任务执行器在运行 |
| **过期任务** | 任何任务超时超过 24 小时会警告 |
| **异步任务队列** | `mate_async_task` 队列长度在正常范围 |

---

## 检查怎么跑

Doctor 两种方式跑：

### 按需

点 Doctor 页面上的**运行所有检查**。按钮并行触发所有检查；UI 在每项检查完成时流式返回结果。大多数检查在一秒内完成；最慢的（MCP 服务连接测试）可能要 10–30 秒。

### 按计划

Doctor 也在后台**每 15 分钟自动跑一次**。结果缓存在内存里并持久化到 `mate_doctor_check`，这样打开页面时它**立刻加载**——你看到的是上次缓存的状态，直到你点**运行所有检查**。

在 `application.yml` 里调整计划：

```yaml
mateclaw:
  doctor:
    enabled: true
    schedule-minutes: 15
    cache-ttl-minutes: 10
```

---

## 读结果

每个检查返回：

```json
{
  "name": "DashScope 供应商连通性",
  "category": "Models",
  "status": "ok",
  "message": "连接测试成功（延迟：240ms）",
  "lastChecked": "2026-04-11T14:30:22",
  "details": {
    "provider": "dashscope",
    "baseUrl": "https://dashscope.aliyuncs.com",
    "latencyMs": 240
  },
  "fixUrl": "/settings/models"
}
```

UI 渲染：

- 顶部的**分类 tab**——基础设施、模型、Agent、记忆、Wiki、渠道、MCP、Cron
- **状态计数器**——绿 / 黄 / 红
- **检查列表**——名字、状态、消息、距上次检查的时间、"查看详情"展开、可选的"修复"按钮跳到相关设置页
- **历史图**——（每个检查）最近 50 次运行的 sparkline，一眼看出抖动的检查

---

## 修复按钮

对可操作的检查，Doctor 行包含一个**修复**按钮，直接跳到相关的设置页面：

- 模型供应商失败 → `设置 → 模型`
- Tool Guard `default-policy: allow` → `设置 → 安全与审批`
- 生产环境的 H2 console → `设置 → 系统`（或显示一个可复制的配置片段）
- JWT 默认 secret → `设置 → 系统`（或显示一个配置片段）
- MCP 服务断开 → `工具 → MCP 服务`
- 卡住的 wiki 消化 → `Wiki → [KB] → 原始材料`

点修复带你到**你能解决问题的那个具体页面**。可能的话，目标页面会预过滤高亮失败的条目。

---

## Doctor API

```bash
# 跑所有检查（同步）
curl http://localhost:18088/api/v1/doctor/run \
  -H "Authorization: Bearer <token>"

# 获取缓存的检查结果
curl http://localhost:18088/api/v1/doctor/checks \
  -H "Authorization: Bearer <token>"

# 只跑特定分类
curl http://localhost:18088/api/v1/doctor/run?category=models \
  -H "Authorization: Bearer <token>"

# 历史结果
curl "http://localhost:18088/api/v1/doctor/history?check=dashscope-connectivity&limit=50" \
  -H "Authorization: Bearer <token>"
```

---

## 在运维中使用 Doctor

### 作为 uptime 监控的健康端点

把你的外部 uptime 监控（UptimeRobot、Pingdom、内部 Prometheus）指向：

```
GET /api/v1/doctor/checks
```

端点返回 HTTP 200 带 JSON 汇总——聚合的通过/失败计数和按分类细分。你的监控应该在 `errorCount > 0` 时报警。

要更简单的健康检查，用：

```
GET /actuator/health
```

这遵循 Spring Boot 的标准格式。

### 升级时

部署新 MateClaw 版本之后跑 Doctor 验证没有回归：

1. 打开 `/doctor`
2. 点**运行所有检查**
3. 看有没有之前没有的黄或红
4. **特别注意数据库 schema**——升级后 schema 不匹配通常意味着某个迁移没跑

### 出问题时

用户报告"它不工作"时 Doctor 是第一个去看的地方。打开页面，看哪个检查是红的，点**修复**，解决问题。**如果没有检查是红的但用户仍然有问题**，大概率是 Doctor 还没覆盖的东西——开一个 [GitHub issue](https://github.com/matevip/mateclaw/issues) 让我们加一个检查。

---

## 数据模型

**`mate_doctor_check`**

| 列 | 用途 |
|----|------|
| `id` | 主键 |
| `name` | 检查名字 |
| `category` | 检查分类 |
| `status` | `ok` / `warning` / `error` |
| `message` | 人类可读的消息 |
| `details` | 额外细节的 JSON |
| `last_checked` | 上次运行时间 |
| `run_duration_ms` | 检查耗时 |
| `workspace_id` | 范围（全局检查为 null） |

历史结果进 `mate_doctor_check_history`，同样的列加上一个保留期清理任务。

---

## 下一步

- [控制台](./console)——Doctor 所在的 UI
- [配置说明](./config)——你可能基于 Doctor 警告配置的东西
- [安全与审批](./security)——Doctor 在 Tool Guard 里检查什么
- [贡献指南](./contributing)——缺了什么就加一个 Doctor 检查
