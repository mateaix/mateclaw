package vip.mate.workflow.runtime;

import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;

/**
 * Test-scoped configuration that swaps {@link AgentInvoker} with the shared
 * {@link StubAgentInvoker}. Tests that exercise the workflow runtime import
 * this class instead of redeclaring the same {@code @Primary} bean.
 */
@TestConfiguration
public class StubAgentInvokerConfig {

    @Bean
    @Primary
    public StubAgentInvoker stubAgentInvoker() {
        return new StubAgentInvoker();
    }
}
