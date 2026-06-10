package vip.mate.approval.grant;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import vip.mate.workspace.conversation.model.ConversationEntity;
import vip.mate.workspace.conversation.repository.ConversationMapper;

import java.time.Duration;

/**
 * Caffeine-backed cache for {@code conversationId → workspaceId} lookups on the
 * tool-call hot path.
 * <p>
 * The conversation→workspace mapping is immutable once a conversation is created,
 * so a 5-minute TTL is purely a bound on cache size, not a correctness guard.
 * Cache misses query MyBatis with a {@code LambdaQueryWrapper} on the
 * {@code conversation_id} business column — calling
 * {@code conversationMapper.selectById(stringConversationId)} would interpret the
 * string as the {@code Long} {@code @TableId} primary key and silently miss every
 * row, which would route every tool call to {@code UNKNOWN_WORKSPACE} and disable
 * auto-grant entirely.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class WorkspaceLookupCache {

    private final ConversationMapper conversationMapper;

    private final Cache<String, Long> cache = Caffeine.newBuilder()
            .maximumSize(5_000)
            .expireAfterWrite(Duration.ofMinutes(5))
            .build();

    /**
     * Returns the workspaceId for the given business conversation id, or {@code null}
     * if the conversation does not exist (or was soft-deleted).
     */
    public Long resolveByConversation(String conversationId) {
        if (conversationId == null || conversationId.isEmpty()) {
            return null;
        }
        return cache.get(conversationId, id -> {
            ConversationEntity conv = conversationMapper.selectOne(
                    Wrappers.<ConversationEntity>lambdaQuery()
                            .eq(ConversationEntity::getConversationId, id)
                            .eq(ConversationEntity::getDeleted, 0)
                            .last("LIMIT 1")
            );
            if (conv == null) {
                log.debug("[APPROVAL] WorkspaceLookupCache: conversation {} not found, returning null", id);
                return null;
            }
            return conv.getWorkspaceId();
        });
    }

    /**
     * Drops a single mapping. Called by the lifecycle listener on
     * {@code ConversationDeletedEvent} so a re-created conversation with the same id
     * does not inherit a stale workspace.
     */
    public void invalidate(String conversationId) {
        if (conversationId != null) {
            cache.invalidate(conversationId);
        }
    }

    /** Test hook. */
    long estimatedSize() {
        return cache.estimatedSize();
    }
}
