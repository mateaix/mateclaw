package vip.mate.workspace.artifact.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.metadata.IPage;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.lang.Nullable;
import org.springframework.stereotype.Service;
import vip.mate.workspace.artifact.model.WorkspaceArtifactEntity;
import vip.mate.workspace.artifact.repository.WorkspaceArtifactMapper;
import vip.mate.workspace.artifact.vo.ArtifactPageVO;
import vip.mate.workspace.artifact.vo.ArtifactVO;

import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.List;

/**
 * Registers files into an Agent's workspace catalog and lists them (issue #514).
 *
 * <p>Two sources feed the catalog:
 * <ul>
 *   <li>{@code agent} — files produced by document-render tools / code-execution
 *       tools, registered via {@link GeneratedFileLink} / {@code WorkspaceArtifactSurfacer}.</li>
 *   <li>{@code user} — files uploaded through the WebChat {@code /upload}
 *       endpoint, registered inline in {@code WebChatController}.</li>
 * </ul>
 *
 * <p>Registration is best-effort by design: a metadata-write failure must never
 * fail the file-producing tool run or the upload — the file is already safely
 * stored; only the catalog row is lost, which is a degraded-but-acceptable
 * outcome (the consumer simply won't list that one file).
 *
 * @author MateClaw Team
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class WorkspaceArtifactService {

    /** {@code source} values. */
    public static final String SOURCE_AGENT = "agent";
    public static final String SOURCE_USER = "user";

    /** {@code storageKind} values. */
    public static final String STORAGE_GENERATED_CACHE = "generated_cache";
    public static final String STORAGE_UPLOAD = "upload";

    /** Max page size accepted by the list endpoint. */
    private static final int MAX_PAGE_SIZE = 200;
    private static final int DEFAULT_PAGE_SIZE = 50;

    private static final DateTimeFormatter ISO_UTC =
            DateTimeFormatter.ISO_OFFSET_DATE_TIME.withZone(ZoneOffset.UTC);

    private final WorkspaceArtifactMapper mapper;

    /**
     * Record a metadata row for an artifact. Best-effort: swallows persistence
     * errors so the caller (a tool run or an upload) is never failed by a
     * catalog write.
     *
     * @return the persisted id, or {@code null} if the write failed.
     */
    public Long register(WorkspaceArtifactEntity entity) {
        try {
            mapper.insert(entity);
            return entity.getId();
        } catch (Exception e) {
            log.warn("[WorkspaceArtifact] failed to register row (name={}, conv={}): {}",
                    entity.getName(), entity.getConversationId(), e.toString());
            return null;
        }
    }

    /**
     * List artifacts for an agent's workspace, newest first, with optional
     * provenance/category filters.
     *
     * @param agentId       the channel-bound agent (required)
     * @param workspaceId   the agent's workspace (required, doubles as the
     *                      soft tenancy boundary)
     * @param conversationId optional session filter (server-derived conv id)
     * @param source         optional {@code agent}/{@code user} filter
     * @param type           optional category filter
     * @param page           1-based page number
     * @param size           page size
     */
    public ArtifactPageVO list(Long agentId, Long workspaceId,
                               @Nullable String conversationId,
                               @Nullable String source,
                               @Nullable String type,
                               int page, int size) {
        int safePage = Math.max(1, page);
        int safeSize = size <= 0 ? DEFAULT_PAGE_SIZE : Math.min(size, MAX_PAGE_SIZE);

        LambdaQueryWrapper<WorkspaceArtifactEntity> qw = new LambdaQueryWrapper<>();
        qw.eq(WorkspaceArtifactEntity::getAgentId, agentId);
        qw.eq(WorkspaceArtifactEntity::getWorkspaceId, workspaceId);
        qw.eq(WorkspaceArtifactEntity::getDeleted, 0);
        if (conversationId != null && !conversationId.isBlank()) {
            qw.eq(WorkspaceArtifactEntity::getConversationId, conversationId);
        }
        if (source != null && !source.isBlank()) {
            qw.eq(WorkspaceArtifactEntity::getSource, source);
        }
        if (type != null && !type.isBlank()) {
            qw.eq(WorkspaceArtifactEntity::getArtifactType, type);
        }
        qw.orderByDesc(WorkspaceArtifactEntity::getCreateTime);

        IPage<WorkspaceArtifactEntity> mpPage = new Page<>(safePage, safeSize);
        IPage<WorkspaceArtifactEntity> result = mapper.selectPage(mpPage, qw);

        List<ArtifactVO> items = result.getRecords().stream()
                .map(WorkspaceArtifactService::toVO)
                .toList();

        return ArtifactPageVO.builder()
                .items(items)
                .total(result.getTotal())
                .page(safePage)
                .size(safeSize)
                .hasMore(safePage * safeSize < result.getTotal())
                .build();
    }

    /** Derive a logical category from a filename + mime type. */
    public static String classifyType(@Nullable String name, @Nullable String mime) {
        String lowerName = name != null ? name.toLowerCase() : "";
        if (lowerName.endsWith(".docx") || lowerName.endsWith(".pdf")
                || lowerName.endsWith(".pptx") || lowerName.endsWith(".doc")
                || lowerName.endsWith(".ppt")) {
            return "document";
        }
        if (lowerName.endsWith(".xlsx") || lowerName.endsWith(".csv")
                || lowerName.endsWith(".xls")) {
            return "data";
        }
        if (lowerName.endsWith(".png") || lowerName.endsWith(".jpg")
                || lowerName.endsWith(".jpeg") || lowerName.endsWith(".gif")
                || lowerName.endsWith(".webp") || lowerName.endsWith(".bmp")
                || lowerName.endsWith(".svg")) {
            return "image";
        }
        if (mime != null) {
            if (mime.startsWith("image/")) return "image";
            if (mime.contains("spreadsheet") || mime.equals("text/csv")) return "data";
            if (mime.contains("presentation") || mime.contains("wordprocessing")
                    || mime.equals("application/pdf")) return "document";
        }
        return "other";
    }

    private static ArtifactVO toVO(WorkspaceArtifactEntity e) {
        return ArtifactVO.builder()
                .id(String.valueOf(e.getId()))
                .name(e.getName())
                .source(e.getSource())
                .type(e.getArtifactType())
                .size(e.getSizeBytes())
                .mime(e.getMime())
                .downloadUrl(e.getDownloadUrl())
                .sessionId(e.getConversationId())
                .toolCallId(e.getToolCallId())
                .createdAt(e.getCreateTime() != null
                        ? ISO_UTC.format(e.getCreateTime())
                        : null)
                .build();
    }
}
