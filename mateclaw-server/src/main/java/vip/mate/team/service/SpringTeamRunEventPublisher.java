package vip.mate.team.service;

import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import vip.mate.team.model.TeamRunView;

import java.util.Map;

/** Publishes run lifecycle events through the unified team event channel. */
@Component
@RequiredArgsConstructor
public class SpringTeamRunEventPublisher implements TeamRunEventPublisher {

    private final TeamEventChannel eventChannel;

    @Override
    public void publishCancelled(TeamRunView run) {
        eventChannel.publishRunEvent(run, "team_run_cancelled", Map.of());
    }
}
