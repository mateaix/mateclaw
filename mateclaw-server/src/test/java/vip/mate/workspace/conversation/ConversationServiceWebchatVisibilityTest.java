package vip.mate.workspace.conversation;

import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.metadata.TableInfoHelper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import org.apache.ibatis.builder.MapperBuilderAssistant;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import vip.mate.agent.repository.AgentMapper;
import vip.mate.auth.model.UserEntity;
import vip.mate.auth.service.AuthService;
import vip.mate.workspace.conversation.model.ConversationEntity;
import vip.mate.workspace.conversation.repository.ConversationMapper;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;

/**
 * Pin the admin-console visibility of webchat conversations.
 *
 * <p>WebChat threads are owned by an external visitor principal
 * ({@code webchat:<visitorId>}), not a MateClaw account. The admin-console
 * list / page surface them alongside {@code system}-owned IM rows — but only
 * for a global admin, because per the cross-workspace guard (issue #344) only a
 * global admin can actually open a webchat-owned conversation. Listing them to
 * a non-admin would show rows the caller would then 403 on, so the webchat
 * clause is gated on the requester's role. The strict overload (visitor
 * self-service path) never widens to other principals.
 *
 * <p>The owner-check matrix itself is covered by
 * {@link ConversationServiceOwnershipWorkspaceTest}.
 */
@ExtendWith(MockitoExtension.class)
class ConversationServiceWebchatVisibilityTest {

    @Mock private ConversationMapper conversationMapper;
    @Mock private AgentMapper agentMapper;
    @Mock private AuthService authService;

    @InjectMocks private ConversationService service;

    /**
     * LambdaQueryWrapper resolves column names from MyBatis-Plus's table-info
     * cache, which a Spring context would normally populate. Seed it directly so
     * {@code getTargetSql()} / {@code getParamNameValuePairs()} work in this pure
     * unit test.
     */
    @BeforeAll
    static void initLambdaCache() {
        TableInfoHelper.initTableInfo(
                new MapperBuilderAssistant(new MybatisConfiguration(), ""),
                ConversationEntity.class);
    }

    @Test
    @DisplayName("lenient list, global admin: includes webchat principals (username LIKE 'webchat:%')")
    void lenientListAdminIncludesWebchat() {
        when(authService.findByUsername("admin")).thenReturn(user("admin"));
        ArgumentCaptor<LambdaQueryWrapper<ConversationEntity>> captor =
                ArgumentCaptor.forClass(LambdaQueryWrapper.class);
        when(conversationMapper.selectList(captor.capture())).thenReturn(List.of());

        service.listConversations("admin", 1L, true);

        String sql = captor.getValue().getTargetSql();
        assertThat(sql).containsIgnoringCase("like");
        assertThat(captor.getValue().getParamNameValuePairs().values())
                .contains("webchat:%");
    }

    @Test
    @DisplayName("lenient list, non-admin: excludes webchat principals (no 'webchat:%' param)")
    void lenientListNonAdminExcludesWebchat() {
        when(authService.findByUsername("alice")).thenReturn(user("member"));
        ArgumentCaptor<LambdaQueryWrapper<ConversationEntity>> captor =
                ArgumentCaptor.forClass(LambdaQueryWrapper.class);
        when(conversationMapper.selectList(captor.capture())).thenReturn(List.of());

        service.listConversations("alice", 1L, true);

        // The malformed-id guard still emits a NOT LIKE, so we assert on the
        // param value instead of the LIKE keyword.
        assertThat(captor.getValue().getParamNameValuePairs().values())
                .doesNotContain("webchat:%");
    }

    @Test
    @DisplayName("strict list excludes webchat principals (no 'webchat:%' param, no role lookup)")
    void strictListExcludesWebchat() {
        ArgumentCaptor<LambdaQueryWrapper<ConversationEntity>> captor =
                ArgumentCaptor.forClass(LambdaQueryWrapper.class);
        when(conversationMapper.selectList(captor.capture())).thenReturn(List.of());

        service.listConversations("admin", 1L); // strict 2-arg

        assertThat(captor.getValue().getParamNameValuePairs().values())
                .doesNotContain("webchat:%");
    }

    @Test
    @DisplayName("page query, global admin: includes webchat principals")
    void pageAdminIncludesWebchat() {
        when(authService.findByUsername("admin")).thenReturn(user("admin"));
        ArgumentCaptor<LambdaQueryWrapper<ConversationEntity>> captor =
                ArgumentCaptor.forClass(LambdaQueryWrapper.class);
        when(conversationMapper.selectPage(any(Page.class), captor.capture()))
                .thenReturn(new Page<>());

        service.pageConversations("admin", 1L, 1, 20, null);

        String sql = captor.getValue().getTargetSql();
        assertThat(sql).containsIgnoringCase("like");
        assertThat(captor.getValue().getParamNameValuePairs().values())
                .contains("webchat:%");
    }

    @Test
    @DisplayName("page query, non-admin: excludes webchat principals")
    void pageNonAdminExcludesWebchat() {
        when(authService.findByUsername("alice")).thenReturn(user("member"));
        ArgumentCaptor<LambdaQueryWrapper<ConversationEntity>> captor =
                ArgumentCaptor.forClass(LambdaQueryWrapper.class);
        when(conversationMapper.selectPage(any(Page.class), captor.capture()))
                .thenReturn(new Page<>());

        service.pageConversations("alice", 1L, 1, 20, null);

        assertThat(captor.getValue().getParamNameValuePairs().values())
                .doesNotContain("webchat:%");
    }

    // ------------------------------------------------------------------
    // Malformed conversationId guard — rows whose id ends in ":" (e.g. an
    // empty-visitorId webchat thread) are filtered out of every admin list
    // query, regardless of role. Surfacing them triggers 500/403 on open
    // because the trailing ":" confuses some reverse proxies (issue #369).
    // ------------------------------------------------------------------

    @Test
    @DisplayName("lenient list: applies NOT LIKE '%:' guard to filter malformed ids")
    void lenientListAppliesMalformedIdGuard() {
        when(authService.findByUsername("admin")).thenReturn(user("admin"));
        ArgumentCaptor<LambdaQueryWrapper<ConversationEntity>> captor =
                ArgumentCaptor.forClass(LambdaQueryWrapper.class);
        when(conversationMapper.selectList(captor.capture())).thenReturn(List.of());

        service.listConversations("admin", 1L, true);

        // Assert on the rendered SQL (not the param values, which MyBatis-Plus
        // percent-escapes internally) so the test stays independent of that
        // implementation detail.
        String sql = captor.getValue().getTargetSql().toLowerCase();
        assertThat(sql).contains("not like");
        assertThat(sql).contains("conversation_id");
    }

    @Test
    @DisplayName("page query: applies the same NOT LIKE '%:' guard")
    void pageAppliesMalformedIdGuard() {
        when(authService.findByUsername("admin")).thenReturn(user("admin"));
        ArgumentCaptor<LambdaQueryWrapper<ConversationEntity>> captor =
                ArgumentCaptor.forClass(LambdaQueryWrapper.class);
        when(conversationMapper.selectPage(any(Page.class), captor.capture()))
                .thenReturn(new Page<>());

        service.pageConversations("admin", 1L, 1, 20, null);

        String sql = captor.getValue().getTargetSql().toLowerCase();
        assertThat(sql).contains("not like");
        assertThat(sql).contains("conversation_id");
    }

    @Test
    @DisplayName("strict list also applies the guard — malformed ids never leak to owner-only views")
    void strictListAppliesMalformedIdGuard() {
        ArgumentCaptor<LambdaQueryWrapper<ConversationEntity>> captor =
                ArgumentCaptor.forClass(LambdaQueryWrapper.class);
        when(conversationMapper.selectList(captor.capture())).thenReturn(List.of());

        service.listConversations("admin", 1L); // strict 2-arg

        String sql = captor.getValue().getTargetSql().toLowerCase();
        assertThat(sql).contains("not like");
        assertThat(sql).contains("conversation_id");
    }

    private static UserEntity user(String role) {
        UserEntity u = new UserEntity();
        u.setRole(role);
        return u;
    }
}
