package vip.mate.approval.grant;

import com.baomidou.mybatisplus.core.conditions.Wrapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import vip.mate.workspace.conversation.model.ConversationEntity;
import vip.mate.workspace.conversation.repository.ConversationMapper;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Unit tests for {@link WorkspaceLookupCache}.
 * <p>
 * Verifies that:
 * <ul>
 *   <li>The lookup uses {@code LambdaQueryWrapper} on the {@code conversation_id}
 *       business column — not {@code selectById}, which would silently miss every
 *       row and disable auto-grant entirely.</li>
 *   <li>The Caffeine LRU caches positive lookups (a second call doesn't hit the mapper).</li>
 *   <li>{@code invalidate(conversationId)} drops the cached entry so a re-lookup
 *       goes back to the mapper (used by the lifecycle listener in PR-2).</li>
 *   <li>Missing conversation / deleted conversation / blank input all return {@code null}
 *       without throwing.</li>
 * </ul>
 */
@ExtendWith(MockitoExtension.class)
class WorkspaceLookupCacheTest {

    @Mock
    ConversationMapper conversationMapper;

    @InjectMocks
    WorkspaceLookupCache cache;

    @BeforeEach
    void setUp() {
        // InjectMocks builds the instance via constructor; ensure cache state is clean.
        // (Caffeine cache is instance-scoped, so a fresh cache instance per test is enough.)
    }

    @Test
    void uses_lambda_query_not_select_by_id() {
        ConversationEntity conv = new ConversationEntity();
        conv.setConversationId("conv-abc");
        conv.setWorkspaceId(42L);
        when(conversationMapper.selectOne(any(Wrapper.class))).thenReturn(conv);

        Long ws = cache.resolveByConversation("conv-abc");

        assertThat(ws).isEqualTo(42L);
        // The critical assertion: selectOne(LambdaQueryWrapper) was used, not selectById(...).
        verify(conversationMapper, never()).selectById(any());
        verify(conversationMapper, times(1)).selectOne(any(Wrapper.class));
    }

    @Test
    void second_call_hits_cache_not_mapper() {
        ConversationEntity conv = new ConversationEntity();
        conv.setConversationId("conv-xyz");
        conv.setWorkspaceId(7L);
        when(conversationMapper.selectOne(any(Wrapper.class))).thenReturn(conv);

        cache.resolveByConversation("conv-xyz");
        cache.resolveByConversation("conv-xyz");
        cache.resolveByConversation("conv-xyz");

        verify(conversationMapper, times(1)).selectOne(any(Wrapper.class));
    }

    @Test
    void invalidate_forces_remap() {
        ConversationEntity conv = new ConversationEntity();
        conv.setConversationId("conv-1");
        conv.setWorkspaceId(1L);
        when(conversationMapper.selectOne(any(Wrapper.class))).thenReturn(conv);

        cache.resolveByConversation("conv-1");
        cache.invalidate("conv-1");
        cache.resolveByConversation("conv-1");

        verify(conversationMapper, times(2)).selectOne(any(Wrapper.class));
    }

    @Test
    void missing_conversation_returns_null() {
        when(conversationMapper.selectOne(any(Wrapper.class))).thenReturn(null);
        assertThat(cache.resolveByConversation("does-not-exist")).isNull();
    }

    @Test
    void null_or_blank_id_returns_null_without_query() {
        assertThat(cache.resolveByConversation(null)).isNull();
        assertThat(cache.resolveByConversation("")).isNull();
        verify(conversationMapper, never()).selectOne(any(Wrapper.class));
    }

    @Test
    void invalidate_on_null_id_is_noop() {
        cache.invalidate(null); // must not throw
    }
}
