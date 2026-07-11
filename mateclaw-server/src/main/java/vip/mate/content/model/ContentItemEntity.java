package vip.mate.content.model;

import com.baomidou.mybatisplus.annotation.FieldFill;
import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * A produced content item (公众号 article / 小红书 note) tracked across its
 * lifecycle. Backs the content calendar so the daily scheduler can avoid
 * repeating topics and so publishing is idempotent and auditable.
 *
 * <p>{@code topicFingerprint} is a stable hash of the normalized topic; it is the
 * dedup key for "did we already cover this recently". {@code status} moves
 * {@code draft/packaged → published} (or {@code failed}).
 */
@Data
@TableName("mate_content_item")
public class ContentItemEntity {

    @TableId(type = IdType.ASSIGN_ID)
    private Long id;

    /** Owning workspace; nullable for single-user setups. */
    private Long workspaceId;

    /** Target platform: {@code gzh} (公众号) or {@code xhs} (小红书). */
    private String platform;

    /** The chosen topic, human-readable. */
    private String topic;

    /** Stable hash of the normalized topic — the recency/dedup key. */
    private String topicFingerprint;

    /** Final title of the produced piece. */
    private String title;

    /** Lifecycle: {@code draft} | {@code packaged} | {@code published} | {@code failed}. */
    private String status;

    /** Platform-side reference: draft media_id / publish_id, when applicable. */
    private String externalRef;

    /** Online-preview link handed to the user. */
    private String previewUrl;

    @TableField(fill = FieldFill.INSERT)
    private LocalDateTime createTime;

    @TableField(fill = FieldFill.INSERT_UPDATE)
    private LocalDateTime updateTime;

    /** Set when the item is marked published. */
    private LocalDateTime publishTime;

    private Integer deleted;
}
