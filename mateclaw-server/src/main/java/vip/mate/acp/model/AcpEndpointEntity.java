package vip.mate.acp.model;

import com.baomidou.mybatisplus.annotation.FieldFill;
import com.baomidou.mybatisplus.annotation.FieldStrategy;
import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * RFC-090 Phase 7 — ACP (Agent Communication Protocol) endpoint registry.
 *
 * <p>Each row describes one external coding agent that MateClaw can
 * delegate to over stdio (codex / claude-code / opencode / qwen-code by
 * default). Bundled via Flyway V68 so the user only has to enable the
 * row once the matching CLI is on their PATH.
 */
@Data
@TableName("mate_acp_endpoint")
public class AcpEndpointEntity {

    @TableId(type = IdType.ASSIGN_ID)
    private Long id;

    /** Stable slug, lowercase. Referenced by skill manifests via {@code type: acp} + {@code endpoint:}. */
    private String name;

    private String displayName;
    private String description;

    /** Process command, e.g. {@code npx} or {@code codex}. */
    private String command;

    /**
     * JSON array of CLI args, e.g. {@code ["-y","@zed-industries/codex-acp"]}.
     * MyBatis Plus stores it as a string; the service layer parses on read.
     */
    @TableField(value = "args_json", updateStrategy = FieldStrategy.ALWAYS)
    private String argsJson;

    /** JSON object of environment variables to inject (merged onto System.getenv()). */
    @TableField(value = "env_json", updateStrategy = FieldStrategy.ALWAYS)
    private String envJson;

    /**
     * call_title | call_detail | update_detail (mirrors the ACP
     * {@code tool_parse_mode} convention). Drives how the wrapper
     * renders ACP tool-call events into MateClaw's stream protocol.
     */
    private String toolParseMode;

    private Boolean builtin;
    /** When true, accept the agent's tool calls without re-prompting the user. */
    private Boolean trusted;
    private Boolean enabled;

    /** Stdio buffer ceiling in bytes; defaults to 50 MiB. */
    private Long stdioBufferLimitBytes;

    /** Max wait for session/prompt, in seconds. Defaults to 300, capped at 3600. */
    private Integer promptTimeoutSeconds;

    /** UNKNOWN / OK / ERROR — last test result. */
    private String lastStatus;

    private LocalDateTime lastTestedAt;
    @TableField(value = "last_error", updateStrategy = FieldStrategy.ALWAYS)
    private String lastError;

    private Long workspaceId;

    @TableField(fill = FieldFill.INSERT)
    private LocalDateTime createTime;

    @TableField(fill = FieldFill.INSERT_UPDATE)
    private LocalDateTime updateTime;

    private Integer deleted;
}
