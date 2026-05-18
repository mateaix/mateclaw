# 多渠道接入

**同一个大脑，同一份记忆，跟着你的团队走到哪里就到哪里。**

MateClaw 里的一个渠道是通往同一个 Agent 的另一扇门。团队在飞书里沟通？把 Agent 放进飞书。有人喜欢用 Telegram？同一个 Agent，同一份记忆，出现在 Telegram。Web 控制台给运维，钉钉给现场，Slack 给工程——**一次部署，九扇门**。

每一个渠道是一个适配器。适配器底下，Agent **不知道（也不在乎）**消息是从哪扇门进来的。

::: tip 1.3.0 渠道层增强
v1.3.0 在渠道层做了一批长跑稳定性 + 群协作的工作：

- **Reply queue + 生命周期 gate**：把"渠道连接 ready"和"消息可派发"拆开，重连窗口里收到的事件不会丢失
- **WS / 长轮询渠道走 leader lease**：多实例部署里只有抢到锁的实例发回应，**不再有同一条消息被两台机器各回一遍**
- **群聊按 sender id 单独归属 + 时间窗去抖**：群里两个人同时讲话，不再把对话搞混
- **粘贴长消息自适应去抖窗口**：一段被切成 5 条的长粘贴自动合并成一条
- **企业微信审批卡片 + keepalive + chunk dedup**：长任务卡片不会被对端会话超时杀掉
- **异步工具结果回流原渠道**：员工跑长任务，跑完后结果回到发起的渠道（飞书 / 钉钉 / 企微 / Slack），文件按渠道分别上传
- **去除编造的生成文件 URL**：员工不再返回"https://example.com/file.docx"这种虚假链接

企业微信的细节调优全部在 [企业微信深度优化](./wecom-tuning) 里。
:::

---

## 九个渠道

| 渠道 | 传输 | 流式 | 说明 |
|------|------|------|------|
| **Web** | SSE | ✅ | 内置，零配置 |
| **钉钉** | Stream（WebSocket）/ Webhook | ✅ AI Card | Stream 不需要公网 IP |
| **飞书** | WebSocket / Webhook | — | WebSocket 不需要公网 IP |
| **企业微信** | 长连接 / Webhook | — | 长连接优先 |
| **微信** | HTTP 长轮询（iLink） | — | 实验性 / beta |
| **Telegram** | Long-Polling / Webhook | 打字指示器 | Long-Polling 不需要公网 IP |
| **Discord** | Gateway WebSocket（JDA） | 打字指示器 | 自动重连 |
| **QQ** | WebSocket / 回调 | — | 官方机器人平台 |
| **Slack** | Events API / Socket mode | — | Socket mode 不需要公网 IP |

---

## 一个渠道实际上怎么工作

```
┌──────┐ ┌──────┐ ┌──────┐ ┌──────┐ ┌──────┐ ┌───────┐ ┌────┐ ┌──────┐ ┌──────┐
│ Web  │ │ 钉钉 │ │ 飞书 │ │企业微│ │ TG   │ │Discord│ │ QQ │ │ 微信 │ │Slack │
└──┬───┘ └──┬───┘ └──┬───┘ └──┬───┘ └──┬───┘ └──┬────┘ └─┬──┘ └──┬───┘ └──┬───┘
   │        │        │        │        │        │        │        │       │
   └────────┴────────┴────┬───┴────────┴────────┴────────┴────────┴───────┘
                          │
                 ┌────────┴─────────┐
                 │   渠道适配层      │  ← 把多种协议归一
                 │  （+ 健康监控）   │
                 └────────┬─────────┘
                          │
                 ┌────────┴─────────┐
                 │     Agent 引擎    │
                 └──────────────────┘
```

每个渠道实现一个统一的适配器形态——把平台特有的事件翻译成 Agent 能消化的消息。**自己加一个新渠道**的开发细节看 [架构说明](./architecture)。

### 渠道健康监控

每一个活跃的 IM 渠道适配器都被一个健康监控盯着。当一个适配器连不上或者长连接断了，监控会启动**指数退避重连**（2s → 4s → 8s → …最大封顶 30s）。瞬时的网络抖动自己就恢复了；持续性的失败会在管理控制台的健康视图里暴露出来。

这就是为什么 MateClaw 的渠道不会在一次抖动之后永远静默：**它们自己会回来。**

---

## 渠道配置基础

渠道在管理 UI 的**渠道管理**里管。底下对应 `mate_channel` 表：

| 列 | 是什么 |
|----|--------|
| `name` | 显示名 |
| `type` | 渠道类型（`dingtalk`、`feishu`、`telegram` 等） |
| `agent_id` | 处理这个渠道消息的 Agent |
| `config` | JSON 对象，渠道特有的凭证 |
| `enabled` | 开关 |

所有凭证在数据库里加密存储。一个 Agent 可以有多个渠道；不同渠道可以绑不同 Agent。

---

## Web 渠道（SSE）

内置。没有外部配置，没有凭证。用 Server-Sent Events 做实时流式。

```
GET /api/v1/chat/{agentId}/stream
```

事件格式在 [聊天与消息](./chat) 里。

---

## 钉钉

两种连接模式（Stream / Webhook）+ 两种消息格式（markdown / card）。

### 一键扫码绑定（推荐，v1.1.0+）

**不需要登录开放平台，不需要"添加机器人能力"，不需要"创建版本"。打开渠道编辑面板，扫码，搞定。**

1. `渠道 → 新建 → 类型选钉钉`
2. 表单里点**扫码绑定钉钉应用**——展开一张钉钉蓝的 QR 码
3. 用钉钉 App 扫码并**确认授权**
4. 回到表单，**Client ID 和 Client Secret 已自动回填**

会话 7 分钟内有效，过期自动失效重新生成。剩下的（连接模式、Agent、消息格式）按你的需求填。

::: tip 它在做什么
背后走钉钉的 OAuth Device Flow——MateClaw 在 `oapi.dingtalk.com` 申请一个设备码，编成 QR；你确认后，凭证从轮询接口落地到表单。**整个过程没有 webhook，没有公网 IP 要求。**
:::

### 手动配置应用（备选）

如果扫码在你的网络下走不通，或者你需要更细的应用控制权，仍然可以走开放平台流程：

1. 打开 [钉钉开放平台](https://open-dev.dingtalk.com/)，**应用开发 > 企业内部应用 > 创建应用**
   ![创建应用](/images/channels/dingtalk/01-create-app.png)

2. **应用能力 > 添加能力** → 添加**机器人**能力
   ![添加机器人](/images/channels/dingtalk/02-add-bot.png)

3. 消息接收模式选 **Stream 模式**，发布
   ![机器人配置](/images/channels/dingtalk/03-bot-config.png) ![Stream 模式](/images/channels/dingtalk/04-stream-publish.png)

4. **应用发布 > 版本管理** → 新建版本并保存
   ![创建版本](/images/channels/dingtalk/05-create-version.png)

5. **基本信息 > 凭证** → 拿 **Client ID**（AppKey）和 **Client Secret**（AppSecret）
   ![凭证](/images/channels/dingtalk/06-credentials.png)

### 在 MateClaw 里配置

```bash
curl -X POST http://localhost:18088/api/v1/channels \
  -H "Content-Type: application/json" \
  -H "Authorization: Bearer <token>" \
  -d '{
    "name": "钉钉机器人",
    "type": "dingtalk",
    "agentId": 1,
    "config": {
      "connection_mode": "stream",
      "client_id": "your-client-id",
      "client_secret": "your-client-secret",
      "message_type": "markdown"
    },
    "enabled": true
  }'
```

::: tip
Stream 模式用官方 SDK 建立 WebSocket 长连接。**不需要公网 IP。** AI Card 流式要把 `message_type` 设为 `card` 并填 template ID。
:::

### 找到机器人

在钉钉里搜机器人名字，**应用**里找到并开始聊天。

![搜索机器人](/images/channels/dingtalk/07-search-bot.png) ![找到机器人](/images/channels/dingtalk/08-find-bot.png) ![聊天](/images/channels/dingtalk/09-chat.png)

Webhook URL（webhook 模式）：`https://your-domain/api/v1/channels/webhook/dingtalk`

---

## 飞书

WebSocket 长连接或 Webhook。WebSocket 更好——**不需要公网 IP**。

### 一键扫码绑定（推荐，v1.1.0+）

**之前你需要"去开放平台 → 创建企业自建应用 → 复制 App ID 和 Secret"。现在你点一下按钮，扫码，凭证就到了。**

1. `渠道 → 新建 → 类型选飞书`
2. 表单里点**扫码绑定飞书应用**——展开一张 QR 码
3. 用**飞书 App** 扫码并**确认授权**（如需 Lark 国际版，把 domain 切到 lark）
4. 回到表单，**App ID 和 App Secret 已自动回填**

剩下的字段（Agent、verification token / encrypt key 如需 webhook 模式）按你的需求填。**会话 5 分钟内有效。**

::: tip 它在做什么
背后调的是飞书 SDK 2.6+ 自带的 `scene/registration` Device Flow。`com.larksuite.oapi:oapi-sdk` 升级到 2.6.1 之后，整个"创建应用 → 复制凭证"动作被打成一次扫码授权。
:::

### 手动配置应用（备选）

如果扫码在你的网络下走不通，或者你需要 webhook 模式 / 自定义权限范围：

1. 打开 [飞书开放平台](https://open.feishu.cn/app)，创建企业自建应用
   ![创建应用](/images/channels/feishu/01-create-app.png) ![应用信息](/images/channels/feishu/02-build.png)

2. **凭证与基础信息** → 拿 **App ID** 和 **App Secret**
   ![凭证](/images/channels/feishu/03-credentials.png)

3. **能力** → 启用**机器人**
   ![启用机器人](/images/channels/feishu/04-enable-bot.png)

4. **权限** → 批量导入：
   ![权限](/images/channels/feishu/05-permissions.png)

   ```json
   {
     "scopes": {
       "tenant": [
         "im:chat", "im:message", "im:message.group_msg",
         "im:message.p2p_msg:readonly", "im:resource",
         "contact:user.base:readonly"
       ]
     }
   }
   ```

5. **事件与回调** → 选 **WebSocket 长连接模式**
   ![WebSocket 配置](/images/channels/feishu/06-websocket.png)

6. 订阅 **接收消息 v2.0**
   ![订阅事件](/images/channels/feishu/07-subscribe-event.png)

7. **应用发布** → 创建版本并发布
   ![创建版本](/images/channels/feishu/08-create-version.png)

### 配置

```bash
curl -X POST http://localhost:18088/api/v1/channels \
  -H "Content-Type: application/json" \
  -H "Authorization: Bearer <token>" \
  -d '{
    "name": "飞书机器人",
    "type": "feishu",
    "agentId": 1,
    "config": {
      "appId": "cli_your_app_id",
      "appSecret": "your-app-secret",
      "verificationToken": "your-verification-token",
      "encryptKey": "your-encrypt-key"
    },
    "enabled": true
  }'
```

![添加到收藏](/images/channels/feishu/09-add-favorite.png) ![聊天](/images/channels/feishu/10-chat.png)

Webhook URL：`https://your-domain/api/v1/channels/webhook/feishu`

---

## 企业微信

1. [企业微信](https://work.weixin.qq.com)——注册或登录
   ![创建企业](/images/channels/wecom/01-create-enterprise.png) ![注册](/images/channels/wecom/02-register.png)

2. 工作台 → **智能机器人 > 创建机器人** → **API 模式 > 长连接**
   ![创建机器人](/images/channels/wecom/03-create-bot.png) ![API 模式](/images/channels/wecom/04-api-mode.png) ![长连接](/images/channels/wecom/05-long-connection.png)

3. 拿 **Bot ID** 和 **Secret**
   ![凭证](/images/channels/wecom/06-credentials.png)

```bash
curl -X POST http://localhost:18088/api/v1/channels \
  -H "Content-Type: application/json" \
  -H "Authorization: Bearer <token>" \
  -d '{
    "name": "企业微信机器人",
    "type": "wecom",
    "agentId": 1,
    "config": {
      "corpId": "your-corp-id",
      "wecomAgentId": "1000002",
      "secret": "your-secret",
      "token": "your-token",
      "encodingAesKey": "your-encoding-aes-key"
    },
    "enabled": true
  }'
```

![开始聊天](/images/channels/wecom/07-chat.png)

Webhook URL：`https://your-domain/api/v1/channels/webhook/wecom`

::: tip 想把企业微信跑稳？
群聊多用户协作、引用消息、appmsg 解析、上传约束、aibot_respond_msg 路由、自循环检测、TLS 重试、平台级权限锁……所有非显然的优化点和踩坑，都在 [企业微信深度优化](./wecom-tuning) 单独整理了。
:::

---

## Telegram

Long-Polling（默认）或 Webhook。Long-Polling **不需要公网 IP**。

### 创建机器人

1. 在 Telegram 搜 **@BotFather**（认准蓝色官方认证标）
2. 发 `/newbot`，跟着提示走
   ![创建机器人](/images/channels/telegram/01-botfather.jpg)
3. 复制 **Bot Token**
   ![拿 Token](/images/channels/telegram/02-token.jpg)

### 配置

```bash
curl -X POST http://localhost:18088/api/v1/channels \
  -H "Content-Type: application/json" \
  -H "Authorization: Bearer <token>" \
  -d '{
    "name": "Telegram 机器人",
    "type": "telegram",
    "agentId": 1,
    "config": {
      "bot_token": "123456:ABC-DEF1234ghIkl-zyx57W2v1u123ew11",
      "show_typing": true,
      "polling_timeout": 20
    },
    "enabled": true
  }'
```

::: tip
- Long-Polling 内置指数退避重连（2s → 30s）
- 打字指示器每 4 秒刷新一次
- 国内用户大概率需要配 `http_proxy`
:::

---

## Discord

基于 **JDA**——通过 Gateway WebSocket 连接，自带自动重连。

### 创建机器人

1. [Discord 开发者门户](https://discord.com/developers/applications)
   ![开发者门户](/images/channels/discord/01-developer-portal.png)

2. 创建 Application
   ![创建应用](/images/channels/discord/02-create-app.png)

3. **Bot** → 创建 Bot，复制 **Token**
   ![Bot Token](/images/channels/discord/03-bot-token.png)

4. 启用 **Message Content Intent**，授予 **Send Messages** + **Attach Files**
   ![权限](/images/channels/discord/04-permissions.png)

5. **OAuth2 > URL Generator** → 选 `bot` scope → 邀请到你的 server
   ![OAuth2](/images/channels/discord/05-oauth2.png) ![邀请](/images/channels/discord/06-invite.png)

### 配置

```bash
curl -X POST http://localhost:18088/api/v1/channels \
  -H "Content-Type: application/json" \
  -H "Authorization: Bearer <token>" \
  -d '{
    "name": "Discord 机器人",
    "type": "discord",
    "agentId": 1,
    "config": {
      "bot_token": "your-bot-token",
      "accept_bot_messages": false
    },
    "enabled": true
  }'
```

::: tip
- 不需要 Webhook URL 或 Interactions Endpoint——Gateway WebSocket 包办一切
- 长回复（超过 2000 字）自动拆分，代码块完整性会被保护
- 消息去重（LRU cache 500 条）防止重连时重复处理
:::

---

## QQ

1. [QQ 开放平台](https://q.qq.com/) → 创建机器人应用
   ![QQ 开放平台](/images/channels/qq/01-open-platform.png) ![创建机器人](/images/channels/qq/02-create-bot.png)

2. **回调配置** → 启用 **C2C 消息事件** 和 **群 AT 消息事件**
   ![事件配置](/images/channels/qq/03-c2c-event.png)

3. **开发管理** → 拿 **AppID** 和 **AppSecret**
   ![凭证](/images/channels/qq/04-credentials.png)

```bash
curl -X POST http://localhost:18088/api/v1/channels \
  -H "Content-Type: application/json" \
  -H "Authorization: Bearer <token>" \
  -d '{
    "name": "QQ 机器人",
    "type": "qq",
    "agentId": 1,
    "config": {
      "appId": "your-app-id",
      "appSecret": "your-app-secret"
    },
    "enabled": true
  }'
```

---

## Slack

Events API（webhook 模式）或 Socket Mode（不需要公网 IP）。

### 创建 app

1. 打开 [Slack API — Your Apps](https://api.slack.com/apps)，创建一个新 app
2. **OAuth & Permissions** → bot scopes：`chat:write`、`app_mentions:read`、`im:history`、`im:read`、`im:write`、`files:write`
3. 安装到你的 workspace
4. 复制 **Bot User OAuth Token**（`xoxb-...`）
5. Socket Mode：**Socket Mode** 里启用，生成一个带 `connections:write` 的 **App-Level Token**（`xapp-...`）
6. 订阅 bot 事件：`app_mention`、`message.im`

### 配置

```bash
curl -X POST http://localhost:18088/api/v1/channels \
  -H "Content-Type: application/json" \
  -H "Authorization: Bearer <token>" \
  -d '{
    "name": "Slack 机器人",
    "type": "slack",
    "agentId": 1,
    "config": {
      "bot_token": "xoxb-...",
      "app_token": "xapp-...",
      "mode": "socket"
    },
    "enabled": true
  }'
```

Webhook URL（webhook 模式）：`https://your-domain/api/v1/channels/webhook/slack`

---

## 微信（iLink）

::: warning
微信个人号机器人（iLink 协议）是 beta 状态，需要申请后才能使用。
:::

- **登录**——首次使用扫码授权，token 自动持久化（跨重启）
- **接收**——HTTP 长轮询
- **发送**——通过 `sendmessage` API 回复（文本 + 语音）

在渠道管理里添加一个微信渠道，点**获取登录二维码**，手机扫。

### 它扛得住什么

个人微信长连接是过去最脆弱的渠道。我们重建了它：

- **看门狗**——不会有静默停止重连的 poller
- **抖动指数退避**——token 过期和网络抖动时自动恢复，不再一崩就死
- **按账号维度陈旧检测**——精确判断哪个账号的连接过期了
- **语音识别三路回退**——覆盖微信加密 CDN 的多种方式

```bash
curl -X POST http://localhost:18088/api/v1/channels \
  -H "Content-Type: application/json" \
  -H "Authorization: Bearer <token>" \
  -d '{
    "name": "微信",
    "type": "weixin",
    "agentId": 1,
    "config": {"botToken": "your-bot-token"},
    "enabled": true
  }'
```

---

## 渠道管理 API

```bash
# 列表
curl http://localhost:18088/api/v1/channels \
  -H "Authorization: Bearer <token>"

# 开关
curl -X PUT "http://localhost:18088/api/v1/channels/1/toggle?enabled=true" \
  -H "Authorization: Bearer <token>"

# 删除
curl -X DELETE http://localhost:18088/api/v1/channels/1 \
  -H "Authorization: Bearer <token>"

# 健康状态（所有渠道）
curl http://localhost:18088/api/v1/channels/health \
  -H "Authorization: Bearer <token>"
```

---

## 会话来源追踪

每一个渠道对话都会记录来源。在会话管理视图和聊天控制台里，每个会话都会显示对应的渠道图标。IM 渠道的会话归属于 `system` 用户。

---

## 全渠道语音支持

IM 渠道（企业微信、微信、钉钉）都支持语音输入。语音识别走 DashScope 或 OpenAI Whisper，微信加密 CDN 的语音有三路回退。语音回复走文字转语音合成后以音频消息发回。

---

## 值得知道的事

- **Webhook 模式需要 HTTPS。** 生产部署应该用 Nginx + SSL 挡在 MateClaw 前面。
- **长连接模式不需要公网 IP。** Telegram Long-Polling、钉钉 Stream、飞书 WebSocket、Discord Gateway、Slack Socket mode、企业微信长连接——全都可以跑在 NAT 后面。
- **一个渠道一个 Agent。** 不同渠道可以指向不同 Agent。
- **凭证在 `mate_channel` 里加密存储。**
- **国内网络**大概率需要配 `http_proxy` 来访问 Telegram 和 Discord。

---

## 下一步

- [聊天与消息](./chat)——消息流、segment、流式事件
- [Agent 引擎](./agents)——实际在每个渠道里回答的是什么
- [配置说明](./config)——全局渠道调优
