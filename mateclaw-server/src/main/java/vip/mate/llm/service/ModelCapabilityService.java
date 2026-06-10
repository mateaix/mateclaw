package vip.mate.llm.service;

import cn.hutool.core.util.StrUtil;
import cn.hutool.json.JSONUtil;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.Collections;
import java.util.Comparator;
import java.util.EnumSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Resolves which modalities (vision / video / audio) a chat model can natively consume.
 * <p>
 * Two-layer lookup:
 * <ol>
 *   <li>Explicit DB declaration on {@code mate_model_config.modalities} (JSON array of
 *       lowercase modality names) — user opt-in, takes precedence.</li>
 *   <li>Built-in heuristics keyed by lowercase model-name prefix; longest match wins.
 *       Granularity is per-model (e.g. {@code glm-4v-plus} supports video but
 *       {@code glm-4v} does not), not per-family.</li>
 * </ol>
 * <p>
 * Resolved at agent build time and cached per agent — see
 * {@link vip.mate.agent.AgentGraphBuilder#buildAgent}.
 */
@Slf4j
@Service
public class ModelCapabilityService {

    public enum Modality {
        TEXT, VISION, VIDEO, AUDIO
    }

    private static final Map<String, EnumSet<Modality>> BUILTIN;

    static {
        // Capability data current as of 2026-04. Keys are lowercase model-name prefixes;
        // longest match wins. When a vendor ships a new model, prefer extending this
        // table over relying on DB overrides — overrides are the per-deployment safety
        // valve, but new models should "just work" out of the box.
        Map<String, EnumSet<Modality>> m = new LinkedHashMap<>();

        // ===== Zhipu GLM =====
        // Granularity matters: 4v (no video) vs 4v-plus (video). 4.1v / 4.5v / 4.6v / 5v
        // lines are all video-capable across variants (turbo / flash / thinking).
        m.put("glm-5v",        EnumSet.of(Modality.VISION, Modality.VIDEO));
        m.put("glm-4.6v",      EnumSet.of(Modality.VISION, Modality.VIDEO));
        m.put("glm-4.5v",      EnumSet.of(Modality.VISION, Modality.VIDEO));
        m.put("glm-4.1v",      EnumSet.of(Modality.VISION, Modality.VIDEO));
        m.put("glm-4v-plus",   EnumSet.of(Modality.VISION, Modality.VIDEO));
        m.put("glm-4v-flash",  EnumSet.of(Modality.VISION));
        m.put("glm-4v",        EnumSet.of(Modality.VISION));

        // ===== Alibaba Qwen-VL / Omni =====
        // Qwen3-VL (Sept 2025+, all sizes 2B/4B/8B/32B/30B-A3B/235B-A22B) and Qwen3.5-Omni
        // (Mar 2026) natively handle video. Older qwen-vl-plus / qwen-vl base are image-only.
        m.put("qwen3.5-omni",  EnumSet.of(Modality.VISION, Modality.VIDEO, Modality.AUDIO));
        m.put("qwen3-omni",    EnumSet.of(Modality.VISION, Modality.VIDEO, Modality.AUDIO));
        m.put("qwen2.5-omni",  EnumSet.of(Modality.VISION, Modality.VIDEO, Modality.AUDIO));
        m.put("qwen3-vl",      EnumSet.of(Modality.VISION, Modality.VIDEO));
        m.put("qwen2.5-vl",    EnumSet.of(Modality.VISION, Modality.VIDEO));
        m.put("qwen2-vl",      EnumSet.of(Modality.VISION, Modality.VIDEO));
        m.put("qwen-vl-max",   EnumSet.of(Modality.VISION, Modality.VIDEO));
        m.put("qwen-vl-plus",  EnumSet.of(Modality.VISION));
        m.put("qwen-vl",       EnumSet.of(Modality.VISION));

        // ===== OpenAI =====
        // IMPORTANT: as of 2026-04 the OpenAI Chat Completions / Responses APIs do NOT
        // accept video files natively for any model — including gpt-4o and gpt-5.x. The
        // recommended workflow is still client-side frame extraction. So vision yes,
        // video no, regardless of marketing copy that says "multimodal video".
        m.put("gpt-5",         EnumSet.of(Modality.VISION));
        m.put("gpt-4.1",       EnumSet.of(Modality.VISION));
        m.put("gpt-4o",        EnumSet.of(Modality.VISION));
        m.put("gpt-4-vision",  EnumSet.of(Modality.VISION));

        // ===== Google Gemini =====
        // Native multimodal across the 1.5/2/2.5 lines, including pro/flash/flash-lite.
        m.put("gemini-2.5",    EnumSet.of(Modality.VISION, Modality.VIDEO, Modality.AUDIO));
        m.put("gemini-2",      EnumSet.of(Modality.VISION, Modality.VIDEO, Modality.AUDIO));
        m.put("gemini-1.5",    EnumSet.of(Modality.VISION, Modality.VIDEO, Modality.AUDIO));

        // ===== Anthropic Claude =====
        // Vision yes (image), native video no — Anthropic's API only accepts images.
        m.put("claude-fable",  EnumSet.of(Modality.VISION));
        m.put("claude-4.7",    EnumSet.of(Modality.VISION));
        m.put("claude-4.5",    EnumSet.of(Modality.VISION));
        m.put("claude-4",      EnumSet.of(Modality.VISION));
        m.put("claude-3.7",    EnumSet.of(Modality.VISION));
        m.put("claude-3.5",    EnumSet.of(Modality.VISION));
        m.put("claude-opus",   EnumSet.of(Modality.VISION));
        m.put("claude-sonnet", EnumSet.of(Modality.VISION));
        m.put("claude-haiku",  EnumSet.of(Modality.VISION));

        // ===== DeepSeek =====
        // V4 (Apr 2026) is the first DeepSeek line with native multimodal — image + video.
        // V3 and earlier are text-only (no entry → defaults to text only).
        m.put("deepseek-v4",   EnumSet.of(Modality.VISION, Modality.VIDEO));

        // ===== ByteDance Doubao / Seed =====
        // Seed 2.0 Pro (Feb 2026) handles hour-long videos. Seed1.5-VL also supports video.
        m.put("doubao-seed-2", EnumSet.of(Modality.VISION, Modality.VIDEO));
        m.put("seed-1.5-vl",   EnumSet.of(Modality.VISION, Modality.VIDEO));
        m.put("doubao-vision", EnumSet.of(Modality.VISION));

        // ===== Moonshot Kimi =====
        // K2.6 (Apr 2026) added video; K2.5 was image-only.
        m.put("kimi-k2.6",     EnumSet.of(Modality.VISION, Modality.VIDEO));
        m.put("kimi-k2.5",     EnumSet.of(Modality.VISION));

        // ===== MiniMax =====
        // MiniMax-VL-01 / abab-vision are vision multimodal. Native video INPUT is not
        // documented in the platform API — Hailuo / video-01 are generation models, not input.
        m.put("minimax-vl",    EnumSet.of(Modality.VISION));
        m.put("abab-vision",   EnumSet.of(Modality.VISION));

        // ===== Tencent Hunyuan =====
        // Hunyuan-Vision-1.5 / Hunyuan-Large-Vision are image-only vision LLMs.
        // HunyuanVideo / HunyuanCustom are video GENERATION (output), not input.
        m.put("hunyuan-large-vision", EnumSet.of(Modality.VISION));
        m.put("hunyuan-vision",       EnumSet.of(Modality.VISION));

        // ===== xAI Grok =====
        // Grok 2/3/4 accept image input. Grok Imagine is video generation, not video input.
        m.put("grok",          EnumSet.of(Modality.VISION));

        // ===== Mistral =====
        // Pixtral / Mistral Small 4 take images via the Pixtral vision stack. No native video.
        m.put("pixtral",       EnumSet.of(Modality.VISION));
        m.put("mistral-small-4", EnumSet.of(Modality.VISION));

        // ===== Meta Llama =====
        // Llama 4 (Scout / Maverick, Apr 2026) is the first Llama line natively trained on
        // text + image + video. Llama 3.x was image-only via separate adapters.
        m.put("llama-4",       EnumSet.of(Modality.VISION, Modality.VIDEO));

        BUILTIN = Collections.unmodifiableMap(m);
    }

    /**
     * Resolve the full capability set for a model.
     *
     * @param modelName       the chat model identifier (e.g. {@code glm-4v-plus})
     * @param modalitiesJson  optional JSON array of declared modality names (case-insensitive),
     *                        e.g. {@code ["vision","video"]}; when present and parseable, takes
     *                        precedence over the heuristic table; pass {@code null} or blank to
     *                        defer entirely to heuristics
     * @return an {@link EnumSet} of modalities the model can consume; always non-null,
     *         {@link Modality#TEXT} is implicit and always included
     */
    public EnumSet<Modality> resolve(String modelName, String modalitiesJson) {
        EnumSet<Modality> result = EnumSet.of(Modality.TEXT);

        if (StrUtil.isNotBlank(modalitiesJson)) {
            EnumSet<Modality> declared = parseDeclared(modalitiesJson, modelName);
            if (declared != null) {
                result.addAll(declared);
                return result;
            }
            // parse failed → fall through to heuristics
        }

        if (StrUtil.isBlank(modelName)) {
            return result;
        }

        String lowered = modelName.toLowerCase();
        BUILTIN.entrySet().stream()
                .filter(e -> lowered.startsWith(e.getKey()))
                .max(Comparator.comparingInt(e -> e.getKey().length()))
                .map(Map.Entry::getValue)
                .ifPresent(result::addAll);
        return result;
    }

    /**
     * @return {@code true} if the model supports the given modality
     */
    public boolean supports(String modelName, String modalitiesJson, Modality modality) {
        return resolve(modelName, modalitiesJson).contains(modality);
    }

    private EnumSet<Modality> parseDeclared(String json, String modelName) {
        try {
            List<String> declared = JSONUtil.toList(json, String.class);
            EnumSet<Modality> set = EnumSet.noneOf(Modality.class);
            for (String name : declared) {
                if (StrUtil.isBlank(name)) continue;
                try {
                    set.add(Modality.valueOf(name.trim().toUpperCase()));
                } catch (IllegalArgumentException ignore) {
                    log.warn("Unknown modality '{}' declared on model '{}'", name, modelName);
                }
            }
            return set;
        } catch (Exception e) {
            log.warn("Invalid modalities JSON for model '{}': {} — falling back to heuristics",
                    modelName, json);
            return null;
        }
    }
}
