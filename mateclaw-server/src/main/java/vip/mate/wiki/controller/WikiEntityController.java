package vip.mate.wiki.controller;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import vip.mate.wiki.dto.WikiEntityGraphView;
import vip.mate.wiki.dto.WikiEntityView;
import vip.mate.wiki.service.WikiEntityExtractionService;
import vip.mate.wiki.service.WikiEntityGraphService;
import vip.mate.wiki.service.WikiProcessingService;

import java.util.List;
import java.util.Map;

/**
 * Read and manual-trigger endpoints for the entity-level knowledge graph.
 *
 * @author MateClaw Team
 */
@Slf4j
@RestController
@RequestMapping("/api/v1/wiki")
@RequiredArgsConstructor
public class WikiEntityController {

    private final WikiEntityGraphService graphService;
    private final WikiEntityExtractionService extractionService;

    /** List entities in a KB, optionally filtered by type, ranked by salience. */
    @GetMapping("/kb/{kbId}/entities")
    public List<WikiEntityView> listEntities(@PathVariable Long kbId,
                                             @RequestParam(required = false) String type,
                                             @RequestParam(defaultValue = "100") int limit) {
        return graphService.listEntities(kbId, type, limit);
    }

    /** Whole-KB entity graph: top entities by salience plus the edges among them. */
    @GetMapping("/kb/{kbId}/entity-graph")
    public WikiEntityGraphView kbEntityGraph(@PathVariable Long kbId,
                                             @RequestParam(defaultValue = "150") int limit) {
        return graphService.graph(kbId, limit);
    }

    /** Ego-graph around a single entity: neighbors, edges, and mentioning pages. */
    @GetMapping("/kb/{kbId}/entities/{entityId}/graph")
    public WikiEntityGraphView entityGraph(@PathVariable Long kbId,
                                           @PathVariable Long entityId,
                                           @RequestParam(defaultValue = "50") int limit) {
        return graphService.ego(kbId, entityId, limit);
    }

    /**
     * Manually trigger an entity-extraction pass for a KB. Runs on the wiki
     * executor so the request returns immediately.
     *
     * @param force when true, re-extract chunks that already have mentions
     */
    @PostMapping("/kb/{kbId}/entities/extract")
    public Map<String, Object> extract(@PathVariable Long kbId,
                                       @RequestParam(defaultValue = "false") boolean force) {
        WikiProcessingService.WIKI_EXECUTOR.submit(() -> {
            try {
                int count = extractionService.extractForKb(kbId, force);
                log.info("[WikiEntity] Manual extraction completed: kbId={}, entities={}", kbId, count);
            } catch (Exception e) {
                log.warn("[WikiEntity] Manual extraction failed for kbId={}: {}", kbId, e.getMessage());
            }
        });
        return Map.of("status", "started", "kbId", kbId);
    }
}
