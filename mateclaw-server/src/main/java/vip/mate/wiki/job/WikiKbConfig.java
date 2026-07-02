package vip.mate.wiki.job;

import lombok.Data;

import java.util.List;
import java.util.Map;

/**
 * RFC-030: Per-KB processing configuration POJO, deserialized from
 * {@link vip.mate.wiki.model.WikiKnowledgeBaseEntity#getConfigContent()}.
 */
@Data
public class WikiKbConfig {

    /**
     * RFC-051: ingest pipeline mode for this KB. {@code "lazy"} skips page
     * generation on upload (chunk + embed only); {@code "eager"} runs the
     * legacy heavy ingest pipeline. {@code null} means caller should apply
     * its own default. PR-1a only adds the field; the lazy branch lands
     * in PR-1b.
     */
    private String ingestMode;

    /**
     * RFC-051: KB-level default chat model. Used by routing as the
     * intermediate fallback between {@link #stepModels} and the system
     * default. {@code null} means the caller should fall through to the
     * system default. The frontend already writes this field; before
     * PR-1a it had no Java field to deserialize into and was silently
     * dropped.
     */
    private Long wikiDefaultModelId;

    /**
     * KB-level lightweight chat model for the cheap, high-volume steps
     * (route / enrich / summary / entity extraction). When set, those steps
     * run on this cheaper model instead of the KB/system default, cutting token
     * spend without touching page generation quality. {@code null} falls back to
     * the system-level light model, then to normal routing. Strong steps
     * (create_page / merge_page) ignore this field.
     */
    private Long wikiLightModelId;

    /** Per-step model overrides: "heavy_ingest.create_page" → modelId */
    private Map<String, Long> stepModels;

    /** Global fallback model chain for all steps in this KB */
    private List<Long> fallbackModelIds;

    /**
     * RFC-051 PR-6b follow-up: per-KB opt-in for structured route output.
     * <p>
     * Different KBs run different chat models — DashScope and Anthropic
     * follow the format hint reliably; weaker locally-served Ollama models
     * may not. Keeping the flag per-KB lets users flip it where it pays
     * off. {@code null} (the common case) falls back to
     * {@link vip.mate.wiki.WikiProperties#isUseStructuredRoute()}.
     */
    private Boolean useStructuredRoute;

    /**
     * KB-level default read policy applied when an agent has no
     * {@code mate_wiki_agent_page_type_permission} rows for this KB.
     * {@code "allow_all"} (the default when {@code null}) keeps existing
     * behaviour — every agent reads every pageType. {@code "deny_all"} flips
     * the default closed so a professional KB can require each readable
     * pageType to be granted explicitly per agent.
     */
    private String defaultReadPolicy;

    /**
     * Opt-in for entity-level knowledge graph extraction on this KB. When
     * {@code true}, an extraction pass runs after ingest/embedding to pull
     * named entities (person, organization, location, ...) and their
     * relations from source chunks into the {@code mate_wiki_entity*} tables.
     * {@code null} or {@code false} keeps the legacy behaviour (page graph
     * only). Off by default because extraction adds LLM calls per chunk.
     */
    private Boolean entityExtractionEnabled;

    /**
     * Optional whitelist of entity types to extract, e.g.
     * {@code ["person","organization","location"]}. {@code null} or empty
     * lets the extractor use its built-in default type set.
     */
    private List<String> entityTypes;
}
