package vip.mate.team.model;

import lombok.Builder;
import lombok.Data;

/** Input required to create a persistent team run. */
@Data
@Builder
public class TeamRunCreateCommand {

    private Long teamId;

    private Long workspaceId;

    private Long leadAgentId;

    private String leadConversationId;

    private Long originMessageId;

    private String title;

    private String objective;

    private String metadata;
}
