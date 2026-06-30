package vip.mate.kbopen.auth;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import vip.mate.exception.MateClawException;
import vip.mate.kbopen.auth.model.KbApiKeyBindingEntity;
import vip.mate.kbopen.auth.model.KbApiKeyEntity;
import vip.mate.kbopen.auth.repository.KbApiKeyBindingMapper;
import vip.mate.kbopen.auth.repository.KbApiKeyMapper;

import java.time.LocalDateTime;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * Lifecycle management for KB Open API Keys: mint, authenticate, revoke,
 * and manage KB bindings.
 *
 * <p>Plaintext is shown exactly once at creation time — the DB stores only
 * the SHA-256 hash (via {@link TokenHashUtil}, the shared kernel).
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class KbApiKeyService {

    public static final String KEY_PREFIX = "mck_";
    private static final int KEY_ENTROPY_BYTES = 32;
    private static final int PREFIX_DISPLAY_LEN = 4;
    private static final long LAST_USED_DEBOUNCE_SECONDS = 60;
    private static final int DEFAULT_RATE_LIMIT = 60;

    private final KbApiKeyMapper keyMapper;
    private final KbApiKeyBindingMapper bindingMapper;
    private final TokenHashUtil tokenHashUtil;

    // ── Mint ──────────────────────────────────────────────────────────────

    /**
     * Mint a new API Key bound to the given KBs.
     *
     * @param workspaceId owning workspace
     * @param createdBy   user id of the creator
     * @param name        human-readable label
     * @param scopes      comma-separated scopes, null/blank = "kb:*"
     * @param kbIds       KBs this key can access — must be non-empty (R3)
     * @param expiresAt   optional expiry, null = never
     * @return the created entity + one-shot plaintext
     */
    @Transactional
    public CreatedKey create(Long workspaceId, Long createdBy, String name,
                             String scopes, Set<Long> kbIds, LocalDateTime expiresAt) {
        if (kbIds == null || kbIds.isEmpty()) {
            // R3: empty binding = zero access, which is useless for an external key.
            throw new MateClawException(400, "At least one knowledge base must be bound to the API key");
        }
        String plaintext = tokenHashUtil.generate(KEY_PREFIX, KEY_ENTROPY_BYTES);
        String hash = tokenHashUtil.hash(plaintext);
        String displayPrefix = plaintext.substring(0, Math.min(PREFIX_DISPLAY_LEN + KEY_PREFIX.length(), plaintext.length()));

        KbApiKeyEntity entity = new KbApiKeyEntity();
        entity.setName(name);
        entity.setTokenHash(hash);
        entity.setPrefix(displayPrefix);
        entity.setWorkspaceId(workspaceId);
        entity.setCreatedBy(createdBy);
        entity.setScopes(scopes != null && !scopes.isBlank() ? scopes : "kb:*");
        entity.setEnabled(true);
        entity.setExpiresAt(expiresAt);
        entity.setRateLimitPerMin(DEFAULT_RATE_LIMIT);
        entity.setDeleted(0);
        keyMapper.insert(entity);

        for (Long kbId : kbIds) {
            KbApiKeyBindingEntity binding = new KbApiKeyBindingEntity();
            binding.setApiKeyId(entity.getId());
            binding.setKbId(kbId);
            bindingMapper.insert(binding);
        }

        log.info("[KbOpenApi] Created key id={} workspaceId={} name={} boundKbs={}",
                entity.getId(), workspaceId, name, kbIds.size());
        return new CreatedKey(entity.getId(), plaintext, entity);
    }

    // ── Authenticate ──────────────────────────────────────────────────────

    /**
     * Auth-filter hot path: find an enabled, unexpired key whose hash matches
     * the SHA-256 of {@code plaintext}, and load its bound KBs + scopes.
     *
     * @return empty for null/blank, wrong prefix, hash miss, disabled, or expired
     */
    public Optional<AuthResult> authenticate(String plaintext) {
        if (plaintext == null || plaintext.isBlank() || !plaintext.startsWith(KEY_PREFIX)) {
            return Optional.empty();
        }
        String hash = tokenHashUtil.hash(plaintext);
        KbApiKeyEntity entity = keyMapper.selectOne(
                new LambdaQueryWrapper<KbApiKeyEntity>()
                        .eq(KbApiKeyEntity::getTokenHash, hash)
                        .eq(KbApiKeyEntity::getEnabled, true)
                        .eq(KbApiKeyEntity::getDeleted, 0)
                        .last("LIMIT 1"));
        if (entity == null) {
            return Optional.empty();
        }
        if (entity.getExpiresAt() != null && entity.getExpiresAt().isBefore(LocalDateTime.now())) {
            return Optional.empty();
        }

        Set<Long> kbIds = loadBoundKbIds(entity.getId());
        Set<String> scopes = parseScopes(entity.getScopes());
        int rateLimit = entity.getRateLimitPerMin() != null ? entity.getRateLimitPerMin() : DEFAULT_RATE_LIMIT;

        KbApiKeyContext context = new KbApiKeyContext(
                entity.getId(), entity.getWorkspaceId(), kbIds, scopes, rateLimit);
        return Optional.of(new AuthResult(context, entity.getLastUsedAt()));
    }

    /**
     * Resolve a context from a known entity id (used by admin endpoints).
     */
    public KbApiKeyEntity getById(Long id) {
        return keyMapper.selectById(id);
    }

    // ── List / Revoke / Update ────────────────────────────────────────────

    public List<KbApiKeyEntity> listByWorkspace(Long workspaceId) {
        return keyMapper.selectList(
                new LambdaQueryWrapper<KbApiKeyEntity>()
                        .eq(KbApiKeyEntity::getWorkspaceId, workspaceId)
                        .eq(KbApiKeyEntity::getDeleted, 0)
                        .orderByDesc(KbApiKeyEntity::getCreateTime));
    }

    public Set<Long> loadBoundKbIds(Long apiKeyId) {
        List<KbApiKeyBindingEntity> bindings = bindingMapper.selectList(
                new LambdaQueryWrapper<KbApiKeyBindingEntity>()
                        .eq(KbApiKeyBindingEntity::getApiKeyId, apiKeyId));
        return bindings.stream()
                .map(KbApiKeyBindingEntity::getKbId)
                .collect(Collectors.toCollection(LinkedHashSet::new));
    }

    @Transactional
    public void updateBindings(Long apiKeyId, Set<Long> newKbIds) {
        if (newKbIds == null || newKbIds.isEmpty()) {
            // R3: cannot reduce to zero access.
            throw new MateClawException(400, "At least one knowledge base must be bound to the API key");
        }
        bindingMapper.delete(new LambdaQueryWrapper<KbApiKeyBindingEntity>()
                .eq(KbApiKeyBindingEntity::getApiKeyId, apiKeyId));
        for (Long kbId : newKbIds) {
            KbApiKeyBindingEntity binding = new KbApiKeyBindingEntity();
            binding.setApiKeyId(apiKeyId);
            binding.setKbId(kbId);
            bindingMapper.insert(binding);
        }
    }

    @Transactional
    public void update(Long apiKeyId, Long workspaceId, String name, String scopes,
                       Set<Long> kbIds, LocalDateTime expiresAt) {
        KbApiKeyEntity existing = keyMapper.selectById(apiKeyId);
        if (existing == null || (existing.getDeleted() != null && existing.getDeleted() == 1)
                || !workspaceId.equals(existing.getWorkspaceId())) {
            throw new MateClawException(404, "API key not found: " + apiKeyId);
        }
        if (name != null) existing.setName(name);
        if (scopes != null) existing.setScopes(scopes);
        if (expiresAt != null) existing.setExpiresAt(expiresAt);
        keyMapper.updateById(existing);
        if (kbIds != null) {
            updateBindings(apiKeyId, kbIds);
        }
    }

    @Transactional
    public void revoke(Long apiKeyId, Long workspaceId) {
        KbApiKeyEntity existing = keyMapper.selectById(apiKeyId);
        if (existing == null || (existing.getDeleted() != null && existing.getDeleted() == 1)
                || !workspaceId.equals(existing.getWorkspaceId())) {
            throw new MateClawException(404, "API key not found: " + apiKeyId);
        }
        existing.setEnabled(false);
        existing.setDeleted(1);
        keyMapper.updateById(existing);
        log.info("[KbOpenApi] Revoked key id={} workspaceId={}", apiKeyId, workspaceId);
    }

    // ── Usage tracking ────────────────────────────────────────────────────

    /**
     * Record last-used timestamp, debounced to once per minute per key
     * (same pattern as PAT). {@code previousLastUsedAt} is the entity's
     * current lastUsedAt value, used to decide whether enough time has passed.
     */
    public void recordUse(Long apiKeyId, LocalDateTime previousLastUsedAt) {
        if (apiKeyId == null) return;
        if (!shouldRecordUse(previousLastUsedAt)) return;
        try {
            KbApiKeyEntity update = new KbApiKeyEntity();
            update.setId(apiKeyId);
            update.setLastUsedAt(LocalDateTime.now());
            keyMapper.updateById(update);
        } catch (Exception e) {
            log.debug("[KbOpenApi] last_used_at write failed for key {}: {}", apiKeyId, e.getMessage());
        }
    }

    static boolean shouldRecordUse(LocalDateTime previousLastUsedAt) {
        if (previousLastUsedAt == null) return true;
        return !previousLastUsedAt.plusSeconds(LAST_USED_DEBOUNCE_SECONDS).isAfter(LocalDateTime.now());
    }

    // ── Helpers ───────────────────────────────────────────────────────────

    private Set<String> parseScopes(String scopes) {
        if (scopes == null || scopes.isBlank()) {
            return Set.of("kb:*");
        }
        return Set.of(scopes.split(",")).stream()
                .map(String::trim)
                .collect(Collectors.toUnmodifiableSet());
    }

    /** Return value of {@link #create}. */
    public record CreatedKey(Long id, String plaintext, KbApiKeyEntity entity) {}

    /** Return value of {@link #authenticate}: context + lastUsedAt for debounce. */
    public record AuthResult(KbApiKeyContext context, LocalDateTime lastUsedAt) {}
}
