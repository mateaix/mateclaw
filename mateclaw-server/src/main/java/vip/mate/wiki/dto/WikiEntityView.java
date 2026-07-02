package vip.mate.wiki.dto;

import lombok.Data;

import java.math.BigDecimal;
import java.util.List;

/**
 * API-facing projection of a canonical entity node. Excludes the raw
 * embedding vector and other internal columns.
 *
 * @author MateClaw Team
 */
@Data
public class WikiEntityView {
    private Long id;
    private Long kbId;
    private String canonicalName;
    private String type;
    private List<String> aliases;
    private String description;
    private BigDecimal salience;
    private Integer mentionCount;
}
