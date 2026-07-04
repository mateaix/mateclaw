package vip.mate.agent.binding.model;

import com.baomidou.mybatisplus.annotation.*;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * RFC-009 Phase 4 PR-3 — agent → preferred provider routing hint.
 *
 * <p>An agent with zero rows here uses the global fallback chain order
 * (no behavior change from pre-PR-3 deployments). When rows exist,
 * {@code AgentGraphBuilder.buildFallbackChain} sorts those provider ids
 * to the front by ascending {@code sortOrder}; non-listed providers
 * follow in their global priority order.</p>
 *
 * <p>Pool/cooldown gating still applies: a preferred provider that is
 * HARD-removed or cooling down is still skipped by the runtime walker.</p>
 */
@Data
@TableName("mate_agent_provider_preference")
public class AgentProviderPreference {

    @TableId(type = IdType.ASSIGN_ID)
    private Long id;

    private Long agentId;

    /** Provider id (matches {@code mate_model_provider.provider_id}). */
    private String providerId;

    /**
     * Specific chat model to pin for this entry (matches
     * {@code mate_model_config.id}). {@code null} means "use the provider's
     * default chat model" — backward compatible with provider-only
     * preferences. With this column the same {@code providerId} may appear
     * in multiple rows, each pinning a different model, forming a per-agent
     * preferred-model chain.
     */
    private Long modelId;

    /** Lower wins. Two rows with the same value tie-break on provider_id alphabetically. */
    private Integer sortOrder;

    private Boolean enabled;

    @TableField(fill = FieldFill.INSERT)
    private LocalDateTime createTime;

    @TableField(fill = FieldFill.INSERT_UPDATE)
    private LocalDateTime updateTime;

    private Integer deleted;
}
