package vip.mate.agent.graph.plan.node;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Pins the triage evidence gate ({@link PlanGenerationNode#shouldOverrideDirectAnswer}).
 *
 * <p>The gate catches the failure mode where the triage model returns a
 * {@code direct_answer} (category A) for a task that actually needs tools —
 * accepting it would end the turn via DirectAnswerNode without ever executing,
 * which surfaces to users as "复杂任务不执行就停止". When fired, the node
 * downgrades to a single-step plan so the executor still reaches the tools.
 *
 * <p>The gate is intentionally biased toward executing: a false positive costs
 * one extra executor pass (which still answers), while a false negative drops
 * the whole task. These tests lock both the must-override cases and the
 * genuine-knowledge-Q&A cases that must NOT be downgraded.
 */
@DisplayName("PlanGeneration triage evidence gate")
class PlanGenerationEvidenceGateTest {

    @Test
    @DisplayName("overrides when the goal contains clear action verbs")
    void overridesOnActionGoal() {
        // file / memory / search / generate actions all imply tools
        assertTrue(PlanGenerationNode.shouldOverrideDirectAnswer("帮我读取 config.yml 并总结配置项", null));
        assertTrue(PlanGenerationNode.shouldOverrideDirectAnswer("记住我偏好简洁的回答", null));
        assertTrue(PlanGenerationNode.shouldOverrideDirectAnswer("搜索一下今天的 AI 新闻", null));
        assertTrue(PlanGenerationNode.shouldOverrideDirectAnswer("帮我生成一份周报模板", null));
        assertTrue(PlanGenerationNode.shouldOverrideDirectAnswer("查一下我的知识库里有没有这份文档", null));
        assertTrue(PlanGenerationNode.shouldOverrideDirectAnswer("你现在挂载了哪些技能和工具？", null));
        assertTrue(PlanGenerationNode.shouldOverrideDirectAnswer("打开 MessageBubble.vue 看看渲染逻辑", null));
    }

    @Test
    @DisplayName("overrides when the answer is a plan preamble that promises action")
    void overridesOnActionPromiseAnswer() {
        assertTrue(PlanGenerationNode.shouldOverrideDirectAnswer(
                "这个项目用了什么技术栈", "我先去读取项目的 pom.xml 再回答你。"));
        assertTrue(PlanGenerationNode.shouldOverrideDirectAnswer(
                "总结一下", "让我先检索一下相关记忆。"));
        assertTrue(PlanGenerationNode.shouldOverrideDirectAnswer(
                "汇总", "接下来我会调用搜索工具获取最新数据。"));
    }

    @Test
    @DisplayName("does NOT override genuine knowledge questions")
    void keepsDirectAnswerForKnowledge() {
        assertFalse(PlanGenerationNode.shouldOverrideDirectAnswer(
                "什么是依赖注入？", "依赖注入是一种控制反转的实现方式……"));
        assertFalse(PlanGenerationNode.shouldOverrideDirectAnswer(
                "用一句话解释一下闭包", "闭包是函数与其词法作用域的组合。"));
        // A normal narrative opener ("我来介绍…") must not be mistaken for an
        // action promise — the verb after it is descriptive, not a tool call.
        assertFalse(PlanGenerationNode.shouldOverrideDirectAnswer(
                "介绍一下杭州", "我来介绍一下杭州这座城市的历史与风景。"));
        assertFalse(PlanGenerationNode.shouldOverrideDirectAnswer(
                "Java 和 Kotlin 的主要区别是什么", "两者的主要区别在于语法简洁性和空安全……"));
    }

    @Test
    @DisplayName("null-safe on missing goal / answer")
    void nullSafe() {
        assertFalse(PlanGenerationNode.shouldOverrideDirectAnswer(null, null));
        assertFalse(PlanGenerationNode.shouldOverrideDirectAnswer("普通问候", null));
    }

    // The goal handed to triage is wrapped by RuntimeContextInjector with a
    // <memory-context> block that itself contains filenames (user.md, PROFILE.md)
    // and memory keywords. The gate must match the user's ASK, not the wrapper —
    // otherwise it fires on every task and kills the direct-answer fast path.
    private static final String MEMORY_WRAPPER =
            "<memory-context>\n"
            + "The following is what you already know about this user.\n"
            + "--- structured/user.md ---\n"
            + "## preferred_answer_style\n用户喜欢简洁、分点的回答方式。\n"
            + "--- PROFILE.md ---\n## 回答偏好\n- 用户喜欢简洁、分点的回答方式。\n"
            + "</memory-context>\n\n";

    @Test
    @DisplayName("ignores the injected memory-context wrapper (no false positive)")
    void ignoresInjectedWrapper() {
        // Trivial knowledge question — the wrapper contains user.md / 偏好, but the
        // real ask needs no tools, so the gate must NOT fire.
        assertFalse(PlanGenerationNode.shouldOverrideDirectAnswer(MEMORY_WRAPPER + "1加1等于几", "2"));
        assertFalse(PlanGenerationNode.shouldOverrideDirectAnswer(MEMORY_WRAPPER + "什么是闭包", "闭包是……"));
    }

    @Test
    @DisplayName("still fires on a real action ask even when wrapped")
    void firesOnRealActionInsideWrapper() {
        assertTrue(PlanGenerationNode.shouldOverrideDirectAnswer(
                MEMORY_WRAPPER + "帮我读取 pom.xml 并总结依赖", null));
        assertTrue(PlanGenerationNode.shouldOverrideDirectAnswer(
                MEMORY_WRAPPER + "搜索一下今天的新闻", null));
    }

    @Test
    @DisplayName("stripInjectedContext returns the raw ask")
    void stripsWrapper() {
        assertEquals("1加1等于几", PlanGenerationNode.stripInjectedContext(MEMORY_WRAPPER + "1加1等于几"));
        assertEquals("无包装直接问", PlanGenerationNode.stripInjectedContext("无包装直接问"));
        assertNull(PlanGenerationNode.stripInjectedContext(null));
    }
}
