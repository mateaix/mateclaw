---
name: channel_message
version: "2.0.0"
description: "当需要主动向某个渠道会话单向推送消息时使用（企业微信、钉钉、飞书、Telegram、Discord、QQ、Slack 等）。适用于任务完成通知、定时提醒告警、异步结果回推等场景。先用 list_channel_sessions 查询目标会话，再用 send_channel_message 发送。"
dependencies:
  tools:
    - list_channel_sessions
    - send_channel_message
---

# 渠道消息推送

## 何时使用

仅在以下情况使用，这是**单向推送**，不会收到回复：

### 应该使用
- 用户明确要求"向某个渠道 / 会话发送消息"
- 异步任务完成后主动通知用户
- 定时提醒、告警、状态更新
- 将后台任务结果推送回指定会话

### 不应使用
- 当前对话中的正常回复（直接回复即可，不要重复推送）
- 需要等待用户回复的双向交互
- 目标渠道或会话不明确时（先询问用户）

## 支持渠道

`wecom`（企业微信）、`dingtalk`、`feishu`、`telegram`、`discord`、`qq`、`slack`、`weixin`

> 注意：只有机器人**收到过消息**的会话才能主动推送——平台的推送句柄是在收到入站消息时记录的。如果目标会话不在列表里，需要先让对方在该会话中给机器人发一条消息。

## 工作流程

### 第一步：查询目标会话

```
list_channel_sessions(channelType="wecom")
```

- `channelType` 可选，不传则列出当前工作区所有可推送会话
- 返回每个会话的 `conversation_id`、渠道名称、用户名、最后活跃时间
- 有多个候选会话时，优先选**最后活跃时间最近**的

### 第二步：发送消息

```
send_channel_message(
  conversationId="wecom:xxxx",
  message="✅ 数据分析已完成，结果已保存到 report.xlsx"
)
```

- `conversationId` 必须来自 `list_channel_sessions` 的返回结果，**不要凭空猜测**
- `message` 为消息正文（纯文本 / Markdown，取决于渠道能力），超过 4096 字符会被截断

## 常见场景示例

### 温度告警推送到企业微信

```
list_channel_sessions(channelType="wecom")
# 从结果中选目标会话，例如 conversation_id 为 wecom:DeBaDe 的会话，然后：
send_channel_message(
  conversationId="wecom:DeBaDe",
  message="【温度告警】中控测试会议室 当前温度 29.3℃，已超过 28℃，请及时处理"
)
```

### 任务完成通知到钉钉

```
list_channel_sessions(channelType="dingtalk")
send_channel_message(
  conversationId="dingtalk:sw:xxxx",
  message="✅ 周报生成完成，已写入知识库"
)
```

## 常见错误

- **没有先查会话就发送**：`conversationId` 必须先通过 `list_channel_sessions` 获取
- **把正常对话回复当成推送**：当前会话直接回复不需要用本技能
- **期望收到回复**：`send_channel_message` 是单向推送，不返回用户回复
- **目标会话不存在**：说明机器人从未在该会话收到过消息，请先让用户在目标会话里给机器人发一条消息
- **渠道未启用 / 不支持主动推送**：按报错提示先在渠道管理中启用对应渠道
