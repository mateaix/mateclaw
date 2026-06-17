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
 * ({@code webchat:<visitorId>}), not a MateClaw account. They must surface in
 * the console list / page / owner-check the same way {@code system}-owned IM
 * conversations do — otherwise they are silently invisible (the reported bug).
 * The strict overload (used by the visitor self-service path) must NOT widen to
 * other principals.
 */
@ExtendWith(MockitoExtension.class)
class ConversationServiceWebchatVisibilityTest {

    @Mock private ConversationMapper conversationMapper;
    @Mock private AgentMapper agentMapper;

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
    @DisplayName("lenient list includes webchat principals (username LIKE 'webchat:%')")
    void lenientListIncludesWebchat() {
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
    @DisplayName("strict list excludes webchat principals (no LIKE clause)")
    void strictListExcludesWebchat() {
        ArgumentCaptor<LambdaQueryWrapper<ConversationEntity>> captor =
                ArgumentCaptor.forClass(LambdaQueryWrapper.class);
        when(conversationMapper.selectList(captor.capture())).thenReturn(List.of());

        service.listConversations("admin", 1L); // strict 2-arg

        String sql = captor.getValue().getTargetSql();
        assertThat(sql).doesNotContainIgnoringCase("like");
    }

    @Test
    @DisplayName("page query includes webchat principals")
    void pageIncludesWebchat() {
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
    @DisplayName("isConversationOwner: webchat + system + self visible; foreign user not")
    void ownerCheckRecognizesWebchat() {
        when(conversationMapper.selectOne(any(LambdaQueryWrapper.class)))
                .thenReturn(ownedBy("webchat:visitor-1"));
        assertThat(service.isConversationOwner("webchat:k:visitor-1", "admin")).isTrue();

        when(conversationMapper.selectOne(any(LambdaQueryWrapper.class)))
                .thenReturn(ownedBy("system"));
        assertThat(service.isConversationOwner("feishu:ou_x", "admin")).isTrue();

        when(conversationMapper.selectOne(any(LambdaQueryWrapper.class)))
                .thenReturn(ownedBy("admin"));
        assertThat(service.isConversationOwner("c1", "admin")).isTrue();

        when(conversationMapper.selectOne(any(LambdaQueryWrapper.class)))
                .thenReturn(ownedBy("bob"));
        assertThat(service.isConversationOwner("c2", "admin")).isFalse();
    }

    private static ConversationEntity ownedBy(String username) {
        ConversationEntity conv = new ConversationEntity();
        conv.setUsername(username);
        return conv;
    }
}
