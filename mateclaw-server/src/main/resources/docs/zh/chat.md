# 聊天与消息

聊天是你真正干活的地方。MateClaw 里其他所有东西——Agent、工具、记忆、Wiki、渠道——存在的理由，都是为了让**这个框里发生的事**足够好。

这一页讲的是这个框到底在做什么。不是 REST 端点，不是 SSE 事件字典。是你看到什么、它为你做了什么、为什么交互设计是现在这样。（API 细节在页面最底部，给需要集成的人看。）

---

## 你看到的东西

你输入，Agent 思考，token 开始流出来，你看着。

但那不是一堵文字墙往外冒。一条 Assistant 消息是由**若干 segment**组成的，每个 segment 都有类型：

- **Thinking（思考）**——Agent 的内部推理过程，显示在一个默认折叠的面板里，点一下展开。默认不打开是为了简洁；网络中断了，这段思考也不会丢。
- **Tool call（工具调用）**——一张卡片，显示工具名、参数、（执行完之后的）结果。ChatGPT 式的展现，直接嵌在对话里，不是藏在一个"调试面板"后面。
- **Content（内容）**——真正的回答正文，一个 token 一个 token 流出来。
- **Attachment（附件）**——生成出来的图像、视频、音乐、TTS 片段，挂在**产出它的那条消息上**，不是飘在一条新气泡里。

Segment 是**渐进到达**的。每个 segment 一落盘就立刻持久化到数据库——意思是你在流式中途刷新页面，已经渲染出来的东西**不会丢**。后端挂了再重启，写到一半的回复回来时还在。

这件事以前不成立。现在成立了。

---

## 持久化的任务清单：给需要时间的计划用

当你问了一个会触发 **Plan-and-Execute** 的问题，对话旁边会出现一个持久化的任务清单。它显示：

- 当前的计划（2–6 步，Agent 在开始执行之前生成）
- 每一步的实时状态：`pending → running → done`（或 `failed`）
- 每一步的输出，执行过程中持续捕获
- 计划完成后的一个紧凑总结

任务清单扛得住刷新，扛得住离开页面再回来，扛得住 Plan 生成失败。如果一个计划在执行中途炸了，你会看到**具体是哪一步炸了、为什么炸**，不会是一个永远转不停的 spinner。

**复杂的、需要一连串有序步骤的任务**用 Plan-and-Execute，你能看着它一步一步做完。其他更小的事情用 ReAct。

---

## 思考、工具调用、以及"该不该信"

MateClaw 的聊天 UI 在试着回答一个问题：**AI 刚刚告诉你的事情，该不该信？** 别处的默认答案是"看答案，自己猜"。MateClaw 想做得更好。

**思考可见。** 如果 Agent 推理得马虎，你可以展开思考面板看它到底是怎么想的。跳过了哪一步？写在里面。在发现错误前先幻觉了一个事实？你能看着它自己把错误抓回来。

**工具调用可见。** Agent 搜网页、读文件、查 Wiki——每一次工具调用你都看得到查询是什么、返回了什么、它怎么用这个结果。没有任何东西藏在一个"相信我"的黑盒里。

**阶段提示可见。** 流式响应顶部有一个小指示器，显示当前阶段——*思考中、搜索中、读取中、生成中、总结中*。你永远不会对着一个 spinner 发呆、不知道 Agent 是死是活。

信任是靠"把过程摊开"挣来的。MateClaw 把过程摊开。

---

## 多渠道实时同步

ChatConsole 不只是你自己聊天的地方。它是一个**运营控制台**。

- **外部渠道实时同步**——一个微信用户在跟你的 Agent 聊天，你在 ChatConsole 侧栏能同步看到推理过程、工具调用、流式回复。不用 F5，不用等。
- **运行指示器**——正在跑 Agent 任务的会话，图标上有琥珀色脉冲。你一眼能看到哪些会话在活跃。
- **切换不踩死**——切到别的会话时，上一个在后台继续跑完。切回来，自动重连到活跃 buffer，一个 token 都不丢。
- **不再重复气泡**——reconcile 层通过 ID 提升把前端流式 placeholder 和后端落库的 assistant 消息匹配为同一条，消息不会闪一下变两条。
- **错误卡可操作**——Ollama "does not support tools" 不再是"未知错误"。现在给你具体中文指引："请切换到 qwen3 / qwen2.5:7b+ / llama3.1:8b+"。

---

## 附件与文件上传

三种给 Agent 文件的方式：

| 方式 | 行为 |
|------|------|
| **点击**附件按钮 | 打开文件选择器，选一个或多个文件 |
| **粘贴**（Ctrl/Cmd+V） | 粘贴从其他应用复制来的图片或文件 |
| **拖拽**到聊天区域 | 出现一个半透明蒙层，在里面任何地方松手 |

在桌面端**拖一个文件夹**进来，Agent 拿到的是这个文件夹的绝对路径引用——它可以用文件读取或 shell 工具去遍历里面的文件。在 Web 端拖文件夹进来，MateClaw 会递归展开、把每个文件单独上传。

默认上传限制：

| 设置 | 默认值 |
|------|--------|
| 单文件最大 | 100 MB |
| 单请求最大 | 200 MB |
| 允许类型 | 全部 |

图片递交给支持视觉的模型做视觉理解。PDF 和 DOCX 走文本抽取（扫描件自动降级到 OCR）。Agent 在本回合读到的所有内容，都会进它的上下文。

### 主模型不支持图片？走"多模态旁路"

::: tip 1.3.0 新增
当 Agent 配的主模型是纯文本模型（如 `deepseek-chat` / `kimi-k2`），上传图片不再"系统无法工作"，而是自动走旁路（sidecar）。详见 [issue #87](https://github.com/matevip/mateclaw/issues/87)。
:::

工作机制：

1. 你在「设置 → 模型 → 多模态旁路」配置一个**视觉旁路模型**（例如 `glm-4v` / `qwen-vl-max`）。
2. 上传图片时，路由器检测主模型是否支持视觉：
   - **支持** → 直接走原有的 native multimodal 路径，图片字节喂给主模型；
   - **不支持** → 旁路触发：视觉模型对图片做一次"看图说话"，把结构化描述拼回 user 消息文本，再交给主模型回答。

这样主对话保持便宜（只在有图片那一次额外花一次视觉模型的钱），同时**用户的工具调用不被压制**——之前的旧逻辑会在 system prompt 里硬性禁止 LLM 调用任何工具去解析跳过的附件，新版本拆掉了这条禁令：当 Agent 绑定了图片/视频处理类工具时，LLM 现在可以自主决定何时调用。

整个过程对用户**完全可见**：

- **输入框上方提示条**：粘贴图片那一刻就告诉你"将自动调用 xxx 识别图片内容（旁路模式）"。
- **消息气泡路由徽章**：助手回复底部多一个 `🔀 routed-to-xxx (图片)` 标签，hover 看到主模型 / 旁路模型 / Provider 详情。
- **历史消息归因**：每条消息都显示当时实际用的模型——之前是黑盒，现在是玻璃盒。

> **当前限制**：v1 仅支持图片旁路。视频附件目前需要切到具备视频能力的多模态主模型，或者绑定自定义视频处理工具（不会再被压制）。视频自动 sidecar 留到下一轮迭代。

---

## 消息实际上怎么流动

三十秒版本在下面。九十秒版本在 [Agent 引擎](./agents)。

```
你输入
   │
   ▼
POST /api/v1/chat?agentId={id}                ← 或走 SSE 流式（POST /api/v1/chat/stream）
   │
   ▼
Conversation Manager                          ← 加载/创建会话，追加用户消息
   │
   ▼
Agent Engine                                  ← ReAct 循环，或 Plan-and-Execute 图
   │     ┌──► 上下文窗口装配：system prompt + 工作空间文件 + 历史
   │     ├──► 工具调用（经过 Tool Guard；可能停下来等审批）
   │     ├──► Wiki 读取（如果绑定了知识库）
   │     └──► 记忆写入（异步，回合结束之后）
   │
   ▼
SSE 流 / 直接响应                             ← segment 一段一段送达
   │
   ▼
写入 mate_message                             ← 实时，segment 级持久化
```

注意这件事：**持久化是和流式同步的**。Segment 是从 LLM 吐出来的同时往数据库写的，不是回合结束一次性写。这就是为什么你在流式中途刷新不会丢掉回复。

---

## 会话管理

一个会话是属于某个 Agent + 某个用户的消息序列。MateClaw 用两张表存它们：

**mate_conversation**

| 列 | 用途 |
|----|------|
| `id` | 会话 ID |
| `user_id` | 所有者 |
| `agent_id` | 会话跑在哪个 Agent 上 |
| `title` | 从第一条用户消息自动生成（可编辑） |
| `create_time` / `update_time` | 时间戳 |

**mate_message**

| 列 | 用途 |
|----|------|
| `id` | 消息 ID |
| `conversation_id` | 所属会话 |
| `role` | `user` / `assistant` / `system` / `tool` |
| `content` | 消息全文（分段响应：拼接完成后的最终正文） |
| `segments` | Segment 的 JSON 数组（thinking、tool_call、tool_result、content），用于渐进展示 |
| `tool_calls` | Assistant 发起的工具调用的 JSON 数组 |
| `tool_call_id` | Tool 角色消息：它满足的那次调用 ID |
| `create_time` | 时间戳 |

Segment 的结构是渐进展示的底层。它也让**数据库成为单一事实源**——UI 可以把任何一条历史回复完整地复现成它流式时的样子。

### 按会话选模型

::: tip 1.4.0 新增
聊天顶栏的模型选择器现在把模型**绑定在会话上**，而不是全局开关。详见 [issue #150](https://github.com/matevip/mateclaw/issues/150)。
:::

在顶栏切换模型，只影响**当前这个会话**：选择会随会话存下来，并从**下一条消息**开始生效。没有显式设置过的会话，回落到工作空间默认模型。运行时模型指示器始终和会话上钉住的那一个保持同步——你看到的就是下一回合真正会用的。

这条隔离也让模型配置更健壮：**一个写错的模型 id 不再拖垮它所在的整个 Provider**。坏会话只影响自己，其他会话照常跑。

### 会话列表管理

::: tip 1.4.0 新增
会话侧栏从一条单纯的历史列表，升级成了一个可操作的运营面板。详见 [issue #144](https://github.com/matevip/mateclaw/issues/144)。
:::

- **置顶 / 取消置顶**——从每行的 `⋮` 溢出菜单操作，重要的会话固定在列表顶部的「置顶」分组里。
- **多选批量删除**——进入多选模式后，每行出现复选框，勾选若干条一次性删除。
- **按员工筛选**——当工作空间里有 **2 个及以上员工**时，侧栏顶部出现一个下拉，按员工过滤会话列表（只有一个员工时不显示，避免无意义的控件）。
- **状态点**——一眼看出每个会话的状态：正在生成（蓝色脉冲）、存在进行中的目标、有未读内容。

### 全局快捷键

::: tip 1.4.0 新增
两个全局快捷键，让你不碰鼠标就能在对话之间跳转。提示常驻在侧栏底部。
:::

| 快捷键 | 行为 |
|--------|------|
| `Ctrl/Cmd + K` | 打开员工选择器，跳到任意一个聊天 |
| `Ctrl/Cmd + N` | 新建一个会话 |

`Ctrl+N` 在你正于输入框 / 文本域里打字时不会触发，留给浏览器原生行为。

### 会话管理页

::: tip 1.4.0 新增
当会话多到侧栏装不下时，从聊天顶栏的溢出菜单进「会话管理」，去一个专门的管理页（`/sessions`）。
:::

这个页面是为「会话很多」而生的：

- **服务端分页**——不再一次把上千条会话塞进侧栏。
- **按标题 / ID 搜索**——输入即筛，定位到具体会话。
- **深度卡片布局**——每个会话一张卡片，信息密度比侧栏更高。
- **行内可编辑的模型 chip**——每行直接显示并切换该会话的模型，不用先进会话。
- **返回按钮**——一键回到聊天控制台。

### 共享的员工选择器

::: tip 1.4.0 新增
一个共享的选择器对话框，被三处复用：侧栏、`Ctrl+K` 快捷键、以及新建会话弹窗。
:::

三个入口打开的是**同一个对话框**，行为完全一致。对话框里的 Agent 图标**按员工做了颜色区分**，多员工工作空间里一眼就能认出谁是谁。

---

## 上下文窗口管理

每一个回合，MateClaw 都会构造真正送进 LLM 的那个 prompt。大致是：

1. **System prompt**——Agent 的指令
2. **工作空间文件注入**——`AGENTS.md`、`SOUL.md`、`PROFILE.md`、`MEMORY.md`（只注入 `enabled=true` 的那些）
3. **会话摘要**——如果早期轮次已经被压缩过
4. **最近的若干轮**——尽可能装进 token 预算
5. **当前用户消息**——永远在最后

当总量超过 `defaultMaxInputTokens × compactTriggerRatio`（默认 128000 × 0.75 = 96000），系统会让 LLM 把早期轮次总结一下，把结果缓存 30 分钟，送出去的是压缩版。如果 LLM 依然报 `context_length_exceeded`，会触发紧急截断：不调 LLM，直接丢掉更早的消息，保留最近两轮。

更多细节，以及"为什么把摘要注入成 `UserMessage` 而不是 `SystemMessage`"的安全设计理由，在 [记忆系统](./memory) 里。

---

## 多渠道：同一个 Agent 在每一处

不同的渠道用不同的传输协议，底下的 Agent 是同一个。同一份 system prompt，同一套工具，同一份记忆。

| 渠道 | 传输协议 | 流式 |
|------|----------|------|
| Web | SSE | ✅ |
| 钉钉 | Stream（WebSocket）/ Webhook | ✅（AI Card） |
| 飞书 | WebSocket / Webhook | ❌ |
| 企业微信 | 长连接 / Webhook | ❌ |
| 微信 | HTTP 长轮询 | ❌ |
| Telegram | Long-Polling / Webhook | 打字指示器 |
| Discord | Gateway WebSocket | 打字指示器 |
| QQ | WebSocket / 回调 | ❌ |
| Slack | Webhook / Socket mode | ❌ |

进 [多渠道接入](./channels) 看细节。

---

## API 参考（给集成者）

### 发送消息

```bash
curl -X POST 'http://localhost:18088/api/v1/chat?agentId=1' \
  -H "Authorization: Bearer YOUR_JWT_TOKEN" \
  -H "Content-Type: application/json" \
  -d '{
    "message": "东京现在几点？",
    "conversationId": "conv-abc123"
  }'
```

省略 `conversationId` 就会开一个新会话。`agentId` 是 query 参数，**不是**路径段。

### SSE 流式

SSE 端点是 `POST /api/v1/chat/stream`，请求体里带 `agentId`。浏览器原生 `EventSource` 只支持 GET，所以集成时用 `fetch()` 读流：

```javascript
const resp = await fetch('/api/v1/chat/stream', {
  method: 'POST',
  headers: {
    'Authorization': 'Bearer YOUR_JWT_TOKEN',
    'Content-Type': 'application/json',
    'Accept': 'text/event-stream',
  },
  body: JSON.stringify({
    agentId: 1,
    message: '东京现在几点？',
    conversationId: 'conv-abc123',
  }),
});

const reader = resp.body.getReader();
const decoder = new TextDecoder();
let buf = '';
while (true) {
  const { value, done } = await reader.read();
  if (done) break;
  buf += decoder.decode(value, { stream: true });
  // 按 SSE 协议拆 `\n\n` 边界，逐事件处理 segment
}
```

完整客户端实现可以参考 `mateclaw-ui/src/composables/chat/useChat.ts`。

### SSE 事件类型

| 事件 | 含义 |
|------|------|
| `phase` | 阶段切换——`thinking`、`action`、`observation`、`summarizing` |
| `message` | 内容 chunk——追加到当前 content segment |
| `thinking` | 思考 chunk——追加到 thinking segment |
| `tool_call_start` | Agent 开始调工具（工具名 + 参数） |
| `tool_call_end` | 工具执行完（结果摘要） |
| `plan_created` | Plan-and-Execute 生成了一个计划 |
| `step_start` / `step_end` | Plan-and-Execute 的步骤边界 |
| `approval_required` | 一次受守护的工具调用需要人工审批 |
| `_usage_final` | Token 用量统计（流结束时） |
| `done` | 流结束 |
| `error` | 出错了 |

### 会话管理

```bash
# 列表
curl http://localhost:18088/api/v1/conversations \
  -H "Authorization: Bearer YOUR_JWT_TOKEN"

# 取消息
curl http://localhost:18088/api/v1/conversations/conv-abc123/messages \
  -H "Authorization: Bearer YOUR_JWT_TOKEN"

# 删除
curl -X DELETE http://localhost:18088/api/v1/conversations/conv-abc123 \
  -H "Authorization: Bearer YOUR_JWT_TOKEN"
```

---

## 下一步

- [Agent 引擎](./agents)——真正在思考的是什么
- [记忆系统](./memory)——跨会话保留下来的是什么
- [多渠道接入](./channels)——聊天还能在哪里发生
- [LLM Wiki](./wiki)——Agent 在回答时能读到什么
