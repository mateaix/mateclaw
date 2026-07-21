package vip.mate.tool.document;

import org.springframework.ai.chat.model.ToolContext;
import org.springframework.lang.Nullable;
import vip.mate.agent.context.ChatOrigin;
import vip.mate.workspace.artifact.model.WorkspaceArtifactEntity;
import vip.mate.workspace.artifact.service.WorkspaceArtifactService;

/**
 * Stash freshly-rendered bytes into the {@link GeneratedFileCache} and format
 * the markdown link the tool returns to the LLM.
 *
 * <p>Two locales are exposed because mateclaw's existing convention has the
 * inline render tools speak Chinese and the file-driven render tools speak
 * English. Each variant tells the model to echo the URL verbatim — neither
 * stripping nor inventing a host — because the URL may already be absolute
 * (when {@code mateclaw.server.public-base-url} is set or a request host is
 * resolvable) and models otherwise tamper with it when echoing it back.
 *
 * <p>When a {@link WorkspaceArtifactService} is supplied (issue #514), the
 * produced file is also registered into the Agent's workspace catalog as a
 * persistent, cross-session artifact with provenance (conversationId /
 * toolCallId) read from the {@link ToolContext}. Registration is best-effort
 * and never fails the render.
 */
public final class GeneratedFileLink {

    private GeneratedFileLink() {}

    /**
     * Chinese-language tool result for inline render entry points
     * ({@code renderDocx} / {@code renderXlsx} / {@code renderPptx}).
     *
     * @param typeLabel "文档" / "工作簿" / "演示文稿"
     */
    public static String resultZh(byte[] bytes, String displayName, String mimeType,
                                  GeneratedFileCache cache, String typeLabel,
                                  @Nullable ToolContext ctx) {
        return resultZh(bytes, displayName, mimeType, cache, typeLabel, ctx, null);
    }

    /**
     * Chinese-language variant that also registers the file as a workspace
     * artifact (issue #514).
     *
     * @param artifactService if non-null, the file is registered as a persistent
     *                        cross-session artifact with provenance from {@code ctx}
     */
    public static String resultZh(byte[] bytes, String displayName, String mimeType,
                                  GeneratedFileCache cache, String typeLabel,
                                  @Nullable ToolContext ctx,
                                  @Nullable WorkspaceArtifactService artifactService) {
        ArtifactRef ref = stash(bytes, displayName, mimeType, cache, ctx, artifactService);
        return typeLabel + "已生成：[" + displayName + "](" + ref.url() + ")（链接 "
                + GeneratedFileCache.TTL.toDays() + " 天内有效）。\n"
                + "重要：回答用户时**必须**使用上述 markdown 链接格式 [" + displayName + "](" + ref.url() + ")，"
                + "保持链接地址**原样照抄**，**不要**用反引号包裹，**不要**增删任何域名或 http(s):// 前缀。";
    }

    /**
     * English-language tool result for file-driven render entry points
     * ({@code renderDocxFromFile} / {@code renderDocxFromFiles} / etc.).
     *
     * @param typeLabel       "Document" / "Workbook" / "Presentation"
     * @param sourceFileCount number of source markdown files combined into the
     *                        artifact; values {@code > 1} produce a "from N files"
     *                        prefix, {@code 1} produces the plain "generated" prefix
     */
    public static String resultEn(byte[] bytes, String displayName, String mimeType,
                                  GeneratedFileCache cache, String typeLabel,
                                  int sourceFileCount, @Nullable ToolContext ctx) {
        return resultEn(bytes, displayName, mimeType, cache, typeLabel, sourceFileCount, ctx, null);
    }

    /**
     * English-language variant that also registers the file as a workspace
     * artifact (issue #514).
     */
    public static String resultEn(byte[] bytes, String displayName, String mimeType,
                                  GeneratedFileCache cache, String typeLabel,
                                  int sourceFileCount, @Nullable ToolContext ctx,
                                  @Nullable WorkspaceArtifactService artifactService) {
        ArtifactRef ref = stash(bytes, displayName, mimeType, cache, ctx, artifactService);
        String prefix = sourceFileCount > 1
                ? typeLabel + " generated from " + sourceFileCount + " files"
                : typeLabel + " generated";
        return prefix + ": [" + displayName + "](" + ref.url() + ") (link valid for "
                + GeneratedFileCache.TTL.toDays() + " days).\n"
                + "IMPORTANT: when replying to the user you **must** keep the markdown link form ["
                + displayName + "](" + ref.url() + ") above. Copy the URL verbatim — do **not** wrap it "
                + "in backticks and do **not** add or remove any https://, http:// or domain.";
    }

    private static ArtifactRef stash(byte[] bytes, String displayName, String mimeType,
                                     GeneratedFileCache cache, @Nullable ToolContext ctx,
                                     @Nullable WorkspaceArtifactService artifactService) {
        boolean register = artifactService != null;
        String id = register
                ? cache.putPersistent(bytes, displayName, mimeType)
                : cache.put(bytes, displayName, mimeType);
        String url = cache.downloadUrl(id, ctx);
        if (register) {
            registerArtifact(artifactService, id, bytes, displayName, mimeType, ctx,
                    WorkspaceArtifactService.STORAGE_GENERATED_CACHE);
        }
        return new ArtifactRef(id, url);
    }

    /**
     * Best-effort artifact registration. Reads provenance (agentId /
     * workspaceId / channelId / conversationId / toolCallId / toolName) from the
     * {@link ChatOrigin} + tool-context keys stamped by {@code ToolExecutionExecutor}.
     * Silently no-ops when the context lacks an agentId (e.g. a unit-test render)
     * — such files are not workspace artifacts.
     */
    static void registerArtifact(@Nullable WorkspaceArtifactService service,
                                 @Nullable String cacheId, byte[] bytes, String displayName,
                                 String mimeType, @Nullable ToolContext ctx,
                                 String storageKind) {
        if (service == null || cacheId == null) {
            return;
        }
        ChatOrigin origin = ChatOrigin.from(ctx);
        Long agentId = origin.agentId();
        // No agent/workspace → not a workspace artifact (cron/test render).
        // Skip silently — such files are not workspace-catalog material.
        if (agentId == null || origin.workspaceId() == null) {
            return;
        }
        WorkspaceArtifactEntity entity = new WorkspaceArtifactEntity();
        entity.setAgentId(agentId);
        entity.setWorkspaceId(origin.workspaceId());
        entity.setChannelId(origin.channelId());
        entity.setConversationId(origin.conversationId());
        // Store the conversationId as the provenance label. We deliberately do
        // not parse the conversationId for a "clean" sessionId because visitorId
        // may legally contain colons (VISITOR_ID_PATTERN allows ':'), making
        // colon-based splitting ambiguous. The consumer filters by sessionId via
        // the list endpoint, which re-derives the same conversationId server-side.
        entity.setSessionLabel(origin.conversationId());
        entity.setToolCallId(ChatOrigin.toolCallId(ctx));
        entity.setToolName(ChatOrigin.toolName(ctx));
        entity.setSource(WorkspaceArtifactService.SOURCE_AGENT);
        entity.setArtifactType(WorkspaceArtifactService.classifyType(displayName, mimeType));
        entity.setName(displayName);
        entity.setMime(mimeType);
        entity.setSizeBytes(bytes != null ? (long) bytes.length : 0L);
        entity.setStorageKind(storageKind);
        entity.setStorageRef(cacheId);
        // downloadUrl is NOT set here — WorkspaceArtifactService.toVO() builds it
        // at read time from the artifact id, so it is always correct.
        service.register(entity);
    }

    /** Internal: the cache id + resolved download URL produced by a stash. */
    private record ArtifactRef(String id, String url) {}
}
