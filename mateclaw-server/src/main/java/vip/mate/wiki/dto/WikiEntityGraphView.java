package vip.mate.wiki.dto;

import lombok.Data;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;

/**
 * Ego-graph around one entity: the center node, its neighbor entity nodes,
 * the relation edges connecting them, and the wiki pages that mention the
 * center entity (the bridge from the entity layer to the page layer).
 *
 * @author MateClaw Team
 */
@Data
public class WikiEntityGraphView {

    private WikiEntityView center;
    private List<WikiEntityView> nodes = new ArrayList<>();
    private List<Edge> edges = new ArrayList<>();
    private List<PageRef> pages = new ArrayList<>();

    @Data
    public static class Edge {
        private Long id;
        private Long subjectEntityId;
        private String predicate;
        private Long objectEntityId;
        private String evidence;
        private BigDecimal confidence;
    }

    @Data
    public static class PageRef {
        private Long pageId;
        private String slug;
        private String title;
    }
}
