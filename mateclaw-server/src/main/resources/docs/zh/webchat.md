# Web / API 接入(WebChat)指南

MateClaw 的 WebChat 渠道让外部网站通过纯 HTTP / SSE 接入对话能力,无需 JWT。访客身份通过 `visitorId + visitorToken`(HMAC 签发)在共享的 API Key 之下做隔离。

## 基础

- **Base URL**:`https://<你的 MateClaw 部署地址>/api/v1/channels/webchat`
- **认证**:所有端点都要求请求头 `X-MC-Key: <API Key>`(从渠道编辑页拿)。
- **会话管理端点**额外要求 `X-MC-Visitor-Token: <HMAC>`(首次 `/stream` 调用时由服务端签发并回传)。
- **响应包装**:`R<T>` → `{"code": 200, "msg": "...", "data": T}`,非 200 视为错误。
- **字符集**:UTF-8。SSE 流使用 `text/event-stream; charset=UTF-8`。

## 端点清单

| 方法 | 路径 | 鉴权 | 用途 |
|---|---|---|---|
| POST | `/stream` | API Key | SSE 流式对话(签发 visitorToken) |
| GET | `/config` | API Key | 拿渠道配置(title/placeholder/...) |
| POST | `/sessions` | API Key | 显式创建空会话线程 |
| GET | `/sessions` | + visitorToken | 列出会话(默认排除 archived) |
| GET | `/sessions/page` | + visitorToken | 分页 + 关键词搜索 |
| PUT | `/sessions/title` | + visitorToken | 重命名 |
| PUT | `/sessions/pinned` | + visitorToken | 置顶 / 取消 |
| PUT | `/sessions/archive` | + visitorToken | 归档 / 取消 |
| DELETE | `/sessions` | + visitorToken | 删除 |
| POST | `/sessions/stop` | + visitorToken | 停止进行中的流 |
| POST | `/sessions/regenerate` | + visitorToken | 重新生成最后一条助手回复 |
| GET | `/sessions/messages` | + visitorToken | 消息列表(支持分页) |
| POST | `/upload` | + visitorToken | 上传附件(拿 fileId) |
| GET | `/files` | + visitorToken | 下载文件(上传的或 Agent 生成的) |

管理员级(需要 MateClaw JWT,不在本表的 permitAll 范围内):

| 方法 | 路径 | 用途 |
|---|---|---|
| POST | `/api/v1/admin/webchat/revoked-visitor` | 撤销某 visitor 的管理 token |
| DELETE | `/api/v1/admin/webchat/revoked-visitor` | 取消撤销 |

## 认证流程

```text
┌──────────┐  POST /stream {visitorId:"v1", message:"你好"}
│  客户端   │ ─────────────────────────────────────────────► ┌──────────┐
└──────────┘                                                  │ MateClaw │
   ▲                                                          └──────────┘
   │  SSE meta event: {sessionId, conversationId, visitorToken}
   │  SSE content_delta events: {text}
   │  SSE done event
   └─────────────────────────────────────────────────────────
                                                              │
┌──────────┐  GET /sessions X-MC-Visitor-Token: <visitorToken>│
│  客户端   │ ─────────────────────────────────────────────► │
└──────────┘ ◄──── 200 {code:200, data:[...]}                │
```

`visitorToken` 默认 7 天有效;过期后通过任意 `/stream` 调用重新签发。

## 错误码

| HTTP | 何时 |
|---|---|
| 400 | 参数不合法(visitorId / sessionId 字符集,title 长度等) |
| 401 | API Key 无效 / visitorToken 缺失、过期、被撤销 |
| 404 | 指定 sessionId 不存在或不属于该 visitor |
| 409 | 未活跃空会话数超过 5 条上限 |

错误消息在 `R.msg` 字段,可直接展示给用户。

## SSE 事件协议

`/stream` 与 `/sessions/regenerate` 返回 `text/event-stream`:

```
event: meta
data: {"sessionId":"s1","conversationId":"webchat:abc123:v1:s1","visitorToken":"xxx.yyy"}

event: content_delta
data: {"text":"你"}

event: content_delta
data: {"text":"好"}

event: thinking_delta
data: {"text":"..."}    (可选,推理过程)

event: done
data: {"status":"completed"}

event: error
data: {"message":"..."}  (出错时)
```

## 文件上传 / 下载

1. `POST /upload`(multipart):返回 `{fileId, fileName, contentType, size}`。
2. 在下一次 `/stream` 的请求体里把 fileId 加到 `attachmentIds` 数组。
3. Agent 下载时直接读服务端文件;消息里的 `fileUrl` 是相对下载路径(`/api/v1/channels/webchat/files?storedName=...`),客户端拼接鉴权头就能下载。
4. Agent 生成的文件(PDF/DOCX/...)在助手回复里以 `/api/v1/files/generated/<uuid>` URL 形式出现,**无鉴权下载**,7 天 TTL。

## visitorToken 撤销(管理员)

某个 visitor 滥用?管理员调:

```bash
curl -X POST https://mate.example.com/api/v1/admin/webchat/revoked-visitor \
  -H "Authorization: Bearer <管理员 JWT>" \
  -H "Content-Type: application/json" \
  -d '{"channelId":123, "visitorId":"v1", "reason":"abuse"}'
```

撤销后该 visitor 的所有管理端点调用返回 401(`/stream` 不受影响,可重新签发新 token)。

## curl 示例

**第一步:发首条消息**

```bash
curl -N -X POST https://mate.example.com/api/v1/channels/webchat/stream \
  -H "X-MC-Key: your-api-key" \
  -H "Content-Type: application/json" \
  -d '{"visitorId":"v1","message":"你好"}'
```

保存 meta 事件里的 `visitorToken` 和 `sessionId`。

**第二步:列会话**

```bash
curl https://mate.example.com/api/v1/channels/webchat/sessions?visitorId=v1 \
  -H "X-MC-Key: your-api-key" \
  -H "X-MC-Visitor-Token: <上一步拿到的>"
```

**第三步:上传附件并发送**

```bash
# 上传
curl -X POST https://mate.example.com/api/v1/channels/webchat/upload \
  -H "X-MC-Key: your-api-key" \
  -H "X-MC-Visitor-Token: <token>" \
  -F "visitorId=v1" \
  -F "file=@report.pdf"
# 返回 {"fileId":"abc-uuid", ...}

# 带附件发消息
curl -N -X POST https://mate.example.com/api/v1/channels/webchat/stream \
  -H "X-MC-Key: your-api-key" \
  -H "Content-Type: application/json" \
  -d '{"visitorId":"v1","sessionId":"<sid>","message":"看看这份报告","attachmentIds":["abc-uuid"]}'
```

## 限制

- 单 visitor 未活跃空会话 ≤ 5(超 5 拒绝创建,需先发消息或删除旧会话)
- 上传:单文件 ≤ 配置上限,扩展名 + MIME 双白名单;每会话 ≤ 50 文件 / 200 MB(可配)
- visitorToken 7 天过期;Agent 生成文件 URL 7 天 TTL
- 当前是单实例部署(staging registry + streamTracker 都在内存)。多实例支持在路线图上。

## 关联

- 上游 epic issue:https://github.com/matevip/mateclaw/issues/355
- 完整 PR 链:#341 / #343 / #346 / #347 / #348 / #349 / #352 / #354 / #356 / #357 / #358 / #359 / #360 / #361
