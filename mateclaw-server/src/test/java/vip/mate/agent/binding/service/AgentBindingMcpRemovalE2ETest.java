package vip.mate.agent.binding.service;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.DisplayName;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.ApplicationEventPublisher;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import vip.mate.agent.binding.model.AgentToolBinding;
import vip.mate.agent.binding.repository.AgentToolBindingMapper;
import vip.mate.tool.mcp.event.McpServerRemovedEvent;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Set;
import java.util.concurrent.atomic.AtomicLong;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Full-path regression (issue #127, MCP half): deleting an MCP server fires
 * {@link McpServerRemovedEvent}, and the listener must drop exactly that
 * server's agent-tool bindings — not a sibling server's whose id shares a
 * prefix, and not unrelated tools.
 */
@SpringBootTest(
        webEnvironment = SpringBootTest.WebEnvironment.NONE,
        properties = {
                "spring.flyway.enabled=true",
                "spring.flyway.locations=classpath:db/migration/h2",
                "mateclaw.feature-flag.refresh-ms=999999"
        }
)
class AgentBindingMcpRemovalE2ETest {

    private static final AtomicLong SEQ = new AtomicLong(System.nanoTime());

    @Autowired
    private AgentToolBindingMapper toolBindingMapper;
    @Autowired
    private ApplicationEventPublisher publisher;

    private void bind(long agentId, String toolName) {
        AgentToolBinding b = new AgentToolBinding();
        b.setId(SEQ.incrementAndGet());
        b.setAgentId(agentId);
        b.setToolName(toolName);
        b.setEnabled(true);
        b.setCreateTime(LocalDateTime.now());
        b.setUpdateTime(LocalDateTime.now());
        b.setDeleted(0);
        toolBindingMapper.insert(b);
    }

    private Set<String> toolsOf(long agentId) {
        return toolBindingMapper.selectList(
                        new LambdaQueryWrapper<AgentToolBinding>().eq(AgentToolBinding::getAgentId, agentId))
                .stream().map(AgentToolBinding::getToolName).collect(Collectors.toSet());
    }

    @Test
    @DisplayName("Removing an MCP server drops only its tool bindings")
    void cascadeDropsOnlyTargetServerBindings() {
        long agent = SEQ.incrementAndGet();
        bind(agent, "mcp_123_ping_ab12cd");
        bind(agent, "mcp_123_search_99ffaa");
        bind(agent, "mcp_1234_ping_ff0011");   // sibling server — must survive
        bind(agent, "web_search");             // builtin — must survive

        assertEquals(4, toolsOf(agent).size());

        publisher.publishEvent(new McpServerRemovedEvent(123L, "test-mcp"));

        Set<String> remaining = toolsOf(agent);
        assertEquals(Set.of("mcp_1234_ping_ff0011", "web_search"), remaining,
                "only server 123's bindings should be cleaned; sibling 1234 and builtin stay");
        assertTrue(remaining.stream().noneMatch(t -> t.startsWith("mcp_123_")));
    }

    @Test
    @DisplayName("No matching bindings is a no-op")
    void noMatchIsNoop() {
        long agent = SEQ.incrementAndGet();
        bind(agent, "web_search");
        publisher.publishEvent(new McpServerRemovedEvent(SEQ.incrementAndGet(), "empty-mcp"));
        assertEquals(Set.of("web_search"), toolsOf(agent));
    }
}
