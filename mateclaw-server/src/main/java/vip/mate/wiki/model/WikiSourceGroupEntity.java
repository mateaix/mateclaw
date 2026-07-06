package vip.mate.wiki.model;

import com.baomidou.mybatisplus.annotation.*;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * Wiki 来源分组实体：一个知识库的 sourceDirectory 拆分出的独立可管理路径/glob。
 *
 * @author MateClaw Team
 */
@Data
@TableName("mate_wiki_source_group")
public class WikiSourceGroupEntity {

    @TableId(type = IdType.ASSIGN_ID)
    private Long id;

    /** 所属知识库 ID */
    private Long kbId;

    /** 冗余自 kb.workspaceId，便于跨 KB 的按工作区查询/隔离校验 */
    private Long workspaceId;

    /** 分组别名（用户可见名称） */
    private String alias;

    /** 单条路径或 glob 模式，语义与 WikiSourcePathValidator 校验的单行一致 */
    private String path;

    /** 可选的扩展名/glob 过滤器；null = 沿用 WikiDirectoryScanService 的默认支持扩展名 */
    private String fileFilter;

    /** incremental（默认）| full，见 WikiDirectoryScanService#scanGroup */
    private String scanMode;

    /** 可选的 cron 表达式，供后续定时调度使用；本轮先落库不接调度器 */
    private String cronExpr;

    /** 1=启用自动扫描，0=禁用 */
    private Integer enabled;

    /** 最近一次扫描完成时间 */
    private LocalDateTime lastScanAt;

    @TableField(fill = FieldFill.INSERT)
    private LocalDateTime createTime;

    @TableField(fill = FieldFill.INSERT_UPDATE)
    private LocalDateTime updateTime;

    private Integer deleted;
}
