package vip.mate.wiki.model;

import com.baomidou.mybatisplus.annotation.*;
import lombok.Data;

import java.math.BigDecimal;
import java.time.LocalDateTime;

/**
 * RFC-029: Wiki page citation entity — links a page to the chunks it was derived from.
 */
@Data
@TableName("mate_wiki_page_citation")
public class WikiPageCitationEntity {

    @TableId(type = IdType.AUTO)
    private Long id;

    private Long pageId;

    private Long chunkId;

    private Integer paragraphIdx;

    private String anchorText;

    private BigDecimal confidence;

    private String createdBy;

    @TableField(fill = FieldFill.INSERT)
    private LocalDateTime createTime;

    @TableLogic
    private Integer deleted;
}
