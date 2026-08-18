package vip.mate.team.service;

import org.junit.jupiter.api.Test;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.annotation.EnableTransactionManagement;
import org.springframework.transaction.support.AbstractPlatformTransactionManager;
import org.springframework.transaction.support.DefaultTransactionStatus;
import org.springframework.transaction.support.TransactionTemplate;
import vip.mate.agent.AgentService;
import vip.mate.channel.web.ChatStreamTracker;
import vip.mate.team.event.TeamTasksDelegatedEvent;
import vip.mate.workspace.conversation.ConversationService;

import java.util.List;

import static org.mockito.Mockito.after;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class TeamDispatchServiceEventTest {

    private static final Long TEAM_ID = 10L;

    @Test
    void delegatedEventDispatchesOnlyAfterCommit() {
        try (AnnotationConfigApplicationContext context =
                     new AnnotationConfigApplicationContext(TestConfig.class)) {
            TeamTaskService taskService = context.getBean(TeamTaskService.class);
            when(taskService.findDispatchable(TEAM_ID)).thenReturn(List.of());
            ApplicationEventPublisher publisher = context;
            TransactionTemplate transactions = new TransactionTemplate(
                    context.getBean(PlatformTransactionManager.class));

            transactions.executeWithoutResult(status -> {
                publisher.publishEvent(new TeamTasksDelegatedEvent(TEAM_ID));
                verify(taskService, after(200).never()).findDispatchable(TEAM_ID);
            });

            verify(taskService, after(1000)).findDispatchable(TEAM_ID);
        }
    }

    @Configuration(proxyBeanMethods = false)
    @EnableTransactionManagement
    static class TestConfig {

        @Bean
        PlatformTransactionManager transactionManager() {
            return new TestTransactionManager();
        }

        @Bean
        TeamTaskService taskService() {
            return mock(TeamTaskService.class);
        }

        @Bean
        TeamDispatchService dispatchService(TeamTaskService taskService) {
            return new TeamDispatchService(
                    mock(TeamService.class), taskService, mock(AgentService.class),
                    mock(ConversationService.class), mock(ChatStreamTracker.class),
                    mock(TeamAnnounceService.class), mock(TeamEventChannel.class));
        }
    }

    static class TestTransactionManager extends AbstractPlatformTransactionManager {

        @Override
        protected Object doGetTransaction() {
            return new Object();
        }

        @Override
        protected void doBegin(Object transaction, TransactionDefinition definition) {
        }

        @Override
        protected void doCommit(DefaultTransactionStatus status) {
        }

        @Override
        protected void doRollback(DefaultTransactionStatus status) {
        }
    }
}
