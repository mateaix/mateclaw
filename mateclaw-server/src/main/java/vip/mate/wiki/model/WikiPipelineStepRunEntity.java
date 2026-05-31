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
 * One step invocation within a {@link WikiPipelineRunEntity}.
 *
 * @author MateClaw Team
 */
@Data
@TableName("mate_wiki_pipeline_step_run")
public class WikiPipelineStepRunEntity {

    @TableId(type = IdType.ASSIGN_ID)
    private Long id;

    private Long runId;

    /** Step id from the definition's steps_json. */
    private String stepId;

    /** {@code llm} / {@code skill} (Python is out of MVP). */
    private String executor;

    /** {@code pending} / {@code running} / {@code succeeded} / {@code failed}. */
    private String status;

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
