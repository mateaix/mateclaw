package vip.mate.workspace.core.service;

import org.junit.jupiter.api.Test;
import vip.mate.exception.MateClawException;
import vip.mate.workspace.conversation.repository.ConversationMapper;
import vip.mate.workspace.core.model.WorkspaceEntity;
import vip.mate.workspace.core.model.WorkspaceMemberEntity;
import vip.mate.workspace.core.repository.WorkspaceMapper;
import vip.mate.workspace.core.repository.WorkspaceMemberMapper;
import vip.mate.wiki.service.WikiKnowledgeBaseService;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

class WorkspaceServiceRoleValidationTest {

    private final WorkspaceMapper workspaceMapper = mock(WorkspaceMapper.class);
    private final WorkspaceMemberMapper memberMapper = mock(WorkspaceMemberMapper.class);
    private final ConversationMapper conversationMapper = mock(ConversationMapper.class);
    private final WikiKnowledgeBaseService wikiKnowledgeBaseService = mock(WikiKnowledgeBaseService.class);
    private final WorkspaceService service = new WorkspaceService(
            workspaceMapper, memberMapper, conversationMapper, wikiKnowledgeBaseService, null);

    @Test
    void addMemberRejectsOwnerRole() {
        WorkspaceEntity workspace = new WorkspaceEntity();
        workspace.setId(1L);
        when(workspaceMapper.selectById(1L)).thenReturn(workspace);
        when(memberMapper.selectOne(any())).thenReturn(null);

        MateClawException ex = assertThrows(MateClawException.class,
                () -> service.addMember(1L, 42L, "owner"));

        assertEquals(400, ex.getCode());
        assertEquals("err.workspace.invalid_member_role", ex.getMsgKey());
        verify(memberMapper, never()).insert(any(WorkspaceMemberEntity.class));
    }

    @Test
    void updateMemberRoleRejectsOwnerEscalation() {
        WorkspaceMemberEntity member = new WorkspaceMemberEntity();
        member.setWorkspaceId(1L);
        member.setUserId(42L);
        member.setRole("admin");
        when(memberMapper.selectOne(any())).thenReturn(member);

        MateClawException ex = assertThrows(MateClawException.class,
                () -> service.updateMemberRole(1L, 42L, "owner"));

        assertEquals(400, ex.getCode());
        assertEquals("err.workspace.invalid_member_role", ex.getMsgKey());
        verify(memberMapper, never()).updateById(any(WorkspaceMemberEntity.class));
    }

    @Test
    void addMemberDefaultsMissingRoleToMember() {
        WorkspaceEntity workspace = new WorkspaceEntity();
        workspace.setId(1L);
        when(workspaceMapper.selectById(1L)).thenReturn(workspace);
        when(memberMapper.selectOne(any())).thenReturn(null);

        WorkspaceMemberEntity member = service.addMember(1L, 42L, null);

        assertEquals("member", member.getRole());
        verify(memberMapper).insert(any(WorkspaceMemberEntity.class));
    }

    @Test
    void updateMemberRoleRejectsUnknownRole() {
        WorkspaceMemberEntity member = new WorkspaceMemberEntity();
        member.setWorkspaceId(1L);
        member.setUserId(42L);
        member.setRole("member");
        when(memberMapper.selectOne(any())).thenReturn(member);

        MateClawException ex = assertThrows(MateClawException.class,
                () -> service.updateMemberRole(1L, 42L, "superuser"));

        assertEquals(400, ex.getCode());
        verify(memberMapper, never()).updateById(any(WorkspaceMemberEntity.class));
    }
}
