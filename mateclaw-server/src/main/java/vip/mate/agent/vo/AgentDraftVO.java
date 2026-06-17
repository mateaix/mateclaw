package vip.mate.agent.vo;

import com.fasterxml.jackson.databind.annotation.JsonSerialize;
import com.fasterxml.jackson.databind.ser.std.ToStringSerializer;
import lombok.Builder;
import lombok.Data;

import java.util.List;

/**
 * An AI-generated employee draft produced from a single natural-language
 * requirement. The draft is never persisted on its own — the create wizard
 * shows it for review, lets the user tweak any field, then commits it through
 * the normal agent-create and capability-binding endpoints.
 *
 * <p>Every suggested capability ({@link #tools}, {@link #skillIds},
 * {@link #primaryKbId}) is validated against the workspace catalog before the
 * draft is returned, so the wizard never offers a tool name or knowledge base
 * that does not actually exist.
 */
@Data
@Builder
public class AgentDraftVO {

    /** Display name for the new employee. */
    private String name;

    /** Emoji icon chosen to match the role. */
    private String icon;

    /** One-line description shown on the roster card. */
    private String description;

    /** Runtime kind: {@code react} or {@code plan_execute}. */
    private String agentType;

    /** Assembled persona / system prompt, editable before commit. */
    private String systemPrompt;

    /** Short role label, used for the card tagline preview. */
    private String role;

    /** Short goal statement, used for the card tagline preview. */
    private String goal;

    /** Suggested tags. */
    private List<String> tags;

    /** A few starter questions to seed the first conversation. */
    private List<String> recommendedQuestions;

    /**
     * Tool names to bind, drawn from the workspace's available tool catalog
     * (built-in and MCP). Hallucinated names are dropped during validation.
     */
    private List<String> tools;

    /** Skill ids to bind, validated against the workspace's enabled skills. */
    @JsonSerialize(contentUsing = ToStringSerializer.class)
    private List<Long> skillIds;

    /** Primary knowledge base id to attach, or null when none fits. */
    @JsonSerialize(using = ToStringSerializer.class)
    private Long primaryKbId;
}
