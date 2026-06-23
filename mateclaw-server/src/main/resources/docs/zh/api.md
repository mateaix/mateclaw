# API 参考

本页以 `mateclaw-server/src/main/java` 下的 Spring MVC Controller 注解为准。下面的路由索引由源码注解重建；如果它和旧功能页冲突，以本页和源码为接口契约。

> 想要机读的 OpenAPI 文档（导入 Postman / Apifox、在线调试）？见 [OpenAPI / Swagger 指南](./openapi.md) —— 部署后访问 `/swagger-ui.html`。

## 全局契约

应用 REST 端点默认使用 `/api/v1` 前缀。大多数 JSON 响应使用项目统一信封：

```json
{
  "code": 200,
  "msg": "success",
  "data": {}
}
```

例外：

- 流式端点（`text/event-stream`）返回 SSE frame，不走 JSON 信封。
- 下载类端点，例如 `/api/v1/files/generated/{id}`、聊天附件、Wiki 原始材料下载，返回字节或 `ResponseEntity`。
- 少量需要客户端按 HTTP 状态码分支的冲突/确认流程会返回独立结构体。

后端 Snowflake `Long` ID 会序列化成 JSON 字符串。前端和第三方客户端都应把 ID 全程当字符串处理。

## 认证

`POST /api/v1/auth/login` 返回 JWT。受保护接口请求头：

```text
Authorization: Bearer <token>
```

`SecurityConfig` 中放行的公共路径包括登录、首次初始化、webhook/webchat 回调、chat stream/stop、agent stream、talk WebSocket、`GET /api/v1/settings/language`，以及 `/api/v1/files/generated/**` 一次性生成文件下载。认证通过后，`@RequireWorkspaceRole`、`@RequireGlobalAdmin` 等角色约束仍会继续生效。

工作空间接口通常接受 `X-Workspace-Id`。省略时，很多 handler 会为了桌面/本地兼容回退到 workspace `1`。

## 通用约定

下面是所有端点共用的结构约定。读完这一节，再看后面的旗舰端点示例和完整路由表就能对上。

### 统一响应信封 `R<T>`

源码：`vip.mate.common.result.R`（`R.java`）。三个字段：

| 字段 | 类型 | 含义 |
|---|---|---|
| `code` | `int` | 状态码。`200` 为成功；其余见下方「状态码」 |
| `msg` | `string` | 提示信息。**注意是 `msg` 不是 `message`** |
| `data` | `T` | 业务数据；失败时为 `null` |

成功示例：

```json
{ "code": 200, "msg": "success", "data": { "id": "1", "name": "助手A" } }
```

失败示例：

```json
{ "code": 401, "msg": "Token expired or invalid", "data": null }
```

**HTTP 状态码与 `code` 对齐**：`RHttpStatusAdvice`（`ResponseBodyAdvice`）在 `code != 200` 时把 HTTP 状态设成 `HttpStatus.resolve(code)`。即业务码 `401` → HTTP 401，业务码 `404` → HTTP 404。业务码若不是合法 HTTP 状态（如 `1001`、`2001`），则 HTTP 回落到 `500`。

### 状态码

源码：`vip.mate.common.result.ResultCode`。注意区分两类：

| 码 | 含义 | 是否直接作 HTTP 状态 |
|---|---|---|
| `200` | 成功 | 是 |
| `400` | 参数错误 | 是 |
| `401` | 未认证 | 是 |
| `403` | 无权限 | 是 |
| `404` | 资源不存在 | 是 |
| `500` | 系统错误 | 是 |
| `1001` | Agent 不存在 | 否（HTTP 500） |
| `1002` | Agent 忙碌 | 否（HTTP 500） |
| `2001` | LLM 调用错误 | 否（HTTP 500） |
| `3001` | 工具不存在 | 否（HTTP 500） |
| `4001` | 渠道错误 | 否（HTTP 500） |

### 错误模型

错误统一由 `GlobalExceptionHandler`（`@RestControllerAdvice`）处理。下表覆盖常见情况：

| HTTP | 触发 | 响应体 |
|---|---|---|
| 400 | `@Valid` / `BindException` 校验失败 | `{code:400, msg:"字段名: 默认信息"}` — 只返回**首个**字段错误 |
| 400 | `MethodArgumentTypeMismatchException`（如 `/{id}` 传了非数字） | `{code:400, msg:"Invalid value for parameter 'X': expected Long"}` |
| 401 | 未认证 / token 无效（`SecurityConfig` 的 `authenticationEntryPoint`） | `{code:401, msg:"Token expired or invalid"}` |
| 403 | 工作区角色不足（`WorkspaceAccessInterceptor` 直接写响应） | `{code:403, msg:"...", data:null}` |
| 404 | 路由不匹配（`NoResourceFoundException`） | `{code:404, msg:"Resource not found"}` |
| 405 | 方法不允许（`HttpRequestMethodNotSupportedException`） | `{code:405, msg:"Method not allowed"}` |
| 409 | 需二次确认（`ConfirmRequiredException`） | **打破信封**：`{code, message, boundAgents}`（字段是 `message`，**不是** `msg`）—— 全 API 唯一非 `R` 响应 |
| 500 | 兜底（`Exception`） | `{code:500, msg:"Internal server error"}` — 栈不外泄 |
| 503 | 异步超时（非 SSE 请求） | `{code:503, msg:"Request timeout, please try again"}` |

> SSE 端点（`/chat/stream` 等）出错时**不发 JSON 信封**，而是发送 SSE `error` 事件：`event: error` / `data: {"message":"..."}`。详见 [WebChat 指南](./webchat.md#sse-事件协议)。

### 分页结构

分页端点直接返回 `R<IPage<T>>`，`data` 是 MyBatis Plus 的 `Page` 序列化结构：

```json
{
  "code": 200,
  "data": {
    "records": [ /* 当前页数据 */ ],
    "total": 128,
    "size": 20,
    "current": 1,
    "pages": 7
  }
}
```

| 字段 | 含义 |
|---|---|
| `records` | 当前页数据数组（**字段名是 `records`**，不是 `list`/`items`） |
| `total` | 总记录数 |
| `size` | 每页条数 |
| `current` | 当前页码，从 `1` 开始 |
| `pages` | 总页数 |

常见分页查询参数：`page`（默认 1）、`size`（默认 20）。示例端点见 `GET /api/v1/audit/events`、`GET /api/v1/conversations/page`。

### ID 与类型约定

- **Snowflake `Long` 序列化为 JSON 字符串**：后端所有主键是 `Long`，但 Jackson 序列化成字符串。客户端（尤其 JS）应**全程按 `string` 处理**，避免 `Number.MAX_SAFE_INTEGER` 精度丢失。
- **密码字段只写不读**：`UserEntity.password` 标注 `@JsonProperty(access = WRITE_ONLY)`，登录/建用户时接受写入，但任何响应里都不会出现。

### 认证模型

`JwtAuthFilter` 支持三种 token 形态，全部走 `Authorization` 头：

1. **JWT**：`Authorization: Bearer <jwt>`。以 `eyJ` 开头（base64 头）。登录接口返回的 `token` 字段即此。
2. **Personal Access Token (PAT)**：`Authorization: Bearer <pat>`。以 `mc_` 前缀开头，用于 headless / CI / SDK 场景。PAT 在 `POST /api/v1/auth/tokens` 创建时**明文只返回一次**，之后只存哈希。filter 按 `mc_` 前缀分发到 PAT 校验路径。
3. **SSE 的 `?token=` 查询参数**：浏览器原生 `EventSource` 不能设置自定义请求头，SSE 流式端点额外接受 `?token=<token>`（JWT 或 PAT 均可）。

**滑动续期**：JWT 接近过期（默认剩余 < 2 小时）时，响应头回传新 token —— `X-New-Token: <newJwt>`（同时设 `Access-Control-Expose-Headers: X-New-Token`）。客户端应监听并替换本地存储的 token。JWT TTL 默认 24 小时（`mateclaw.jwt.expiration=86400000`）。

### `X-Workspace-Id` 的工作机制

- **无 ThreadLocal / 请求上下文持有**。工作区 ID 通过两种途径消费：
  1. **RBAC 拦截**：`WorkspaceAccessInterceptor` 对标注了 `@RequireWorkspaceRole`（角色 owner > admin > member > viewer）或 `@RequireGlobalAdmin` 的方法，读取 `X-Workspace-Id` 做权限校验。缺失或无法解析时回落 workspace `1`。权限不足直接写 403 JSON 响应。
  2. **业务读取**：许多 Controller 用 `@RequestHeader(value="X-Workspace-Id", required=false) Long workspaceId` 直接取值用于数据查询范围；缺失时同样回落 `1`。
- 因此工作区隔离由「拦截器鉴权 + Controller 自取」两段配合实现，客户端调用工作区相关接口时应显式传 `X-Workspace-Id`。

## 常用接口

### 登录

```bash
curl -X POST http://localhost:18088/api/v1/auth/login \
  -H "Content-Type: application/json" \
  -d '{"username":"admin","password":"admin123"}'
```

### 聊天

```bash
curl -N -X POST http://localhost:18088/api/v1/chat/stream \
  -H "Authorization: Bearer $TOKEN" \
  -H "Content-Type: application/json" \
  -H "Accept: text/event-stream" \
  -d '{"agentId":"1","message":"你好","conversationId":"conv-abc123"}'
```

`/chat/stream` 是 POST SSE；浏览器原生 `EventSource` 不能带 POST body，请用 `fetch()` 读取流。

### 工具审批

当前没有 `POST /api/v1/approvals/{id}/resolve` REST 端点。Web 端批准/拒绝通过等待中的会话发送 `/approve` 或 `/deny`，走 chat stream replay 流程。刷新页面后的只读补水接口仍是 `GET /api/v1/chat/{conversationId}/pending-approvals`。自动批准策略在 `/api/v1/approval/grants` 下管理。

### Doctor / 健康检查

当前后端健康接口是 `GET /api/v1/system/health`。旧文档里的 `/api/v1/doctor/*` 端点在当前源码中没有实现。

### 多模态生成

图片、视频、音乐、3D 生成是 Agent 工具（`image_generate`、`video_generate`、`music_generate`、`model3d_generate`），不是独立的 `/api/v1/image`、`/api/v1/video`、`/api/v1/music` REST Controller。当前存在的相关 REST 面是 TTS/STT 和生成文件下载。

### 非 REST 端点

`/api/v1/talk/ws` 由 `WebSocketConfig` 注册，用于 Talk Mode。它会出现在 `SecurityConfig` 的公共 WebSocket 路由里，但不计入下面的 controller 路由索引。

## 旗舰端点参考

下面是接入最频繁的端点的完整请求/响应说明。字段均与源码 DTO 一一对齐。下面的 406 行路由表是完整索引，本节是高频端点的「人读」详解。

### 登录：`POST /api/v1/auth/login`

公开端点（无需认证）。换取 JWT。

**请求体** `LoginRequest`（`AuthController.java`）：

| 字段 | 类型 | 说明 |
|---|---|---|
| `username` | string | 用户名 |
| `password` | string | 密码 |

**响应** `R<LoginResponse>`：

```json
{
  "code": 200,
  "data": {
    "id": "1",
    "token": "eyJhbGciOi...",
    "username": "admin",
    "nickname": "管理员",
    "role": "admin"
  }
}
```

| 字段 | 类型 | 说明 |
|---|---|---|
| `id` | string | 用户 ID（Snowflake，字符串） |
| `token` | string | **JWT**，后续请求放 `Authorization: Bearer <token>`。响应里没有独立 expiry 字段，过期时间在 JWT 的 `exp` claim 内 |
| `username` | string | 用户名 |
| `nickname` | string | 昵称 |
| `role` | string | `admin` 或 `user` |

**错误**：用户名/密码错误 → HTTP 401，`{code:401, msg:"用户名或密码错误"}`。

```bash
curl -X POST http://localhost:18088/api/v1/auth/login \
  -H "Content-Type: application/json" \
  -d '{"username":"admin","password":"admin123"}'
```

### 流式对话：`POST /api/v1/chat/stream`

> 公开端点（`SecurityConfig` 放行 `/api/v1/chat/stream`），但实际使用时仍需 token 才能定位用户与权限——传 `?token=` 或 `Authorization` 头。

返回 `text/event-stream`，**不走 JSON 信封**。SSE 事件协议（`meta` / `content_delta` / `done` / `error` 等）见 [WebChat 指南](./webchat.md#sse-事件协议)。

**请求体** `ChatController.ChatStreamRequest`（`ChatController.java:1211`）：

| 字段 | 类型 | 默认 | 说明 |
|---|---|---|---|
| `agentId` | string | — | 必填，目标 Agent ID |
| `message` | string | — | 本轮用户消息（与 `contentParts` 二选一） |
| `contentParts` | array | — | 多模态消息分片（图文混合），与 `message` 二选一 |
| `conversationId` | string | `"default"` | 会话 ID；新会话用客户端生成的唯一串 |
| `reconnect` | boolean | — | `true` = 断线重连，不发新消息，只附着到已有流 |
| `lastEventId` | string | — | 仅 `reconnect=true` 有意义：跳过 id ≤ 此值的事件，避免重放重复 |
| `thinkingLevel` | string | null | 思考深度：`off` / `low` / `medium` / `high` / `max`；null 跟随 Agent 默认 |
| `modelProvider` | string | null | 本会话模型 provider 覆盖（与 `modelName` 配对） |
| `modelName` | string | null | 本会话模型名覆盖 |
| `endUserId` | string | null | 第三方终端用户 ID，用于一个 MateClaw 账号下的记忆隔离 |

浏览器原生 `EventSource` 不支持 POST body，必须用 `fetch()` + 流式 reader。

```bash
curl -N -X POST "http://localhost:18088/api/v1/chat/stream?token=$TOKEN" \
  -H "Content-Type: application/json" \
  -H "Accept: text/event-stream" \
  -d '{"agentId":"1","message":"你好","conversationId":"conv-abc123"}'
```

相关端点：`POST /api/v1/chat/{conversationId}/stop`（停止生成）、`POST /api/v1/chat/{conversationId}/interrupt`（排队后续消息，不打断当前流）。

### Agent 管理

挂在 `/api/v1/agents`，`@Tag("Agent管理")`。所有方法需 `@RequireWorkspaceRole`（至少 `viewer`，写入需 `member`）。

**列表** `GET /api/v1/agents?enabled=true` — 请求头 `X-Workspace-Id`；返回 `R<List<AgentEntity>>`。

**创建** `POST /api/v1/agents` — 请求体是 `AgentEntity`（关键字段见下），后端强制注入 `workspaceId` 与 `creatorUserId`。返回创建后的完整实体。

`AgentEntity` 关键字段（`AgentEntity.java`）：

| 字段 | 类型 | 说明 |
|---|---|---|
| `id` | string | Agent ID（创建时忽略，后端分配） |
| `name` | string | 名称 |
| `description` | string | 描述 |
| `agentType` | string | `react` 或 `plan_execute` |
| `systemPrompt` | string | 系统提示词 |
| `modelName` | string | Per-Agent 模型覆盖（模型名）；空则用全局默认 |
| `maxIterations` | int | 最大迭代次数 |
| `enabled` | boolean | 是否启用 |
| `icon` | string | 图标（emoji 或 URL） |
| `tags` | string | 标签（逗号分隔） |
| `defaultThinkingLevel` | string | 默认思考深度 |
| `primaryKbId` | string | 主知识库 ID |
| `skillsDisabled` | boolean | 显式禁用所有 Skill |
| `toolsDisabled` | boolean | 显式禁用所有非系统工具 |

**删除** `DELETE /api/v1/agents/{id}` — 三选一鉴权：系统 admin / 工作区 admin+ / 创建者本人。否则 403。

```bash
# 列表
curl http://localhost:18088/api/v1/agents?enabled=true \
  -H "Authorization: Bearer $TOKEN" -H "X-Workspace-Id: 1"

# 创建
curl -X POST http://localhost:18088/api/v1/agents \
  -H "Authorization: Bearer $TOKEN" -H "X-Workspace-Id: 1" \
  -H "Content-Type: application/json" \
  -d '{"name":"客服助手","agentType":"react","systemPrompt":"你是一名客服","enabled":true}'
```

> 同目录下还有 `GET /api/v1/agents/{id}/chat/stream`（GET 形式的 SSE，与上面 POST `/chat/stream` 并存）、`POST /api/v1/agents/{id}/chat`（同步对话）、`POST /api/v1/agents/{id}/execute`（Plan-Execute）。

### 会话管理

挂在 `/api/v1/conversations`，`@Tag("会话管理")`。按当前登录用户（JWT principal）隔离。

**列表** `GET /api/v1/conversations` — 返回 `R<List<ConversationVO>>`。`ConversationVO` 在会话实体基础上补充展示字段：

| 字段 | 说明 |
|---|---|
| `conversationId` | 会话 ID（字符串，非 Snowflake） |
| `title` | 标题 |
| `agentId` / `agentName` / `agentIcon` | 关联 Agent |
| `username` | 会话归属用户 |
| `messageCount` | 消息数 |
| `lastMessage` / `lastActiveTime` | 最近消息与时间 |
| `pinned` / `archived` | 置顶 / 归档（0/1） |
| `modelProvider` / `modelName` | 会话级模型覆盖 |
| `status` | `active`（24h 内活跃）/ `closed` |
| `streamStatus` | `idle` / `running` |
| `source` | 来源渠道：`web` / `feishu` / `dingtalk` / `telegram` / `discord` / `wecom` / `qq` / `weixin` / `cron` |

**分页** `GET /api/v1/conversations/page?page=1&size=20&keyword=xxx` — 返回 `R<IPage<ConversationVO>>`（分页结构见「通用约定」）。

**消息历史** `GET /api/v1/conversations/{conversationId}/messages` — 支持三种模式：
- 不传 `limit`：返回全部消息（`R<List<MessageVO>>`，向后兼容）。
- 传 `limit`：返回最新 `limit` 条 + `hasMore` 标志：`R<{messages: MessageVO[], hasMore: boolean}>`。
- 传 `beforeId` + `limit`：上拉加载更早消息。

`MessageVO` 关键字段：`id`、`role`、`content`、`toolName`、`status`、`metadata`（对象，含 toolCalls 等）、`promptTokens` / `completionTokens`、`runtimeModel` / `runtimeProvider`、`contentParts`、`createTime`。

**会话级操作**：`PUT .../title`（重命名）、`PUT .../pin`（置顶 `{pinned:bool}`）、`PUT .../model`（切换模型 `{modelProvider, modelName}`）、`DELETE .../messages`（清空消息保留会话）、`DELETE .../{conversationId}`（删除会话）、`POST /batch-delete`（`{conversationIds: [...]}`）、`GET .../status`（查流状态 `{streamStatus}`）。

> 所有操作都先校验 `isConversationOwner(conversationId, username)`，非归属者返回 403。

### 模型配置

挂在 `/api/v1/models`，`@Tag("模型配置管理")`。`GET /` 与 `GET /catalog` 需 `@RequireGlobalAdmin`（含 API Key 等敏感信息）；`/enabled`、`/default`、`/active` 仅需 `viewer`。

- `GET /api/v1/models` — 已启用 Provider 列表（`R<List<ProviderInfoDTO>>`，含密钥，admin only）。
- `GET /api/v1/models/enabled` — 已启用模型列表（`R<List<ModelConfigEntity>>`，无密钥）。
- `GET /api/v1/models/default` — 全局默认模型（`R<ModelConfigEntity>`）。
- `GET /api/v1/models/active` — 当前激活模型 `{activeLlm: {provider, modelName}}`。
- `PUT /api/v1/models/active` — 设置激活模型。

### 审计事件（分页示范）

`GET /api/v1/audit/events` — `@RequireWorkspaceRole("admin")`，返回 `R<IPage<AuditEventEntity>>`，是「分页 + 工作区头」的标准示范。

| 查询参数 | 默认 | 说明 |
|---|---|---|
| `action` | — | 动作过滤（如 `CREATE` / `UPDATE` / `DELETE`） |
| `resourceType` | — | 资源类型过滤（如 `AGENT`） |
| `startTime` | — | ISO 8601 起始时间 |
| `endTime` | — | ISO 8601 结束时间 |
| `page` | 1 | 页码 |
| `size` | 20 | 每页条数 |

```bash
curl "http://localhost:18088/api/v1/audit/events?page=1&size=20&resourceType=AGENT" \
  -H "Authorization: Bearer $TOKEN" -H "X-Workspace-Id: 1"
```

### 修改密码：`PUT /api/v1/auth/users/{id}/password`

注意三点（与直觉不同）：

1. 参数走 **`@RequestParam` 而非请求体**：`oldPassword` 和 `newPassword` 都是 query 参数。
2. 路径里的 `{id}` **仅信息性**：实际操作的用户从 JWT principal 解析（`auth.getName()`），用户只能改自己的密码。
3. 需登录（非 `@RequireGlobalAdmin`）。

```bash
curl -X PUT "http://localhost:18088/api/v1/auth/users/1/password?oldPassword=admin123&newPassword=newPass456" \
  -H "Authorization: Bearer $TOKEN"
```

### Personal Access Token

挂在 `/api/v1/auth/tokens`，`@Tag("Personal Access Tokens")`。用于 headless / CI / SDK。

- `GET /api/v1/auth/tokens` — 列出我的 PAT（仅元数据，**明文永不返回**）。
- `POST /api/v1/auth/tokens` — 创建 PAT：**明文只在此响应里出现一次**，之后只存 SHA-256 哈希，无法找回。创建后立即保存。
- `DELETE /api/v1/auth/tokens/{id}` — 软删除吊销，此后用该 token 鉴权会失败。

创建出的 PAT（`mc_` 前缀）可直接放 `Authorization: Bearer mc_...`，`JwtAuthFilter` 按前缀分发到 PAT 校验路径，与 JWT 行为一致。

### 工具审批（重要澄清）

**没有** `POST /api/v1/approvals/{id}/resolve` 这样的独立审批 REST 端点。Web 端的批准 / 拒绝通过在等待中的会话里发送 `/approve` 或 `/deny` 走 chat stream replay 流程。刷新页面后的只读「补水」接口是 `GET /api/v1/chat/{conversationId}/pending-approvals`。自动批准策略在 `/api/v1/approval/grants` 下管理。

> 需二次确认的危险操作会触发 `ConfirmRequiredException` —— 返回 **HTTP 409** 且**打破 `R` 信封**：`{code, message, boundAgents}`（字段是 `message` 不是 `msg`），客户端应按 409 状态码分支渲染确认弹窗。

## 源码对齐路由索引

抽取到的路由总数：406。

### 认证

| 方法 | 路径 | 用途 / handler |
|---|---|---|
| `POST` | `/api/v1/auth/login` | `用户登录` |
| `GET` | `/api/v1/auth/tokens` | `List my PATs (metadata only — plaintext is never returned after creation)` |
| `POST` | `/api/v1/auth/tokens` | `Mint a new PAT — returned plaintext is shown once and cannot be recovered` |
| `DELETE` | `/api/v1/auth/tokens/{id}` | `Revoke a PAT — soft-delete; further auth attempts with this token will fail` |
| `GET` | `/api/v1/auth/users` | `获取用户列表` |
| `POST` | `/api/v1/auth/users` | `创建用户` |
| `PUT` | `/api/v1/auth/users/{id}/password` | `修改密码` |

### 聊天

| 方法 | 路径 | 用途 / handler |
|---|---|---|
| `POST` | `/api/v1/chat` | `同步对话` |
| `GET` | `/api/v1/chat/files/{conversationId}/{storedName:.+}` | `读取聊天附件` |
| `POST` | `/api/v1/chat/stream` | `结构化 SSE 流式对话（支持重连）` |
| `POST` | `/api/v1/chat/upload` | `上传聊天附件` |
| `POST` | `/api/v1/chat/{conversationId}/interrupt` | `排队后续消息（不打断当前流）` |
| `GET` | `/api/v1/chat/{conversationId}/pending-approvals` | `查询待审批记录` |
| `POST` | `/api/v1/chat/{conversationId}/stop` | `停止流式生成` |

### 会话

| 方法 | 路径 | 用途 / handler |
|---|---|---|
| `GET` | `/api/v1/conversations` | `获取会话列表` |
| `POST` | `/api/v1/conversations/batch-delete` | `批量删除会话` |
| `GET` | `/api/v1/conversations/page` | `分页查询会话列表` |
| `DELETE` | `/api/v1/conversations/{conversationId}` | `删除会话` |
| `DELETE` | `/api/v1/conversations/{conversationId}/messages` | `清空会话消息` |
| `GET` | `/api/v1/conversations/{conversationId}/messages` | `获取会话消息历史（支持分页）` |
| `PUT` | `/api/v1/conversations/{conversationId}/model` | `切换会话使用的模型 (provider + model name)` |
| `PUT` | `/api/v1/conversations/{conversationId}/pin` | `置顶或取消置顶会话` |
| `GET` | `/api/v1/conversations/{conversationId}/status` | `获取会话流状态` |
| `PUT` | `/api/v1/conversations/{conversationId}/title` | `重命名会话` |

### Agent

| 方法 | 路径 | 用途 / handler |
|---|---|---|
| `GET` | `/api/v1/agents` | `获取Agent列表` |
| `POST` | `/api/v1/agents` | `创建Agent` |
| `GET` | `/api/v1/agents/{agentId}/provider-preferences` | `获取 Agent 的偏好 Provider 顺序` |
| `PUT` | `/api/v1/agents/{agentId}/provider-preferences` | `批量设置 Agent 的偏好 Provider 顺序（替换模式）` |
| `GET` | `/api/v1/agents/{agentId}/skills` | `获取 Agent 已绑定的 Skills` |
| `PUT` | `/api/v1/agents/{agentId}/skills` | `批量设置 Agent 的 Skill 绑定` |
| `DELETE` | `/api/v1/agents/{agentId}/skills/{skillId}` | `解绑单个 Skill` |
| `POST` | `/api/v1/agents/{agentId}/skills/{skillId}` | `绑定单个 Skill` |
| `GET` | `/api/v1/agents/{agentId}/tools` | `获取 Agent 已绑定的 Tools` |
| `PUT` | `/api/v1/agents/{agentId}/tools` | `批量设置 Agent 的 Tool 绑定` |
| `GET` | `/api/v1/agents/{agentId}/workspace/files` | `列出工作区文件` |
| `DELETE` | `/api/v1/agents/{agentId}/workspace/files/**` | `删除工作区文件` |
| `GET` | `/api/v1/agents/{agentId}/workspace/files/**` | `读取工作区文件` |
| `PUT` | `/api/v1/agents/{agentId}/workspace/files/**` | `保存工作区文件` |
| `GET` | `/api/v1/agents/{agentId}/workspace/memory/export` | `导出 Agent 记忆快照（ZIP）` |
| `POST` | `/api/v1/agents/{agentId}/workspace/memory/import` | `导入 Agent 记忆快照（写入）` |
| `POST` | `/api/v1/agents/{agentId}/workspace/memory/import/preview` | `预览导入 Agent 记忆快照（不写入）` |
| `GET` | `/api/v1/agents/{agentId}/workspace/prompt-files` | `获取系统提示文件列表` |
| `PUT` | `/api/v1/agents/{agentId}/workspace/prompt-files` | `设置系统提示文件列表` |
| `DELETE` | `/api/v1/agents/{id}` | `删除Agent` |
| `GET` | `/api/v1/agents/{id}` | `获取Agent详情` |
| `PUT` | `/api/v1/agents/{id}` | `更新Agent` |
| `GET` | `/api/v1/agents/{id}/capabilities` | `获取Agent当前能力（modality 集合 + sidecar 配置），用于聊天页提示条` |
| `POST` | `/api/v1/agents/{id}/chat` | `同步对话` |
| `GET` | `/api/v1/agents/{id}/chat/stream` | `流式对话（SSE）` |
| `POST` | `/api/v1/agents/{id}/execute` | `执行复杂任务（Plan-Execute）` |
| `GET` | `/api/v1/agents/{id}/state` | `获取Agent运行状态` |

### Agent 模板

| 方法 | 路径 | 用途 / handler |
|---|---|---|
| `GET` | `/api/v1/templates` | `获取模板列表` |
| `POST` | `/api/v1/templates/{id}/apply` | `应用模板创建Agent` |

### 子 Agent

| 方法 | 路径 | 用途 / handler |
|---|---|---|
| `GET` | `/api/v1/subagents/active` | `List active sub-agents in a conversation's delegation tree` |
| `POST` | `/api/v1/subagents/spawn-pause` | `Set sub-agent spawn-pause for a conversation` |
| `POST` | `/api/v1/subagents/{subagentId}/interrupt` | `Interrupt a running sub-agent` |

### 运行时管理

| 方法 | 路径 | 用途 / handler |
|---|---|---|
| `POST` | `/api/v1/admin/agent-runtime/runs/{conversationId}/recycle` | `Force recycle — dispose flux + drop RunState; use after friendly stop ignored` |
| `POST` | `/api/v1/admin/agent-runtime/runs/{conversationId}/stop` | `Friendly stop — request the run to wind down at its next checkpoint` |
| `GET` | `/api/v1/admin/agent-runtime/snapshot` | `Snapshot of every in-flight agent turn` |
| `POST` | `/api/v1/admin/agent-runtime/subagents/{subagentId}/interrupt` | `Interrupt one sub-agent (admin override of ownership check)` |
| `POST` | `/api/v1/admin/agent-runtime/sweep` | `Recycle every run currently flagged as stuck` |

### 自动批准策略

| 方法 | 路径 | 用途 / handler |
|---|---|---|
| `GET` | `/api/v1/approval/grants` | `列出当前 workspace 的自动批准策略（分页）` |
| `POST` | `/api/v1/approval/grants` | `创建自动批准策略` |
| `GET` | `/api/v1/approval/grants/active` | `当前 workspace 的活跃策略数量摘要` |
| `DELETE` | `/api/v1/approval/grants/{id}` | `撤销自动批准策略` |
| `GET` | `/api/v1/approval/resolutions` | `查询审批最终决策日志（按 grantId 或 conversationId 过滤）` |

### 安全与 Tool Guard

| 方法 | 路径 | 用途 / handler |
|---|---|---|
| `GET` | `/api/v1/security/approvals` | `审批记录（管理视角）` |
| `GET` | `/api/v1/security/audit/logs` | `审计日志` |
| `GET` | `/api/v1/security/audit/stats` | `审计统计` |
| `GET` | `/api/v1/security/guard/config` | `获取 Guard 配置` |
| `PUT` | `/api/v1/security/guard/config` | `更新 Guard 配置` |
| `GET` | `/api/v1/security/guard/config/file-guard` | `获取 File Guard 配置` |
| `PUT` | `/api/v1/security/guard/config/file-guard` | `更新 File Guard 配置` |
| `GET` | `/api/v1/security/guard/rules` | `规则列表` |
| `POST` | `/api/v1/security/guard/rules` | `新增自定义规则` |
| `GET` | `/api/v1/security/guard/rules/builtin` | `内置规则列表` |
| `DELETE` | `/api/v1/security/guard/rules/by-id/{id}` | `按主键 ID 删除自定义规则（兜底，rule_id 异常时使用）` |
| `GET` | `/api/v1/security/guard/rules/export` | `导出全部规则为 JSON` |
| `POST` | `/api/v1/security/guard/rules/import` | `从 JSON 批量导入规则（upsert 语义）` |
| `DELETE` | `/api/v1/security/guard/rules/{ruleId}` | `删除自定义规则` |
| `PUT` | `/api/v1/security/guard/rules/{ruleId}` | `更新规则` |
| `PUT` | `/api/v1/security/guard/rules/{ruleId}/toggle` | `启用/禁用规则` |

### 审计

| 方法 | 路径 | 用途 / handler |
|---|---|---|
| `GET` | `/api/v1/audit/events` | `分页查询审计事件` |

### 活动流

| 方法 | 路径 | 用途 / handler |
|---|---|---|
| `GET` | `/api/v1/activity/feed` | `Unified activity feed (audit + approval + tool calls)` |

### 通知

| 方法 | 路径 | 用途 / handler |
|---|---|---|
| `GET` | `/api/v1/notifications/summary` | `Aggregated counts for the sidebar attention badges` |

### 工作空间

| 方法 | 路径 | 用途 / handler |
|---|---|---|
| `GET` | `/api/v1/workspaces` | `获取当前用户的工作区列表（含 memberRole 与 effectiveRole）` |
| `POST` | `/api/v1/workspaces` | `创建工作区` |
| `DELETE` | `/api/v1/workspaces/{id}` | `删除工作区` |
| `GET` | `/api/v1/workspaces/{id}` | `获取工作区详情` |
| `PUT` | `/api/v1/workspaces/{id}` | `更新工作区` |
| `GET` | `/api/v1/workspaces/{id}/access` | `获取当前用户在指定工作区的访问能力（路由守卫消费）` |
| `GET` | `/api/v1/workspaces/{id}/members` | `获取工作区成员列表` |
| `POST` | `/api/v1/workspaces/{id}/members` | `添加工作区成员` |
| `DELETE` | `/api/v1/workspaces/{id}/members/{targetUserId}` | `移除工作区成员` |
| `PUT` | `/api/v1/workspaces/{id}/members/{targetUserId}` | `更新成员角色` |

### 系统设置

| 方法 | 路径 | 用途 / handler |
|---|---|---|
| `GET` | `/api/v1/settings` | `获取系统设置` |
| `PUT` | `/api/v1/settings` | `保存系统设置` |
| `GET` | `/api/v1/settings/language` | `获取当前语言` |
| `PUT` | `/api/v1/settings/language` | `更新当前语言` |
| `PUT` | `/api/v1/settings/sidecar` | `更新多模态 sidecar 配置` |

### 首次初始化

| 方法 | 路径 | 用途 / handler |
|---|---|---|
| `POST` | `/api/v1/setup/init` | `Init` |
| `GET` | `/api/v1/setup/onboarding-status` | `Get Onboarding Status` |
| `GET` | `/api/v1/setup/status` | `Get Status` |

### 系统健康

| 方法 | 路径 | 用途 / handler |
|---|---|---|
| `GET` | `/api/v1/system/browser-health` | `Browser launch diagnostics` |
| `GET` | `/api/v1/system/health` | `System health check` |

### 仪表盘

| 方法 | 路径 | 用途 / handler |
|---|---|---|
| `GET` | `/api/v1/dashboard/cron-runs` | `获取最近执行记录（当前 workspace 关联的 CronJob）` |
| `GET` | `/api/v1/dashboard/cron-runs/{cronJobId}` | `获取 CronJob 执行历史` |
| `GET` | `/api/v1/dashboard/overview` | `获取概览统计` |
| `GET` | `/api/v1/dashboard/trend` | `获取日用量趋势` |

### Token 用量

| 方法 | 路径 | 用途 / handler |
|---|---|---|
| `GET` | `/api/v1/token-usage` | `获取 Token 使用统计` |

### 模型

| 方法 | 路径 | 用途 / handler |
|---|---|---|
| `GET` | `/api/v1/models` | `获取 Provider 列表（仅 enabled）` |
| `POST` | `/api/v1/models` | `创建模型` |
| `GET` | `/api/v1/models/active` | `获取当前激活模型` |
| `PUT` | `/api/v1/models/active` | `设置当前激活模型` |
| `GET` | `/api/v1/models/by-type` | `按类型筛选模型（chat / embedding），可选 modality 过滤` |
| `GET` | `/api/v1/models/catalog` | `获取 Provider 全量目录（含未启用），供 Add Provider 抽屉使用` |
| `DELETE` | `/api/v1/models/custom-providers` | `删除自定义 Provider（查询参数变体，兼容含特殊字符的旧 ID）` |
| `POST` | `/api/v1/models/custom-providers` | `创建自定义 Provider` |
| `DELETE` | `/api/v1/models/custom-providers/{providerId}` | `删除自定义 Provider` |
| `GET` | `/api/v1/models/default` | `获取默认模型` |
| `GET` | `/api/v1/models/embedding/default` | `获取系统默认 Embedding 模型 ID` |
| `POST` | `/api/v1/models/embedding/default` | `设置系统默认 Embedding 模型` |
| `POST` | `/api/v1/models/embedding/{modelId}/test` | `测试 Embedding 模型连通性（嵌入一个短文本验证 API key）` |
| `GET` | `/api/v1/models/enabled` | `获取启用模型列表` |
| `DELETE` | `/api/v1/models/{id}` | `删除模型` |
| `GET` | `/api/v1/models/{id}` | `获取模型详情` |
| `PUT` | `/api/v1/models/{id}` | `更新模型` |
| `POST` | `/api/v1/models/{id}/default` | `设置默认模型` |
| `PUT` | `/api/v1/models/{providerId}/config` | `更新 Provider 配置` |
| `POST` | `/api/v1/models/{providerId}/disable` | `禁用 Provider（如其下模型为当前默认会自动切换）` |
| `POST` | `/api/v1/models/{providerId}/discover` | `发现远端模型` |
| `POST` | `/api/v1/models/{providerId}/discover/apply` | `批量添加发现的模型` |
| `POST` | `/api/v1/models/{providerId}/enable` | `启用 Provider` |
| `DELETE` | `/api/v1/models/{providerId}/models` | `从 Provider 删除模型` |
| `POST` | `/api/v1/models/{providerId}/models` | `向 Provider 添加模型` |
| `POST` | `/api/v1/models/{providerId}/models/test` | `测试单个模型可用性` |
| `POST` | `/api/v1/models/{providerId}/test-connection` | `测试供应商连接` |

### OAuth

| 方法 | 路径 | 用途 / handler |
|---|---|---|
| `POST` | `/api/v1/oauth/anthropic/reload` | `Force re-detect credentials and refresh if near expiry` |
| `GET` | `/api/v1/oauth/anthropic/status` | `Read current Claude Code OAuth credential status from local disk` |
| `GET` | `/api/v1/oauth/openai/authorize` | `获取 OAuth 授权 URL（自动选 LOCAL / MANUAL_PASTE 模式）` |
| `POST` | `/api/v1/oauth/openai/callback-paste` | `MANUAL_PASTE 模式：用户粘贴浏览器回调 URL 完成 OAuth` |
| `POST` | `/api/v1/oauth/openai/device/cancel` | `Device flow: cancel a pending session` |
| `POST` | `/api/v1/oauth/openai/device/poll` | `Device flow: poll for completion` |
| `POST` | `/api/v1/oauth/openai/device/start` | `Device flow: start — request user_code` |
| `POST` | `/api/v1/oauth/openai/refresh` | `手动刷新 Token` |
| `DELETE` | `/api/v1/oauth/openai/revoke` | `清除 OAuth 凭证` |
| `GET` | `/api/v1/oauth/openai/status` | `获取 OAuth 连接状态` |

### LLM 运行时

| 方法 | 路径 | 用途 / handler |
|---|---|---|
| `GET` | `/api/v1/llm/provider-pool` | `查询所有 provider 的池状态 + 冷却信息` |
| `POST` | `/api/v1/llm/provider-pool/{providerId}/reprobe` | `手动重新探测某个 provider，立即更新池状态` |

### 工具

| 方法 | 路径 | 用途 / handler |
|---|---|---|
| `GET` | `/api/v1/tools` | `获取工具列表` |
| `POST` | `/api/v1/tools` | `创建工具（MCP）` |
| `GET` | `/api/v1/tools/available` | `获取员工可绑定的全部原子工具（含 MCP）` |
| `GET` | `/api/v1/tools/enabled` | `获取已启用工具列表` |
| `DELETE` | `/api/v1/tools/{id}` | `删除工具` |
| `GET` | `/api/v1/tools/{id}` | `获取工具详情` |
| `PUT` | `/api/v1/tools/{id}` | `更新工具` |
| `PUT` | `/api/v1/tools/{id}/disclosure-tier` | `设置工具披露分级（core / extension）` |
| `PUT` | `/api/v1/tools/{id}/toggle` | `启用/禁用工具` |

### MCP 服务

| 方法 | 路径 | 用途 / handler |
|---|---|---|
| `GET` | `/api/v1/mcp/servers` | `获取 MCP Server 列表` |
| `POST` | `/api/v1/mcp/servers` | `创建 MCP Server` |
| `POST` | `/api/v1/mcp/servers/refresh` | `刷新所有 MCP Server 连接` |
| `DELETE` | `/api/v1/mcp/servers/{id}` | `删除 MCP Server` |
| `GET` | `/api/v1/mcp/servers/{id}` | `获取 MCP Server 详情` |
| `PUT` | `/api/v1/mcp/servers/{id}` | `更新 MCP Server` |
| `PUT` | `/api/v1/mcp/servers/{id}/disclosure-tier` | `设置 MCP Server 披露分级（core / extension），整组工具跟随` |
| `POST` | `/api/v1/mcp/servers/{id}/test` | `测试 MCP Server 连接` |
| `PUT` | `/api/v1/mcp/servers/{id}/toggle` | `启用/禁用 MCP Server` |
| `GET` | `/api/v1/mcp/servers/{id}/tools` | `列出 MCP Server 已发现的工具` |

### ACP 端点

| 方法 | 路径 | 用途 / handler |
|---|---|---|
| `GET` | `/api/v1/acp/endpoints` | `List ACP endpoints` |
| `POST` | `/api/v1/acp/endpoints` | `Create a custom ACP endpoint` |
| `DELETE` | `/api/v1/acp/endpoints/{id}` | `Delete an ACP endpoint (builtins are protected)` |
| `GET` | `/api/v1/acp/endpoints/{id}` | `Get ACP endpoint by id` |
| `PUT` | `/api/v1/acp/endpoints/{id}` | `Update an ACP endpoint` |
| `POST` | `/api/v1/acp/endpoints/{id}/test` | `Test ACP endpoint connection (initialize handshake)` |
| `PUT` | `/api/v1/acp/endpoints/{id}/toggle` | `Enable / disable an ACP endpoint` |

### 技能

| 方法 | 路径 | 用途 / handler |
|---|---|---|
| `GET` | `/api/v1/skills` | `获取技能分页列表` |
| `POST` | `/api/v1/skills` | `创建技能` |
| `GET` | `/api/v1/skills/counts` | `获取各类型技能计数（tab 徽章用）` |
| `POST` | `/api/v1/skills/curator/activate` | `激活/取消激活 curator（真正归档 vs 仅预览）` |
| `POST` | `/api/v1/skills/curator/dry-run` | `立即运行一次 curator 预览（dry-run）` |
| `POST` | `/api/v1/skills/curator/pause` | `暂停 curator 定时扫描` |
| `GET` | `/api/v1/skills/curator/reports` | `列出最近的 curator 运行报告` |
| `GET` | `/api/v1/skills/curator/reports/{runId}` | `读取某次 curator 运行报告` |
| `POST` | `/api/v1/skills/curator/resume` | `恢复 curator 定时扫描` |
| `GET` | `/api/v1/skills/curator/status` | `curator 控制面状态` |
| `GET` | `/api/v1/skills/enabled` | `获取已启用技能列表` |
| `POST` | `/api/v1/skills/install/cancel/{taskId}` | `取消安装任务` |
| `GET` | `/api/v1/skills/install/hub/search` | `搜索 ClawHub 市场` |
| `POST` | `/api/v1/skills/install/start` | `开始异步安装 skill` |
| `GET` | `/api/v1/skills/install/status/{taskId}` | `查询安装任务状态` |
| `POST` | `/api/v1/skills/install/upload` | `上传 ZIP 安装 skill` |
| `DELETE` | `/api/v1/skills/install/{skillName}` | `卸载 skill` |
| `GET` | `/api/v1/skills/prompt-preview` | `预览技能 Prompt 增强效果（调试用，与 Agent 真实运行时一致）` |
| `GET` | `/api/v1/skills/runtime/active` | `获取 active skills 运行时视图` |
| `POST` | `/api/v1/skills/runtime/refresh` | `刷新 active skills 缓存，resync=true 时同步内置技能到 workspace` |
| `GET` | `/api/v1/skills/runtime/status` | `获取所有技能的运行时解析状态（管理页面使用）` |
| `GET` | `/api/v1/skills/summary` | `获取已启用技能摘要（按类型分组）` |
| `POST` | `/api/v1/skills/sync-files` | `Re-sync every skill's bundle files (admin)` |
| `POST` | `/api/v1/skills/synthesize-from-conversation` | `从对话历史合成 Skill` |
| `GET` | `/api/v1/skills/type/{skillType}` | `按类型获取技能列表` |
| `DELETE` | `/api/v1/skills/{id}` | `硬删除技能 (admin only — 物理删除 + 工作区清空)` |
| `GET` | `/api/v1/skills/{id}` | `获取技能详情` |
| `PUT` | `/api/v1/skills/{id}` | `更新技能` |
| `POST` | `/api/v1/skills/{id}/archive` | `手动归档技能` |
| `GET` | `/api/v1/skills/{id}/employees` | `列出能使用该技能的员工` |
| `POST` | `/api/v1/skills/{id}/export-workspace` | `将 skill 导出到工作区目录` |
| `GET` | `/api/v1/skills/{id}/lessons` | `读取该技能的 LESSONS.md` |
| `POST` | `/api/v1/skills/{id}/lessons/clear` | `清空该技能的所有 lessons` |
| `POST` | `/api/v1/skills/{id}/pin` | `钉住/取消钉住技能（钉住的技能不会被自动归档）` |
| `GET` | `/api/v1/skills/{id}/requirements` | `该技能的前置依赖检查状态` |
| `POST` | `/api/v1/skills/{id}/rescan` | `重新扫描单个技能` |
| `POST` | `/api/v1/skills/{id}/restore` | `恢复已归档的技能` |
| `POST` | `/api/v1/skills/{id}/sync-files` | `Re-sync this skill's bundle files from DB → local workspace cache` |
| `PUT` | `/api/v1/skills/{id}/toggle` | `启用/禁用技能` |
| `GET` | `/api/v1/skills/{id}/workspace` | `获取 skill 工作区信息` |
| `GET` | `/api/v1/skills/{skillId}/secrets` | `List secret keys + masked previews for a skill` |
| `POST` | `/api/v1/skills/{skillId}/secrets` | `Upsert a secret value (empty value deletes it)` |
| `DELETE` | `/api/v1/skills/{skillId}/secrets/{key}` | `Delete a single secret by key` |

### 技能模板

| 方法 | 路径 | 用途 / handler |
|---|---|---|
| `GET` | `/api/v1/skill-templates` | `获取技能模板列表` |
| `GET` | `/api/v1/skill-templates/{id}` | `Get a single skill template` |
| `POST` | `/api/v1/skill-templates/{id}/instantiate` | `Instantiate a template into a skill` |

### 插件

| 方法 | 路径 | 用途 / handler |
|---|---|---|
| `GET` | `/api/v1/plugins` | `List all plugins` |
| `GET` | `/api/v1/plugins/{name}` | `Get plugin detail` |
| `PUT` | `/api/v1/plugins/{name}/config` | `Update plugin configuration` |
| `POST` | `/api/v1/plugins/{name}/disable` | `Disable a plugin` |
| `POST` | `/api/v1/plugins/{name}/enable` | `Enable a plugin` |

### LLM Wiki

| 方法 | 路径 | 用途 / handler |
|---|---|---|
| `POST` | `/api/v1/wiki/admin/backfill-tokens` | `Force-run the token-count backfill batch now` |
| `POST` | `/api/v1/wiki/admin/kb/{kbId}/rebuild-overview` | `Ensure overview/log scaffold + rebuild overview stats now` |
| `GET` | `/api/v1/wiki/chunks/{chunkId}/pages` | `Pages By Chunk Id` |
| `DELETE` | `/api/v1/wiki/hot-cache/{kbId}` | `Soft-delete the hot cache row` |
| `GET` | `/api/v1/wiki/hot-cache/{kbId}` | `Get the current hot cache snapshot for a KB` |
| `POST` | `/api/v1/wiki/hot-cache/{kbId}/regenerate` | `Schedule a manual rebuild of the hot cache` |
| `GET` | `/api/v1/wiki/kb/{kbId}/jobs` | `Get Jobs` |
| `GET` | `/api/v1/wiki/kb/{kbId}/pages/{pageId}/citations` | `Page Citations` |
| `GET` | `/api/v1/wiki/kb/{kbId}/pages/{slugA}/relation/{slugB}` | `Explain Relation` |
| `POST` | `/api/v1/wiki/kb/{kbId}/pages/{slug}/enrich` | `Enrich Page` |
| `GET` | `/api/v1/wiki/kb/{kbId}/pages/{slug}/related` | `Related Pages` |
| `POST` | `/api/v1/wiki/kb/{kbId}/pages/{slug}/repair` | `Repair Page` |
| `POST` | `/api/v1/wiki/kb/{kbId}/search-preview` | `Search Preview` |
| `GET` | `/api/v1/wiki/kb/{kbId}/stats` | `Kb Stats` |
| `GET` | `/api/v1/wiki/knowledge-bases` | `获取所有知识库` |
| `POST` | `/api/v1/wiki/knowledge-bases` | `创建知识库` |
| `GET` | `/api/v1/wiki/knowledge-bases/agent/{agentId}` | `按 Agent 获取知识库` |
| `GET` | `/api/v1/wiki/knowledge-bases/bindable` | `列出可绑定到指定 Agent 的知识库` |
| `DELETE` | `/api/v1/wiki/knowledge-bases/{id}` | `删除知识库` |
| `GET` | `/api/v1/wiki/knowledge-bases/{id}` | `获取知识库详情` |
| `PUT` | `/api/v1/wiki/knowledge-bases/{id}` | `更新知识库` |
| `GET` | `/api/v1/wiki/knowledge-bases/{id}/config` | `获取知识库配置` |
| `PUT` | `/api/v1/wiki/knowledge-bases/{id}/config` | `更新知识库配置` |
| `GET` | `/api/v1/wiki/knowledge-bases/{id}/page-type-profile` | `获取知识库 pageType profile（未配置则返回内置默认）` |
| `PUT` | `/api/v1/wiki/knowledge-bases/{id}/page-type-profile` | `保存知识库 pageType profile` |
| `POST` | `/api/v1/wiki/knowledge-bases/{id}/page-type-profile/reset-default` | `重置 pageType profile 为内置默认` |
| `POST` | `/api/v1/wiki/knowledge-bases/{id}/page-type-profile/validate` | `校验 pageType profile JSON（不保存）` |
| `POST` | `/api/v1/wiki/knowledge-bases/{id}/scan` | `扫描关联目录导入文件` |
| `PUT` | `/api/v1/wiki/knowledge-bases/{id}/source-directory` | `设置知识库关联目录` |
| `GET` | `/api/v1/wiki/knowledge-bases/{id}/source-watcher` | `查看知识库源监听状态` |
| `POST` | `/api/v1/wiki/knowledge-bases/{id}/source-watcher/scan` | `手动触发一次源监听扫描` |
| `GET` | `/api/v1/wiki/knowledge-bases/{kbId}/agents/{agentId}/page-type-permissions` | `列出某 Agent 在知识库下的 pageType 权限规则` |
| `POST` | `/api/v1/wiki/knowledge-bases/{kbId}/agents/{agentId}/page-type-permissions` | `新增或更新 Agent 的 pageType 权限规则（按 agent+kb+pageType 去重）` |
| `DELETE` | `/api/v1/wiki/knowledge-bases/{kbId}/agents/{agentId}/page-type-permissions/{id}` | `删除一条 Agent pageType 权限规则` |
| `GET` | `/api/v1/wiki/knowledge-bases/{kbId}/lint/broken-links` | `读取最近一次死链扫描的聚合结果` |
| `POST` | `/api/v1/wiki/knowledge-bases/{kbId}/lint/broken-links` | `启动 Wiki 死链扫描 job（异步）` |
| `GET` | `/api/v1/wiki/knowledge-bases/{kbId}/lint/broken-links/jobs/{jobId}` | `查询 Wiki 死链扫描 job 状态` |
| `GET` | `/api/v1/wiki/knowledge-bases/{kbId}/pages` | `获取 Wiki 页面列表（可按原始材料过滤）` |
| `GET` | `/api/v1/wiki/knowledge-bases/{kbId}/pages/archived` | `列出知识库中所有 archived=1 的页面（不含 content）` |
| `DELETE` | `/api/v1/wiki/knowledge-bases/{kbId}/pages/batch` | `批量删除 Wiki 页面` |
| `GET` | `/api/v1/wiki/knowledge-bases/{kbId}/pages/refs` | `获取 Wiki 页面引用索引（slug/title/archived，供 wikilink 解析）` |
| `DELETE` | `/api/v1/wiki/knowledge-bases/{kbId}/pages/{slug}` | `删除 Wiki 页面` |
| `GET` | `/api/v1/wiki/knowledge-bases/{kbId}/pages/{slug}` | `获取 Wiki 页面内容` |
| `PUT` | `/api/v1/wiki/knowledge-bases/{kbId}/pages/{slug}` | `手动编辑 Wiki 页面` |
| `POST` | `/api/v1/wiki/knowledge-bases/{kbId}/pages/{slug}/archive` | `归档单个页面（软归档；可恢复）` |
| `GET` | `/api/v1/wiki/knowledge-bases/{kbId}/pages/{slug}/backlinks` | `获取反向链接` |
| `POST` | `/api/v1/wiki/knowledge-bases/{kbId}/pages/{slug}/rename` | `重命名 Wiki 页面，并级联更新所有引用方` |
| `POST` | `/api/v1/wiki/knowledge-bases/{kbId}/pages/{slug}/unarchive` | `取消归档` |
| `GET` | `/api/v1/wiki/knowledge-bases/{kbId}/pipeline-runs/{runId}` | `查询单次 run 的步骤明细` |
| `GET` | `/api/v1/wiki/knowledge-bases/{kbId}/pipelines` | `列出知识库的 pipeline 定义` |
| `POST` | `/api/v1/wiki/knowledge-bases/{kbId}/pipelines` | `保存(创建/更新)pipeline 定义(YAML/JSON)` |
| `POST` | `/api/v1/wiki/knowledge-bases/{kbId}/pipelines/validate` | `校验 pipeline 配置(不保存)` |
| `DELETE` | `/api/v1/wiki/knowledge-bases/{kbId}/pipelines/{id}` | `删除 pipeline 定义` |
| `GET` | `/api/v1/wiki/knowledge-bases/{kbId}/pipelines/{id}/runs` | `查询 pipeline 运行记录` |
| `POST` | `/api/v1/wiki/knowledge-bases/{kbId}/process` | `触发知识库处理（异步）；force=true 时清空所有 last_processed_hash 并重新入队全部材料` |
| `GET` | `/api/v1/wiki/knowledge-bases/{kbId}/processing-status` | `获取处理状态` |
| `GET` | `/api/v1/wiki/knowledge-bases/{kbId}/progress` | `订阅处理进度 SSE` |
| `GET` | `/api/v1/wiki/knowledge-bases/{kbId}/raw` | `获取原始材料列表（含每条材料生成的页面数）` |
| `POST` | `/api/v1/wiki/knowledge-bases/{kbId}/raw/text` | `添加文本材料` |
| `POST` | `/api/v1/wiki/knowledge-bases/{kbId}/raw/upload` | `上传文件材料` |
| `DELETE` | `/api/v1/wiki/knowledge-bases/{kbId}/raw/{rawId}` | `删除原始材料` |
| `POST` | `/api/v1/wiki/knowledge-bases/{kbId}/raw/{rawId}/cancel` | `请求取消正在进行的处理（仅在 processing 状态有效）` |
| `GET` | `/api/v1/wiki/knowledge-bases/{kbId}/raw/{rawId}/download` | `下载原始材料` |
| `POST` | `/api/v1/wiki/knowledge-bases/{kbId}/raw/{rawId}/reprocess` | `重新处理原始材料（force=true 时绕过 content_hash 短路）` |
| `GET` | `/api/v1/wiki/pages/lookup` | `跨 KB 按 title 或 slug 查找页面（chat 端 wikilink 跳转用）` |
| `GET` | `/api/v1/wiki/raw/{rawId}/pages` | `Pages By Raw Id` |
| `POST` | `/api/v1/wiki/research/start` | `启动 Deep Research，返回 SSE sessionId` |
| `GET` | `/api/v1/wiki/research/stream/{sessionId}` | `订阅 Deep Research SSE 事件流` |
| `GET` | `/api/v1/wiki/transformations` | `List transformations available to a KB` |
| `POST` | `/api/v1/wiki/transformations` | `Create` |
| `GET` | `/api/v1/wiki/transformations/runs` | `List Runs` |
| `DELETE` | `/api/v1/wiki/transformations/runs/{runId}` | `Delete Run` |
| `GET` | `/api/v1/wiki/transformations/runs/{runId}` | `Get Run` |
| `POST` | `/api/v1/wiki/transformations/runs/{runId}/cancel` | `Cancel a still-running transformation run` |
| `POST` | `/api/v1/wiki/transformations/runs/{runId}/save-as-page` | `Save a completed run's output as a synthesis wiki page` |
| `DELETE` | `/api/v1/wiki/transformations/{id}` | `Delete` |
| `GET` | `/api/v1/wiki/transformations/{id}` | `Get` |
| `PUT` | `/api/v1/wiki/transformations/{id}` | `Update` |
| `POST` | `/api/v1/wiki/transformations/{id}/aggregate` | `Aggregate all completed runs of a template into one KB-level synthesis page` |
| `POST` | `/api/v1/wiki/transformations/{id}/apply` | `Run a transformation against a raw material or wiki page` |

### 记忆

| 方法 | 路径 | 用途 / handler |
|---|---|---|
| `GET` | `/api/v1/memory/{agentId}/dream/events` | `Subscribe to dream events (SSE)` |
| `GET` | `/api/v1/memory/{agentId}/dream/morning-card` | `Get morning card for current user + agent` |
| `POST` | `/api/v1/memory/{agentId}/dream/morning-card/seen` | `Mark morning card as seen` |
| `GET` | `/api/v1/memory/{agentId}/dream/reports` | `List dream reports (paginated, newest first)` |
| `GET` | `/api/v1/memory/{agentId}/dream/reports/{reportId}` | `Get a single dream report by ID` |
| `POST` | `/api/v1/memory/{agentId}/dream/reports/{reportId}/entries/{key}/confirm` | `Confirm a memory entry (no-op acknowledgment)` |
| `POST` | `/api/v1/memory/{agentId}/dream/reports/{reportId}/entries/{key}/edit` | `Edit a memory entry — writes back to the target memory file with user-edited metadata` |
| `GET` | `/api/v1/memory/{agentId}/dreaming/candidates` | `查询召回候选列表（含评分详情）` |
| `GET` | `/api/v1/memory/{agentId}/dreaming/dreams` | `查询 DREAMS.md 整合日记` |
| `POST` | `/api/v1/memory/{agentId}/dreaming/focused` | `Focused Dream — 围绕指定主题触发记忆整合` |
| `GET` | `/api/v1/memory/{agentId}/dreaming/status` | `查询 Dreaming 状态（配置、统计、上次运行时间）` |
| `POST` | `/api/v1/memory/{agentId}/emergence` | `手动触发记忆整合（daily notes → MEMORY.md，NIGHTLY 模式）` |
| `GET` | `/api/v1/memory/{agentId}/facts` | `List facts for an agent` |
| `GET` | `/api/v1/memory/{agentId}/facts/contradictions` | `List unresolved contradictions` |
| `POST` | `/api/v1/memory/{agentId}/facts/contradictions/{contradictionId}/resolve` | `Resolve a contradiction (KEEP_A / KEEP_B / MERGE / IGNORE)` |
| `POST` | `/api/v1/memory/{agentId}/facts/{factId}/feedback` | `Submit feedback on a fact (HELPFUL/UNHELPFUL)` |
| `POST` | `/api/v1/memory/{agentId}/facts/{factId}/forget` | `Forget a fact — writes canonical metadata, rebuilds projection` |
| `POST` | `/api/v1/memory/{agentId}/summarize/{conversationId}` | `手动触发对话记忆提取` |

### 目标

| 方法 | 路径 | 用途 / handler |
|---|---|---|
| `GET` | `/api/v1/goals` | `List goals (optionally filtered by status)` |
| `POST` | `/api/v1/goals` | `Create a persistent goal for a conversation` |
| `GET` | `/api/v1/goals/by-conversation/{conversationId}` | `Get the active goal bound to a conversation (or null)` |
| `GET` | `/api/v1/goals/{id}` | `Get goal detail by id` |
| `PATCH` | `/api/v1/goals/{id}` | `Sparse update of a non-terminal goal` |
| `POST` | `/api/v1/goals/{id}/abandon` | `Abandon a goal (terminal)` |
| `POST` | `/api/v1/goals/{id}/criteria` | `Append a sub-criterion to an active goal` |
| `GET` | `/api/v1/goals/{id}/events` | `Get the event timeline for a goal` |
| `POST` | `/api/v1/goals/{id}/pause` | `Pause an active goal` |
| `POST` | `/api/v1/goals/{id}/resume` | `Resume a paused goal` |

### 定时任务

| 方法 | 路径 | 用途 / handler |
|---|---|---|
| `GET` | `/api/v1/cron-jobs` | `获取定时任务列表` |
| `POST` | `/api/v1/cron-jobs` | `创建定时任务` |
| `GET` | `/api/v1/cron-jobs/active-runs` | `查询会话下正在执行的定时任务运行` |
| `DELETE` | `/api/v1/cron-jobs/{id}` | `删除定时任务` |
| `GET` | `/api/v1/cron-jobs/{id}` | `获取定时任务详情` |
| `PUT` | `/api/v1/cron-jobs/{id}` | `更新定时任务` |
| `POST` | `/api/v1/cron-jobs/{id}/run` | `立即执行定时任务` |
| `PUT` | `/api/v1/cron-jobs/{id}/toggle` | `启用/禁用定时任务` |

### 触发器

| 方法 | 路径 | 用途 / handler |
|---|---|---|
| `GET` | `/api/v1/triggers` | `List triggers in the caller's workspace.` |
| `POST` | `/api/v1/triggers` | `Create a trigger; if enabled, registers it with the scheduler.` |
| `POST` | `/api/v1/triggers/events` | `Ingest one event envelope; returns per-trigger fire / drop summary.` |
| `DELETE` | `/api/v1/triggers/{id}` | `Delete a trigger and unregister its schedule.` |
| `GET` | `/api/v1/triggers/{id}` | `Get a trigger by id, scoped to the caller's workspace.` |
| `PUT` | `/api/v1/triggers/{id}` | `Update a trigger; pattern_version bumps when the cron expression changes.` |

### 工作流

| 方法 | 路径 | 用途 / handler |
|---|---|---|
| `GET` | `/api/v1/workflows` | `List workflows in the workspace` |
| `POST` | `/api/v1/workflows` | `Create a workflow row (draft starts empty).` |
| `POST` | `/api/v1/workflows/draft/generate` | `Generate a workflow draft from a natural-language description.` |
| `POST` | `/api/v1/workflows/draft/preview-compile` | `Compile arbitrary draft JSON without persisting — used by the template picker / generator preview to surface real ACL + schema diagnostics before a workflow row exists.` |
| `GET` | `/api/v1/workflows/draft/templates` | `List the canonical workflow templates the generator can apply directly.` |
| `GET` | `/api/v1/workflows/runs/paused` | `List paused runs across the workspace so operators can resume them.` |
| `GET` | `/api/v1/workflows/runs/{runId}` | `Inspect a single run with its step rows for replay / debugging.` |
| `POST` | `/api/v1/workflows/runs/{runId}/resume` | `Resume a paused workflow run with the given outcome.` |
| `DELETE` | `/api/v1/workflows/{id}` | `Soft-delete a workflow row.` |
| `GET` | `/api/v1/workflows/{id}` | `Get a workflow by id (includes inline draft + latest published graph).` |
| `PUT` | `/api/v1/workflows/{id}` | `Update workflow metadata (name / description / enabled).` |
| `POST` | `/api/v1/workflows/{id}/compile` | `Compile the draft and surface diagnostics without persisting a revision.` |
| `PUT` | `/api/v1/workflows/{id}/draft` | `Save the inline draft graph_json without compiling.` |
| `POST` | `/api/v1/workflows/{id}/publish` | `Compile the draft and persist a new revision pointed at by latest_revision_id.` |
| `GET` | `/api/v1/workflows/{id}/runs` | `List the most recent runs for a workflow.` |

### 渠道

| 方法 | 路径 | 用途 / handler |
|---|---|---|
| `GET` | `/api/v1/channels` | `获取渠道列表` |
| `POST` | `/api/v1/channels` | `创建渠道` |
| `GET` | `/api/v1/channels/health` | `批量获取所有渠道健康状态` |
| `POST` | `/api/v1/channels/preflight` | `Pre-flight: validate draft channel config without persisting` |
| `POST` | `/api/v1/channels/qrcode/{channelType}/begin` | `启动指定渠道的扫码授权流程` |
| `GET` | `/api/v1/channels/qrcode/{channelType}/status` | `查询指定渠道的扫码授权状态` |
| `GET` | `/api/v1/channels/status` | `获取渠道运行状态（全局系统视图，仅管理员可见）` |
| `GET` | `/api/v1/channels/type/{channelType}` | `按类型获取渠道列表` |
| `GET` | `/api/v1/channels/webchat/config` | `获取 WebChat 配置` |
| `POST` | `/api/v1/channels/webchat/stream` | `WebChat SSE 流式对话` |
| `POST` | `/api/v1/channels/webhook/dingtalk` | `钉钉消息回调` |
| `POST` | `/api/v1/channels/webhook/dingtalk/register/begin` | `启动钉钉扫码注册应用流程` |
| `GET` | `/api/v1/channels/webhook/dingtalk/register/status` | `查询钉钉扫码注册状态` |
| `POST` | `/api/v1/channels/webhook/discord` | `Discord 消息回调（已废弃：Discord 已切换为 Gateway WebSocket 模式）` |
| `POST` | `/api/v1/channels/webhook/feishu` | `飞书消息回调` |
| `POST` | `/api/v1/channels/webhook/feishu/register/begin` | `启动飞书扫码注册应用流程` |
| `GET` | `/api/v1/channels/webhook/feishu/register/status` | `查询飞书扫码注册状态` |
| `POST` | `/api/v1/channels/webhook/slack` | `Slack Events API 回调` |
| `GET` | `/api/v1/channels/webhook/status` | `获取渠道运行状态` |
| `POST` | `/api/v1/channels/webhook/telegram` | `Telegram 消息回调` |
| `POST` | `/api/v1/channels/webhook/wecom` | `企业微信消息回调（智能机器人模式不使用，保留兼容）` |
| `GET` | `/api/v1/channels/webhook/weixin/qrcode` | `获取微信登录二维码` |
| `GET` | `/api/v1/channels/webhook/weixin/qrcode/status` | `查询微信二维码扫码状态` |
| `DELETE` | `/api/v1/channels/{id}` | `删除渠道` |
| `GET` | `/api/v1/channels/{id}` | `获取渠道详情` |
| `PUT` | `/api/v1/channels/{id}` | `更新渠道` |
| `GET` | `/api/v1/channels/{id}/health` | `获取指定渠道的实时健康状态（真连接状态，前端绿点应该绑这个）` |
| `PUT` | `/api/v1/channels/{id}/toggle` | `启用/禁用渠道` |

### 数据源

| 方法 | 路径 | 用途 / handler |
|---|---|---|
| `GET` | `/api/v1/datasources` | `获取数据源列表` |
| `POST` | `/api/v1/datasources` | `创建数据源` |
| `DELETE` | `/api/v1/datasources/{id}` | `删除数据源` |
| `GET` | `/api/v1/datasources/{id}` | `获取数据源详情` |
| `PUT` | `/api/v1/datasources/{id}` | `更新数据源` |
| `POST` | `/api/v1/datasources/{id}/test` | `测试数据源连接` |
| `PUT` | `/api/v1/datasources/{id}/toggle` | `启用/禁用数据源` |

### 语音转文本

| 方法 | 路径 | 用途 / handler |
|---|---|---|
| `POST` | `/api/v1/stt/transcribe` | `Transcribe` |

### 文本转语音

| 方法 | 路径 | 用途 / handler |
|---|---|---|
| `POST` | `/api/v1/tts/synthesize` | `Synthesize` |
| `GET` | `/api/v1/tts/voices` | `List Voices` |

### 生成文件

| 方法 | 路径 | 用途 / handler |
|---|---|---|
| `GET` | `/api/v1/files/generated/{id}` | `Download a tool-generated file by its one-time id` |

### 计划

| 方法 | 路径 | 用途 / handler |
|---|---|---|
| `GET` | `/api/v1/plans` | `获取 Agent 的计划列表` |
| `GET` | `/api/v1/plans/{id}` | `获取计划详情（含步骤）` |

### Feature Flags

| 方法 | 路径 | 用途 / handler |
|---|---|---|
| `GET` | `/api/v1/feature-flags` | `List` |
| `PUT` | `/api/v1/feature-flags/{flagKey}` | `Update` |
