package vip.mate.auth.sso.model;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * OAuth2 state / bind-token 防重放存储。
 * <p>
 * 落 DB 而非内存: 多节点部署下 /authorize 与 /callback 可能落到不同节点,
 * 内存存储会导致 state 找不到、登录硬失败。{@code kind} 区分 {@code state}
 * (OAuth2 CSRF state) 与 {@code bind} (bind_token 的 jti)。
 *
 * <p>一次性消费: state 用 {@code UPDATE ... SET consumed=1 WHERE token=? AND consumed=0},
 * affected rows 必须 = 1; bind_token 的 jti 用 {@code INSERT} 撞 PK 实现首个消费成功。
 *
 * @author MateClaw Team
 */
@Data
@TableName("sso_state")
public class SsoStateEntity {

    @TableId(type = IdType.INPUT)
    private String token;

    /** state | bind */
    private String kind;

    private String provider;

    private Integer consumed;

    private LocalDateTime createdAt;
}
