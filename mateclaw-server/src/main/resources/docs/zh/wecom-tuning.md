# 企业微信深度优化

**让一个真正能被企业内部群里几十号人用起来的 bot，远不止"接通就行"。**

[多渠道接入 → 企业微信](./channels#企业微信) 那一节是把 bot 跑起来；这一篇是把 bot **跑稳**——所有 MateClaw 在企业微信适配层做过的非显然优化、踩过的平台边角，以及为什么这么处理。

阅读对象：

- 已经把企业微信渠道连通、想理解"为什么我的群聊体验是这样"的运维 / 一线
- 想加新功能但需要先知道平台限制的开发者
- 想把 bot 推给真实业务团队前做技术评估的负责人

---

## 平台一句话总结

**企业微信 AI Bot 是个"看起来像聊天 SDK，本质是个事件回调"的平台。**

它给你三种能力：

1. **接收事件** —— 用户在群里 @ bot，平台通过长连接（WebSocket）或 webhook 把消息推过来
2. **回复**（同一会话内）—— 用 `aibot_respond_msg` 把答案"贴"到对应的 frame 上
3. **主动推**（不限于回复）—— 用 `aibot_send_msg` 但**仅限单聊**

**最关键的隐藏规则**：第 2、3 条在群聊里是不一样的，单聊里也不一样。下面的所有优化都围绕这个矩阵展开。

---

## 群聊多用户协作

### 平台默认行为

群聊里 A、B、C 三个人都在 @ bot，平台会把每个人的消息当一条独立 frame 推过来，但都打到**同一个 chatId** 上。

如果你直接按 chatId 分会话（这是最自然的做法），后果是：

- 持久化的对话历史里全是 `user: ...` 没有发送人前缀，模型读历史看到的是一锅粥
- 防抖窗口（500ms / 2.5s 自适应）会把 A 和 B 的连发消息合并成一条
- A 问"我想查 X"，B 接着问"我想查 Y"，bot 看到的是"用户问了 X 和 Y 两个不相关的事"

### MateClaw 的处理

**两层修复**：

**1. 防抖按 sender 切边界。** 同一会话内连续两条消息进来时，先看 senderId：

- 同一个人 → 合并（典型场景：粘贴长文被 IM 客户端切片）
- 不同人 → 立即 flush 已有 pending，给新发送人开新窗口

代码层面是 [`ChannelMessageRouter.isSameSender`](https://github.com/anthropics/mateclaw/blob/main/mateclaw-server/src/main/java/vip/mate/channel/ChannelMessageRouter.java)。null 防御：任一 senderId 缺失都不合并，宁可多 flush 一次也不要错串归属。

**2. 持久化 + Prompt 都带 `[@sender]` 前缀。** 群聊（`chatId != null`）的每条 user 消息在落库时和送给 LLM 之前都会被 `applyGroupTag(message, content)` 包一层：

```
[@XuZhanFu] @迈特云的机器人 我想查 X
[@xuzf] @迈特云的机器人 我想查 Y
```

这样：

- 第 30 条历史消息也能让模型知道是谁说的
- 持久化的对话时间线读起来像 `[@A] ...; [@B] ...; [@A] ...`，模型能正确处理跟问、引用回复、互相纠错
- 单聊（`chatId == null`）零开销，行为不变

senderName 优先于 senderId（友好），都没有时返回 null（避免 `[@null]` 这种垃圾标签）。

### 你能观察到什么

```
[wecom] Sender boundary in conversation wecom:{chatId}: flushing pending from sender=A, accepting new sender=B
```

DB 里 `mate_message.content` 列直接看 `[@xxx]` 前缀。

---

## 上传约束矩阵

企业微信平台对 bot 上传的媒体有**硬性大小限制**，超限的请求在 **chunk-finish 阶段**被拒（已经传完所有字节才报错），用户体验是"传了三分钟然后什么都没发出来"。

### 限制

| 类型 | 大小上限 | 格式要求 |
|------|---------|---------|
| 文件 | **20 MB** | 任意 |
| 图片 | **10 MB** | 任意常见格式 |
| 视频 | **10 MB** | 任意常见格式 |
| 语音 | **2 MB** | **必须 AMR**（其他格式平台拒收） |
| 全局 | **20 MB** | 兜底硬上限 |

### MateClaw 的处理

**客户端预检**，避免无效上传。`applyWeComUploadLimits(fileSize, mediaType, contentType)` 在上传前判定结果：

- 文件 > 20 MB → 拒绝，告诉用户"超过 20MB 上限"
- 图片 > 10 MB → 降级为文件上传（用户在群里能看到附件，只是不再是缩略图）
- 视频 > 10 MB → 降级为文件上传
- 语音 > 2 MB **或** mime 不是 `audio/amr` → 降级为文件上传
- 文件 + 任何类型 > 20 MB → 直接拒绝（绝对硬上限）

降级时附带一段说明文字（"图片超过 10MB，已转为文件附件发送"），用户立刻知道发生了什么，不会以为 bot 抽风。

### 智能识别没有 filename 的文件

WeCom 群里转发的文件经常**没有 filename 字段**。落地存成 `file.bin` 的话，下游所有按扩展名 dispatch 的工具（PDF 阅读、DOCX 解析等）会全部失效。

修复：通过 magic-byte 嗅探还原扩展名：

- `%PDF` → `.pdf`
- `PK\x03\x04` 是 ZIP 容器；进一步 peek 内部条目区分 `.docx` / `.xlsx` / `.pptx` / `.odt` / `.epub` / `.jar`
- 其他常见格式（PNG / JPEG / MP4 / MP3 / WAV）都能正确识别
- 实在认不出 → 保留 `.bin`，至少不假装是其他格式

实现在 `WeComChannelAdapter.sniffMagic()` + `refineZipKind()`。

---

## 引用消息（quote）

WeCom 用户引用前一条消息（图片、文件、文本、语音、小程序）然后追加问题，是**最常见的群聊交互模式**。

### 支持的引用类型

| 引用类型 | bot 看到的 | 是否能进一步处理 |
|----------|------------|------------------|
| 引用文本 | `[引用消息: 之前的文本内容]\n用户的新问题` | ✅ 文本一并送给模型 |
| 引用语音 | `[引用消息: [语音] ASR 转文字]\n用户的新问题` | ✅ 语音 ASR 结果作为上下文 |
| 引用图片 | `[引用消息: [图片]]\n用户的新问题` + 图片 attached part | ✅ 视觉模型 sidecar 看图 |
| 引用文件 | `[引用消息: [文件: report.pdf]]\n用户的新问题` + 文件 attached part | ✅ 文件 tool 可读 |
| 引用混合 | 各子类按上面规则展开 | ✅ |

### 实现要点

- **媒体一并下载**：引用的图片 / 文件不只是个标记字符串，会真的下载、AES-256-CBC 解密、落到 `data/chat-uploads/{conversationId}/...`，然后作为 MessageContentPart 给 agent
- **路径一致**：媒体落盘的 conversationId **必须**等于 `mate_conversation` 表里的 conversationId，否则下游 `/api/v1/chat/files/{convId}/{name}` 会因 `isConversationOwner` 查不到行直接 403，前端 `<img>` 显示图裂

历史 bug：早期版本 `inboundConversationId()` 给群聊路径加了 `wecom:group:` 中缀，但 router 持久化时是 `wecom:{chatId}` 没中缀，两边一对不上整批群聊引用图片全部图裂。已修。

---

## appmsg 消息类型

`msgtype=appmsg` 是 WeCom 给富媒体卡片留的扩展点，常见四种子变体：

| 变体 | 实际是什么 | bot 怎么处理 |
|------|-----------|--------------|
| `appmsg.file` | 转发的文件（PDF / Word / Excel） | 走完整下载 pipeline，等同 `msgtype=file` |
| `appmsg.image` | 图片卡片 | 走完整下载 pipeline，等同 `msgtype=image` |
| `appmsg.url` | **公众号文章 / 外链** | 见下一节 |
| `appmsg.miniprogram` | 小程序 | 把 title 暴露给模型，附件无法获取 |

未知子类型 fallback 成 `[appmsg: title]` 标记，至少模型知道"用户分享了某种富媒体"。

### 公众号文章

mp.weixin.qq.com 的文章页是**带 captcha-gated SSR 的**，任何 LLM 工具都抓不到正文。如果 bot 假装能读，模型会**凭标题瞎编内容**（生产里观察到："本文讲了三个要点……" 完全是幻觉）。

MateClaw 在 link 分支检测到 `mp.weixin.qq.com` 后，会自动给模型追加一段提示：

> （提示：该链接为公众号文章，正文需要用户在微信内打开后复制粘贴，请优先请用户粘贴正文，不要凭标题猜测内容。）

效果：模型不再编造，主动让用户粘贴正文。其他正常网址（github、维基、随便一个外链）**不**触发提示，因为它们的 body 是普通工具能 fetch 的。

---

## 群聊主动推送（aibot_send_msg vs aibot_respond_msg）

### 平台规则

```
单聊：aibot_send_msg ✓     aibot_respond_msg ✓
群聊：aibot_send_msg ✗     aibot_respond_msg ✓ (必须绑定一个 inbound frame 的 reqId)
```

群聊里 bot 任何主动消息（cron 推送、异步任务回推、图像生成完成）都必须**搭一辆顺风车**——绑到一个之前用户 inbound 的 frameReqId 上，否则平台拒收。

### MateClaw 的处理

**LRU 缓存最近 inbound reqId**。`lastChatReqIds: ConcurrentHashMap<chatId, latest-reqId>` 在每条群聊 inbound 进来时被更新，上限 1000 个 chat。

**统一出口 `sendOutboundFrame(chatId, body)`**：

- 缓存命中 → `aibot_respond_msg` + 缓存的 reqId
- 缓存未命中 → 降级 `aibot_send_msg`（单聊或新 chat）

这样：

- cron 定时摘要 → 群聊有人说过话 → 走 respond 推送成功；从来没说过话 → 降级 send_msg 失败，但至少不会一刀切都失败
- 异步任务（图像 / 音乐 / 视频生成）完成后 → `AsyncTaskMediaDispatcher` 调用统一出口
- 同一条 LLM 回复跨多个 chunk → 同一个 reqId 复用

### 你能观察到什么

```
[wecom] Group send via aibot_respond_msg: chatId=..., reqId=...
```

---

## 异步任务回推

图像生成 (`image_generate`) / 音乐生成 (`music_generate`) / 视频生成 (`video_generate`) / 3D 模型生成 (`model3d_generate`) 都是**异步任务**——agent 拿到 task id 立刻返回，真正的产物 30 秒～几分钟后才出来。

历史问题：产物只出现在 Web 控制台的会话历史里，**WeCom 群里看不到**。

修复：`AsyncTaskMediaDispatcher.forwardToImIfBound(conversationId, parts)`——

- 任务完成后，从 `ChannelSessionStore` 反查 conversationId 绑的渠道
- 跳过 `web` / `webchat`（SSE 已经覆盖）
- 调对应渠道适配器的 `sendContentParts(targetId, parts)`
- WeCom：image / audio / video / file 全部支持，走原生附件
- Slack：通过 `filesUploadV2` 直传（参考 [Slack channel](./channels#slack)）
- 不支持 `sendContentParts` 的渠道（QQ 等）：catch UnsupportedOperationException + log，不让一个不支持的渠道卡住整批分发

文件路径在 `data/chat-uploads/{conversationId}/`，serve URL 是 `/api/v1/chat/files/{conversationId}/{storedName}`，前端 / 渠道附件视图都按这个 URL 读。

---

## 模型行为：假装调用工具

观察：**qwen3.6-plus** 在长上下文 + 工具调用密集的场景下偶发地"懒"——它会用 Markdown 代码块**伪装**自己调了工具，但实际 `toolCallCount=0`：

````
🎵 《在熟悉的路口》 重新创作任务已提交！
⏳ 生成约需 1-2 分钟，完成后音频会自动推送到对话中...

```json
{ "prompt": "...", "lyrics": "..." }
```
````

后端没拿到 tool_call → 永远不会真的发起音乐生成 → 用户永远收不到歌。

**目前的应对**：换更稳定执行 tool_calls 的模型（kimi-for-coding、claude-sonnet-4.5、deepseek-r1）。在 [模型配置](./models) 里把 agent 的默认模型改掉即可。

未来可能加：服务端检测"任务已提交 + toolCallCount=0"模式 → 自动注入纠正提示重试一次。

---

## 模型行为：自循环输出

另一种偶发故障：模型陷入"思考-输出"自循环，重复同一段中文回答几十次直到耗尽 max_tokens（16384）。生产上观察到的模式：

```
"Wait, I should X." → 写中文答案 → "Done." → 写同一份中文答案 → "Wait, Y." → 同一份答案 → ...
```

用户全程看 "生成中..." 等几十秒到几分钟，最后收到一坨重复文本。

### MateClaw 的处理

**两层守卫**：

1. **检测**：[`hasRepeatingSuffix`](https://github.com/anthropics/mateclaw/blob/main/mateclaw-server/src/main/java/vip/mate/agent/graph/NodeStreamingChatHelper.java) 探测 buffer 尾部是否被同一个 24~240 字符的 unit 连续重复 4 次以上 → 立即 dispose 上游订阅
2. **去重 + 标记**：`dedupTrailingRepeats` 把已累积的 buffer 尾部 N 份拷贝缩成 1 份；ReasoningNode 把 finishReason 设为 `INCOMPLETE`，前端展示截断卡 + "重新生成"按钮

为什么不无脑发警告就算了：用户已经在 SSE 流里看到那坨重复文本（SSE 单向 push 没法 unsend），但 **DB 持久化** 和 **WeCom 回推** 用的都是 `finalAnswer`——所以 IM 群里只看到一份干净的回答 + INCOMPLETE 提示。

阈值选得**特别窄**（4 次 verbatim 连续）就是为了不误伤合法的"TL;DR / body / TL;DR 三段式"输出。

---

## 网络层稳定性

### TLS / Socket 瞬时错误重试

DashScope / OpenAI / 各家 LLM 网关在公网传输中偶发产生：

- `bad_record_mac`（TLS RFC 5246 §7.2.2 fatal alert 20）
- `SSLHandshakeException`
- `SocketException: Connection reset by peer`
- `Premature close` / `Broken pipe`

之前这些一旦发生直接给 agent 抛 `LLM 调用失败` 红字，没有重试。

修复：把这些都归类成 `SERVER_ERROR`，走现有的指数退避重试链：3s → 6s → 12s（带 jitter）最多 5 次。详见 [agents 引擎](./agents#错误恢复)。

### keepalive

群聊回复用 `aibot_respond_msg` 时，平台对**单条流**有 60 秒 TTL——超过 60 秒不发新数据，平台会丢弃这个 stream slot，后续真正的 reply 静默失败。

agent 处理复杂任务（多次工具调用 + LLM 推理）经常超过 60 秒。`WeComKeepaliveScheduler` 每 30 秒往 stream 上发一个 noop "正在处理..." 心跳，slot 永不过期。180 秒兜底强制 finish，避免任务真挂了 keepalive 一直续命。

### 重连 + 指数退避

WeCom 长连接断开时（NAT 超时、网络抖动），适配器自动重连：2s → 4s → 8s → 16s → 30s 封顶。**永远不会放弃**——只要进程还活着，下次能连上就立刻恢复消息接收。

控制台健康视图能看到当前重连次数，运维心里有数。

---

## 平台级约束（不是 bug，是限制）

这些是**企业微信平台本身**的约束，没法在代码层绕过，只能配置层规避：

### 数据权限锁

API 模式 bot 在企业微信管理后台勾选**任何一项数据使用权限**（如"读取消息"、"获取群信息"），bot 会**自动锁定为仅创建者可用**。其他成员发消息 bot 不响应。

**解决**：在管理后台**取消勾选**全部 7 项数据权限，bot 即可对所有授权成员可见。MateClaw 通过 webhook 拿消息，不需要这些数据权限。

### 可见范围 + 数据权限二维矩阵

| 可见范围 | 数据权限 | 实际效果 |
|---------|---------|---------|
| 全员 | 全部勾选 | **仅创建者**可用（数据权限锁覆盖可见范围） |
| 全员 | 全部取消 | 全员可用（推荐） |
| 指定部门 | 全部取消 | 指定部门成员可用 |
| 指定人员 | 全部取消 | 指定人列表内可用 |

### 群聊里 @bot 才会触发

WeCom 群的 bot 必须被 `@` 才收到消息。私聊不需要 `@`。这是平台行为，没办法绕过。MateClaw 不会在群里 broadcast 监听所有消息（也做不到）。

---

## 调试技巧

### 看群聊归属是否生效

```sql
SELECT content FROM mate_message
WHERE conversation_id = 'wecom:{chatId}' AND role = 'user'
ORDER BY id DESC LIMIT 5;
```

期望：每条 user 消息都以 `[@username]` 开头。

### 看媒体落盘路径

```bash
ls data/chat-uploads/wecom:{chatId}/
```

**不应该**有 `wecom:group:{chatId}` 这种带 `group:` 中缀的目录（早期 bug 残留可以手动清理）。

### 看群聊回推路径

后端日志里：

```
[wecom] Group send via aibot_respond_msg: chatId=..., reqId=...
```

如果群聊里 bot 没回复，但日志里看到这行 + reqId 不为空，说明回推到了平台但平台拒收（一般是 reqId 已被消费过、或 bot 已被踢出群）。

### 看 keepalive 状态

```bash
grep "wecom-keepalive" logs/mateclaw.log | tail
```

期望看到周期性的 "Heartbeat sent" + "Heartbeat ACK received" / 偶尔的 "force-finished stream" 强制完成。

---

## 已知 corner case

| 场景 | 当前行为 | 后续可能 |
|------|---------|---------|
| 群里第一条消息就是 cron 推送（chat 还没人说过话） | 缓存里没有 reqId，降级 `aibot_send_msg` 被平台拒 | 加 ring buffer 缓存多条历史 reqId（仅修复有限场景，不上） |
| 模型在长会话里"懒"得调工具 | 用户重发 / 换模型 | 加服务端检测注入纠正提示 |
| 同一群同时来 3 条不同 sender 的消息 | 串行处理，每个用户独立窗口（生效） | — |
| 公众号文章用户拒绝粘贴正文 | bot 礼貌引导用户复制 | — |
| OOXML 文档 magic-byte 误判（极小概率） | 退回到 `.zip` | 已通过 ZIP 内部条目 peek 解决 90% 场景 |

---

## 一图概括

```
                  ┌─────────────────────┐
                  │  企业微信群里的用户   │
                  └──────────┬──────────┘
                             │ inbound (含 chatId)
                             ▼
       ┌────────────────────────────────────────┐
       │  WeComChannelAdapter                    │
       │  ├─ chunk upload pre-check (4 类限制)    │
       │  ├─ magic-byte sniff (OOXML peek)       │
       │  ├─ AES 解密 + 落 chat-uploads/{convId}/ │
       │  ├─ quote 引用解析（5 子类型）            │
       │  ├─ appmsg 解析（4 子类型 + 公众号提示）   │
       │  └─ 缓存 lastChatReqIds[chatId]         │
       └──────────────┬─────────────────────────┘
                      │ ChannelMessage(content="[@xxx] ...")
                      ▼
       ┌────────────────────────────────────────┐
       │  ChannelMessageRouter                   │
       │  ├─ 自适应 debounce (500ms / 2.5s)      │
       │  ├─ sender boundary 切断（群聊关键）      │
       │  ├─ applyGroupTag 落库 + 送 LLM         │
       │  └─ 队列 + sessionLock 串行             │
       └──────────────┬─────────────────────────┘
                      │
                      ▼
                ┌──────────┐
                │  Agent   │  ← StateGraph + ReAct
                └─────┬────┘
                      │ finalAnswer / tool_calls
                      ▼
       ┌────────────────────────────────────────┐
       │  sendOutboundFrame(chatId, body)        │
       │  ├─ 缓存命中 → aibot_respond_msg         │
       │  ├─ 缓存未命中 → aibot_send_msg          │
       │  ├─ keepalive scheduler (60s TTL 续命)   │
       │  └─ 重连退避（NAT / 抖动自愈）            │
       └────────────────────────────────────────┘
```

---

## 相关阅读

- [多渠道接入](./channels) — 9 个渠道的总览 + 设置
- [Agent 引擎](./agents) — TLS 重试、错误分类、自循环检测
- [模型配置](./models) — 怎么换默认模型、failover chain
- [安全与审批](./security) — 群里执行高风险工具的审批流
- [Doctor 健康检查](./doctor) — 怎么用诊断命令排查渠道问题
