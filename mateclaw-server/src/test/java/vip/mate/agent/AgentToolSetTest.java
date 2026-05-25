package vip.mate.agent;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.ai.support.ToolCallbacks;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.tool.annotation.ToolParam;

import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * Regression test for <a href="https://github.com/matevip/mateclaw/issues/24">issue #24</a>.
 * <p>
 * Symptom: agent tool bindings persisted under the Java class name
 * (e.g. {@code BrowserUseTool}, which is what {@code mate_tool.name} stores) or the
 * Spring bean name (e.g. {@code browserUseTool}) had no effect at runtime, because the
 * graph runtime matches against the {@code @Tool} function name (e.g. {@code browser_use}).
 * <p>
 * Fix: {@link AgentToolSet} builds an alias index so any of these three identifiers
 * resolves to the same callback. This test pins that contract.
 */
class AgentToolSetTest {

    /** Fixture: a bean exposing two {@code @Tool} methods, mirroring real tools like
     *  {@code BrowserUseTool} ({@code browser_use}, {@code browser_screenshot}, ...). */
    static class FakeBrowserTool {
        @Tool(description = "Open a URL in the browser")
        public String browser_use(@ToolParam(description = "url to open") String url) {
            return "opened " + url;
        }

        @Tool(description = "Take a screenshot")
        public String browser_screenshot() {
            return "shot.png";
        }
    }

    @Test
    @DisplayName("Issue #24: class name and bean name resolve to the same callbacks as the @Tool function name")
    void aliasIndex_resolvesClassNameAndBeanNameToFunctionCallbacks() {
        FakeBrowserTool bean = new FakeBrowserTool();
        List<ToolCallback> callbacks = List.of(ToolCallbacks.from(bean));
        assertEquals(2, callbacks.size(), "fixture should expose 2 @Tool methods");

        AgentToolSet base = AgentToolSet.fromCallbacks(
                List.of(bean),
                callbacks,
                b -> "fakeBrowserTool" // simulate Spring bean-name lookup
        );
        assertEquals(2, base.size());

        // (A) Function name — the historically-correct form
        AgentToolSet byFn = base.withAllowedToolsOnly(Set.of("browser_use"));
        assertEquals(1, byFn.size());
        assertEquals("browser_use", byFn.callbacks().get(0).getToolDefinition().name());

        // (B) Spring bean name → expands to ALL @Tool methods on that bean
        AgentToolSet byBean = base.withAllowedToolsOnly(Set.of("fakeBrowserTool"));
        assertEquals(2, byBean.size(),
                "bean name should pull in every @Tool method on the class");

        // (C) Java class simple name (this is what mate_tool.name actually stores —
        //     e.g. 'BrowserUseTool' — and what the legacy bug saved into mate_agent_tool.tool_name)
        AgentToolSet byClass = base.withAllowedToolsOnly(Set.of("FakeBrowserTool"));
        assertEquals(2, byClass.size(),
                "class simple name should expand to all bean methods (this is the issue #24 fix)");

        // (D) Mixed: known + unknown aliases. Unknowns are silently dropped — callers persist
        //     stale data and we'd rather degrade gracefully than throw.
        AgentToolSet mixed = base.withAllowedToolsOnly(Set.of("FakeBrowserTool", "nonexistent_tool"));
        assertEquals(2, mixed.size());

        // (E) Empty allow-list yields empty tool set (NOT global default — only null does that)
        AgentToolSet none = base.withAllowedToolsOnly(Set.of());
        assertEquals(0, none.size());

        // (F) null = no per-agent binding → fall back to global default (every tool visible)
        AgentToolSet allDefault = base.withAllowedToolsOnly(null);
        assertEquals(2, allDefault.size());
    }

    @Test
    @DisplayName("withDeniedToolsFiltered accepts function / bean / class names interchangeably")
    void deniedAliases_areToleranceOfNamingConvention() {
        FakeBrowserTool bean = new FakeBrowserTool();
        List<ToolCallback> callbacks = List.of(ToolCallbacks.from(bean));

        AgentToolSet base = AgentToolSet.fromCallbacks(
                List.of(bean),
                callbacks,
                b -> "fakeBrowserTool"
        );

        // Deny by class name: removes both @Tool methods on that class
        AgentToolSet none = base.withDeniedToolsFiltered(Set.of("FakeBrowserTool"));
        assertEquals(0, none.size());

        // Deny by single function name: only that method drops
        AgentToolSet justOne = base.withDeniedToolsFiltered(Set.of("browser_screenshot"));
        assertEquals(1, justOne.size());
        assertEquals("browser_use", justOne.callbacks().get(0).getToolDefinition().name());
    }

    @Test
    @DisplayName("Two-arg fromCallbacks (no bean-name resolver): function name + class simple name still indexed (Spring bean name is not)")
    void twoArgFactory_indexesByFunctionNameAndClassName() {
        FakeBrowserTool bean = new FakeBrowserTool();
        List<ToolCallback> callbacks = List.of(ToolCallbacks.from(bean));

        AgentToolSet noResolver = AgentToolSet.fromCallbacks(List.of(bean), callbacks);

        // Function name resolves
        assertEquals(1, noResolver.withAllowedToolsOnly(Set.of("browser_use")).size());
        // Class simple name resolves too — derived from bean.getClass() reflection, no resolver needed
        assertEquals(2, noResolver.withAllowedToolsOnly(Set.of("FakeBrowserTool")).size());
        // Spring bean name does NOT resolve without a resolver (no source for it)
        assertEquals(0, noResolver.withAllowedToolsOnly(Set.of("fakeBrowserTool")).size());
    }
}
