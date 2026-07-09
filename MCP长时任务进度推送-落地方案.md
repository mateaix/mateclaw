# MCP 长时任务进度推送落地方案（复用现有 SSE 通道）

## 一、目标

让 MateClaw 支持 MCP 长时任务（最大 360 分钟）的**实时进度展示**，兼容 **SSE 和 streamable_http** 两种 MCP transport，全程**零新增 HTTP 接口、零轮询、零中间件**。

---

## 二、背景

### 2.1 当前问题

- MCP 工具调用为同步阻塞模式（`McpSyncClient.callTool()`），最长 60 秒超时
- `tool_call_started` 和 `tool_call_completed` 之间前端只显示旋转加载器，用户无感知
- 长时任务（如 Linux 源码编译安装，最长 360 分钟）缺乏进度反馈，用户体验差

### 2.2 为什么不用原有方案文档（stderr / 自定义 WS）

| 原有方案 | 问题 |
|---------|------|
| stderr 管道 | 绕过 MCP 协议标准；仅 stdio transport 可用；AI 侧需额外解析 |
| 自定义 WebSocket | MCP Server 需自建 WS 服务；AI 侧需额外建立 WS 连接；非 MCP 标准 |

### 2.3 本方案的核心思路

利用 MCP 协议标准 `notifications/progress` 机制，在 MateClaw（MCP Client 侧）接收进度通知后，**直接注入现有 SSE 推送通道**传到前端浏览器——数据流完全复用已有基础设施。

---

## 三、架构与数据流

### 3.1 全链路数据流

```
MCP Server（任意 transport: SSE / streamable_http / stdio）
   │
   │  notifications/progress {progressToken, progress, total, message}
   ▼
McpClientManager.progressConsumer              ← 新增注册
   │  根据 progressToken 查表得到 (conversationId, toolCallId)
   ▼
Spring McpProgressEvent                        ← 新增事件类型
   │
   ▼
McpProgressRelay.onMcpProgress()               ← 新增监听器
   │  调用 ChatStreamTracker.broadcastObject()
   ▼
ChatStreamTracker                              ← 已有，纯内存广播
   │  SSE: event=tool_call_progress
   │  data={toolCallId, toolName, percent, stage, message}
   ▼
浏览器 ToolCallSegment.vue                     ← 已有组件，加进度条渲染
```

### 3.2 progressToken 映射机制

MCP 协议要求 client 生成唯一的 `progressToken` 随 `tools/call` 请求发给 server，server 在 `notifications/progress` 中**原样回传**——这是天然的请求-响应绑定。

```
工具调用前:
  progressToken = UUID.randomUUID()
  progressTokenMap.put(progressToken, ProgressContext(conversationId, toolCallId, serverId, toolName))

MCP tools/call 请求:
  { name: "linux_source_install", _meta: { progressToken: "xxx-uuid" }, ... }

MCP 服务端推送:
  { method: "notifications/progress", params: { progressToken: "xxx-uuid", progress: 0.5, ... } }

MateClaw 收到:
  context = progressTokenMap.get("xxx-uuid")
  → ChatStreamTracker.broadcastObject(context.conversationId, "tool_call_progress", {...})

工具调用完成后:
  progressTokenMap.remove("xxx-uuid")
```

### 3.3 360 分钟超长任务处理

`ChatStreamTracker` 的环形缓冲区上限 16000 条事件，360 分钟 × 每 2 秒一次 = 10800 条 progress，会挤占 content/thinking delta 空间。

**策略**：
- progress 事件**不缓存**到 event buffer（`skipBuffer = true`）
- 维护独立内存快照：`Map<conversationId, Map<toolCallId, ProgressSnapshot>>`
- SSE 重连时不做全量 progress 回放，只下发**一条最新进度快照**
- 快照仅存最新值，内存恒定 O(1) per tool call

---

## 四、涉及文件与改动说明

| # | 文件路径 | 改动类型 | 说明 |
|---|---------|---------|------|
| 1 | `mateclaw-server/.../mcp/runtime/McpClientManager.java` | 修改 | 注册 progressConsumer |
| 2 | `mateclaw-server/.../mcp/runtime/McpProgressContext.java` | 新建 | progressToken 映射表 + 线程安全存取 |
| 3 | `mateclaw-server/.../mcp/runtime/McpProgressEvent.java` | 新建 | Spring Event 定义 |
| 4 | `mateclaw-server/.../mcp/runtime/McpProgressRelay.java` | 新建 | Event Listener → ChatStreamTracker |
| 5 | `mateclaw-server/.../agent/ToolExecutionExecutor.java` | 修改 | 调用前注册映射，完成后清理 |
| 6 | `mateclaw-server/.../mcp/runtime/SyncMcpToolCallbackProvider.java` | 修改 | 向 tools/call 请求注入 progressToken |
| 7 | `mateclaw-server/.../channel/web/ChatStreamTracker.java` | 修改 | 支持 skipBuffer + 重连下发进度快照 |
| 8 | `mateclaw-ui/.../chat/ToolCallSegment.vue` | 修改 | 渲染进度条 |
| 9 | `mateclaw-ui/.../chat/useChat.ts` | 修改 | 监听 tool_call_progress 事件 |

---

## 五、逐文件实现规格

### 5.1 McpProgressEvent.java（新建）

位置：`mateclaw-server/src/main/java/vip/mate/tool/mcp/runtime/McpProgressEvent.java`

```java
package vip.mate.tool.mcp.runtime;

import org.springframework.context.ApplicationEvent;
import java.util.Map;

/**
 * MCP 工具调用进度事件。
 * 由 McpClientManager.progressConsumer 发布，
 * 由 McpProgressRelay 消费并转发到 ChatStreamTracker。
 */
public class McpProgressEvent extends ApplicationEvent {

    private final String conversationId;
    private final String toolCallId;
    private final String toolName;
    private final double progress;       // 0.0 ~ 1.0
    private final Double total;          // 可为 null
    private final String message;        // 当前阶段描述

    public McpProgressEvent(Object source, String conversationId, String toolCallId,
                            String toolName, double progress, Double total, String message) {
        super(source);
        this.conversationId = conversationId;
        this.toolCallId = toolCallId;
        this.toolName = toolName;
        this.progress = progress;
        this.total = total;
        this.message = message;
    }

    // getters...
}
```

### 5.2 McpProgressContext.java（新建）

位置：`mateclaw-server/src/main/java/vip/mate/tool/mcp/runtime/McpProgressContext.java`

职责：
- `Map<String, ProgressEntry>` — progressToken → {conversationId, toolCallId, serverId, toolName}
- 线程安全（`ConcurrentHashMap`）
- 提供 `register(token, entry)` / `lookup(token)` / `remove(token)`
- 提供 `getLatestSnapshot(conversationId, toolCallId)` — 用于 SSE 重连时下发进度快照

```java
package vip.mate.tool.mcp.runtime;

import org.springframework.stereotype.Component;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

@Component
public class McpProgressContext {

    private final Map<String, ProgressEntry> tokenMap = new ConcurrentHashMap<>();
    // 进度快照：conversationId -> toolCallId -> 最新进度 JSON
    private final Map<String, Map<String, String>> snapshotMap = new ConcurrentHashMap<>();

    public record ProgressEntry(String conversationId, String toolCallId,
                                String serverId, String toolName) {}

    public void register(String progressToken, ProgressEntry entry) {
        tokenMap.put(progressToken, entry);
    }

    public ProgressEntry lookup(String progressToken) {
        return tokenMap.get(progressToken);
    }

    public void remove(String progressToken) {
        tokenMap.remove(progressToken);
    }

    /** 更新进度快照（每次收到 progress 时调用） */
    public void updateSnapshot(String conversationId, String toolCallId, String progressJson) {
        snapshotMap.computeIfAbsent(conversationId, k -> new ConcurrentHashMap<>())
                   .put(toolCallId, progressJson);
    }

    /** SSE 重连时获取进度快照 */
    public String getSnapshot(String conversationId, String toolCallId) {
        Map<String, String> tools = snapshotMap.get(conversationId);
        return tools != null ? tools.get(toolCallId) : null;
    }

    /** 工具完成后清理快照 */
    public void removeSnapshot(String conversationId, String toolCallId) {
        Map<String, String> tools = snapshotMap.get(conversationId);
        if (tools != null) {
            tools.remove(toolCallId);
        }
    }
}
```

### 5.3 McpClientManager.java（修改）

位置：`mateclaw-server/src/main/java/vip/mate/tool/mcp/runtime/McpClientManager.java`

在 `buildClient()` 方法中（约第 413 行，`spec.toolsChangeConsumer(...)` 之后）新增：

```java
import vip.mate.tool.mcp.runtime.McpProgressContext;
import vip.mate.tool.mcp.runtime.McpProgressEvent;

// 字段注入
private final McpProgressContext progressContext;

// buildClient() 中，toolsChangeConsumer 之后:
spec.progressConsumer(progressNotification -> {
    if (progressNotification == null || progressNotification.progressToken() == null) return;
    McpProgressContext.ProgressEntry entry = progressContext.lookup(progressNotification.progressToken());
    if (entry == null) return;
    try {
        McpProgressEvent event = new McpProgressEvent(
                this,
                entry.conversationId(),
                entry.toolCallId(),
                entry.toolName(),
                progressNotification.progress(),
                progressNotification.total(),
                progressNotification.message()
        );
        eventPublisher.publishEvent(event);
    } catch (Exception e) {
        log.warn("Failed to publish McpProgressEvent: {}", e.getMessage());
    }
});
```

### 5.4 SyncMcpToolCallbackProvider.java（修改）

位置：`mateclaw-server/src/main/java/vip/mate/tool/mcp/runtime/SyncMcpToolCallbackProvider.java`

> 注意：如果 `SyncMcpToolCallbackProvider` 来自 Spring AI SDK 且无法直接修改，则需要创建一个 **wrapper** 类 `ProgressAwareSyncMcpToolCallback`，在 `call()` 方法中：
> 1. 生成 `progressToken = UUID.randomUUID().toString()`
> 2. 将 `progressToken` 设置到 `CallToolRequest._meta`
> 3. 委托给原始 `SyncMcpToolCallback.call()` 或直接调 `McpSyncClient.callTool(request)`

核心逻辑：

```java
public String call(String toolInput, ToolContext toolContext) {
    // 仅对 MCP 工具生效
    if (!isMcpTool) return delegate.call(toolInput, toolContext);

    String progressToken = UUID.randomUUID().toString();

    // 注册映射
    progressContext.register(progressToken,
            new McpProgressContext.ProgressEntry(conversationId, toolCallId, serverId, toolName));

    try {
        // 构造带 progressToken 的 CallToolRequest
        McpSchema.CallToolRequest request = McpSchema.CallToolRequest.builder()
                .name(toolName)
                .arguments(arguments)
                .meta(Map.of("progressToken", progressToken))
                .build();
        return mcpSyncClient.callTool(request).content().toString();
    } finally {
        progressContext.remove(progressToken);
    }
}
```

### 5.5 ToolExecutionExecutor.java（修改）

位置：`mateclaw-server/src/main/java/vip/mate/tool/agent/ToolExecutionExecutor.java`

在 `executeSingleTool()` 方法中（约第 892 行，`callback.call()` 调用前后）：

```java
// 调用前：对于 MCP 工具，注册 progressToken 映射
// (这部分逻辑实际在 SyncMcpToolCallbackProvider wrapper 中完成)
// ToolExecutionExecutor 此处主要负责调用完成后通知清理
```

> 实际需要改动的地方较少——progressToken 的注册和清理已由 wrapper 负责，ToolExecutionExecutor 的改动主要是确保 `toolCallId` 和 `conversationId` 能传递到 wrapper 中。

### 5.6 McpProgressRelay.java（新建）

位置：`mateclaw-server/src/main/java/vip/mate/tool/mcp/runtime/McpProgressRelay.java`

```java
package vip.mate.tool.mcp.runtime;

import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;
import vip.mate.channel.web.ChatStreamTracker;

import java.util.Map;

@Slf4j
@Component
@RequiredArgsConstructor
public class McpProgressRelay {

    private final ChatStreamTracker streamTracker;
    private final McpProgressContext progressContext;
    private final ObjectMapper objectMapper;

    @EventListener
    public void onMcpProgress(McpProgressEvent event) {
        try {
            Map<String, Object> data = Map.of(
                    "toolCallId", event.getToolCallId(),
                    "toolName", event.getToolName(),
                    "percent", Math.round(event.getProgress() * 10000.0) / 100.0,  // 保留两位小数
                    "total", event.getTotal() != null ? event.getTotal() : 1.0,
                    "message", event.getMessage() != null ? event.getMessage() : "",
                    "stage", inferStage(event.getProgress())   // 根据百分比推断阶段
            );
            String jsonData = objectMapper.writeValueAsString(data);

            // 更新进度快照（用于重连）
            progressContext.updateSnapshot(event.getConversationId(), event.getToolCallId(), jsonData);

            // 广播到 SSE（skipBuffer = true，不缓存到环形缓冲区）
            streamTracker.broadcast(event.getConversationId(), "tool_call_progress", jsonData, true);
        } catch (Exception e) {
            log.warn("Failed to relay MCP progress: {}", e.getMessage());
        }
    }

    /** 根据百分比推断阶段名 */
    private String inferStage(double progress) {
        if (progress <= 0.05) return "prepare";
        if (progress <= 0.95) return "execute";
        return "finalize";
    }
}
```

### 5.7 ChatStreamTracker.java（修改）

位置：`mateclaw-server/src/main/java/vip/mate/channel/web/ChatStreamTracker.java`

改动 1：`broadcast()` 方法新增 `skipBuffer` 参数重载

```java
/**
 * 广播事件到所有 SSE 订阅者（可选是否缓存）。
 * @param skipBuffer true 时不写入环形缓冲区，用于高频 transient 事件（如 progress）
 */
public void broadcast(String conversationId, String eventName, String jsonData, boolean skipBuffer) {
    // 现有 broadcast 逻辑 + skipBuffer 判断
}
```

改动 2：`attach()` 重连时下发进度快照

```java
// 在 attach() 方法的 buffer 回放完成后：
McpProgressContext progressCtx = springContext.getBean(McpProgressContext.class);
Map<String, String> toolSnapshots = progressCtx.getSnapshots(conversationId);
if (toolSnapshots != null) {
    for (Map.Entry<String, String> entry : toolSnapshots.entrySet()) {
        sendToEmitter(emitter, "tool_call_progress", entry.getValue());
    }
}
```

### 5.8 useChat.ts（修改）

位置：`mateclaw-ui/src/composables/chat/useChat.ts`

在 SSE 事件处理注册中添加：

```typescript
stream.on('tool_call_progress', (event: SSEEvent) => {
    const data = parseSSEData(event.data)
    if (!data?.toolCallId) return

    const msgIdx = messages.value.findIndex(m =>
        m.segments?.some(s => s.toolCallId === data.toolCallId))
    if (msgIdx < 0) return

    const msg = messages.value[msgIdx]
    const segIdx = msg.segments!.findIndex(s => s.toolCallId === data.toolCallId)
    if (segIdx < 0) return

    // 更新 segment 的 progress 字段
    msg.segments![segIdx] = {
        ...msg.segments![segIdx],
        progress: data.percent,
        progressMessage: data.message,
        progressStage: data.stage
    }
})
```

### 5.9 ToolCallSegment.vue（修改）

位置：`mateclaw-ui/src/components/chat/ToolCallSegment.vue`

在运行状态（`status === 'running'`）时，如果有 progress 数据，渲染进度条替代纯旋转加载器：

```vue
<!-- 运行中 + 有 progress 数据 → 显示进度条 -->
<div v-if="segment.status === 'running' && segment.progress != null" class="progress-bar-wrapper">
    <div class="progress-label">{{ segment.progress }}%</div>
    <div class="progress-bar">
        <div class="progress-fill" :style="{ width: segment.progress + '%' }"></div>
    </div>
    <div class="progress-message">{{ segment.progressMessage }}</div>
</div>
<!-- 运行中 + 无 progress 数据 → 显示原有旋转加载器 -->
<div v-else-if="segment.status === 'running'" class="loading-spinner">...</div>
```

---

## 六、边界情况处理

| 场景 | 处理方式 |
|------|---------|
| MCP Server 不支持 progress | `progressConsumer` 收不到回调，路径完全不变，前端展示旋转加载器 |
| progressConsumer 内部异常 | try-catch 包围，log.warn，不传播异常 |
| progressTokenMap 内存泄漏 | `finally` 块保证清理；工具超时后通过定时任务扫描清理超过 400 分钟的陈旧 entry |
| SSE 断开重连（5 分钟内） | progress 不参与 buffer 回放，attach 后从快照下发最新进度 |
| SSE 断开超过 5 分钟 | RunState 已销毁，attach 失败，前端重新发起请求 |
| progress 推送频率过高 | 接收端不节流（交给 MCP Server 侧控制），前端直接渲染，无性能问题 |
| 多个 MCP Server 同时运行 | progressToken 全局唯一（UUID），不同 server 的 token 不会冲突 |
| conversationId 找不到 | `ChatStreamTracker.broadcast()` 内部 state 为 null 时静默丢弃，不报错 |

---

## 七、验证方法

### 7.1 后端验证

**步骤 1**：启动一个 MCP Server（SSE transport 或 streamable_http），实现 `@McpProgressToken` 推送进度。

用 [Spring AI MCP Server Boot Starter](https://docs.spring.io/spring-ai/reference/api/mcp/mcp-server-boot-starter.html) 写一个简单的测试工具：

```java
@McpTool(name = "long_running_test", description = "模拟长时任务")
public String longRunning(@McpProgressToken String progressToken,
                          McpSyncServerExchange exchange) throws Exception {
    for (int i = 0; i <= 10; i++) {
        Thread.sleep(2000);  // 每 2 秒推进 10%
        exchange.progressNotification(p -> p
                .progressToken(progressToken)
                .progress(i * 0.1)
                .total(1.0)
                .message("Step " + i + "/10"));
    }
    return "done";
}
```

**步骤 2**：在 MateClaw 中注册该 MCP Server，通过聊天界面触发 `long_running_test` 工具。

**预期结果**：
- MateClaw 后端日志输出：`McpProgressRelay` 收到 progress 事件并广播
- `ChatStreamTracker` 广播 `tool_call_progress` 事件（`skipBuffer=true`）

### 7.2 前端验证

**步骤 1**：调用 `long_running_test` 后，打开浏览器 DevTools → Network → 找到 `/api/v1/chat/stream` 的 SSE 响应。

**预期结果**：
- SSE 流中出现 `event: tool_call_progress` 事件，data 包含 `toolCallId`、`percent`、`message`
- `tool_call_started` 之后，`ToolCallSegment` 不再只显示旋转加载器，而是显示进度条和百分比

**步骤 2**：在任务运行过程中，刷新浏览器页面（模拟重连）。

**预期结果**：
- SSE 重连成功（`Last-Event-ID` 回放）
- progress 事件不会批量回放（因为 `skipBuffer=true`）
- 连接恢复后立即下发一条最新的 progress 快照
- 后续 progress 照常实时推送

### 7.3 兼容性验证

| 测试项 | 方法 | 预期 |
|--------|------|------|
| SSE transport MCP Server | 用 SSE transport 注册 MCP Server，触发 progress 工具 | 正常展示进度 |
| streamable_http transport | 用 streamable_http transport 注册（需客户端支持），触发 progress 工具 | 正常展示进度 |
| 非 MCP 工具（内置工具） | 调用 read_file / write_file 等 | 不受影响，仍展示旋转加载器 |
| 无 progress 的 MCP 工具 | 调用不发送 progress 的 MCP 工具 | 不受影响，仍展示旋转加载器 |
| 360 分钟长任务 | 模拟推送 360 分钟的 progress 事件 | 进度持续更新，event buffer 未被挤占，内存不增长 |

---

## 八、实施顺序（推荐）

1. **`McpProgressContext.java`** — 先建映射表
2. **`McpProgressEvent.java`** — 事件定义
3. **`McpClientManager.java`** — 注册 progressConsumer
4. **`SyncMcpToolCallbackProvider.java`** — 注入 progressToken
5. **`McpProgressRelay.java`** — 转发到 SSE
6. **`ChatStreamTracker.java`** — skipBuffer + 重连快照
7. **`useChat.ts` + `ToolCallSegment.vue`** — 前端渲染
8. **集成测试** — 用测试 MCP Server 端到端验证

---

## 九、注意事项

- `progressConsumer` 注册在 `McpClientManager.buildClient()` 中，每次 MCP 连接建立/重建时生效
- `progressToken` 的生命周期必须与工具调用严格绑定：调用前注册、finally 清理
- progress 事件不走 `StreamAccumulator` 和 `GraphEvent` 管道（因为图节点在工具执行期间阻塞），直接由 `McpProgressRelay` 注入 `ChatStreamTracker`
- transport 类型对方案无影响——`progressConsumer` 是 SDK 层面抽象，stdio/SSE/streamable_http 均支持
