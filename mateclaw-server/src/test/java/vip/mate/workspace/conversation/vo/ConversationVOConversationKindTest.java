package vip.mate.workspace.conversation.vo;

import org.junit.jupiter.api.Test;
import vip.mate.workspace.conversation.model.ConversationEntity;

import static org.assertj.core.api.Assertions.assertThat;

class ConversationVOConversationKindTest {

    @Test
    void classifiesExplicitChildLegacyScheduledAndPrimaryConversations() {
        assertThat(vo("worker-any-name", "lead", "team_worker").getConversationKind()).isEqualTo("team_worker");
        assertThat(vo("delegate-child", "lead", null).getConversationKind()).isEqualTo("primary");
        assertThat(vo("team-task-legacy", null, null).getConversationKind()).isEqualTo("team_worker");
        assertThat(vo("tasks_1", null, null).getConversationKind()).isEqualTo("scheduled");
        assertThat(vo("ordinary-team-task-note", null, null).getConversationKind()).isEqualTo("primary");
    }

    private static ConversationVO vo(String id, String parentId, String kind) {
        ConversationEntity entity = new ConversationEntity();
        entity.setConversationId(id);
        entity.setParentConversationId(parentId);
        entity.setConversationKind(kind);
        return ConversationVO.from(entity, null, null);
    }
}
