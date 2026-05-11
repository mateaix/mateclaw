# ISSUE: Agent 消息内容重复 — STREAMED_CONTENT 与 FINAL_ANSWER 重复发送

## 问题描述

Agent 返回的消息中，日报内容被生成两遍。表现为：同一段内容在消息正文中出现两次。

用户反馈：
> 日报内容被生成了两遍，是因为在输出流程末尾，我把用于预览的片段和正式输出错误地拼接在了一起（或者系统缓存了上次输出），导致同一段内容被重复显示。这不是 API 数据重复，而是输出阶段的渲染/拼接问题。

## 根因分析

问题位于 `StateGraphReActAgent.chatStructuredStream()` 方法（第 225-232 行）。

当 `contentAlreadyStreamed = true` 且 `hasFinalAnswer = true` 时，同一答案内容会被发送两次：

```java
// STREAMED_CONTENT 发送（line 219-223）
String streamed = output.state().<String>value(STREAMED_CONTENT).orElse("");
if (!streamed.isEmpty() && !streamed.equals(lastEmittedStreamedContent.get())) {
    lastEmittedStreamedContent.set(streamed);
    deltas.add(AgentService.StreamDelta.persistOnly(streamed, null));  // [A]
}

// FINAL_ANSWER 发送（line 225-232）
if (hasFinalAnswer(output) && finalAnswerEmitted.compareAndSet(false, true)) {
    String answer = extractFinalAnswer(output);
    if (answer != null && !answer.isEmpty()) {
        deltas.add(contentAlreadyStreamed
                ? AgentService.StreamDelta.persistOnly(answer, null)  // [B]
                : new AgentService.StreamDelta(answer, null));
    }
}
```

当 `contentAlreadyStreamed = true` 时：
- `[A]` 通过 `persistOnly(streamed, null)` 发送 STREAMED_CONTENT（包含完整答案）
- `[B]` 也通过 `persistOnly(answer, null)` 发送 FINAL_ANSWER（内容相同）

两者都标记为 `persistenceOnly=true`，都会进入 Accumulator 进行数据库持久化。消息重建时，两个相同的完整答案被拼接在一起，导致重复。

## 修复方案

修改 `StateGraphReActAgent.java` 第 225-232 行：

当内容已经通过流式发送（`contentAlreadyStreamed = true`）时，不再重复发送 FINAL_ANSWER，因为流式内容已通过 STREAMED_CONTENT 的 persistOnly 保存。

```java
if (hasFinalAnswer(output) && finalAnswerEmitted.compareAndSet(false, true)) {
    String answer = extractFinalAnswer(output);
    if (answer != null && !answer.isEmpty()) {
        // 只有在内容尚未流式发送的情况下，才发送 FINAL_ANSWER
        if (!contentAlreadyStreamed) {
            deltas.add(new AgentService.StreamDelta(answer, null));
        }
    }
}
```

## 影响范围

- **状态变量**: `STREAMED_CONTENT`、`FINAL_ANSWER`、`CONTENT_STREAMED`
- **节点**: `ReasoningNode`、`FinalAnswerNode`、`StateGraphReActAgent`
- **持久化**: `Accumulator`（数据库消息持久化）

## 验证步骤

1. 启动 mateclaw-server
2. 触发 Agent 生成日报任务
3. 检查消息是否只显示一遍，不再重复
4. 确认历史消息刷新后内容正确（不会因持久化层拼接错误而重复）
5. 运行相关单元测试

## 涉及文件

- `mateclaw-server/src/main/java/vip/mate/agent/graph/StateGraphReActAgent.java` (第 225-232 行)
- `mateclaw-server/src/main/java/vip/mate/agent/graph/node/ReasoningNode.java` (设置 `contentStreamed` 标志)
- `mateclaw-server/src/main/java/vip/mate/agent/graph/node/FinalAnswerNode.java` (设置 `finalAnswer`)

## 标签

- bug
- agent
- streaming
- persistence