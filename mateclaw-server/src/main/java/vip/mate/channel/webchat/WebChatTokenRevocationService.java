package vip.mate.channel.webchat;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import vip.mate.channel.webchat.repository.WebChatRevokedVisitorMapper;

import java.time.Duration;
import java.time.LocalDateTime;

/**
 * Visitor-token revocation lookup with a process-local Caffeine cache in front
 * of the {@code webchat_revoked_visitor} table.
 *
 * <p>The cache is best-effort: a revoked visitor may take up to
 * {@link #CACHE_TTL} to become effectively revoked on a node that has the
 * un-revoked entry cached. We accept that window — webchat is low-volume —
 * rather than pay a DB round-trip on every management endpoint call. For
 * multi-instance deployments the same eventual-consistency applies
 * independently per node; the DB remains the source of truth and a fresh
 * node sees revocations immediately on cold cache.
 *
 * <p>All operations are idempotent: revoking an already-revoked visitor is a
 * no-op (the row's {@code revokedAt} is updated for record-keeping);
 * un-revoking an un-revoked one is also a no-op.
 *
 * @author MateClaw Team
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class WebChatTokenRevocationService {

    static final Duration CACHE_TTL = Duration.ofMinutes(5);
    private static final int CACHE_MAX_SIZE = 10_000;

    private final WebChatRevokedVisitorMapper revokedVisitorMapper;

    /**
     * Keyed by "{channelId}:{visitorId}". Value is true when an active
     * revocation row exists, false otherwise. absence means "not cached";
     * callers must treat null as "fall through to DB".
     */
    private final Cache<String, Boolean> revocationCache = Caffeine.newBuilder()
            .expireAfterWrite(CACHE_TTL)
            .maximumSize(CACHE_MAX_SIZE)
            .build();

    /**
     * True if this visitor is currently revoked on this channel. Pads the
     * cache miss with a single DB lookup; the result is then cached for
     * {@link #CACHE_TTL}.
     */
    public boolean isRevoked(Long channelId, String visitorId) {
        if (channelId == null || visitorId == null) {
            return false;
        }
        String key = channelId + ":" + visitorId;
        Boolean cached = revocationCache.getIfPresent(key);
        if (cached != null) {
            return cached;
        }
        boolean revoked = lookupRevoked(channelId, visitorId);
        revocationCache.put(key, revoked);
        return revoked;
    }

    /**
     * Record a revocation. Idempotent: re-revoking an already-revoked visitor
     * refreshes {@code revokedAt} + {@code reason} on the existing row.
     * Flushes the cache so the change is visible immediately on this node.
     */
    public void revoke(Long channelId, String visitorId, String reason) {
        WebChatRevokedVisitorEntity existing = findActive(channelId, visitorId);
        LocalDateTime now = LocalDateTime.now();
        if (existing == null) {
            WebChatRevokedVisitorEntity row = new WebChatRevokedVisitorEntity();
            row.setChannelId(channelId);
            row.setVisitorId(visitorId);
            row.setReason(reason);
            row.setRevokedAt(now);
            row.setCreateTime(now);
            row.setUpdateTime(now);
            row.setDeleted(0);
            revokedVisitorMapper.insert(row);
        } else {
            existing.setReason(reason);
            existing.setRevokedAt(now);
            existing.setUpdateTime(now);
            revokedVisitorMapper.updateById(existing);
        }
        revocationCache.put(channelId + ":" + visitorId, true);
        log.info("[WebChat] visitor revoked: channelId={}, visitorId={}, reason={}",
                channelId, visitorId, reason);
    }

    /**
     * Lift a revocation. Idempotent. Removes the cache entry so subsequent
     * {@link #isRevoked} calls re-query the DB (and find nothing).
     */
    public void unrevoke(Long channelId, String visitorId) {
        WebChatRevokedVisitorEntity existing = findActive(channelId, visitorId);
        if (existing != null) {
            existing.setDeleted(1);
            existing.setUpdateTime(LocalDateTime.now());
            revokedVisitorMapper.updateById(existing);
        }
        // Invalidate rather than put(false): the un-revoke may race with a
        // concurrent revoke on another node. Forcing a DB re-lookup is safer.
        revocationCache.invalidate(channelId + ":" + visitorId);
        log.info("[WebChat] visitor un-revoked: channelId={}, visitorId={}",
                channelId, visitorId);
    }

    private boolean lookupRevoked(Long channelId, String visitorId) {
        return findActive(channelId, visitorId) != null;
    }

    private WebChatRevokedVisitorEntity findActive(Long channelId, String visitorId) {
        return revokedVisitorMapper.selectOne(new LambdaQueryWrapper<WebChatRevokedVisitorEntity>()
                .eq(WebChatRevokedVisitorEntity::getChannelId, channelId)
                .eq(WebChatRevokedVisitorEntity::getVisitorId, visitorId)
                .eq(WebChatRevokedVisitorEntity::getDeleted, 0)
                .last("LIMIT 1"));
    }

    /** Test-only: drop every cached entry so the next isRevoked() falls through to DB. */
    void invalidateCacheForTest() {
        revocationCache.invalidateAll();
    }
}
