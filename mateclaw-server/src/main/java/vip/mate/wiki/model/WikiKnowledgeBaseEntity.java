package vip.mate.wiki.model;

import com.baomidou.mybatisplus.annotation.*;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * Wiki 知识库实体
 *
 * @author MateClaw Team
 */
@Data
@TableName("mate_wiki_knowledge_base")
public class WikiKnowledgeBaseEntity {

    @TableId(type = IdType.ASSIGN_ID)
    private Long id;

    /** 知识库名称 */
    private String name;

    /** 描述 */
    private String description;

    /** 关联的 Agent ID（可选） */
    private Long agentId;

    /** Wiki 处理规则配置（WIKI.md 等效物） */
    @TableField(updateStrategy = FieldStrategy.ALWAYS)
    private String configContent;

    /** 关联的本地目录路径（可选，用于批量扫描导入） */
    private String sourceDirectory;

    /** 状态：active / processing / error */
    private String status;

    /** Wiki 页面数量 */
    private Integer pageCount;

    /** 原始材料数量 */
    private Integer rawCount;

    /** 所属工作区 ID（默认 1 = default） */
    private Long workspaceId;

    /**
     * 绑定的 Embedding 模型 ID（mate_model_config.id，model_type='embedding'）。
     * <p>
     * NULL = 使用系统默认（mate_system_setting 的 embedding.default.model.id），
     * 再无则取任意 enabled 的 embedding 模型，最终全无则语义搜索降级为不可用。
     */
    @TableField(updateStrategy = FieldStrategy.ALWAYS)
    private Long embeddingModelId;

    @TableField(fill = FieldFill.INSERT)
    private LocalDateTime createTime;

    @TableField(fill = FieldFill.INSERT_UPDATE)
    private LocalDateTime updateTime;

    private Integer deleted;
}
