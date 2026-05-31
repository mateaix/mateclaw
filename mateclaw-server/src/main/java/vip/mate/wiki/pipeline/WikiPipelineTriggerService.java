package vip.mate.wiki.pipeline;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import vip.mate.wiki.model.WikiPageEntity;
import vip.mate.wiki.model.WikiPipelineDefinitionEntity;
import vip.mate.wiki.repository.WikiPageMapper;
import vip.mate.wiki.repository.WikiPipelineDefinitionMapper;

import java.util.List;

/**
 * Evaluates {@code page_type_count} pipeline triggers: when a KB accumulates a
 * multiple of the configured threshold of a given pageType, the matching
 * pipeline definitions fire once per threshold bucket.
 *
 * <p>The bucket is {@code count / threshold}; the run table's unique key makes
 * each bucket fire at most once, so re-evaluating on every page create is safe
 * and idempotent across instances.
 *
 * @author MateClaw Team
 */
@Slf4j
@Service
public class WikiPipelineTriggerService {

    private static final String TRIGGER_PAGE_TYPE_COUNT = "page_type_count";

    private final WikiPipelineDefinitionMapper definitionMapper;
    private final WikiPipelineService pipelineService;
    private final WikiPageMapper pageMapper;
    private final ObjectMapper objectMapper;

    public WikiPipelineTriggerService(WikiPipelineDefinitionMapper definitionMapper,
                                      WikiPipelineService pipelineService,
                                      WikiPageMapper pageMapper,
                                      ObjectMapper objectMapper) {
        this.definitionMapper = definitionMapper;
        this.pipelineService = pipelineService;
        this.pageMapper = pageMapper;
        this.objectMapper = objectMapper;
    }

    /**
     * Re-evaluate count-threshold pipelines for a KB / pageType. Returns the
     * number of runs actually started (0 when no threshold bucket was newly
     * crossed). Safe to call after every page create.
     */
    /**
     * Fire {@code page_created} definitions once per matching page creation
     * (deduped by page id). Optional {@code page_type} in the trigger config
     * narrows which page types fire. Returns the number of runs started.
     */
    public int onPageCreated(Long kbId, String pageType, Long pageId) {
        if (kbId == null || pageType == null || pageId == null) {
            return 0;
        }
        List<WikiPipelineDefinitionEntity> defs = definitionMapper.selectList(
                new LambdaQueryWrapper<WikiPipelineDefinitionEntity>()
                        .eq(WikiPipelineDefinitionEntity::getKbId, kbId)
                        .eq(WikiPipelineDefinitionEntity::getTriggerType, "page_created")
                        .eq(WikiPipelineDefinitionEntity::getEnabled, 1));
        int started = 0;
        for (WikiPipelineDefinitionEntity def : defs) {
            TriggerConfig cfg = parseConfig(def.getTriggerConfigJson());
            if (cfg != null && cfg.pageType != null && !pageType.equalsIgnoreCase(cfg.pageType)) {
                continue; // type filter set and doesn't match
            }
            String input = "{\"pageType\":\"" + pageType + "\",\"pageId\":\"" + pageId + "\"}";
            WikiPipelineService.RunOutcome outcome =
                    pipelineService.execute(def, pageType, "page:" + pageId, input);
            if (!outcome.duplicate() && outcome.run() != null) {
                started++;
            }
        }
        return started;
    }

    public int onPageTypeCount(Long kbId, String pageType) {
        if (kbId == null || pageType == null || pageType.isBlank()) {
            return 0;
        }
        List<WikiPipelineDefinitionEntity> defs = definitionMapper.selectList(
                new LambdaQueryWrapper<WikiPipelineDefinitionEntity>()
                        .eq(WikiPipelineDefinitionEntity::getKbId, kbId)
                        .eq(WikiPipelineDefinitionEntity::getTriggerType, TRIGGER_PAGE_TYPE_COUNT)
                        .eq(WikiPipelineDefinitionEntity::getEnabled, 1));
        if (defs.isEmpty()) {
            return 0;
        }
        int started = 0;
        for (WikiPipelineDefinitionEntity def : defs) {
            TriggerConfig cfg = parseConfig(def.getTriggerConfigJson());
            if (cfg == null || cfg.threshold <= 0 || !pageType.equalsIgnoreCase(cfg.pageType)) {
                continue;
            }
            long count = countPagesOfType(kbId, cfg.pageType);
            long bucket = count / cfg.threshold;
            if (bucket < 1) {
                continue; // threshold not reached yet
            }
            String input = "{\"pageType\":\"" + cfg.pageType + "\",\"count\":" + count + "}";
            WikiPipelineService.RunOutcome outcome =
                    pipelineService.execute(def, cfg.pageType, String.valueOf(bucket), input);
            if (!outcome.duplicate() && outcome.run() != null) {
                started++;
                log.info("[WikiPipeline] trigger fired: def={} pageType={} count={} bucket={}",
                        def.getId(), cfg.pageType, count, bucket);
            }
        }
        return started;
    }

    private long countPagesOfType(Long kbId, String pageType) {
        return pageMapper.selectCount(new LambdaQueryWrapper<WikiPageEntity>()
                .eq(WikiPageEntity::getKbId, kbId)
                .eq(WikiPageEntity::getPageType, pageType.toLowerCase())
                .and(w -> w.ne(WikiPageEntity::getArchived, 1).or().isNull(WikiPageEntity::getArchived)));
    }

    private TriggerConfig parseConfig(String json) {
        if (json == null || json.isBlank()) {
            return null;
        }
        try {
            JsonNode node = objectMapper.readTree(json);
            TriggerConfig cfg = new TriggerConfig();
            cfg.pageType = node.path("page_type").asText(null);
            cfg.threshold = node.path("threshold").asInt(0);
            return cfg.pageType == null ? null : cfg;
        } catch (Exception e) {
            log.warn("[WikiPipeline] bad trigger config: {}", e.getMessage());
            return null;
        }
    }

    private static final class TriggerConfig {
        private String pageType;
        private int threshold;
    }
}
