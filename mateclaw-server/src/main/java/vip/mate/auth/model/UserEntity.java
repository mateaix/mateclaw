package vip.mate.auth.model;

import com.baomidou.mybatisplus.annotation.*;
import com.fasterxml.jackson.annotation.JsonProperty;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * 用户实体
 *
 * @author MateClaw Team
 */
@Data
@TableName("mate_user")
public class UserEntity {

    @TableId(type = IdType.ASSIGN_ID)
    private Long id;

    /** 用户名 */
    private String username;

    /**
     * 密码（BCrypt加密）。WRITE_ONLY: accepted from request bodies (login / user
     * creation) but never serialized into a response, so the bcrypt hash cannot
     * leak via endpoints that return UserEntity (e.g. GET /auth/users).
     */
    @JsonProperty(access = JsonProperty.Access.WRITE_ONLY)
    private String password;

    /** 昵称 */
    private String nickname;

    /** 头像URL */
    private String avatar;

    /** 邮箱 */
    private String email;

    /** 角色：admin / user */
    private String role;

    /** 是否启用 */
    private Boolean enabled;

    @TableField(fill = FieldFill.INSERT)
    private LocalDateTime createTime;

    @TableField(fill = FieldFill.INSERT_UPDATE)
    private LocalDateTime updateTime;

    private Integer deleted;
}
