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
 * Cached factual caption for one image, keyed by SHA-256 of raw bytes.
 *
 * <p>Backed by {@code mate_wiki_image_caption_cache}. Cache is shared
 * across all knowledge bases so an image uploaded twice in different
 * contexts costs exactly one vision call.
 *
 * @author MateClaw Team
 */
@Data
@TableName("mate_wiki_image_caption_cache")
public class WikiImageCaptionCacheEntity {

    @TableId(type = IdType.ASSIGN_ID)
    private Long id;

    /** SHA-256 hex digest (64 chars, lowercase) of the original image bytes. */
    private String imageSha256;

    /** Primary output: 2-4 sentence factual description. */
    private String caption;

    /** Best-effort OCR text recovered from the image; may be null. */
    private String visibleText;

    private String mimeType;

    /** Vendor-specific model identifier (e.g. {@code qwen-vl-max}). */
    private String captureModel;

    /** Provider id from the SPI registry (e.g. {@code dashscope-vision}). */
    private String providerId;

    /** Wall-clock duration of the original vision call, in milliseconds. */
    private Long durationMs;

    /** Bumped lazily on each lookup hit; failure to bump is silently ignored. */
    private Long hitCount;

    private LocalDateTime capturedAt;

    @TableField(fill = FieldFill.INSERT)
    private LocalDateTime createTime;

    @TableField(fill = FieldFill.INSERT_UPDATE)
    private LocalDateTime updateTime;

    @TableLogic
    private Integer deleted;
}
