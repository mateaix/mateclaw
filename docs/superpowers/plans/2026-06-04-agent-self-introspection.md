# Agent 自省（Self-Introspection）Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 让 Agent 能稳定回答"你用什么模型 / 你是谁、基于什么 / 你正在干什么"三类问题，做法是把已存在但模型看不到的运行时信息注入到 prompt 中。

**Architecture:** 静态身份（不变量）追加到缓存稳定的 system prompt（`AgentGraphBuilder.buildEnhancedPrompt`）；动态事实（模型/提供商）复用每轮注入的 `RuntimeContextInjector` 引导 UserMessage。不新增工具。"你正在干什么"靠已有的 progress ledger / 计划步骤可见性，无需改动。

**Tech Stack:** Java 21, Spring Boot 3.5, Spring AI Alibaba Graph, JUnit 5, Maven。

---

## 背景与约束（实现前必读）

- `RuntimeContextInjector`（`mateclaw-server/src/main/java/vip/mate/agent/context/RuntimeContextInjector.java`）是一个工具类，每轮把"当前时间 / 工作目录 / 渠道 / 发送者"渲染成一条 `[system-context]` 引导 `UserMessage`。**缓存纪律**：整条内容必须远小于 spring-ai 的 1024 字符用户缓存阈值，否则会污染对话缓存。新增的模型行很短，无虞，但不要往这里堆长文本。
- 现有签名（保留，勿破坏）：
  - `buildContextMessage()`
  - `buildContextMessage(String workspaceBasePath)`
  - `buildContextMessage(String workspaceBasePath, I18nService i18n)`
  - `buildContextMessage(String workspaceBasePath, I18nService i18n, ChatOrigin origin)` ← 当前三个 node 调的就是这个
- 现有测试 `RuntimeContextInjectorSenderTest` 用 3-arg / 4-arg 重载并断言**没有**模型行。因此模型行**只能由新增的 5-arg 重载**产出；旧重载保持字节不变。
- 模型/提供商的值在 graph state 里：`MateClawStateKeys.RUNTIME_MODEL_NAME`（常量值 `"runtime_model_name"`）、`MateClawStateKeys.RUNTIME_PROVIDER_ID`（`"runtime_provider_id"`），由 `buildInitialState` 从 `BaseAgent.modelName`/`runtimeProviderId` 写入。
- 三个调用点都能拿到 `state`：
  - `ReasoningNode`（`agent/graph/node/ReasoningNode.java`）：`state` 在 `apply` 作用域内（约 509 行），经 `buildNonHistoryPrefix(...)`（约 519 行调用、966 行定义）下传到 `buildContextMessage`（973 行）。
  - `PlanGenerationNode`（`agent/graph/plan/node/PlanGenerationNode.java`）：`state` 直接可用（164-169 行）。
  - `StepExecutionNode`（`agent/graph/plan/node/StepExecutionNode.java`）：`state` 在 `apply`（153 行）；模型行注入点在 `buildStepMessages(...)`（512 行定义、541-542 行调用）。
- 已接受的权衡：模型名是**本轮开始时**的值，mid-run failover 不更新。与 OpenClaw 同样的限制。

---

## File Structure

| 文件 | 责任 | 改动 |
|---|---|---|
| `agent/context/RuntimeContextInjector.java` | 渲染运行时上下文行 | 新增 5-arg 重载，产出模型行（对所有 origin 生效）；旧重载委托且行为不变 |
| `resources/messages.properties` / `messages_en.properties` | i18n 文案 | 新增 `context.model_identity` / `context.model_identity_hint` 两个键 |
| `agent/graph/node/ReasoningNode.java` | ReAct 推理节点 | `buildNonHistoryPrefix` 增加 model/provider 形参，从 state 取值并下传 |
| `agent/graph/plan/node/PlanGenerationNode.java` | Plan 三分类节点 | 从 state 取 model/provider，改调 5-arg 重载 |
| `agent/graph/plan/node/StepExecutionNode.java` | Plan 步骤执行节点 | `apply` 取 model/provider，下传到 `buildStepMessages` 并改调 5-arg 重载 |
| `agent/AgentGraphBuilder.java` | system prompt 装配 | `buildEnhancedPrompt` 末尾追加 `## About You` 静态身份块 |
| `test/.../context/RuntimeContextInjectorModelTest.java` | 新测试 | 覆盖模型行渲染矩阵 |
| `test/.../agent/AgentGraphBuilderIdentityBlockTest.java` | 新测试 | 覆盖静态身份块文案 |

---

## Task 1: RuntimeContextInjector 新增模型行（5-arg 重载 + i18n）

**Files:**
- Modify: `mateclaw-server/src/main/java/vip/mate/agent/context/RuntimeContextInjector.java`
- Modify: `mateclaw-server/src/main/resources/messages.properties:293-296`
- Modify: `mateclaw-server/src/main/resources/messages_en.properties:300-302`
- Test: `mateclaw-server/src/test/java/vip/mate/agent/context/RuntimeContextInjectorModelTest.java`（新建）

- [ ] **Step 1: 写失败测试**

新建 `mateclaw-server/src/test/java/vip/mate/agent/context/RuntimeContextInjectorModelTest.java`：

```java
package vip.mate.agent.context;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Pin the runtime model-identity line added by the 5-arg
 * {@link RuntimeContextInjector#buildContextMessage} overload.
 *
 * <p>Unlike the sender block, the model line is about the AGENT (which
 * model is driving this run), not the caller — so it must appear for
 * every origin, including web and cron. The legacy 3/4-arg overloads
 * must stay model-free so existing eval baselines don't shift.
 */
class RuntimeContextInjectorModelTest {

    @Test
    @DisplayName("model + provider present → emits Model line with provider parenthetical + hint")
    void modelLineWithProvider() {
        String ctx = RuntimeContextInjector.buildContextMessage(
                "/data/ws/5", null, ChatOrigin.EMPTY, "gpt-4o", "openai");

        assertTrue(ctx.contains("[system-context] Model: gpt-4o"), "model line missing: " + ctx);
        assertTrue(ctx.contains("(provider: openai)"), "provider missing: " + ctx);
        assertTrue(ctx.contains("answer with this value for the current run"),
                "model-identity hint missing: " + ctx);
    }

    @Test
    @DisplayName("blank provider → Model line without provider parenthetical")
    void modelLineWithoutProvider() {
        String ctx = RuntimeContextInjector.buildContextMessage(
                "/data/ws/5", null, ChatOrigin.EMPTY, "claude-sonnet-4-6", "  ");

        assertTrue(ctx.contains("[system-context] Model: claude-sonnet-4-6"), "model line missing: " + ctx);
        assertFalse(ctx.contains("(provider:"), "blank provider must not emit parenthetical: " + ctx);
    }

    @Test
    @DisplayName("web origin still gets the model line (agent fact, not sender fact)")
    void webOriginStillGetsModelLine() {
        ChatOrigin origin = ChatOrigin.web("conv_1", "user-1", 5L, "/data/ws/5");

        String ctx = RuntimeContextInjector.buildContextMessage(
                "/data/ws/5", null, origin, "gpt-4o", "openai");

        assertFalse(ctx.contains("Channel:"), "web origin must still suppress sender block: " + ctx);
        assertTrue(ctx.contains("Model: gpt-4o"), "web origin must still get model line: " + ctx);
    }

    @Test
    @DisplayName("blank modelName → no model line at all")
    void blankModelNoLine() {
        String ctx = RuntimeContextInjector.buildContextMessage(
                "/data/ws/5", null, ChatOrigin.EMPTY, "  ", "openai");

        assertFalse(ctx.contains("Model:"), "blank model must not emit a model line: " + ctx);
    }

    @Test
    @DisplayName("IM origin → both sender block AND model line present")
    void imOriginHasSenderAndModel() {
        ChatOrigin origin = new ChatOrigin(
                7L, "feishu:oc_abc", "ou_xyz", 5L, "/data/ws/5",
                9L, null, false, "Alice", "feishu", "oc_abc");

        String ctx = RuntimeContextInjector.buildContextMessage(
                "/data/ws/5", null, origin, "gpt-4o", "openai");

        assertTrue(ctx.contains("Channel: feishu"), "sender block missing: " + ctx);
        assertTrue(ctx.contains("Model: gpt-4o"), "model line missing: " + ctx);
    }
}
```

- [ ] **Step 2: 运行测试确认失败（编译失败：5-arg 重载不存在）**

Run: `mvn -q -pl mateclaw-server test -Dtest=RuntimeContextInjectorModelTest`
Expected: 编译错误 `method buildContextMessage cannot be applied to given types`（5 个参数的重载还不存在）。

- [ ] **Step 3: 加 i18n 键**

在 `mateclaw-server/src/main/resources/messages.properties` 第 296 行 `context.skill_dir_hint=...` 之后追加：

```properties
context.model_identity=[system-context] 模型: {0}
context.model_identity_hint=被问到你用的什么模型时，按本轮这个值回答。
```

在 `mateclaw-server/src/main/resources/messages_en.properties` 第 302 行 `context.working_dir_hint=...` 之后追加：

```properties
context.model_identity=[system-context] Model: {0}
context.model_identity_hint=If asked which model you are using, answer with this value for the current run.
```

- [ ] **Step 4: 实现 5-arg 重载**

在 `RuntimeContextInjector.java` 中，修改现有 4-arg 重载使其委托新 5-arg 重载，并新增 5-arg 重载。具体：

把现有签名（67 行起）
```java
    public static String buildContextMessage(String workspaceBasePath,
                                              vip.mate.i18n.I18nService i18n,
                                              ChatOrigin origin) {
```
改为委托：
```java
    public static String buildContextMessage(String workspaceBasePath,
                                              vip.mate.i18n.I18nService i18n,
                                              ChatOrigin origin) {
        return buildContextMessage(workspaceBasePath, i18n, origin, null, null);
    }

    /**
     * Full overload that also renders the agent's runtime model identity.
     * The model line is emitted for EVERY origin (web / cron / IM / null)
     * because it describes the agent, not the caller — only the sender
     * block stays IM-only. {@code modelName}/{@code providerId} come from
     * graph state ({@code RUNTIME_MODEL_NAME}/{@code RUNTIME_PROVIDER_ID}),
     * i.e. the model selected at run start (mid-run failover is not
     * reflected — accepted trade-off). Stays well under the 1024-char
     * spring-ai user-cache threshold.
     */
    public static String buildContextMessage(String workspaceBasePath,
                                              vip.mate.i18n.I18nService i18n,
                                              ChatOrigin origin,
                                              String modelName,
                                              String providerId) {
```
然后把原方法体（70-94 行的 `LocalDateTime now = ...` 到 `return sb.toString();`）移入新的 5-arg 方法体，并在 `appendSenderBlockIfPresent(sb, origin);` 之后、`return sb.toString();` 之前插入模型行渲染：
```java
        appendSenderBlockIfPresent(sb, origin);
        appendModelLineIfPresent(sb, modelName, providerId, i18n);
        return sb.toString();
```
并新增私有方法（放在 `appendSenderBlockIfPresent` 之后）：
```java
    /**
     * Append the agent's runtime model identity. Emitted for all origins
     * (it's an agent fact, not a sender fact). Skipped when modelName is
     * blank. Provider parenthetical is omitted when providerId is blank.
     */
    private static void appendModelLineIfPresent(StringBuilder sb, String modelName,
                                                 String providerId,
                                                 vip.mate.i18n.I18nService i18n) {
        if (modelName == null || modelName.isBlank()) return;
        String model = modelName.trim();
        sb.append("\n");
        if (i18n != null) {
            sb.append(i18n.msg("context.model_identity", model));
        } else {
            sb.append("[system-context] Model: ").append(model);
        }
        if (providerId != null && !providerId.isBlank()) {
            sb.append(" (provider: ").append(providerId.trim()).append(')');
        }
        sb.append("\n");
        if (i18n != null) {
            sb.append(i18n.msg("context.model_identity_hint"));
        } else {
            sb.append("If asked which model you are using, answer with this value for the current run.");
        }
    }
```

- [ ] **Step 5: 运行测试确认通过**

Run: `mvn -q -pl mateclaw-server test -Dtest=RuntimeContextInjectorModelTest,RuntimeContextInjectorSenderTest`
Expected: 两个测试类全部 PASS（旧的 sender 测试不受影响，因为它们调旧重载、无模型行）。

- [ ] **Step 6: 提交**

```bash
git add mateclaw-server/src/main/java/vip/mate/agent/context/RuntimeContextInjector.java \
        mateclaw-server/src/main/resources/messages.properties \
        mateclaw-server/src/main/resources/messages_en.properties \
        mateclaw-server/src/test/java/vip/mate/agent/context/RuntimeContextInjectorModelTest.java
git commit -m "feat(agent): render runtime model identity line in RuntimeContextInjector"
```

---

## Task 2: 把 model/provider 从 state 透传到三个注入点

**Files:**
- Modify: `mateclaw-server/src/main/java/vip/mate/agent/graph/node/ReasoningNode.java`（约 519、966-973）
- Modify: `mateclaw-server/src/main/java/vip/mate/agent/graph/plan/node/PlanGenerationNode.java`（约 164-169）
- Modify: `mateclaw-server/src/main/java/vip/mate/agent/graph/plan/node/StepExecutionNode.java`（约 162、512、541-542）

> 本任务无独立单测（这些 node 的 `apply` 依赖完整 graph state，难以单测）；正确性由 Task 1 的渲染测试 + Task 4 的编译 + 端到端冒烟共同保证。每步改完跑全量编译。

- [ ] **Step 1: 改 ReasoningNode —— 给 buildNonHistoryPrefix 加 model/provider 形参**

在 `ReasoningNode.java` 方法定义处（约 966 行）把签名
```java
    List<Message> buildNonHistoryPrefix(String systemPrompt,
                                        String workspaceBasePath,
                                        String agentIdStr,
                                        String userMsg,
                                        vip.mate.agent.context.ChatOrigin chatOrigin) {
```
改为：
```java
    List<Message> buildNonHistoryPrefix(String systemPrompt,
                                        String workspaceBasePath,
                                        String agentIdStr,
                                        String userMsg,
                                        vip.mate.agent.context.ChatOrigin chatOrigin,
                                        String runtimeModelName,
                                        String runtimeProviderId) {
```
并把方法体内（约 973 行）
```java
        prefix.add(new UserMessage(RuntimeContextInjector.buildContextMessage(workspaceBasePath, null, chatOrigin)));
```
改为：
```java
        prefix.add(new UserMessage(RuntimeContextInjector.buildContextMessage(
                workspaceBasePath, null, chatOrigin, runtimeModelName, runtimeProviderId)));
```

- [ ] **Step 2: 改 ReasoningNode 调用点 —— 从 state 取值并传入**

在 `ReasoningNode.java` 约 509-520 行，调用 `buildNonHistoryPrefix` 之前从 state 读取 model/provider，并加到调用实参：
```java
        String workspaceBasePath = state.value(vip.mate.agent.graph.state.MateClawStateKeys.WORKSPACE_BASE_PATH, "");
        String agentIdStr = state.value(MateClawStateKeys.AGENT_ID, "");
        String userMsg = state.value(MateClawStateKeys.USER_MESSAGE, "");
        String runtimeModelName = state.value(MateClawStateKeys.RUNTIME_MODEL_NAME, "");
        String runtimeProviderId = state.value(MateClawStateKeys.RUNTIME_PROVIDER_ID, "");

        List<Message> nonHistoryPrefix = buildNonHistoryPrefix(systemPrompt, workspaceBasePath, agentIdStr, userMsg,
                accessor.chatOrigin(), runtimeModelName, runtimeProviderId);
```

> 注意：`ReasoningNodePtlPromptTest` 直接调用 `buildNonHistoryPrefix`，**共 6 处**（约 46、75、78、96、113、133 行）。每处都要在末尾补两个实参 `""`（runtimeModelName）、`""`（runtimeProviderId）。这些测试只断言 system/wiki/runtime 布局，传空串不会影响其断言（空模型名不产出模型行）。Step 5 的 test-compile 会暴露任何遗漏。

- [ ] **Step 3: 改 PlanGenerationNode**

在 `PlanGenerationNode.java` 约 164-169 行，把
```java
            String workspaceBasePath = state.value(MateClawStateKeys.WORKSPACE_BASE_PATH, "");
            vip.mate.agent.context.ChatOrigin chatOrigin =
                    state.<vip.mate.agent.context.ChatOrigin>value(MateClawStateKeys.CHAT_ORIGIN)
                            .orElse(vip.mate.agent.context.ChatOrigin.EMPTY);
            promptMessages.add(new UserMessage(
                    RuntimeContextInjector.buildContextMessage(workspaceBasePath, null, chatOrigin)));
```
改为：
```java
            String workspaceBasePath = state.value(MateClawStateKeys.WORKSPACE_BASE_PATH, "");
            vip.mate.agent.context.ChatOrigin chatOrigin =
                    state.<vip.mate.agent.context.ChatOrigin>value(MateClawStateKeys.CHAT_ORIGIN)
                            .orElse(vip.mate.agent.context.ChatOrigin.EMPTY);
            String runtimeModelName = state.value(MateClawStateKeys.RUNTIME_MODEL_NAME, "");
            String runtimeProviderId = state.value(MateClawStateKeys.RUNTIME_PROVIDER_ID, "");
            promptMessages.add(new UserMessage(
                    RuntimeContextInjector.buildContextMessage(
                            workspaceBasePath, null, chatOrigin, runtimeModelName, runtimeProviderId)));
```

- [ ] **Step 4: 改 StepExecutionNode**

`buildStepMessages` 没有 `state`，但 `apply`（153 行）有。在 `apply` 中靠近 162 行 `String workspaceBasePath = state.value(...)` 处新增两行读取，并把值传入 `buildStepMessages`。

4a. 在 `StepExecutionNode.java` 约 162 行后加：
```java
        String runtimeModelName = state.value(MateClawStateKeys.RUNTIME_MODEL_NAME, "");
        String runtimeProviderId = state.value(MateClawStateKeys.RUNTIME_PROVIDER_ID, "");
```
4b. 找到 `apply` 中对 `buildStepMessages(...)` 的调用（在 153-512 之间），把实参补上 `runtimeModelName, runtimeProviderId`。
4c. 把 `buildStepMessages` 定义（512 行）
```java
    private List<Message> buildStepMessages(PlanStateAccessor accessor, String step, String systemPrompt, String workspaceBasePath) {
```
改为：
```java
    private List<Message> buildStepMessages(PlanStateAccessor accessor, String step, String systemPrompt,
                                            String workspaceBasePath, String runtimeModelName, String runtimeProviderId) {
```
4d. 把方法体内 541-542 行
```java
        messages.add(new UserMessage(
                RuntimeContextInjector.buildContextMessage(workspaceBasePath, null, accessor.chatOrigin())));
```
改为：
```java
        messages.add(new UserMessage(
                RuntimeContextInjector.buildContextMessage(
                        workspaceBasePath, null, accessor.chatOrigin(), runtimeModelName, runtimeProviderId)));
```

> 确认 `StepExecutionNode` 已 import `MateClawStateKeys`（162 行已用到 `MateClawStateKeys.WORKSPACE_BASE_PATH`，故已 import）。

- [ ] **Step 5: 全量编译 + 跑受影响测试**

Run: `mvn -q -pl mateclaw-server -am test-compile && mvn -q -pl mateclaw-server test -Dtest=ReasoningNodePtlPromptTest,RuntimeContextInjectorModelTest`
Expected: 编译通过；测试 PASS。`ReasoningNodePtlPromptTest` 的 6 处 `buildNonHistoryPrefix` 调用必须已按 Step 2 备注各补两个 `""` 实参，否则 test-compile 会失败——逐处修好后重跑。

- [ ] **Step 6: 提交**

```bash
git add mateclaw-server/src/main/java/vip/mate/agent/graph/node/ReasoningNode.java \
        mateclaw-server/src/main/java/vip/mate/agent/graph/plan/node/PlanGenerationNode.java \
        mateclaw-server/src/main/java/vip/mate/agent/graph/plan/node/StepExecutionNode.java
git commit -m "feat(agent): thread runtime model/provider into per-turn context injection"
```

---

## Task 3: 在 system prompt 注入静态身份块（## About You）

**Files:**
- Modify: `mateclaw-server/src/main/java/vip/mate/agent/AgentGraphBuilder.java`（`buildEnhancedPrompt`，约 1318-1474）
- Test: `mateclaw-server/src/test/java/vip/mate/agent/AgentGraphBuilderIdentityBlockTest.java`（新建）

> `buildEnhancedPrompt` 是 private 且依赖大量注入的 Spring bean，难以整体单测。为可测，把静态身份文案抽成一个 `static` 常量 + 一个 `package-private static` 拼装方法，针对该方法单测。

- [ ] **Step 1: 写失败测试**

新建 `mateclaw-server/src/test/java/vip/mate/agent/AgentGraphBuilderIdentityBlockTest.java`：

```java
package vip.mate.agent;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Pin the static "## About You" identity block appended to every agent's
 * system prompt. This is the cache-stable half of the self-introspection
 * feature — it answers "who are you / what are you built on", while the
 * volatile model line lives in RuntimeContextInjector.
 */
class AgentGraphBuilderIdentityBlockTest {

    @Test
    @DisplayName("identity block names MateClaw and the core tech stack")
    void identityBlockMentionsPlatformAndStack() {
        String block = AgentGraphBuilder.ABOUT_YOU_BLOCK;

        assertTrue(block.contains("## About You"), "missing heading: " + block);
        assertTrue(block.contains("MateClaw"), "must name the platform: " + block);
        assertTrue(block.contains("Spring AI Alibaba Graph"), "must name the graph runtime: " + block);
    }
}
```

- [ ] **Step 2: 运行确认失败**

Run: `mvn -q -pl mateclaw-server test -Dtest=AgentGraphBuilderIdentityBlockTest`
Expected: 编译失败 `cannot find symbol: variable ABOUT_YOU_BLOCK`。

- [ ] **Step 3: 加常量并拼接进 prompt**

在 `AgentGraphBuilder.java` 的 `// ==================== Prompt 构建 ====================`（1316 行）之后、`buildEnhancedPrompt` 之前，新增常量：

```java
    /**
     * Cache-stable platform identity, appended to every agent's system
     * prompt. Answers "who are you / what are you based on". The volatile
     * "which model right now" fact is injected per-turn by
     * {@link vip.mate.agent.context.RuntimeContextInjector} instead, to
     * keep this prefix's prompt-cache hash stable.
     */
    static final String ABOUT_YOU_BLOCK = """

            ## About You
            You are powered by MateClaw — a multi-user AI Agent platform built on
            Spring Boot 3.5 and Spring AI Alibaba Graph. You are reachable through
            WebChat and 8+ IM channels (DingTalk, Feishu, WeCom, WeChat, Telegram,
            Discord, QQ, Slack). If asked who you are or what you are based on,
            answer with MateClaw and the technology stack above.
            """;
```

然后把 `buildEnhancedPrompt` 的 `return` 语句（1473 行）
```java
        return basePrompt + toolGuidance + searchGuidance + wikiContext;
```
改为：
```java
        return basePrompt + ABOUT_YOU_BLOCK + toolGuidance + searchGuidance + wikiContext;
```

- [ ] **Step 4: 运行确认通过**

Run: `mvn -q -pl mateclaw-server test -Dtest=AgentGraphBuilderIdentityBlockTest`
Expected: PASS。

- [ ] **Step 5: 提交**

```bash
git add mateclaw-server/src/main/java/vip/mate/agent/AgentGraphBuilder.java \
        mateclaw-server/src/test/java/vip/mate/agent/AgentGraphBuilderIdentityBlockTest.java
git commit -m "feat(agent): append static About You identity block to system prompt"
```

---

## Task 4: 全量回归 + 端到端冒烟验证

**Files:** 无（验证任务）

- [ ] **Step 1: 跑后端全量测试**

Run: `mvn -q -pl mateclaw-server test`
Expected: BUILD SUCCESS，无新增失败。若有失败，定位是否本次签名变更的调用点未更新。

- [ ] **Step 2: 启动开发服务器**

Run: `cd mateclaw-server && mvn spring-boot:run`
Expected: 启动到端口 18088，无报错。

- [ ] **Step 3: WebChat 冒烟 —— 三类问题**

用 `admin` / `admin123` 登录 UI（或直接走 `/chat` 接口），依次问：
1. "你用的是什么模型？" → 期望答出当前模型名（与该 Agent 配置/会话置顶模型一致）。
2. "你是谁？基于什么实现？" → 期望提到 MateClaw + Spring Boot / Spring AI Alibaba Graph。
3. 先让它做一个多步任务（触发 Plan-Execute），执行中途问"你正在干什么？" → 期望复述当前步骤；空闲时问则答"在等你的指令"类。

Expected: 三问均答得准、不编造。记录实际回答到验证笔记。

- [ ] **Step 4:（可选）IM 渠道回归**

若本地接了任一 IM 渠道，发"你用什么模型"确认渠道路径也能答出，且发送者块仍正常（Channel/Sender 行还在）。

- [ ] **Step 5: 收尾提交（如有验证脚本/笔记）**

```bash
git add -A
git commit -m "test(agent): verify self-introspection answers across web + IM"
```
（无新增文件则跳过。）

---

## Self-Review 记录

- **Spec 覆盖**：① 静态身份 → Task 3；② 动态模型/provider → Task 1（渲染）+ Task 2（透传）；③ 你在干什么 → 复用现有可见状态，Task 4 Step 3 验证。三块全覆盖。
- **占位符**：无 TBD/TODO，所有代码步骤含完整代码。
- **类型一致性**：新 5-arg 重载签名 `buildContextMessage(String, I18nService, ChatOrigin, String, String)` 在 Task 1 定义、Task 2 三处调用一致；`buildNonHistoryPrefix` 新签名（+2 String）在 Task 2 Step 1 定义、Step 2 调用一致；`buildStepMessages` 新签名（+2 String）在 Task 2 Step 4 定义与调用一致；`ABOUT_YOU_BLOCK` 在 Task 3 定义、测试引用一致。
- **缓存纪律**：模型行仅由 5-arg 重载产出，短文本；身份块进缓存稳定的 system prompt 前缀，不含每轮变化值（模型名不放这里）。
