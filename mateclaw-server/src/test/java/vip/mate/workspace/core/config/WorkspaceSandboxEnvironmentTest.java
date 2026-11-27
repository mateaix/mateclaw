package vip.mate.workspace.core.config;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.env.YamlPropertySourceLoader;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.env.SystemEnvironmentPropertySource;
import org.springframework.core.io.ClassPathResource;
import vip.mate.agent.context.ChatOrigin;
import vip.mate.auth.repository.UserMapper;
import vip.mate.workspace.conversation.repository.ConversationMapper;
import vip.mate.workspace.core.service.MemberFileIsolation;
import vip.mate.workspace.core.service.MemberWorkspaceService;
import vip.mate.workspace.core.service.WorkspaceService;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.mock;

class WorkspaceSandboxEnvironmentTest {
    @AfterEach
    void resetIsolation() {
        MemberFileIsolation.configure(null);
    }

    private ApplicationContextRunner runner(Map<String, Object> environment) {
        return new ApplicationContextRunner().withUserConfiguration(Config.class)
                .withInitializer(context -> {
                    var sources = context.getEnvironment().getPropertySources();
                    sources.replace("systemEnvironment",
                            new SystemEnvironmentPropertySource("systemEnvironment", environment));
                    try {
                        for (var source : new YamlPropertySourceLoader().load(
                                "application", new ClassPathResource("application.yml"))) {
                            sources.addLast(source);
                        }
                    } catch (java.io.IOException e) {
                        throw new java.io.UncheckedIOException(e);
                    }
                });
    }

    @Test
    void documentedEnvironmentSwitchActivatesFailClosedIsolation() {
        runner(Map.of("MATECLAW_WORKSPACE_SANDBOX_MEMBER_ISOLATION_ENABLED", "true"))
                .run(context -> {
                    assertNull(context.getStartupFailure());
                    assertTrue(context.getBean(WorkspaceSandboxProperties.class).isMemberIsolationEnabled());
                    assertTrue(MemberFileIsolation.isEnabled());
                    assertThrows(SecurityException.class, () -> MemberFileIsolation.scope(ChatOrigin.EMPTY));
                });
    }

    @Test
    void defaultAndExplicitFalseKeepCompatibleMode() {
        for (var environment : java.util.List.of(Map.<String, Object>of(),
                Map.<String, Object>of("MATECLAW_WORKSPACE_SANDBOX_MEMBER_ISOLATION_ENABLED", "false"))) {
            runner(environment).run(context -> {
                assertNull(context.getStartupFailure());
                assertFalse(context.getBean(WorkspaceSandboxProperties.class).isMemberIsolationEnabled());
                assertFalse(MemberFileIsolation.isEnabled());
            });
        }
    }

    @Test
    void canonicalEnvironmentNameRemainsSupported() {
        runner(Map.of("MATECLAW_WORKSPACE_SANDBOX_MEMBERISOLATIONENABLED", "true"))
                .run(context -> {
                    assertNull(context.getStartupFailure());
                    assertTrue(context.getBean(WorkspaceSandboxProperties.class).isMemberIsolationEnabled());
                    assertTrue(MemberFileIsolation.isEnabled());
                });
    }

    @Configuration(proxyBeanMethods = false)
    @EnableConfigurationProperties(WorkspaceSandboxProperties.class)
    static class Config {
        @Bean
        MemberWorkspaceService memberWorkspaceService(WorkspaceSandboxProperties properties) {
            return new MemberWorkspaceService(properties, mock(ConversationMapper.class),
                    mock(UserMapper.class), mock(WorkspaceService.class));
        }
    }
}
