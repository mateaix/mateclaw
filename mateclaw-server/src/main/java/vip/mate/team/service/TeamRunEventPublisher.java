package vip.mate.team.service;

import vip.mate.team.model.TeamRunView;

/** Stable application boundary for team run lifecycle events. */
public interface TeamRunEventPublisher {

    void publishCancelled(TeamRunView run);
}
