package vip.mate.wiki.model;

import com.baomidou.mybatisplus.annotation.FieldFill;
import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableLogic;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * A KB-scoped pipeline definition: a processing chain triggered by a pageType
 * event, executed under a concrete owner agent's permissions.
 *
 * @author MateClaw Team
 */
@Data
@TableName("mate_wiki_pipeline_definition")
public class WikiPipelineDefinitionEntity {

    @TableId(type = IdType.ASSIGN_ID)
    private Long id;

    private Long kbId;

    private String name;

    /** Agent whose identity (and RFC permissions) the steps run under. */
    private Long ownerAgentId;

    /** Trigger kind, e.g. {@code page_type_count}. */
    private String triggerType;

    /** Trigger configuration as JSON (e.g. page_type + threshold). */
    private String triggerConfigJson;

    /** Ordered step definitions as JSON. */
    private String stepsJson;

    /** Window (seconds) within which duplicate triggers collapse to one run. */
    private Integer dedupWindowSeconds;

    private Integer enabled;

    @TableField(fill = FieldFill.INSERT)
    private LocalDateTime createTime;

    @TableField(fill = FieldFill.INSERT_UPDATE)
    private LocalDateTime updateTime;

    @TableLogic
    private Integer deleted;
}
