# Web / API 接入(WebChat)指南

MateClaw 的 WebChat 渠道让外部网站通过纯 HTTP / SSE 接入对话能力,无需 JWT。访客身份通过 `visitorId + visitorToken`(HMAC 签发)在共享的 API Key 之下做隔离。

接入有两条路径:

- **嵌入式小部件** —— 引入一个 JS 文件、调一次 `init(...)`,右下角即出现聊天气泡。最快上线,适合官网 / 落地页客服。
- **自定义 HTTP / SSE 集成** —— 直接调用下面的 REST + SSE 端点,自己渲染 UI。适合需要深度定制交互的场景。

## 嵌入式小部件(mateclaw-webchat)

小部件是一个零依赖的浏览器库,产物同时提供 UMD(`<script>` 标签)和 ESM(npm)两种格式。

**方式一:script 标签(UMD)**

```html
<script src="https://<你的部署地址>/mateclaw-webchat.umd.js"></script>
<script>
  MateClawWebChat.init({
    apiKey: 'your-channel-api-key',   // 从渠道编辑页拿
    server: 'https://<你的部署地址>',  // MateClaw 服务地址
    title: '在线客服',
    placeholder: '输入消息...'
  })
</script>
```

**方式二:npm(ESM)**

```bash
npm install @mateclaw/webchat
```

```ts
import { init } from '@mateclaw/webchat'

init({ apiKey: 'your-channel-api-key', server: 'https://<你的部署地址>' })
```

**配置项**

| 字段 | 必填 | 默认 | 说明 |
|---|---|---|---|
| `apiKey` | 是 | — | 渠道 API Key |
| `server` | 是 | — | MateClaw 服务地址(不带尾斜杠) |
| `position` | 否 | `bottom-right` | 气泡位置:`bottom-right` / `bottom-left` |
| `primaryColor` | 否 | `#D97757` | 主色(任意 CSS 颜色) |
| `title` | 否 | `MateClaw` | 面板标题 |
| `placeholder` | 否 | `Type a message...` | 输入框占位符 |

**行为说明**

- 访客 ID 首次打开时在 `localStorage`(键 `mc-webchat-visitor`)生成并复用,无需自行管理。
- 面板样式全部走 CSS 变量(`--mc-primary` / `--mc-bg-elevated` / ...),宿主页可在 `:root` 覆盖做主题定制。
- 小部件内部消费本指南下半部分描述的 `/stream` SSE 协议;若要更复杂的交互(会话列表、附件、撤销等),直接走下面的 HTTP 端点自建。

## 自定义集成:基础

- **Base URL**:`https://<你的 MateClaw 部署地址>/api/v1/channels/webchat`
- **认证**:所有端点都要求请求头 `X-MC-Key: <API Key>`(从渠道编辑页拿)。
- **会话管理端点**额外要求 `X-MC-Visitor-Token: <HMAC>`(首次 `/stream` 调用时由服务端签发并回传)。
- **响应包装**:`R<T>` → `{"code": 200, "msg": "...", "data": T}`,非 200 视为错误。
- **字符集**:UTF-8。SSE 流使用 `text/event-stream; charset=UTF-8`。

## 端点清单

| 方法 | 路径 | 鉴权 | 用途 |
|---|---|---|---|
| POST | `/stream` | API Key | SSE 流式对话(签发 visitorToken);请求体可选传 `agentId` 覆盖渠道绑定的 agent(必须与渠道同 workspace) |
| GET | `/config` | API Key | 拿渠道配置(title/placeholder/...) |
| GET | `/skills` | + visitorToken | 列出该 agent 绑定的可见技能(供下游自建 slash picker UI) |
| GET | `/wiki/pages` | + visitorToken | 列出该 agent 可见的 wiki 页面(供下游自建 `[[slug]]` 引用 picker UI) |
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

> 管理控制台里的「会话」列表默认对普通管理员隐藏 WebChat 访客会话,仅全局管理员可见 —— 这是跨工作区隔离与访客隐私的防护。

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

`visitorToken` 默认 7 天有效;过期后通过任意 `/stream` 调用重新签发。每次 `/stream`(即便旧 token 仍有效)都会在 meta 事件里回传一个新 token,客户端应持续更新本地存储,保持常新。

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

event: phase
data: {"phase":"planning","timestamp":1716700000000}

event: tool_start
data: {"tool":"web_search"}

event: tool_end
data: {"tool":"web_search","success":true}

event: plan
data: {"steps":["search the web","summarize"]}

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

> SSE 规范要求客户端忽略未知事件类型。服务端可能发出以下划线开头的内部事件(如 `_usage_final`),这些不对访客承诺、可安全忽略。

### 可选的实时进度事件

`phase` / `tool_start` / `tool_end` / `plan` 是**可选**事件 —— 用于在
SDK 里展示"AI 正在打字..."气泡、工具执行徽章("正在搜索...")、Plan-Execute
步骤清单。SDK 可以全部忽略,只看 `content_delta` 也能完整渲染回复。

| 事件 | 触发时机 | 数据字段 |
|---|---|---|
| `phase` | agent 进入新的执行阶段(planning / generating / summarizing / ...) | `phase`, `timestamp` |
| `tool_start` | agent 调用工具 | `tool`(工具名) |
| `tool_end` | 工具调用完成 | `tool`, `success` |
| `plan` | Plan-Execute agent 拆解出步骤 | `steps`(字符串数组) |

**注意**:`tool_start` / `tool_end` **只携带工具名**,不携带调用参数或返回
结果 —— agent 工具调用可能涉及 PII(文件路径、用户查询、凭据),转发给
第三方网站前端会有数据泄露风险。SDK 应基于工具名做本地化 label 映射
(`web_search` → "正在搜索...")。

## 文件上传 / 下载

1. `POST /upload`(multipart):返回 `{fileId, fileName, contentType, size}`。
2. 在下一次 `/stream` 的请求体里把 fileId 加到 `attachmentIds` 数组。未知 / 过期 / 不属于该访客的 fileId 会被静默丢弃(仅发送文本部分,不报错)。
3. Agent 下载时直接读服务端文件;消息里的 `fileUrl` 是相对下载路径(`/api/v1/channels/webchat/files?storedName=...`),客户端拼接鉴权头就能下载。
4. Agent 生成的文件(PDF/DOCX/...)在助手回复里以 `/api/v1/files/generated/<uuid>` URL 形式出现,**无鉴权下载**,7 天 TTL。

## 会话生命周期:置顶 / 归档 / 删除

- **置顶**(`PUT /sessions/pinned`):在 `/sessions` 列表里排序优先。
- **归档**(`PUT /sessions/archive`):软关闭 —— 线程留在库里(历史可查、可按 sessionId 寻址、文件可下载),但默认从 `/sessions` 列表隐藏(传 `includeArchived=true` 才返回),且不再占用"未活跃空会话 ≤ 5"的配额。
- **删除**(`DELETE /sessions`):永久删除,不可恢复。

`/sessions` 返回的每个会话含:`sessionId`、`title`、`lastActiveTime`、`messageCount`、`pinned`、`archived`、`streamStatus`(`running` / `idle`)。

## visitorToken 撤销(管理员)

某个 visitor 滥用?管理员调:

```bash
curl -X POST https://mate.example.com/api/v1/admin/webchat/revoked-visitor \
  -H "Authorization: Bearer <管理员 JWT>" \
  -H "Content-Type: application/json" \
  -d '{"channelId":123, "visitorId":"v1", "reason":"abuse"}'
```

撤销后该 visitor 的所有管理端点调用返回 401(`/stream` 不受影响,可重新签发新 token)。撤销状态带短时缓存,多实例下最长约 10 分钟生效。取消撤销用 `DELETE` 同一端点。

## 技能调用(slash picker)

主控台前端在输入框键入 `/` 会弹出技能选择菜单。这是**纯前端 affordance**——选中后输入框被改写成指令文本:

- 中文:`使用「<技能名>」技能:<用户消息>`
- 英文:`Use the "<skill-name>" skill: <用户消息>`

指令文本作为普通 user message 发到 `/stream`,LLM 收到后调用 `load_skill` 元工具(详见 [skills.md](./skills.md#slash-菜单))。后端**不做 `/` 解析**,webchat 走的是和主控台完全一样的 agent runtime,所以这条路径对 webchat 调用方**开箱即用**。

下游集成方要自建 picker UI,先用新端点拿清单:

```bash
curl https://mate.example.com/api/v1/channels/webchat/skills?visitorId=v1 \
  -H "X-MC-Key: your-api-key" \
  -H "X-MC-Visitor-Token: <token>"
# 返回 [{"id":..., "name":"news-summary", "nameZh":"新闻摘要", "description":"...", "icon":"..."}]
```

可选 `agentId` 参数(默认回落到渠道绑定的 agent);只返回该 agent **显式绑定且 enabled** 的技能,按 slug 字母序排序。返回字段是展示级元数据,**不包含** SKILL.md 正文、configJson、安全扫描结果——这些只走管理控制台。

选中后构造消息:

```bash
curl -N -X POST https://mate.example.com/api/v1/channels/webchat/stream \
  -H "X-MC-Key: your-api-key" \
  -H "Content-Type: application/json" \
  -d '{"visitorId":"v1","message":"使用「新闻摘要」技能:总结今天最重要的 3 条 AI 新闻"}'
```

> 注意:指令文本依赖 LLM "听话"调用 `load_skill`。复杂任务下偶发漂移,生产环境建议把目标技能**绑定**到 agent(`agentId` 对应的)并在 `AGENTS.md` 里强化系统提示。

## Wiki 知识库引用(`[[slug]]` picker)

跟技能调用一样,wiki 知识库也可以通过 picker 显式指代——用 **Obsidian / Wikipedia 风格的 `[[slug]]` 链接语法**。用户在输入框敲 `[[` 触发 picker,选中后插入 `[[<slug>]]` token,发送时改写为指令文本,LLM 收到后调 `wiki_read_page(slug=...)` 读取该页面再做答。后端**不做任何 `[[` 解析**,webchat 走的是和主控台完全一样的 agent runtime。

指令文本格式(**精确**):

- 中文:`参考知识库页面 [[<slug>]]:<用户消息>`
- 英文:`Reference the wiki page [[<slug>]]: <user message>`

多引用天然支持(并列写即可):

```
参考知识库页面 [[auth-design]]、[[webchat-integration]]:这两套怎么协同?
```

下游集成方要自建 picker UI,先用端点拿页面清单:

```bash
curl "https://mate.example.com/api/v1/channels/webchat/wiki/pages?visitorId=v1" \
  -H "X-MC-Key: your-api-key" \
  -H "X-MC-Visitor-Token: <token>"
# 返回 [{"kbId":1,"kbName":"MateClaw 文档","slug":"webchat-integration",
#        "title":"WebChat 接入指南","summary":"...","pageType":"source"}, ...]
```

可选 query 参数:

| 参数 | 必填 | 说明 |
|---|---|---|
| `visitorId` | 是 | 访客 ID |
| `agentId` | 否 | 显式指定 agent,缺省回落到渠道绑定;必须与渠道同 workspace |
| `keyword` | 否 | 关键词过滤,匹配 `slug` 或 `title`(LIKE) |

行为约束:

- **可见范围**:agent 显式绑定的 KB(`mate_agent_wiki_kb` 表);无绑定时回落到该 workspace 全部 KB(跟 wiki 工具的默认行为一致)
- **页面过滤**:排除 `pageType=synthesis`(LLM 中间产物,对终端访客无意义)
- **100 页上限**:超出时(且未传 `keyword`)返回 `422`,要求传 `keyword` 收窄
- **返回字段**:仅 `kbId / kbName / slug / title / summary / pageType`;**不包含**正文、embedding、sourceRawIds、outgoingLinks(这些只走管理控制台)
- **排序**:按 `slug` 字母序

选中后构造消息(中文 directive 示例):

```bash
curl -N -X POST https://mate.example.com/api/v1/channels/webchat/stream \
  -H "X-MC-Key: your-api-key" \
  -H "Content-Type: application/json" \
  -d '{"visitorId":"v1","message":"参考知识库页面 [[webchat-integration]]:总结这套接入流程"}'
```

> 注意:`[[slug]]` 是给 LLM 看的约定提示(`wiki_read_page` 的 `@Tool` description 里写明了),但 LLM 仍可能漂移——复杂任务下建议同时在 `AGENTS.md` 里强化提示,或把目标 KB **绑定**到专用 agent 收窄检索空间。

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
