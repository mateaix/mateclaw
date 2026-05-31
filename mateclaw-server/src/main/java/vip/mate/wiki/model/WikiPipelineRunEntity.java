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
 * One execution instance of a pipeline definition. The
 * (definition_id, trigger_type, trigger_subject, trigger_bucket) unique key
 * makes duplicate triggers idempotent across instances.
 *
 * @author MateClaw Team
 */
@Data
@TableName("mate_wiki_pipeline_run")
public class WikiPipelineRunEntity {

    @TableId(type = IdType.ASSIGN_ID)
    private Long id;

    private Long definitionId;

    private Long kbId;

    /** {@code pending} / {@code running} / {@code succeeded} / {@code failed}. */
    private String status;

    private String triggerType;

    /** The entity the trigger fired on, e.g. a pageType name. */
    private String triggerSubject;

    /** Dedup envelope (time/threshold bucket) for idempotency. */
    private String triggerBucket;

    private String triggerPayloadJson;

    private String inputJson;

    private String outputJson;

    private String errorMessage;

    private LocalDateTime startedAt;

    private LocalDateTime finishedAt;

    @TableField(fill = FieldFill.INSERT)
    private LocalDateTime createTime;

    @TableLogic
    private Integer deleted;
}
