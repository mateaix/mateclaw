package vip.mate.wiki.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import vip.mate.wiki.job.WikiKbConfig;
import vip.mate.wiki.job.WikiKbConfigParser;
import vip.mate.wiki.model.WikiAgentPageTypePermissionEntity;
import vip.mate.wiki.model.WikiKnowledgeBaseEntity;
import vip.mate.wiki.repository.WikiAgentPageTypePermissionMapper;

import java.util.List;
import java.util.Locale;

/**
 * Resolves whether an agent may read or write wiki pages of a given pageType
 * within a knowledge base.
 *
 * <p><b>Matching precedence</b>: an exact {@code page_type} row wins over the
 * agent's {@code page_type='*'} default row. The unique key
 * {@code (agent_id, kb_id, page_type, deleted)} guarantees at most one of each,
 * so resolution is unambiguous without a same-level tie-break.
 *
 * <p><b>Read default</b>: when no row matches the pageType, read access falls
 * back to the KB-level {@code defaultReadPolicy} ({@code allow_all} unless the
 * KB config sets {@code deny_all}). This keeps existing KBs fully readable
 * after upgrade.
 *
 * <p><b>Write default</b>: writes are gated opt-in. When an agent has no rows
 * at all for a KB, writes are {@link WriteDecision#ALLOW}ed (preserving current
 * behaviour). Once any row exists for that agent+KB, the KB is considered
 * locked down: a pageType with no matching row resolves to
 * {@link WriteDecision#DENY} (fail-safe).
 *
 * @author MateClaw Team
 */
@Slf4j
@Service
public class WikiPageTypePermissionService {

    /** Wildcard page_type for the agent's KB-wide default row. */
    public static final String WILDCARD = "*";

    private final WikiAgentPageTypePermissionMapper permissionMapper;
    private final WikiKnowledgeBaseService kbService;
    private final ObjectMapper objectMapper;

    public WikiPageTypePermissionService(WikiAgentPageTypePermissionMapper permissionMapper,
                                         WikiKnowledgeBaseService kbService,
                                         ObjectMapper objectMapper) {
        this.permissionMapper = permissionMapper;
        this.kbService = kbService;
        this.objectMapper = objectMapper;
    }

    /** Write operations gated by {@link #resolveWrite}. */
    public enum WriteOp { CREATE, UPDATE, DELETE }

    /** Resolution of a write request. */
    public enum WriteDecision { ALLOW, APPROVAL_REQUIRED, DENY }

    /**
     * Load the agent's permission view for a KB once, so a list of pages can be
     * filtered without re-querying per row. {@code null} agentId yields an
     * allow-all view (e.g. internal callers without an agent context).
     */
    public Access resolve(Long agentId, Long kbId) {
        if (agentId == null || kbId == null) {
            return new Access(List.of(), false, false);
        }
        List<WikiAgentPageTypePermissionEntity> rows = permissionMapper.selectList(
                new LambdaQueryWrapper<WikiAgentPageTypePermissionEntity>()
                        .eq(WikiAgentPageTypePermissionEntity::getAgentId, agentId)
                        .eq(WikiAgentPageTypePermissionEntity::getKbId, kbId));
        boolean denyByDefault = isDenyAll(kbId);
        return new Access(rows, denyByDefault, !rows.isEmpty());
    }

    /** Convenience single-shot read check. */
    public boolean canRead(Long agentId, Long kbId, String pageType) {
        return resolve(agentId, kbId).canRead(pageType);
    }

    /** Convenience single-shot write resolution. */
    public WriteDecision resolveWrite(Long agentId, Long kbId, String pageType, WriteOp op) {
        return resolve(agentId, kbId).resolveWrite(pageType, op);
    }

    // ==================== Config CRUD (admin surface) ====================

    /** All permission rows for an agent within a KB, ordered with the wildcard last. */
    public List<WikiAgentPageTypePermissionEntity> listRows(Long agentId, Long kbId) {
        if (agentId == null || kbId == null) {
            return List.of();
        }
        return permissionMapper.selectList(
                new LambdaQueryWrapper<WikiAgentPageTypePermissionEntity>()
                        .eq(WikiAgentPageTypePermissionEntity::getAgentId, agentId)
                        .eq(WikiAgentPageTypePermissionEntity::getKbId, kbId)
                        .orderByAsc(WikiAgentPageTypePermissionEntity::getPageType));
    }

    /**
     * Upsert a permission row by the {@code (agent_id, kb_id, page_type)} natural
     * key: an existing row for the same triple is updated in place, otherwise a
     * new row is inserted. The pageType is normalized to lowercase (the wildcard
     * {@code *} is preserved as-is). Returns the persisted row.
     */
    public WikiAgentPageTypePermissionEntity saveRow(WikiAgentPageTypePermissionEntity row) {
        if (row == null || row.getAgentId() == null || row.getKbId() == null) {
            throw new IllegalArgumentException("agentId and kbId are required");
        }
        String type = row.getPageType() == null || row.getPageType().isBlank()
                ? WILDCARD
                : (WILDCARD.equals(row.getPageType().trim())
                        ? WILDCARD
                        : row.getPageType().trim().toLowerCase(Locale.ROOT));
        row.setPageType(type);
        // Normalize the write policy so resolution never sees an unexpected token.
        if (row.getWritePolicy() != null) {
            String wp = row.getWritePolicy().trim().toLowerCase(Locale.ROOT);
            row.setWritePolicy(switch (wp) {
                case "allow", "deny", "approval_required" -> wp;
                default -> "approval_required";
            });
        }
        WikiAgentPageTypePermissionEntity existing = permissionMapper.selectOne(
                new LambdaQueryWrapper<WikiAgentPageTypePermissionEntity>()
                        .eq(WikiAgentPageTypePermissionEntity::getAgentId, row.getAgentId())
                        .eq(WikiAgentPageTypePermissionEntity::getKbId, row.getKbId())
                        .eq(WikiAgentPageTypePermissionEntity::getPageType, type)
                        .last("LIMIT 1"));
        if (existing != null) {
            row.setId(existing.getId());
            permissionMapper.updateById(row);
        } else {
            row.setId(null);
            permissionMapper.insert(row);
        }
        return row;
    }

    /** Logically delete a permission row by id. Returns true when a row was removed. */
    public boolean deleteRow(Long id) {
        if (id == null) {
            return false;
        }
        return permissionMapper.deleteById(id) > 0;
    }

    private boolean isDenyAll(Long kbId) {
        WikiKnowledgeBaseEntity kb = kbService.getById(kbId);
        if (kb == null || kb.getConfigContent() == null) {
            return false;
        }
        WikiKbConfig config = WikiKbConfigParser.parse(objectMapper, kb.getConfigContent());
        if (config == null || config.getDefaultReadPolicy() == null) {
            return false;
        }
        return "deny_all".equalsIgnoreCase(config.getDefaultReadPolicy().trim());
    }

    /**
     * A resolved per-(agent, KB) permission view. Holds the agent's rows so
     * repeated pageType checks (e.g. filtering a page list) hit memory, not DB.
     */
    public static final class Access {

        private final List<WikiAgentPageTypePermissionEntity> rows;
        private final boolean denyReadByDefault;
        private final boolean hasAnyRow;

        Access(List<WikiAgentPageTypePermissionEntity> rows, boolean denyReadByDefault, boolean hasAnyRow) {
            this.rows = rows;
            this.denyReadByDefault = denyReadByDefault;
            this.hasAnyRow = hasAnyRow;
        }

        /**
         * Whether the agent may read pages of {@code pageType}. Exact row wins
         * over {@code '*'}; absent a matching row, the KB default read policy
         * decides.
         */
        public boolean canRead(String pageType) {
            WikiAgentPageTypePermissionEntity match = match(pageType);
            if (match != null) {
                return flag(match.getCanRead());
            }
            return !denyReadByDefault;
        }

        /**
         * Resolve a write request. See class javadoc for the opt-in /
         * fail-safe defaults.
         */
        public WriteDecision resolveWrite(String pageType, WriteOp op) {
            WikiAgentPageTypePermissionEntity match = match(pageType);
            if (match == null) {
                // No rows at all → not gated yet → preserve current behaviour.
                // Some rows but none cover this type → KB is locked down.
                return hasAnyRow ? WriteDecision.DENY : WriteDecision.ALLOW;
            }
            boolean opAllowed = switch (op) {
                case CREATE -> flag(match.getCanCreate());
                case UPDATE -> flag(match.getCanUpdate());
                case DELETE -> flag(match.getCanDelete());
            };
            if (!opAllowed) {
                return WriteDecision.DENY;
            }
            return mapPolicy(match.getWritePolicy());
        }

        /** Exact page_type row wins; otherwise the wildcard row; else null. */
        private WikiAgentPageTypePermissionEntity match(String pageType) {
            String needle = pageType == null ? "" : pageType.trim().toLowerCase(Locale.ROOT);
            WikiAgentPageTypePermissionEntity wildcard = null;
            for (WikiAgentPageTypePermissionEntity row : rows) {
                String type = row.getPageType() == null ? "" : row.getPageType().trim();
                if (WILDCARD.equals(type)) {
                    wildcard = row;
                } else if (type.toLowerCase(Locale.ROOT).equals(needle)) {
                    return row;
                }
            }
            return wildcard;
        }

        private static WriteDecision mapPolicy(String writePolicy) {
            if (writePolicy == null) {
                return WriteDecision.APPROVAL_REQUIRED;
            }
            return switch (writePolicy.trim().toLowerCase(Locale.ROOT)) {
                case "allow" -> WriteDecision.ALLOW;
                case "deny" -> WriteDecision.DENY;
                default -> WriteDecision.APPROVAL_REQUIRED;
            };
        }

        private static boolean flag(Integer value) {
            return value != null && value != 0;
        }
    }
}
