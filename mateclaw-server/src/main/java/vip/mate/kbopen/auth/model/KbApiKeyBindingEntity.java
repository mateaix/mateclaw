package vip.mate.kbopen.auth.model;

import com.baomidou.mybatisplus.annotation.FieldFill;
import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * Many-to-many binding between an API Key and a Knowledge Base.
 *
 * <p>An empty binding set means <strong>zero access</strong> (R3) — unlike
 * internal {@code AgentWikiKbBinding} where empty means "all KBs". This
 * prevents an external key from silently gaining access to the entire
 * workspace or auto-including newly created KBs.
 */
@Data
@TableName("mate_kb_api_key_binding")
public class KbApiKeyBindingEntity {

    @TableId(type = IdType.ASSIGN_ID)
    private Long id;

    private Long apiKeyId;

    private Long kbId;

    @TableField(fill = FieldFill.INSERT)
    private LocalDateTime createTime;
}
