---
title: MCP 协议集成 — Model Context Protocol 工具扩展
description: MateClaw 作为 MCP 客户端，通过 Model Context Protocol 接入任意外部工具服务器。JSON-RPC 动态发现、SSE/stdio 双传输、与内置工具无缝统一。
head:
  - - meta
    - name: keywords
      content: MCP,Model Context Protocol,MCP客户端,工具协议,JSON-RPC,AI工具扩展,Anthropic MCP
---

# MCP 协议

**MCP 是 MateClaw 跟"别人写的工具"对话的方式。**

Model Context Protocol 是 Anthropic 提出的一个开放标准，用来把 AI 模型和外部工具/数据连起来。一个 MCP 服务是一个进程——本地或远程——通过 JSON-RPC 向外宣告一组工具。MateClaw 扮演 MCP **客户端**：连接上去、通过 `tools/list` 发现工具、把它们当原生工具暴露给你的 Agent。**从 Agent 的视角，一个内置的 `@Tool` Spring bean 和一个从 MCP 服务来的工具，没有任何区别。**

这是 MateClaw 的**逃生口**。你需要一个 MateClaw 没自带的能力——沙盒目录的文件访问、Tavily 搜索、某个自定义的企业数据服务、一整套浏览器自动化——大概率已经有现成的 MCP 服务，你可以把它接进来，不用写一行 Java。

---

## MCP 到底是什么

```
┌───────────────────────┐              ┌───────────────────────┐
│     MateClaw           │              │     MCP Server        │
│     (MCP Client)       │              │     (工具提供方)       │
│                       │   JSON-RPC   │                       │
│  Agent Engine  ───────┼──────────────┼──► Tool A             │
│                       │              │    Tool B             │
│  Tool Registry ◄──────┼──────────────┼─── Tool Discovery     │
│                       │              │    (tools/list)       │
└───────────────────────┘              └───────────────────────┘
```

核心概念：

- **MCP 客户端**——MateClaw，负责连接 MCP 服务、发现工具、转发工具调用
- **MCP 服务**——第三方工具服务器
- **工具发现**——客户端发送 `tools/list` 请求拿到服务器上所有工具
- **工具调用**——Agent 决定调一个工具时，客户端转发给对应的 MCP 服务执行

新工具能力变成 Agent 可用的——**不改代码、不重启服务**。

---

## 三种传输类型

### stdio（标准 I/O）

MateClaw 启动一个本地子进程，通过 stdin/stdout 交换 JSON-RPC 消息。

```
MateClaw  ── stdin ──►  MCP Server 子进程
          ◄─ stdout ──
```

**适用：** 本地 Node.js/Python MCP 工具包、命令行工具封装、开发调试。  
**优势：** 不需要网络配置，开箱即用，进程隔离。  
**限制：** 仅本地。

### streamable_http（可流式 HTTP）

标准 HTTP POST 发 JSON-RPC，响应通过 HTTP 流返回。**生产环境推荐。**

```
MateClaw  ── HTTP POST ──►  远程 MCP 服务
          ◄─ HTTP Stream ──
```

**适用：** 云部署的 MCP 服务、前面有负载均衡的场景。  
**优势：** 标准 HTTP，CDN/防火墙友好，支持认证头。

### sse（Server-Sent Events）

早期 HTTP 传输模式，用 SSE 做服务端到客户端推送。遗留兼容，新项目优先选 `streamable_http`。

### 传输对比

| 特性 | stdio | streamable_http | sse |
|------|-------|-----------------|-----|
| 部署 | 仅本地 | 本地或远程 | 本地或远程 |
| 网络要求 | 无 | HTTP 可达 | HTTP 可达 |
| 认证 | 环境变量 | HTTP Headers | HTTP Headers |
| 进程管理 | MateClaw 管理子进程 | 外部 | 外部 |
| 推荐 | 本地工具 | 远程服务 | 遗留兼容 |

---

## UI 配置

`工具 → MCP 服务 → 添加 MCP 服务`。填：

- **名称**——唯一标识符（字母、数字、`_`、`-`、`.`、空格；1–128 字符）
- **描述**——可选
- **传输类型**——`stdio`、`streamable_http`、`sse`
- **命令**（stdio）——`npx`、`node`、`python` 等
- **参数**（stdio）——JSON 数组（例如 `["-y", "@anthropic/mcp-filesystem", "/path"]`）
- **工作目录**（stdio）——可选
- **环境变量**（stdio）——JSON 对象；支持 `${ENV_VAR}` 引用
- **URL**（streamable_http / sse）——服务端点
- **HTTP Headers**（streamable_http / sse）——JSON 对象
- **连接超时**——默认 30 秒
- **读取超时**——默认 30 秒

保存。启用状态时 MateClaw 自动尝试连接并发现工具。

### 测试、启用、状态

- **测试连接**——发送 `tools/list`，返回结果、延迟、工具列表
- **启用/禁用开关**——断开连接但保留配置
- **状态**——`connected` / `disconnected` / `error` 带错误详情

---

## REST API 配置

完整 CRUD 在 `/api/v1/mcp/servers`。

### 列表

```bash
curl -s http://localhost:18088/api/v1/mcp/servers \
  -H "Authorization: Bearer <token>" | jq
```

响应里的 `headersJson` 和 `envJson` 字段自动**脱敏**（`sk-****abcd`）。

### 创建 —— stdio

```bash
curl -X POST http://localhost:18088/api/v1/mcp/servers \
  -H "Content-Type: application/json" \
  -H "Authorization: Bearer <token>" \
  -d '{
    "name": "filesystem",
    "transport": "stdio",
    "command": "npx",
    "argsJson": "[\"-y\", \"@anthropic/mcp-filesystem\", \"/home/user/workspace\"]",
    "enabled": true
  }'
```

### 创建 —— streamable_http

```bash
curl -X POST http://localhost:18088/api/v1/mcp/servers \
  -H "Content-Type: application/json" \
  -H "Authorization: Bearer <token>" \
  -d '{
    "name": "remote-tools",
    "transport": "streamable_http",
    "url": "https://mcp.example.com/mcp",
    "headersJson": "{\"Authorization\": \"Bearer your-api-key\"}",
    "connectTimeoutSeconds": 15,
    "readTimeoutSeconds": 60,
    "enabled": true
  }'
```

### 更新（PATCH 语义）

```bash
curl -X PUT http://localhost:18088/api/v1/mcp/servers/{id} \
  -H "Content-Type: application/json" \
  -H "Authorization: Bearer <token>" \
  -d '{"description": "更新后的描述", "readTimeoutSeconds": 60}'
```

### 删除 / 开关 / 测试 / 刷新

```bash
curl -X DELETE http://localhost:18088/api/v1/mcp/servers/{id} \
  -H "Authorization: Bearer <token>"

curl -X PUT "http://localhost:18088/api/v1/mcp/servers/{id}/toggle?enabled=false" \
  -H "Authorization: Bearer <token>"

curl -X POST http://localhost:18088/api/v1/mcp/servers/{id}/test \
  -H "Authorization: Bearer <token>"

curl -X POST http://localhost:18088/api/v1/mcp/servers/refresh \
  -H "Authorization: Bearer <token>"
```

**内置服务**（`builtin=true`）**不能删除**。

---

## 实战示例

### 示例 1 —— 文件系统 MCP（stdio）

```bash
curl -X POST http://localhost:18088/api/v1/mcp/servers \
  -H "Content-Type: application/json" \
  -H "Authorization: Bearer <token>" \
  -d '{
    "name": "filesystem",
    "description": "文件系统读写（限制在指定目录内）",
    "transport": "stdio",
    "command": "npx",
    "argsJson": "[\"-y\", \"@anthropic/mcp-filesystem\", \"/home/user/workspace\"]",
    "enabled": true
  }'
```

发现的工具：`read_file`、`write_file`、`list_directory`、`search_files`、`get_file_info`。

安全：`@anthropic/mcp-filesystem` **只允许访问启动参数里指定的目录及其子目录**。

### 示例 2 —— 带认证的远程 HTTP

```bash
curl -X POST http://localhost:18088/api/v1/mcp/servers \
  -H "Content-Type: application/json" \
  -H "Authorization: Bearer <token>" \
  -d '{
    "name": "internal-data-service",
    "transport": "streamable_http",
    "url": "https://mcp-api.internal.example.com/mcp",
    "headersJson": "{\"Authorization\": \"Bearer sk-your-api-key\", \"X-Team-Id\": \"engineering\"}",
    "connectTimeoutSeconds": 10,
    "readTimeoutSeconds": 120,
    "enabled": true
  }'
```

**Header 值支持环境变量引用**：`{"Authorization": "Bearer ${MCP_API_KEY}"}` 在运行时被替换，**secret 不落库**。

### 示例 3 —— Tavily 搜索（stdio + 环境变量）

```bash
curl -X POST http://localhost:18088/api/v1/mcp/servers \
  -H "Content-Type: application/json" \
  -H "Authorization: Bearer <token>" \
  -d '{
    "name": "tavily-search",
    "transport": "stdio",
    "command": "npx",
    "argsJson": "[\"-y\", \"@anthropic/mcp-tavily\"]",
    "envJson": "{\"TAVILY_API_KEY\": \"${TAVILY_API_KEY}\"}",
    "enabled": true
  }'
```

---

## MCP 工具是怎么变成 Agent 可用的

```
应用启动
   │
   ▼
遍历启用的 MCP 服务
   │
   ▼
按传输类型连接 → 调用 initialize → 列出工具 → 缓存
   │
   ▼
工具注册表（聚合内置工具 + MCP 工具）
   │
   ▼
Agent 工具集
```

**关键：** Agent 每次需要调工具时都拉**最新**的活跃工具列表，所以添加或删除 MCP 服务**不需要重启**。从 Agent 的视角看，**MCP 工具和内置工具完全一样**，没有差别。

---

## Per-agent 工具绑定

::: tip 1.3.0 新增
v1.2.0 之前所有员工默认能用全部 MCP 工具——这是个全局开关。v1.3.0 把粒度拆细到**每个员工独立绑定哪些 MCP 工具**，并加了脏状态识别 + 命名空间防撞。
:::

### 三个解决的问题

**问题 1：工具命名空间冲突**
两个 MCP server 都暴露 `read_file`——agent 调用时哪个赢？v1.3.0 在内部使用**带 server 前缀的稳定 callback name**（`{serverName}__{toolName}`），并把它持久化到 `mate_mcp_server.cached_tools`。两个 read_file 在 picker 里显示为 `serverA__read_file` 和 `serverB__read_file`，agent 看到的 prompt 里映射回原始名以减少 token + 不让 LLM 困惑。

**问题 2：MCP server 改名 / 工具改名 → 员工绑定全部失效**
v1.2.0 时 server 一改名，绑这个 server 的员工全瞎了。v1.3.0 引入**持久化 tool cache**：每次成功 list-tools 后把工具元数据写到 `mate_mcp_server.cached_tools` JSON 列。agent binding 校验时如果 server 暂时连不上，就走 cache fallback——绑定保留为 `stale`，连接恢复后立即可用。

**问题 3：员工保存时静默接受不存在的工具引用**
v1.2.0 时员工配置里写了一个 `nonexistent-server.weird-tool`，保存成功，运行时报错。v1.3.0 在保存时跑 `AgentBindingService.validate(...)`：

| 状态 | 含义 | 保存行为 |
|---|---|---|
| `connected` | server 在线，工具可见 | ✅ 正常保留 |
| `stale` | server 暂时离线但 cache 里有 | ✅ 保留（标记 stale） |
| `unavailable` | server 被禁用 | ✅ 保留（标记 unavailable） |
| `orphan` | server / tool 完全不存在了 | ❌ 拒绝保存，提示用户清理 |

### 工具状态在哪里看

`Agents → 选员工 → 工具`——见 [数字员工的工具绑定](./agents#工具绑定per-agent-tool-picker)。

### 数据契约

- `mate_mcp_server.cached_tools`（v1.3.0 新列）：JSON 数组，每个元素 `{name, description, inputSchema, lastSeenAt}`
- `mate_agent_tool.tool_name`：存的是**带前缀的 callback name** `{serverName}__{toolName}` 而不是原始名，这样 server 改名时 join 失败立刻可观测
- `AgentBindingService.getEffectiveToolNames(agentId)` 是工具下发的唯一入口——agent 每个回合都跑一遍，确保运行时和编辑期看到的工具集一致

### 服务端规则

- MCP server 列表里**不可以编辑** ACP 桥接进来的 MCP 工具（它们是 ACP server 自己生命周期管的）
- 工具被 mark unavailable 后，agent system prompt 里**不再列出它**——LLM 不会想到调用它，但绑定数据保留
- `returnDirect=true` 的工具（直接把工具输出当回答）走同一套 ACL，**不绕过** binding

---

## 连接管理

### 启动时自动连接

所有 `enabled=true` 的 MCP 服务在应用启动时自动连接。单个服务失败不会阻塞其他或启动。

### 线程安全

活跃的 client 表是并发安全的，每个服务有独立的锁。

### 连接替换

**"先连新的，再断旧的"** 策略：建一个新 client、初始化、放进池、关闭旧的。新 client 失败时旧的保持不变。

### 子进程清理

stdio 服务：禁用/删除、配置替换、应用关闭（`@PreDestroy`）、连接失败时都会清理。

### 状态监控

每次连接操作后持久化：

- `last_status`——`connected` / `disconnected` / `error`
- `last_error`——错误消息
- `last_connected_time`——上次成功连接时间
- `tool_count`——当前发现的工具数

### 手动刷新

`POST /api/v1/mcp/servers/refresh` 断开所有现有连接并重连所有启用的服务。用于排查连接问题。

---

## 数据库存储 —— `mate_mcp_server`

| 列 | 类型 | 默认值 | 用途 |
|----|------|--------|------|
| `id` | BIGINT | — | 主键 |
| `name` | VARCHAR(128) | — | 唯一标识符 |
| `description` | TEXT | NULL | 服务描述 |
| `transport` | VARCHAR(32) | `stdio` | `stdio` / `streamable_http` / `sse` |
| `url` | VARCHAR(512) | NULL | 远程 URL |
| `headers_json` | TEXT | NULL | HTTP headers JSON |
| `command` | VARCHAR(512) | NULL | 启动命令 |
| `args_json` | TEXT | NULL | 命令参数 JSON 数组 |
| `env_json` | TEXT | NULL | 环境变量 JSON；支持 `${VAR}` |
| `cwd` | VARCHAR(512) | NULL | 工作目录 |
| `enabled` | BOOLEAN | TRUE | 开关 |
| `connect_timeout_seconds` | INT | 30 | HTTP 连接超时 |
| `read_timeout_seconds` | INT | 30 | 请求响应超时 |
| `last_status` | VARCHAR(32) | `disconnected` | 上次连接状态 |
| `last_error` | TEXT | NULL | 上次错误消息 |
| `last_connected_time` | DATETIME | NULL | 上次成功连接时间 |
| `tool_count` | INT | 0 | 发现的工具数 |
| `builtin` | BOOLEAN | FALSE | 是否内置 |
| `create_time` / `update_time` | DATETIME | — | 时间戳 |
| `deleted` | INT | 0 | 逻辑删除 |

### 敏感数据脱敏

API 响应里 `headers_json` 和 `env_json` 的值自动**脱敏**。`args_json` 按原样返回。

### 环境变量引用

- `${VAR_NAME}`——精确匹配和替换
- `$VAR_NAME`——正则匹配

**明文 secret 不进数据库。**

---

## 故障排查

### "命令找不到"（stdio）

1. 确认命令在运行 MateClaw 的用户的 PATH 里
2. 验证：`which npx` 或 `npx --version`
3. Docker：确认命令在容器里装了
4. 用完整路径：`/usr/local/bin/npx`

### 连接超时

1. HTTP/SSE：确认 URL 可达（`curl -v <url>`）
2. 检查防火墙规则
3. 调大 `connectTimeoutSeconds` / `readTimeoutSeconds`
4. stdio：第一次 `npx -y` 可能要下包

### SSL/TLS 错误

1. 确认远程 SSL 证书有效
2. 自签证书：把 CA 证书加进 JVM trust store
3. 确认 JDK 支持需要的 TLS 版本

### 工具没显示

1. 看 `tool_count > 0`
2. 用测试连接确认 `discoveredTools` 非空
3. 确认 MCP 服务实现了 `tools/list`
4. 看后端日志里 MCP 工具发现相关的输出

### 工具调用失败

1. 看后端日志的具体错误
2. 确认 MCP 服务进程还在跑（stdio）
3. 确认远程服务可达（HTTP/SSE）
4. 看 `readTimeoutSeconds` 够不够
5. 试刷新连接

### 孤儿子进程（stdio）

`@PreDestroy` 钩子正常会清理。MateClaw 被强杀（`kill -9`）的话子进程可能残留。`ps aux | grep mcp` 找到并杀掉。

---

## 下一步

- [工具系统](./tools)——MCP 工具和内置工具的关系
- [技能系统](./skills)——MCP 支撑的技能
- [配置说明](./config)——完整配置参考
