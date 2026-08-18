package vip.mate.team.service;

import org.junit.jupiter.api.Test;
import vip.mate.team.model.TeamRunStatus;
import vip.mate.team.model.TeamRunView;

import java.util.List;
import java.util.Map;

import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;

class SpringTeamRunEventPublisherTest {

    @Test
    void cancellationPublishesUnifiedRunProjection() {
        TeamEventChannel channel = mock(TeamEventChannel.class);
        SpringTeamRunEventPublisher publisher = new SpringTeamRunEventPublisher(channel);
        TeamRunView run = new TeamRunView(20L, 10L, 30L, 1L, "lead-conversation",
                null, "Run", "Objective", TeamRunStatus.CANCELLED, null, "stop", null,
                null, null, null, null,
                new TeamRunView.Progress(1, 0, 0, 0, 0), List.of());

        publisher.publishCancelled(run);

        verify(channel).publishRunEvent(run, "team_run_cancelled", Map.of());
    }
}
