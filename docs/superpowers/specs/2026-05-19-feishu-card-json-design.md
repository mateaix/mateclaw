# 飞书卡片 JSON 发送侧设计

**Issue**: matevip/mateclaw#141  
**日期**: 2026-05-19  
**范围**: 仅发送侧（卡片交互回调留下一个 Issue）

---

## 背景

当前 `FeishuChannelAdapter.sendMessage()` 只能发送 `msg_type: "text"` 纯文本。Agent 工具调用返回的结构化数据（表格、列表、摘要）和 Markdown 格式回复在飞书里可读性差。飞书卡片 JSON v2 支持表格、富文本、摘要等组件，适合承载这类内容。

---

## 核心思路

不引入 WeCom 风格的 `CardDispatcher`（那套按业务事件类型路由，适合审批流程）。飞书卡片的使用场景更接近「内容渲染器」，因此按**内容形态**自动选择渲染方式：

| 内容形态 | 判定条件 | 渲染结果 |
|---|---|---|
| JSON 数据 | 合法 JSON 对象或对象数组（≤32K） | 表格卡片 / 摘要卡片 |
| Markdown | 含代码块、标题、表格分隔行或 ≥2 列表项 | 富文本卡片（lark_md） |
| 长文本 | >300 字 + 含 `\n\n`，无 Markdown 结构 | 纯文本卡片 |
| 短纯文本 | <100 字，无结构 | 原有文本消息（不包卡片） |

---

## 新增文件：`FeishuCardFormatter`

**位置**：`vip.mate.channel.feishu.FeishuCardFormatter`

### 检测层

```java
enum ContentFormat { JSON, MARKDOWN, LONG_TEXT, PLAIN_TEXT }

static ContentFormat detect(String content)
```

**检测优先级**：JSON → MARKDOWN → LONG_TEXT → PLAIN_TEXT

**JSON 检测细节**：
- 仅当内容长度 ≤ 32,000 字符时尝试解析（防止大负载性能问题）
- 用 `objectMapper.readTree()` 解析，而非 `readValue(Object.class)`（精确控制类型）
- 仅接受非空对象（`isObject() && !isEmpty()`）或对象数组（`isArray() && get(0).isObject()`）
- 原始类型数组（`[1,2,3]`、`["a","b"]`）不走 JSON 卡片路径

**Markdown 检测细节**（强 → 弱）：
1. 含 ` ``` `（代码块，最强信号）
2. 含行首 `#` 标题：`(?m)^#{1,6}\s`
3. 含表格分隔行：`(?m)^\|[\s|:-]+\|\s*$`（避免 `---` HR 误判）
4. 行首列表项 ≥ 2 条：`stripLeading()` 后以 `- ` / `* ` / `数字. ` 开头（避免句中 `-` 误计）

**LONG_TEXT 是独立分支**：不走 `lark_md` 渲染器，避免对普通长段落做 Markdown 解析。

### 渲染层

```java
static Map<String, Object> render(String content, ContentFormat format)
```

所有卡片统一骨架：

```json
{
  "schema": "2.0",
  "config": { "wide_screen_mode": true },
  "header": { "title": { "tag": "plain_text", "content": "..." } },
  "body": { "elements": [ ... ] }
}
```

**各格式渲染规则**：

| ContentFormat | Header | Body 组件 |
|---|---|---|
| JSON (object) | 无 | 双列 `column_set`，每个 key-value 一行（摘要卡片） |
| JSON (array) | 字段数 ≤4 → `table` 组件；>4 → 每条 `div`（列表卡片） | 同左 |
| MARKDOWN | "AI 助手" | `div { tag: lark_md, content: ... }` |
| LONG_TEXT | 无 | `div { tag: plain_text, content: ... }` |

---

## `FeishuChannelAdapter` 改动

### `sendMessage()` 路径调整

```
sendMessage(targetId, content)
  ├─ card_format = "never"   → 原有文本路径（所有内容）
  ├─ card_format = "always"  → 跳过 detect()，直接 sendCard()
  └─ card_format = "auto"（默认）
       ├─ PLAIN_TEXT → 原有文本路径（detect() 返回此值意味着无结构，无论长短都不包卡片）
       └─ JSON / MARKDOWN / LONG_TEXT → FeishuCardFormatter.render() → sendCard()
```

### 新增方法

```java
// 发送 Interactive Card（msg_type: "interactive"）
public void sendCard(String targetId, Map<String, Object> cardJson)

// 通过 PATCH API 更新已发送卡片（为流式更新预留，本次不接入 Agent 输出管道）
public void updateCard(String messageId, Map<String, Object> cardJson)
```

`sendCard()` 实现：

`receive_id_type` 复用 `proactiveSend()` 中已有的前缀检测逻辑（`ou_` 开头 → `open_id`，否则 → `chat_id`），不硬编码：

```java
String receiveIdType = targetId.startsWith("ou_") ? "open_id" : "chat_id";
POST /open-apis/im/v1/messages?receive_id_type={receiveIdType}
{
  "receive_id": targetId,
  "msg_type":   "interactive",
  "content":    objectMapper.writeValueAsString(cardJson)
}
```

`updateCard()` 实现：

```java
PATCH /open-apis/im/v1/messages/{messageId}
{
  "msg_type": "interactive",
  "content":  objectMapper.writeValueAsString(cardJson)
}
```

---

## 配置项

在渠道 `configJson` 新增可选字段（通过前端 JSON tab 设置，不需要前端表单改动）：

```json
"card_format": "auto"
```

| 值 | 行为 |
|---|---|
| `"auto"`（默认） | 走 `FeishuCardFormatter.detect()` 检测逻辑 |
| `"always"` | 所有回复包卡片（跳过检测） |
| `"never"` | 所有回复走原有文本路径（降级/调试用） |

在 `FeishuChannelAdapter` 类 JavaDoc 的配置项说明中补充此字段，与现有 `enable_reaction`、`media_download_enabled` 风格一致。

---

## 不在本次范围内

- 卡片交互回调（`card.action.trigger` Webhook 处理）
- 流式更新接入 Agent 输出管道（`updateCard` 已预留接口）
- 前端表单字段新增
- 其他渠道（仅改飞书 Adapter）
