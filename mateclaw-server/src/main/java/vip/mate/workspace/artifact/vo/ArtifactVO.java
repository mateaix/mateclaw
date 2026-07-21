package vip.mate.workspace.artifact.vo;

import com.fasterxml.jackson.annotation.JsonInclude;
import lombok.Builder;
import lombok.Data;

/**
 * Consumer-facing representation of a workspace artifact (issue #514).
 *
 * <p>Strips internal storage details ({@code storageKind}/{@code storageRef})
 * and exposes only what the WebChat panel needs: identity, provenance, and a
 * relative {@link #downloadUrl}. The {@link #id} is returned as a string so the
 * snowflake BIGINT survives JS number precision loss in the browser.
 *
 * @author MateClaw Team
 */
@Data
@Builder
@JsonInclude(JsonInclude.Include.NON_NULL)
public class ArtifactVO {

    /** Artifact id (stringified snowflake). */
    private String id;

    /** File name including extension. */
    private String name;

    /** {@code agent} (Agent-produced) or {@code user} (user-uploaded). */
    private String source;

    /** Logical category: document / data / image / chart / other. */
    private String type;

    /** Size in bytes. */
    private Long size;

    /** MIME type. */
    private String mime;

    /** Relative download path (consumer prepends its base URL). */
    private String downloadUrl;

    /** Provenance: which session produced/received this file. */
    private String sessionId;

    /** Provenance: which tool call produced this file (null for user uploads). */
    private String toolCallId;

    /** ISO-8601 creation timestamp (UTC). */
    private String createdAt;
}
