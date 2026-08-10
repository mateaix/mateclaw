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
- **读取超时**——默认 **60 秒**（1.5.0 起从 30s 提到 60s，#247；单次 callTool 往返合法地跑久一点的工具不再被掐断。每台服务可单独调 5–300s）

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
两个 MCP server 都暴露 `read_file`——agent 调用时哪个赢？v1.3.0 在内部使用**带 server 前缀的稳定 callback name**（`{serverName}__{toolName}`），并把它持久化到 `mate_mcp_server.tools_cache_json`。两个 read_file 在 picker 里显示为 `serverA__read_file` 和 `serverB__read_file`，agent 看到的 prompt 里映射回原始名以减少 token + 不让 LLM 困惑。

**问题 2：MCP server 改名 / 工具改名 → 员工绑定全部失效**
v1.2.0 时 server 一改名，绑这个 server 的员工全瞎了。v1.3.0 引入**持久化 tool cache**：每次成功 list-tools 后把工具元数据写到 `mate_mcp_server.tools_cache_json` JSON 列。agent binding 校验时如果 server 暂时连不上，就走 cache fallback——绑定保留为 `stale`，连接恢复后立即可用。

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

- `mate_mcp_server.tools_cache_json`（v1.3.0 新列）：JSON 数组，每个元素 `{name, description, inputSchema, lastSeenAt}`
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
| `read_timeout_seconds` | INT | 60 | 请求响应超时（1.5.0 起默认 60，旧为 30） |
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

## 透传用户身份给 MCP server（on-behalf-of）

STDIO MCP server 是**每个配置一个共享子进程**，所有用户共用；env 在子进程启动时一次性注入、之后不可变，STDIO 也没有 HTTP 那种 per-request header 通道。所以**不能用 env 传 per-user 身份**——身份必须随每次工具调用在带内传递。

MateClaw 支持把**调用者身份**注入到每次工具调用的参数里，让 MCP server 代表该用户调用底层 REST 后端。注入的身份是**带类型**的，后端可据此区分"MateClaw 已认证该用户"和"这是外部/匿名标识"（见下方[身份类型](#身份类型)）。

### 开启（opt-in，按 server）

默认关闭——全量注入会把用户名泄漏给任意第三方 MCP server。用允许清单按 **server 名或 id** 开启：

```yaml
mateclaw:
  mcp:
    identity-forward:
      servers:
        - my-internal-api      # mate_mcp_server 里的 server 名
        - 1000000042           # 或数字 server id
```

### 数据契约

开启后，MateClaw 在调用该 server 的每个工具时，往参数 JSON 里注入保留字段 **`__mateclaw_user__`**。值为 **`<trust>:<subject>`**——信任前缀加主体标识——这样后端无需验签 JWT 就能区分认证用户与匿名/外部身份。该值由受信服务端注入、**不经 LLM**；若 LLM 伪造了同名字段会被覆盖，因此模型无法冒充身份。无可用身份（cron / system / 无归属）时不注入（不伪造身份）。

信任前缀取值：

| 前缀 | 含义 | 示例值 |
|---|---|---|
| `authenticated` | MateClaw web 控制台登录（JWT/PAT）。`sub` = 用户不可变数字 id（旧路径回退为用户名）。 | `authenticated:42` |
| `anonymous` | webchat 访客 / 第三方 `endUserId`。**背后无 MateClaw 账号。** | `anonymous:visitor-xyz` |
| `external` | IM 发送者（飞书/企微…）。外部平台 id。 | `external:im_user_1` |

MCP server 侧读出该字段、剥掉，再连同自己持有的后端 API Key 一起调 REST（如 `X-On-Behalf-Of` header）：

```python
# FastMCP 示例：MCP server 用 Python 命令行脚本（STDIO）
import os, httpx
from mcp.server.fastmcp import FastMCP

mcp = FastMCP("my-internal-api")
REST_BASE = os.environ["REST_BASE"]          # 后端地址
API_KEY = os.environ["BACKEND_API_KEY"]      # 服务级 API Key（认证 MCP 服务本身）

@mcp.tool()
def query_orders(keyword: str, __mateclaw_user__: str | None = None) -> str:
    if not __mateclaw_user__:
        raise ValueError("missing injected identity")   # 拒绝无身份调用
    # __mateclaw_user__ 是 "<trust>:<subject>"，例如 "authenticated:42"。
    # 拆出信任前缀；仅对 "authenticated" 放行 on-behalf-of。
    trust, _, subject = __mateclaw_user__.partition(":")
    if trust != "authenticated":
        raise ValueError(f"refusing on-behalf-of for non-authenticated identity: {__mateclaw_user__}")
    headers = {
        "Authorization": f"ApiKey {API_KEY}",            # 服务身份
        "X-On-Behalf-Of": subject,                       # 代表的用户
    }
    r = httpx.get(f"{REST_BASE}/orders", params={"q": keyword}, headers=headers, timeout=30)
    r.raise_for_status()
    return r.text

if __name__ == "__main__":
    mcp.run()   # STDIO
```

> 工具的入参 schema 若是 `additionalProperties: false`，记得像上面那样把 `__mateclaw_user__` 声明为可选参数，否则严格校验会拒绝。

### 身份类型

并非每个调用者都是 MateClaw 已认证账号。注入的身份带类型，让 REST 后端能判断这次 on-behalf-of 调用该信多少：

- **`authenticated`**——web 控制台登录（JWT/PAT）。MateClaw 为该用户背书。标准路径上 `sub` 是用户**不可变数字 id**（`ChatOrigin.requesterUserId`），仅旧 ThreadLocal 路径回退为用户名。后端可放心按该用户做 on-behalf-of 授权。
- **`anonymous`**——webchat 访客 / 第三方单账号 `endUserId`。背后无 MateClaw 账号，`sub` 是访客 id。**后端必须视作未认证**，自行决定是否/如何服务——不要把 `sub` 映射到 MateClaw 账号，也不要授予认证用户权限。
- **`external`**——IM 发送者（飞书/企微…）。`sub` 是平台发送者 id；同 anonymous 的注意事项。
- _（不注入）_——cron / system / 无归属来源。MateClaw 从不为非用户断言身份（fail-closed）。

这一类型划分在下面的**签名 token**模型里尤为关键：验签通过只证明 token 由 MateClaw 签发，**并不**代表 `sub` 是认证账号。授权前务必看 `trust` claim（或明文前缀）。

### 两种信任模型

**① 明文（默认）**：注入明文用户名。适合 REST 在内网、且后端用 API Key 认证 MCP 服务、把转发用户当 on-behalf-of 的场景。后端裸信这个字符串。

**② 签名 token（推荐用于跨信任边界）**：注入一个 MateClaw 用私钥现签的**短时 RS256 JWT**（保留字段换成 **`__mateclaw_token__`**），REST 后端用**公钥验签**——它信任的是签名，而非 MCP 服务/Python/传输。

```yaml
mateclaw:
  mcp:
    identity-forward:
      servers:
        - my-internal-api
      token:
        enabled: true
        issuer: mateclaw
        ttl-seconds: 60                 # 短时，几十秒
        key-id: mateclaw-mcp-1
        private-key-pem: ${MCP_IDFWD_PRIVATE_KEY_PEM:}   # PKCS#8 PEM（RS256 私钥）
        audiences:                      # 可选；默认 aud = server 名
          my-internal-api: https://api.internal
```

生成密钥对（私钥配给 MateClaw，公钥配给 REST 后端）：

```bash
openssl genpkey -algorithm RSA -pkcs8 -out mcp-idfwd-private.pem
openssl pkey -in mcp-idfwd-private.pem -pubout -out mcp-idfwd-public.pem
# private-key-pem 用私钥内容（带不带 PEM 头都行，解析时会剥掉）
```

token 的 claims：`iss`、`sub`=主体、`aud`=该 server、`iat`、`exp`（短）、`jti`，外加两个类型 claim——**`trust`**（`authenticated` / `anonymous` / `external`）和 **`channel_type`**（`web` / `api` / `feishu` / …）。`aud`+短 `exp` 把重放限制在几十秒内、且只对这一个后端。**token 模式开启但没配私钥时 fail-closed**（不签、不注入，后端自然拒绝），不会偷偷退回明文。

> **`sub` 语义。** 标准认证 web 路径上 `sub` 是用户的**不可变数字 id**（`ChatOrigin.requesterUserId`）；仅旧 ThreadLocal 路径回退为用户名。后端无需再做用户名→id 解析。授权前务必同时看 `trust` claim——验签通过只证明 token 由 MateClaw 签发，不代表 `sub` 是认证账号（匿名访客的 token 同样能验签通过）。

MCP server（Python）只透传、不验签：

```python
@mcp.tool()
def query_orders(keyword: str, __mateclaw_token__: str | None = None) -> str:
    if not __mateclaw_token__:
        raise ValueError("missing identity token")
    headers = {"Authorization": f"Bearer {__mateclaw_token__}"}   # 直接透传给 REST
    return httpx.get(f"{REST_BASE}/orders", params={"q": keyword}, headers=headers, timeout=30).text
```

REST 后端验签（伪代码）：

```python
import jwt  # PyJWT
# 留一点时钟偏差余量——iat/exp 用的是 MateClaw 的时钟，可能与本后端有漂移。
# ttl-seconds=60 时，leeway≈10s 可避免在窗口边界拒绝合法 token。
claims = jwt.decode(token, public_key_pem, algorithms=["RS256"],
                    issuer="mateclaw", audience="https://api.internal", leeway=10)
# 验签通过只证明 token 由 MateClaw 签发——并不代表 sub 是认证账号。
# 授权前务必看 trust claim。
if claims.get("trust") != "authenticated":
    raise PermissionError(f"refuse on-behalf-of for {claims.get('trust')} identity")
user = claims["sub"]            # 不可变数字 id（认证路径）
# → 按 user 做 per-user 授权；验签失败/过期 → 401
```

> **时钟偏差。** `iat`/`exp` 按 MateClaw 主机时钟计算。默认 60s TTL 下，请让
> MateClaw 与 REST 后端共用同一 NTP 源，并在验签侧配置 leeway（PyJWT
> `leeway=10`、jjwt `setAllowedClockSkewSeconds(10)`），避免微小漂移误拒合法
> token。部署偏差较大时调大 `ttl-seconds`。

> **密钥轮换手册（暂无 JWKS）。** 公钥当前带外分发（JWKS 端点是后续工作），
> 因此轮换签名密钥是**多主机协同操作**，不是 MateClaw 单侧一键完成：
> 1. 生成新密钥对（`openssl genpkey -algorithm RSA -pkcs8 ...`）。
> 2. **先把每个 REST 后端的公钥更新到位**——在后端全部接受新公钥之前，用
>    新私钥签的 token 会被 401 拒绝。
> 3. 然后更新 MateClaw 的 `MCP_IDFWD_PRIVATE_KEY_PEM` 并重启（或对 properties
>    bean 做 refresh-scoped 重绑）。
> 4. 观察日志出现 `[McpIdentity] loaded RS256 signing key (kid=…)`——新 `kid`
>    确认轮换已生效。
>
> MateClaw 侧轮换后若后端 401 激增，几乎一定是某个后端漏做了第 2 步。

> 公钥分发：当前由运维把上面生成的公钥配到 REST 侧（带外）。后续可加一个 JWKS 端点自动分发+轮换。
>
> 与 API Key 的关系：可保留 API Key 作"服务/通道认证"（这台 MCP 服务被允许跟后端说话）+ JWT 作"用户断言"，双层更清晰；也可让 JWT 一肩挑。

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
