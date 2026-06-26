package vip.mate.auth.sso.model;

import com.baomidou.mybatisplus.annotation.FieldFill;
import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableLogic;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * 用户外部身份关联实体（SSO）。
 * <p>
 * 一个 {@code mate_user} 可绑定多个 IdP 身份；一个 {@code (provider, external_id)}
 * 至多归属一个用户。匹配优先级：union_id（跨应用唯一）优先，回退到 external_id。
 *
 * @author MateClaw Team
 */
@Data
@TableName("mate_user_external_identity")
public class ExternalIdentityEntity {

    @TableId(type = IdType.ASSIGN_ID)
    private Long id;

    private Long userId;

    /** 身份提供方标识: feishu / dingtalk / wecom / ... */
    private String provider;

    /** IdP 内用户标识, 通常是 open_id */
    private String externalId;

    /** 跨应用唯一标识 (飞书特有), nullable */
    private String unionId;

    private String externalName;
    private String externalAvatar;
    private String externalEmail;

    private LocalDateTime lastLoginAt;

    @TableField(fill = FieldFill.INSERT)
    private LocalDateTime createTime;

    @TableField(fill = FieldFill.INSERT_UPDATE)
    private LocalDateTime updateTime;

    /**
     * 逻辑删除 (与 wiki 系列表 @TableLogic 约定一致)。
     * <p>
     * 注意: {@code mate_user.deleted} 当前无 @TableLogic, 全局无 logic-delete-field,
     * 其 deleteById 是物理删 —— 本表的逻辑删除独立于 mate_user。解绑时 service 层
     * 改写 external_id / union_id 为 {@code <原值>_del_<timestamp>} 释放唯一约束。
     */
    @TableLogic
    private Integer deleted;
}
