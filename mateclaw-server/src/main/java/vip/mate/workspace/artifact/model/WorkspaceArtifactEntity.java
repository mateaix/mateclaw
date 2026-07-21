package vip.mate.workspace.artifact.model;

import com.baomidou.mybatisplus.annotation.FieldFill;
import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * Agent-level artifact metadata row.
 *
 * <p>Catalogs every file that enters an Agent's workspace — both Agent-produced
 * output (rendered documents, code-generated files) and user-uploaded inputs —
 * so a WebChat consumer can list the full cross-session file inventory
 * (issue #514). The bytes themselves live in their original store
 * ({@code GeneratedFileCache} for agent output, the chat-uploads dir for user
 * uploads); this row only carries provenance + the relative {@link #downloadUrl}
 * the consumer uses to fetch the file.
 *
 * <p><b>Ownership model:</b> a file belongs to its Agent/Workspace, NOT to a
 * session. {@link #conversationId} and {@link #sessionLabel} are provenance
 * labels — deleting a conversation must not delete its artifacts.
 */
@Data
@TableName("mate_workspace_artifact")
public class WorkspaceArtifactEntity {

    @TableId(type = IdType.ASSIGN_ID)
    private Long id;

    private Long workspaceId;

    private Long channelId;

    private Long agentId;

    /** Server-derived conversation id — the storage partition key, also used
     *  for session-filtered listing. */
    private String conversationId;

    /** The client-supplied sessionId (e.g. {@code sess_001}), kept separately
     *  from conversationId so the consumer can show the human-facing label. */
    private String sessionLabel;

    /** Which tool call produced this file. Null for user uploads. */
    private String toolCallId;

    /** Tool bean name that produced the file (e.g. {@code docxRenderTool}).
     *  Null for user uploads. */
    private String toolName;

    /** {@code agent} (Agent-produced) or {@code user} (user-uploaded). */
    private String source;

    /** Logical category derived from mime/extension: document / data / image /
     *  chart / other. */
    private String artifactType;

    /** File name including extension. */
    private String name;

    private String mime;

    private Long sizeBytes;

    /** Which backing store holds the bytes: {@code generated_cache} (agent
     *  output via GeneratedFileCache) or {@code upload} (user upload under the
     *  chat-uploads dir). */
    private String storageKind;

    /** Opaque reference into the backing store — a GeneratedFileCache uuid for
     *  {@code generated_cache}, or the storedName for {@code upload}. */
    private String storageRef;

    /** Relative download path the consumer appends to its base URL. */
    private String downloadUrl;

    @TableField(fill = FieldFill.INSERT)
    private LocalDateTime createTime;

    @TableField(fill = FieldFill.INSERT_UPDATE)
    private LocalDateTime updateTime;

    private Integer deleted;
}
