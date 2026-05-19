package vip.mate.workspace.conversation;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import vip.mate.agent.model.AgentEntity;
import vip.mate.approval.ApprovalPlaceholderUtil;
import vip.mate.approval.MetadataDecision;
import vip.mate.agent.repository.AgentMapper;
import vip.mate.approval.model.ToolApprovalEntity;
import vip.mate.approval.repository.ToolApprovalMapper;
import vip.mate.channel.model.ChannelSessionEntity;
import vip.mate.channel.repository.ChannelSessionMapper;
import vip.mate.task.model.AsyncTaskEntity;
import vip.mate.task.repository.AsyncTaskMapper;
import vip.mate.workspace.conversation.event.ConversationDeletedEvent;
import vip.mate.workspace.conversation.model.ConversationEntity;
import vip.mate.workspace.conversation.model.MessageContentPart;
import vip.mate.workspace.conversation.model.MessageEntity;
import vip.mate.workspace.conversation.repository.ConversationMapper;
import vip.mate.workspace.conversation.repository.MessageMapper;
import vip.mate.workspace.conversation.vo.ConversationVO;
import vip.mate.workspace.conversation.vo.MessageVO;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.InvalidPathException;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;
import java.util.stream.Stream;

/**
 * 会话管理服务
 *
 * @author MateClaw Team
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class ConversationService {

    public static final String SYSTEM_USER = "system";

    private final ConversationMapper conversationMapper;
    private final MessageMapper messageMapper;
    private final AgentMapper agentMapper;
    private final ObjectMapper objectMapper;
    private final ToolApprovalMapper toolApprovalMapper;
    private final AsyncTaskMapper asyncTaskMapper;
    private final ChannelSessionMapper channelSessionMapper;
    private final ApplicationEventPublisher eventPublisher;

    /**
     * Optional spill store. Injected via a setter so the existing @RequiredArgsConstructor
     * stays stable and tests that build the service directly don't need to wire
     * tool-result storage. When present, deleteConversation also purges any spill
     * files this conversation produced so they don't outlive the row that owned them.
     */
    private vip.mate.agent.graph.executor.ToolResultStorage toolResultStorage;

    @org.springframework.beans.factory.annotation.Autowired(required = false)
    public void setToolResultStorage(vip.mate.agent.graph.executor.ToolResultStorage toolResultStorage) {
        this.toolResultStorage = toolResultStorage;
    }

    /**
     * 获取用户的会话列表（返回 VO，包含 agentName/agentIcon/status）
     */
    public List<ConversationVO> listConversations(String username) {
        return listConversations(username, null);
    }

    /**
     * 获取用户的会话列表（按工作区过滤）
     */
    public List<ConversationVO> listConversations(String username, Long workspaceId) {
        // 同时返回当前用户的会话 和 定时任务（system）产生的会话
        // 排除子会话（委派产生的子会话不在侧边栏显示）
        LambdaQueryWrapper<ConversationEntity> wrapper = new LambdaQueryWrapper<ConversationEntity>()
                .in(ConversationEntity::getUsername, username, SYSTEM_USER)
                .isNull(ConversationEntity::getParentConversationId)
                .orderByDesc(ConversationEntity::getPinned)
                .orderByDesc(ConversationEntity::getLastActiveTime);
        if (workspaceId != null) {
            wrapper.eq(ConversationEntity::getWorkspaceId, workspaceId);
        }
        List<ConversationEntity> entities = conversationMapper.selectList(wrapper);

        if (entities.isEmpty()) {
            return List.of();
        }

        // 批量查询关联的 Agent 信息，避免 N+1 查询
        List<Long> agentIds = entities.stream()
                .filter(e -> e.getAgentId() != null)
                .map(ConversationEntity::getAgentId)
                .distinct()
                .collect(Collectors.toList());

        Map<Long, AgentEntity> agentMap = agentIds.isEmpty()
                ? Map.of()
                : agentMapper.selectBatchIds(agentIds).stream()
                        .collect(Collectors.toMap(AgentEntity::getId, a -> a));

        // 转换为 VO，补充 agentName/agentIcon/status
        return entities.stream()
                .map(entity -> {
                    AgentEntity agent = entity.getAgentId() != null
                            ? agentMap.get(entity.getAgentId())
                            : null;
                    String agentName = agent != null ? agent.getName() : null;
                    String agentIcon = agent != null ? agent.getIcon() : null;
                    return ConversationVO.from(entity, agentName, agentIcon);
                })
                .collect(Collectors.toList());
    }

    /**
     * 获取或创建会话（向后兼容，默认 workspace 1）
     */
    @Transactional
    public ConversationEntity getOrCreateConversation(String conversationId, Long agentId, String username) {
        return getOrCreateConversation(conversationId, agentId, username, 1L);
    }

    /**
     * 获取或创建会话（workspace 感知）
     */
    @Transactional
    public ConversationEntity getOrCreateConversation(String conversationId, Long agentId,
                                                       String username, Long workspaceId) {
        ConversationEntity conv = conversationMapper.selectOne(new LambdaQueryWrapper<ConversationEntity>()
                .eq(ConversationEntity::getConversationId, conversationId));
        if (conv == null) {
            conv = new ConversationEntity();
            conv.setConversationId(conversationId);
            conv.setAgentId(agentId);
            conv.setUsername(username != null ? username : "anonymous");
            conv.setWorkspaceId(workspaceId != null ? workspaceId : 1L);
            conv.setTitle("新对话");
            conv.setMessageCount(0);
            conv.setLastActiveTime(LocalDateTime.now());
            conversationMapper.insert(conv);
        } else if (!conv.getUsername().equals(username)) {
            throw new IllegalArgumentException("无权操作该会话");
        }
        return conv;
    }

    /**
     * 创建子会话（委派场景），关联父会话 ID。
     */
    @Transactional
    public ConversationEntity createChildConversation(String childConversationId, Long agentId,
                                                        String username, Long workspaceId,
                                                        String parentConversationId) {
        ConversationEntity conv = getOrCreateConversation(childConversationId, agentId, username, workspaceId);
        conv.setParentConversationId(parentConversationId);
        conv.setTitle("子任务");
        conversationMapper.updateById(conv);
        return conv;
    }

    /**
     * 获取或创建共享渠道会话。
     * <p>
     * IM 渠道（飞书/钉钉/企微等）的会话需要在控制台中对登录用户可见，
     * 因此统一使用 system 作为 owner。对于历史上已写成发送者昵称/open_id 的会话，
     * 这里会自动修正为 system，避免控制台列表和消息接口因权限校验而不可见。
     */
    @Transactional
    public ConversationEntity getOrCreateSharedConversation(String conversationId, Long agentId) {
        return getOrCreateSharedConversation(conversationId, agentId, null);
    }

    /**
     * 获取或创建共享渠道会话（workspace 感知）
     */
    @Transactional
    public ConversationEntity getOrCreateSharedConversation(String conversationId, Long agentId, Long workspaceId) {
        ConversationEntity conv = conversationMapper.selectOne(new LambdaQueryWrapper<ConversationEntity>()
                .eq(ConversationEntity::getConversationId, conversationId));
        if (conv == null) {
            conv = new ConversationEntity();
            conv.setConversationId(conversationId);
            conv.setAgentId(agentId);
            conv.setUsername(SYSTEM_USER);
            conv.setWorkspaceId(workspaceId != null ? workspaceId : 1L);
            conv.setTitle("新对话");
            conv.setMessageCount(0);
            conv.setLastActiveTime(LocalDateTime.now());
            try {
                conversationMapper.insert(conv);
            } catch (org.springframework.dao.DuplicateKeyException e) {
                // 并发插入：另一个线程已创建，回退到查询
                conv = conversationMapper.selectOne(new LambdaQueryWrapper<ConversationEntity>()
                        .eq(ConversationEntity::getConversationId, conversationId));
                if (conv == null) {
                    throw new IllegalStateException("Conversation vanished after duplicate key: " + conversationId, e);
                }
                // 继续走下面的 owner 修正逻辑
            }
        }

        boolean changed = false;
        if (!SYSTEM_USER.equals(conv.getUsername())) {
            conv.setUsername(SYSTEM_USER);
            changed = true;
        }
        if (conv.getAgentId() == null && agentId != null) {
            conv.setAgentId(agentId);
            changed = true;
        }
        if (changed) {
            conversationMapper.updateById(conv);
        }
        return conv;
    }

    /**
     * 保存消息并更新会话统计
     */
    @Transactional
    public MessageEntity saveMessage(String conversationId, String role, String content) {
        return saveMessage(conversationId, role, content, null, "completed");
    }

    @Transactional
    public MessageEntity saveMessage(String conversationId, String role, String content, List<MessageContentPart> parts) {
        return saveMessage(conversationId, role, content, parts, "completed");
    }

    @Transactional
    public MessageEntity saveMessage(String conversationId, String role, String content,
            List<MessageContentPart> parts, String status) {
        return saveMessage(conversationId, role, content, parts, status, 0, 0, null, null);
    }

    @Transactional
    public MessageEntity saveMessage(String conversationId, String role, String content,
            List<MessageContentPart> parts, String status,
            int promptTokens, int completionTokens,
            String runtimeModel, String runtimeProvider) {
        return saveMessage(conversationId, role, content, parts, status,
                promptTokens, completionTokens, runtimeModel, runtimeProvider, null);
    }

    @Transactional
    public MessageEntity saveMessage(String conversationId, String role, String content,
            List<MessageContentPart> parts, String status,
            int promptTokens, int completionTokens,
            String runtimeModel, String runtimeProvider, String metadata) {
        MessageEntity message = new MessageEntity();
        message.setConversationId(conversationId);
        message.setRole(role);
        message.setContent(content);
        message.setContentParts(serializeParts(parts));
        message.setStatus(status != null ? status : "completed");
        message.setTokenUsage(promptTokens + completionTokens);
        message.setPromptTokens(promptTokens);
        message.setCompletionTokens(completionTokens);
        message.setRuntimeModel(runtimeModel);
        message.setRuntimeProvider(runtimeProvider);
        message.setMetadata(metadata != null ? metadata : "{}");  // 初始化为空对象
        messageMapper.insert(message);

        // 更新会话信息
        ConversationEntity conv = conversationMapper.selectOne(new LambdaQueryWrapper<ConversationEntity>()
                .eq(ConversationEntity::getConversationId, conversationId));
        if (conv != null) {
            conv.setMessageCount(conv.getMessageCount() + 1);
            conv.setLastActiveTime(LocalDateTime.now());
            String summary = summarizeMessage(content, parts);
            // 用第一条用户消息作为会话标题
            if ("user".equals(role) && "新对话".equals(conv.getTitle())) {
                conv.setTitle(summary.length() > 20 ? summary.substring(0, 20) + "..." : summary);
            }
            // 保存最后一条 AI 回复摘要
            if ("assistant".equals(role)) {
                conv.setLastMessage(summary.length() > 50 ? summary.substring(0, 50) + "..." : summary);
            }
            conversationMapper.updateById(conv);
        }
        return message;
    }

    /**
     * 更新消息的元数据（toolCalls, plan, currentPhase 等）
     */
    @Transactional
    public void updateMessageMetadata(Long messageId, String metadata) {
        MessageEntity message = new MessageEntity();
        message.setId(messageId);
        message.setMetadata(metadata);
        messageMapper.updateById(message);
    }

    /**
     * 重命名会话
     */
    @Transactional
    public void renameConversation(String conversationId, String title) {
        ConversationEntity conv = conversationMapper.selectOne(new LambdaQueryWrapper<ConversationEntity>()
                .eq(ConversationEntity::getConversationId, conversationId));
        if (conv != null) {
            conv.setTitle(title);
            conversationMapper.updateById(conv);
        }
    }

    /**
     * Pin or unpin a conversation. Pinned conversations sort ahead of unpinned
     * ones in the sidebar list regardless of last-active time.
     */
    public void setPinned(String conversationId, boolean pinned) {
        ConversationEntity conv = conversationMapper.selectOne(new LambdaQueryWrapper<ConversationEntity>()
                .eq(ConversationEntity::getConversationId, conversationId));
        if (conv != null) {
            conv.setPinned(pinned ? 1 : 0);
            conversationMapper.updateById(conv);
        }
    }

    /**
     * 更新会话的流状态（running / idle）
     */
    @Transactional
    public void updateStreamStatus(String conversationId, String streamStatus) {
        ConversationEntity conv = conversationMapper.selectOne(new LambdaQueryWrapper<ConversationEntity>()
                .eq(ConversationEntity::getConversationId, conversationId));
        if (conv != null) {
            conv.setStreamStatus(streamStatus);
            conversationMapper.updateById(conv);
        }
    }

    /**
     * Pin the model a conversation uses. A blank provider or model id is a
     * no-op (no override supplied — the conversation keeps inheriting the
     * agent / global default). The write is skipped when the stored value
     * already matches, so persisting the same model on every turn costs only
     * a SELECT.
     */
    @Transactional
    public void updateConversationModel(String conversationId, String modelProvider, String modelName) {
        if (modelProvider == null || modelProvider.isBlank()
                || modelName == null || modelName.isBlank()) {
            return;
        }
        ConversationEntity conv = conversationMapper.selectOne(new LambdaQueryWrapper<ConversationEntity>()
                .eq(ConversationEntity::getConversationId, conversationId));
        if (conv == null) {
            return;
        }
        if (modelProvider.equals(conv.getModelProvider()) && modelName.equals(conv.getModelName())) {
            return;
        }
        conv.setModelProvider(modelProvider);
        conv.setModelName(modelName);
        conversationMapper.updateById(conv);
    }

    /**
     * Persist an assistant placeholder marker only when the last message is a
     * user turn (i.e., the assistant never got to reply). Used by the admin
     * force-recycle path so a torn-down turn leaves a visible "已被用户中止"
     * marker instead of an empty conversation. Idempotent: if the previous
     * emergency-save path already wrote an assistant row, this is a no-op.
     *
     * @return the saved message, or {@code null} if the marker was not needed
     */
    @Transactional
    public MessageEntity saveStopMarkerIfDangling(String conversationId, String markerText, String status) {
        List<MessageEntity> recent = messageMapper.selectList(
                new LambdaQueryWrapper<MessageEntity>()
                        .eq(MessageEntity::getConversationId, conversationId)
                        .orderByDesc(MessageEntity::getCreateTime)
                        .orderByDesc(MessageEntity::getId)
                        .last("LIMIT 1"));
        if (recent.isEmpty()) return null;
        MessageEntity last = recent.get(0);
        if (!"user".equals(last.getRole())) return null;
        return saveMessage(conversationId, "assistant", markerText, null,
                status != null ? status : "stopped");
    }

    /**
     * 获取会话最后一条消息内容（用于 rate limit 防护等场景）
     */
    public String getLastMessage(String conversationId) {
        ConversationEntity conv = conversationMapper.selectOne(new LambdaQueryWrapper<ConversationEntity>()
                .eq(ConversationEntity::getConversationId, conversationId));
        return conv != null ? conv.getLastMessage() : null;
    }

    /**
     * 获取会话的消息数量
     */
    public int getMessageCount(String conversationId) {
        ConversationEntity conv = conversationMapper.selectOne(new LambdaQueryWrapper<ConversationEntity>()
                .eq(ConversationEntity::getConversationId, conversationId));
        return conv != null && conv.getMessageCount() != null ? conv.getMessageCount() : 0;
    }

    /**
     * 获取会话的消息历史
     */
    public List<MessageEntity> listMessages(String conversationId) {
        return messageMapper.selectList(new LambdaQueryWrapper<MessageEntity>()
                .eq(MessageEntity::getConversationId, conversationId)
                .orderByAsc(MessageEntity::getCreateTime)
                .orderByAsc(MessageEntity::getId));
    }

    /**
     * Returns the most recent compression boundary row for the conversation,
     * or {@code null} if no boundary exists yet. Used by the agent loader to
     * recover the structured summary when the boundary itself sits outside the
     * recent-message window — without this, a long conversation that already
     * compacted would feed the model the last N raw messages while silently
     * dropping the goal / progress digest the boundary holds.
     *
     * <p>Implemented as a single indexed query rather than a full
     * {@code listMessages} + filter so it stays cheap on conversations with
     * thousands of messages. Selection: {@code role=system} +
     * {@code metadata like '%compression_summary%'} (the metadata column always
     * carries that literal — see {@link #saveCompressionSummary}).
     */
    public MessageEntity findLatestCompressionBoundary(String conversationId) {
        return messageMapper.selectOne(new LambdaQueryWrapper<MessageEntity>()
                .eq(MessageEntity::getConversationId, conversationId)
                .eq(MessageEntity::getRole, "system")
                .like(MessageEntity::getMetadata, "compression_summary")
                .orderByDesc(MessageEntity::getCreateTime)
                .orderByDesc(MessageEntity::getId)
                .last("LIMIT 1"));
    }

    /**
     * 加载最近 N 条消息（倒序取出后翻转为正序）。
     * 利用复合索引 (conversation_id, create_time) 高效分页。
     */
    public List<MessageEntity> listRecentMessages(String conversationId, int lastN) {
        List<MessageEntity> recent = messageMapper.selectList(
                new LambdaQueryWrapper<MessageEntity>()
                        .eq(MessageEntity::getConversationId, conversationId)
                        .orderByDesc(MessageEntity::getCreateTime)
                        .orderByDesc(MessageEntity::getId)
                        .last("LIMIT " + lastN));
        Collections.reverse(recent);
        return recent;
    }

    /**
     * 分页加载指定 ID 之前的消息（用于前端上拉加载更早消息）。
     * 返回倒序结果，调用方需自行 reverse。
     */
    public List<MessageEntity> listMessagesBefore(String conversationId, Long beforeId, int limit) {
        List<MessageEntity> results = messageMapper.selectList(
                new LambdaQueryWrapper<MessageEntity>()
                        .eq(MessageEntity::getConversationId, conversationId)
                        .lt(MessageEntity::getId, beforeId)
                        .orderByDesc(MessageEntity::getCreateTime)
                        .orderByDesc(MessageEntity::getId)
                        .last("LIMIT " + limit));
        Collections.reverse(results);
        return results;
    }

    /**
     * 查询会话消息总数。
     */
    public long countMessages(String conversationId) {
        return messageMapper.selectCount(
                new LambdaQueryWrapper<MessageEntity>()
                        .eq(MessageEntity::getConversationId, conversationId));
    }

    /**
     * Persist a compaction boundary as a role=system message. The body is
     * the summary text; the metadata describes <em>what happened</em> at
     * this boundary (trigger, pre/post tokens, how many messages were
     * summarised, how many spill files were produced, how many tail
     * messages survived). On the next load this row is the cut-off — older
     * messages are skipped, the model picks up from the summary forward.
     *
     * <p>Backward-compat overload: legacy callers that only know the row
     * count still work and produce a minimal metadata block.
     */
    public void saveCompressionSummary(String conversationId, String summary, int compressedCount) {
        saveCompressionSummary(conversationId, summary, compressedCount, Map.of());
    }

    /**
     * Same as {@link #saveCompressionSummary(String, String, int, Map)} but
     * returns the inserted row's id so callers (notably
     * {@code ConversationWindowManager}) can include the {@code summaryId}
     * in the {@code compact_status} SSE payload. The id is also written back
     * into the row's metadata JSON by the underlying overload, so the row is
     * still self-describing if a client misses the SSE event and loads
     * history later.
     *
     * <p>Returns {@code null} when the insert path failed (logged at INFO);
     * callers should treat that as "no boundary was persisted" and still
     * broadcast a {@code done} event without {@code summaryId}.
     */
    public Long saveCompressionSummaryReturningId(String conversationId, String summary,
                                                  int compressedCount, Map<String, Object> extraMetadata) {
        return saveCompressionSummaryInternal(conversationId, summary, compressedCount, extraMetadata);
    }

    /**
     * Same as the 3-arg overload but accepts extra structured fields that
     * are merged into the boundary's metadata JSON. Fields the frontend
     * and observability pipeline care about:
     * <ul>
     *   <li>{@code trigger} — what fired this boundary
     *       ({@code token_threshold}, {@code user_compact}, etc.)</li>
     *   <li>{@code preTokens} / {@code postTokens} — context size before
     *       and after, for the in-prompt status row</li>
     *   <li>{@code messagesSummarized} / {@code tailKept} — partition
     *       counts the user sees in the boundary card</li>
     *   <li>{@code toolResultsSpilled} — how many bodies the spill store
     *       absorbed during this boundary</li>
     *   <li>{@code summaryId} — stable id (the inserted message id) for
     *       deep-linking from the SSE event</li>
     * </ul>
     * <p>{@code type=compression_summary} is always present — the loader
     * keys off it. {@code compressedCount} is kept for backward compat.
     */
    public void saveCompressionSummary(String conversationId, String summary, int compressedCount,
                                       Map<String, Object> extraMetadata) {
        saveCompressionSummaryInternal(conversationId, summary, compressedCount, extraMetadata);
    }

    private Long saveCompressionSummaryInternal(String conversationId, String summary, int compressedCount,
                                                Map<String, Object> extraMetadata) {
        MessageEntity entity = new MessageEntity();
        entity.setConversationId(conversationId);
        entity.setRole("system");
        entity.setContent(summary);
        entity.setStatus("completed");

        Map<String, Object> metadata = new java.util.LinkedHashMap<>();
        metadata.put("type", "compression_summary");
        metadata.put("compressedCount", compressedCount);
        if (extraMetadata != null) {
            extraMetadata.forEach((k, v) -> {
                if (v != null) metadata.put(k, v);
            });
        }
        // First write a placeholder so the row lands with the structured
        // fields; we backfill summaryId in a second step once MyBatis Plus
        // has assigned the snowflake id. ASSIGN_ID actually populates the
        // id BEFORE flushing the INSERT, but reading it back this way means
        // the contract holds even if the ID generation strategy changes.
        try {
            entity.setMetadata(objectMapper.writeValueAsString(metadata));
        } catch (com.fasterxml.jackson.core.JsonProcessingException e) {
            log.warn("[Conversation] Failed to serialise compaction metadata, falling back to minimal: {}",
                    e.getMessage());
            entity.setMetadata("{\"type\":\"compression_summary\",\"compressedCount\":" + compressedCount + "}");
        }
        messageMapper.insert(entity);

        // Backfill summaryId now that the row owns an id. Best-effort: a
        // failure here doesn't invalidate the boundary itself, it just
        // means SSE clients won't have a deep-link target for this row.
        if (entity.getId() != null) {
            metadata.put("summaryId", entity.getId());
            try {
                entity.setMetadata(objectMapper.writeValueAsString(metadata));
                messageMapper.updateById(entity);
            } catch (Exception e) {
                log.warn("[Conversation] Failed to backfill summaryId on compression boundary: {}",
                        e.getMessage());
            }
        }
        log.info("[Conversation] Saved compression boundary conv={}, compressedCount={}, metadata={}",
                conversationId, compressedCount, entity.getMetadata());
        return entity.getId();
    }

    public List<MessageVO> listMessageViews(String conversationId) {
        return listMessages(conversationId).stream()
                .map(message -> MessageVO.from(message, parseMessageParts(message), renderMessageContent(message)))
                .toList();
    }

    /**
     * Delete a conversation and cascade-clean every row that referenced it.
     * <p>
     * Tables cleaned in the same transaction:
     * <ul>
     *   <li>{@code mate_message} — chat history</li>
     *   <li>{@code mate_tool_approval} — pending approvals would otherwise
     *       point to a non-existent conversation and surface as ghost items
     *       in the approvals list</li>
     *   <li>{@code mate_async_task} — long-running task records keyed on
     *       this conversation</li>
     *   <li>{@code mate_channel_session} — channel-side session row (the
     *       column is UNIQUE; leaving it would block reuse of the same id)</li>
     *   <li>{@code mate_conversation} — the conversation itself</li>
     * </ul>
     * Child conversations (delegated turns) have their
     * {@code parent_conversation_id} set to NULL rather than cascade-deleted,
     * so the user keeps independent access to delegated work.
     * <p>
     * Audit / history tables ({@code mate_tool_guard_audit_log},
     * {@code mate_cron_job_run}, {@code mate_skill.source_conversation_id},
     * {@code mate_skill_usage_stat}) are intentionally left alone — those
     * are append-only records that should outlive their source conversation.
     * <p>
     * Attachment file cleanup is registered as an after-commit hook so it
     * runs only when the DB cascade actually persists, and an IO failure
     * cannot roll back the database deletes.
     */
    @Transactional
    public void deleteConversation(String conversationId) {
        int messages = messageMapper.delete(new LambdaQueryWrapper<MessageEntity>()
                .eq(MessageEntity::getConversationId, conversationId));
        int approvals = toolApprovalMapper.delete(new LambdaQueryWrapper<ToolApprovalEntity>()
                .eq(ToolApprovalEntity::getConversationId, conversationId));
        int asyncTasks = asyncTaskMapper.delete(new LambdaQueryWrapper<AsyncTaskEntity>()
                .eq(AsyncTaskEntity::getConversationId, conversationId));
        int channelSessions = channelSessionMapper.delete(new LambdaQueryWrapper<ChannelSessionEntity>()
                .eq(ChannelSessionEntity::getConversationId, conversationId));
        int childrenUnlinked = conversationMapper.update(null, new LambdaUpdateWrapper<ConversationEntity>()
                .set(ConversationEntity::getParentConversationId, null)
                .eq(ConversationEntity::getParentConversationId, conversationId));
        int conversations = conversationMapper.delete(new LambdaQueryWrapper<ConversationEntity>()
                .eq(ConversationEntity::getConversationId, conversationId));

        log.info("[Conversation] Deleted {}: messages={}, approvals={}, asyncTasks={},"
                        + " channelSessions={}, childrenUnlinked={}, conversationRow={}",
                conversationId, messages, approvals, asyncTasks,
                channelSessions, childrenUnlinked, conversations);

        registerPostCommitCleanup(conversationId);
    }

    /**
     * After-commit cleanup: file IO and the {@link ConversationDeletedEvent}
     * fan-out both run only if the cascade actually persists, and an IO
     * failure cannot roll back the DB cascade. The event lets approval and
     * async-task modules drop their in-memory state (pendingMap, active
     * pollers, canceled-conv set) so workers cannot resurrect orphan rows
     * after the conversation row is gone.
     */
    private void registerPostCommitCleanup(String conversationId) {
        if (TransactionSynchronizationManager.isSynchronizationActive()) {
            TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
                @Override
                public void afterCommit() {
                    cleanAttachmentFiles(conversationId);
                    purgeToolResultSpill(conversationId);
                    eventPublisher.publishEvent(new ConversationDeletedEvent(conversationId));
                }
            });
        } else {
            cleanAttachmentFiles(conversationId);
            purgeToolResultSpill(conversationId);
            eventPublisher.publishEvent(new ConversationDeletedEvent(conversationId));
        }
    }

    /**
     * Best-effort: ask the spill store to delete every tool-result file this
     * conversation produced. No-op when no spill store is wired in (legacy
     * deployments or tests that don't need spill). Failures are logged but
     * never propagated — leaving an extra file on disk is a small price
     * compared to surfacing IO errors as a 500 on the delete endpoint.
     */
    private void purgeToolResultSpill(String conversationId) {
        if (toolResultStorage == null) return;
        try {
            toolResultStorage.purgeConversation(conversationId);
        } catch (Exception e) {
            log.warn("[Conversation] tool-result spill purge failed for {}: {}",
                    conversationId, e.getMessage());
        }
    }

    /**
     * 清空会话消息（同时清理附件文件）
     */
    @Transactional
    public void clearMessages(String conversationId) {
        messageMapper.delete(new LambdaQueryWrapper<MessageEntity>()
                .eq(MessageEntity::getConversationId, conversationId));
        ConversationEntity conv = conversationMapper.selectOne(new LambdaQueryWrapper<ConversationEntity>()
                .eq(ConversationEntity::getConversationId, conversationId));
        if (conv != null) {
            conv.setMessageCount(0);
            conv.setLastMessage(null);
            conversationMapper.updateById(conv);
        }
        cleanAttachmentFiles(conversationId);
    }

    public List<MessageContentPart> parseMessageParts(MessageEntity message) {
        if (message == null || message.getContentParts() == null || message.getContentParts().isBlank()) {
            return List.of();
        }
        try {
            return objectMapper.readValue(message.getContentParts(), new TypeReference<List<MessageContentPart>>() {});
        } catch (Exception e) {
            log.warn("Failed to parse content_parts for message {}: {}", message.getId(), e.getMessage());
            return List.of(MessageContentPart.parseError(
                    message.getId() != null ? message.getId().toString() : "unknown",
                    e.getMessage() != null ? e.getMessage() : "unknown error"));
        }
    }

    public String renderMessageContent(MessageEntity message) {
        List<MessageContentPart> parts = parseMessageParts(message);
        if (parts.isEmpty()) {
            return message.getContent() != null ? message.getContent() : "";
        }

        StringBuilder text = new StringBuilder();
        for (MessageContentPart part : parts) {
            if (part == null || part.getType() == null) {
                continue;
            }
            switch (part.getType()) {
                case "text" -> appendSegment(text, part.getText());
                case "thinking", "tool_call", "parse_error" -> { /* skip — frontend reads these from contentParts directly */ }
                case "file" -> appendSegment(text, renderFilePart(part));
                case "image", "video", "audio", "model3d" -> appendSegment(text, renderMediaPart(part));
                default -> appendSegment(text, part.getText());
            }
        }
        return text.toString().trim();
    }

    private String serializeParts(List<MessageContentPart> parts) {
        if (parts == null || parts.isEmpty()) {
            return null;
        }
        try {
            return objectMapper.writeValueAsString(parts);
        } catch (Exception e) {
            throw new IllegalStateException("Failed to serialize message parts", e);
        }
    }

    private String summarizeMessage(String content, List<MessageContentPart> parts) {
        String rendered = content;
        if ((rendered == null || rendered.isBlank()) && parts != null && !parts.isEmpty()) {
            rendered = parts.stream()
                    .map(part -> {
                        if (part == null || part.getType() == null) {
                            return "";
                        }
                        return switch (part.getType()) {
                            case "text", "thinking" -> safe(part.getText());
                            case "tool_call" -> "";
                            case "file" -> "[附件] " + safe(part.getFileName());
                            default -> safe(part.getText());
                        };
                    })
                    .filter(text -> !text.isBlank())
                    .collect(Collectors.joining(" "));
        }
        if (rendered == null || rendered.isBlank()) {
            return "新消息";
        }
        return rendered;
    }

    /**
     * Render a "file" content part for the LLM prompt. The original filename can be
     * non-ASCII (Chinese, emoji, …); the upload pipeline sanitizes those characters
     * to underscores when storing on disk, so the LLM-visible name and the on-disk
     * name diverge. Surface the actual server-side path here so any tool the LLM
     * picks (read_file / extract_document_text / detect_file_type / …) can be called
     * with a path that resolves directly, instead of relying on per-tool fallbacks.
     */
    private String renderFilePart(MessageContentPart part) {
        String name = safe(part.getFileName());
        String path = safe(part.getPath());
        if (path.isBlank()) {
            return "[附件] " + name;
        }
        return "[附件] " + name + "（路径: " + path + "）";
    }

    /**
     * Render an image/video/audio/3D-model content part for the LLM prompt.
     * <p>
     * Without this marker, media parts are invisible in the rendered text — the LLM
     * sees only the user's accompanying text and has no idea an attachment was sent.
     * That fails closed when the multimodal Media injection in {@code BaseAgent} is
     * upstream-stripped (model heuristic claims vision but the actual provider drops
     * the image), leaving the agent to ask "which image?" for an attachment the user
     * already uploaded. The path lets file-reading tools ({@code read_file},
     * {@code extract_document_text}, {@code detect_file_type}) work as a fallback.
     */
    private String renderMediaPart(MessageContentPart part) {
        String label = switch (part.getType()) {
            case "image" -> "[图片]";
            case "video" -> "[视频]";
            case "audio" -> "[音频]";
            case "model3d" -> "[3D 模型]";
            default -> "[附件]";
        };
        String name = safe(part.getFileName());
        if (name.isBlank()) {
            name = "未命名";
        }
        String path = safe(part.getPath());
        if (path.isBlank()) {
            return label + " " + name;
        }
        return label + " " + name + "（路径: " + path + "）";
    }

    private void appendSegment(StringBuilder builder, String text) {
        String safeText = safe(text);
        if (safeText.isBlank()) {
            return;
        }
        if (!builder.isEmpty()) {
            builder.append('\n');
        }
        builder.append(safeText);
    }

    private String safe(String text) {
        return text == null ? "" : text;
    }

    /**
     * 删除指定会话中所有审批占位 assistant 消息
     * <p>
     * 在 replay 前调用，确保 LLM 上下文中不包含任何审批相关文本。
     */
    /**
     * Reconcile persisted assistant-message state when one or more pending approvals
     * leave the {@code pending} status (approve / deny / timeout / superseded / consumed).
     * <p>
     * For each assistant message in the conversation whose
     * {@code metadata.pendingApproval.pendingId} appears in {@code resolvedPendingIds}
     * and whose {@code metadata.pendingApproval.status == "pending_approval"}, this
     * method updates three fields atomically (within a single transaction):
     * <ol>
     *   <li>{@code metadata.pendingApproval.status} → {@code decision.pendingApprovalStatus}</li>
     *   <li>{@code metadata.currentPhase} flips {@code awaiting_approval} → {@code resolved}</li>
     *   <li>{@code MessageEntity.status} flips {@code awaiting_approval}
     *       → {@code decision.messageStatus} (one of the existing terminal states the
     *       frontend Message.status union supports)</li>
     * </ol>
     * Without this synchronization, a page refresh re-hydrates the stale
     * {@code pending_approval} status from message metadata and the UI pops a ghost
     * approval banner for an approval the user already settled. See RFC-067 §4.1.5.
     * <p>
     * Idempotent: messages whose metadata does not match, or whose status already moved
     * off {@code pending_approval}, are left untouched. Timeout / superseded callers
     * pass {@link MetadataDecision#DENIED}; the more specific terminal status lives
     * on {@code mate_tool_approval.status} for audit (see RFC-067 §4.4.1).
     *
     * @param conversationId target conversation
     * @param resolvedPendingIds pendingIds whose owning message metadata should be reconciled
     * @param decision the metadata-layer decision to apply
     * @return number of messages whose state was rewritten
     */
    @Transactional
    public int markPendingApprovalsResolved(String conversationId,
                                            java.util.Set<String> resolvedPendingIds,
                                            MetadataDecision decision) {
        if (conversationId == null || resolvedPendingIds == null || resolvedPendingIds.isEmpty()) {
            return 0;
        }
        if (decision == null) {
            throw new IllegalArgumentException("decision must not be null");
        }
        List<MessageEntity> messages = listMessages(conversationId);
        int rewritten = 0;
        for (MessageEntity msg : messages) {
            if (!"assistant".equals(msg.getRole())) continue;
            String raw = msg.getMetadata();
            if (raw == null || raw.isBlank() || !raw.contains("pendingApproval")) continue;

            try {
                // H2's JSON column returns the metadata as a JSON-encoded string
                // (wrapped + escaped) when read through MyBatis. MessageVO.parseMetadataToObject
                // (the read-to-frontend path) already handles this; we mirror the same
                // unwrap here. Without it, readValue tokenizes the leading `"` as a
                // String token and explodes with "Cannot construct LinkedHashMap from
                // String value", silently turning every approve / deny / Stop sweep
                // into a no-op (messagesRewritten=0).
                String json = raw.trim();
                if (json.startsWith("\"") && json.endsWith("\"")) {
                    json = objectMapper.readValue(json, String.class);
                }
                java.util.Map<String, Object> meta = objectMapper.readValue(json,
                        new TypeReference<java.util.Map<String, Object>>() {});
                Object pa = meta.get("pendingApproval");
                if (!(pa instanceof java.util.Map)) continue;
                @SuppressWarnings("unchecked")
                java.util.Map<String, Object> pendingApproval = (java.util.Map<String, Object>) pa;
                Object pid = pendingApproval.get("pendingId");
                if (pid == null || !resolvedPendingIds.contains(String.valueOf(pid))) continue;
                Object pendingStatus = pendingApproval.get("status");
                if (!"pending_approval".equals(String.valueOf(pendingStatus))) continue;

                pendingApproval.put("status", decision.pendingApprovalStatus);
                meta.put("pendingApproval", pendingApproval);

                Object phase = meta.get("currentPhase");
                if ("awaiting_approval".equals(String.valueOf(phase))) {
                    meta.put("currentPhase", "resolved");
                }

                // RFC-067 §4.10 (PR 9): flip the matching toolCall + segment entries
                // inside this message's metadata. Both DENIED and APPROVED need this
                // because the LLM streamed tool_call_started → segment.status='running'
                // before the user's decision arrived, and replay creates a NEW assistant
                // message rather than updating the original — so without this fix the
                // gate message's tool card stays as an orange spinner forever.
                //   DENIED   → success=false + result='[已拒绝]'  → red ✗
                //   APPROVED → success=true  + result='[已批准]'  → green ✓ on the gate
                //              row; the actual execution result still appears in the
                //              replayed assistant message that follows.
                Object toolName = pendingApproval.get("toolName");
                Object toolArgs = pendingApproval.get("arguments");
                String tnStr = toolName == null ? null : String.valueOf(toolName);
                String taStr = toolArgs == null ? null : String.valueOf(toolArgs);
                flipResolvedToolCalls(meta, tnStr, taStr, decision);
                flipResolvedSegments(meta, tnStr, taStr, decision);

                msg.setMetadata(objectMapper.writeValueAsString(meta));
                if ("awaiting_approval".equals(msg.getStatus())) {
                    msg.setStatus(decision.messageStatus);
                }
                messageMapper.updateById(msg);
                rewritten++;
            } catch (Exception e) {
                String preview = raw.length() > 200 ? raw.substring(0, 200) + "..." : raw;
                log.warn("[ConversationService] Failed to rewrite pendingApproval status for message {} " +
                                "(rawLen={}, preview={}): {}",
                        msg.getId(), raw.length(), preview, e.getMessage());
            }
        }
        if (rewritten > 0) {
            log.info("[ConversationService] Reconciled {} message(s) in conversation {} " +
                            "to decision={} (cleared {} ghost pendings)",
                    rewritten, conversationId, decision, resolvedPendingIds.size());
        }
        return rewritten;
    }

    /**
     * Flip the gate message's tool-call entry to a terminal state matching the
     * approval decision (RFC-067 §4.10).
     * <p>
     * Driven by {@link MetadataDecision}:
     * <ul>
     *   <li>{@link MetadataDecision#APPROVED} → {@code status='completed'} +
     *       {@code success=true} + {@code result='[已批准]'}. The actual tool
     *       execution result appears in the replayed assistant message that
     *       follows — not on this gate row.</li>
     *   <li>{@link MetadataDecision#DENIED} → {@code status='completed'} +
     *       {@code success=false} + {@code result='[已拒绝]'}. MessageBubble
     *       renders this as a red ✗.</li>
     * </ul>
     * Both paths flip status off {@code awaiting_approval} / {@code running} so
     * MessageBubble's icon precedence (running > awaiting_approval > success
     * branches) can reach the right terminal icon. Without the flip the card
     * stays as an orange spinner forever — replay creates a new message
     * instead of overwriting the gate row, so nothing else updates it.
     * Best-effort: if metadata.toolCalls is missing or no entry matches, this
     * is a silent no-op.
     */
    @SuppressWarnings("unchecked")
    private void flipResolvedToolCalls(java.util.Map<String, Object> meta,
                                       String toolName, String toolArgs,
                                       MetadataDecision decision) {
        Object tc = meta.get("toolCalls");
        if (!(tc instanceof java.util.List)) return;
        boolean approved = decision == MetadataDecision.APPROVED;
        String resultText = approved ? "[已批准]" : "[已拒绝]";
        for (Object entry : (java.util.List<Object>) tc) {
            if (!(entry instanceof java.util.Map)) continue;
            java.util.Map<String, Object> call = (java.util.Map<String, Object>) entry;
            if (!matchesNameAndArgs(call.get("name"), call.get("arguments"), toolName, toolArgs)) continue;
            Object status = call.get("status");
            if ("awaiting_approval".equals(String.valueOf(status))
                    || "running".equals(String.valueOf(status))) {
                call.put("status", "completed");
            }
            call.put("success", approved ? Boolean.TRUE : Boolean.FALSE);
            call.put("result", resultText);
        }
    }

    /**
     * Same terminal-state flip as {@link #flipResolvedToolCalls} but on the
     * streaming-segments timeline. Segments use {@code toolName} / {@code toolArgs}
     * + {@code toolSuccess} / {@code toolResult} field names (not
     * {@code name} / {@code arguments} / {@code success} / {@code result}); the
     * shape is otherwise symmetric.
     */
    @SuppressWarnings("unchecked")
    private void flipResolvedSegments(java.util.Map<String, Object> meta,
                                      String toolName, String toolArgs,
                                      MetadataDecision decision) {
        Object segs = meta.get("segments");
        if (!(segs instanceof java.util.List)) return;
        boolean approved = decision == MetadataDecision.APPROVED;
        String resultText = approved ? "[已批准]" : "[已拒绝]";
        for (Object entry : (java.util.List<Object>) segs) {
            if (!(entry instanceof java.util.Map)) continue;
            java.util.Map<String, Object> seg = (java.util.Map<String, Object>) entry;
            if (!"tool_call".equals(String.valueOf(seg.get("type")))) continue;
            if (!matchesNameAndArgs(seg.get("toolName"), seg.get("toolArgs"), toolName, toolArgs)) continue;
            Object status = seg.get("status");
            if ("awaiting_approval".equals(String.valueOf(status))
                    || "running".equals(String.valueOf(status))) {
                seg.put("status", "completed");
            }
            seg.put("toolSuccess", approved ? Boolean.TRUE : Boolean.FALSE);
            seg.put("toolResult", resultText);
        }
    }

    private static boolean matchesNameAndArgs(Object actualName, Object actualArgs,
                                              String expectedName, String expectedArgs) {
        if (expectedName == null || actualName == null) return false;
        if (!expectedName.equals(String.valueOf(actualName))) return false;
        // Arguments equality: pendingApproval stores them as the JSON-stringified form
        // produced by the tool-call creator, identical to what's recorded on the
        // toolCall / segment entry. A null comparator on either side falls through.
        if (expectedArgs == null) return true;
        return expectedArgs.equals(String.valueOf(actualArgs));
    }

    @Transactional
    public void removeApprovalPlaceholders(String conversationId) {
        List<MessageEntity> messages = listMessages(conversationId);
        int removed = 0;
        for (int i = messages.size() - 1; i >= 0; i--) {
            MessageEntity msg = messages.get(i);
            if ("assistant".equals(msg.getRole()) && isApprovalPlaceholder(msg.getContent())) {
                messageMapper.deleteById(msg.getId());
                removed++;
            }
        }
        if (removed > 0) {
            log.info("[ConversationService] Removed {} approval placeholder(s) from conversation {}",
                    removed, conversationId);
        }
    }

    private static boolean isApprovalPlaceholder(String content) {
        return ApprovalPlaceholderUtil.isApprovalPlaceholder(content);
    }

    /**
     * 检查会话是否存在
     */
    public boolean conversationExists(String conversationId) {
        return conversationMapper.selectCount(
                new LambdaQueryWrapper<ConversationEntity>()
                        .eq(ConversationEntity::getConversationId, conversationId)) > 0;
    }

    /**
     * 校验用户是否拥有该会话。
     * 定时任务产生的会话（username=system）对所有登录用户可见。
     */
    public boolean isConversationOwner(String conversationId, String username) {
        ConversationEntity conv = conversationMapper.selectOne(
                new LambdaQueryWrapper<ConversationEntity>()
                        .eq(ConversationEntity::getConversationId, conversationId));
        if (conv == null) {
            return false;
        }
        return username.equals(conv.getUsername()) || SYSTEM_USER.equals(conv.getUsername());
    }

    /**
     * 获取会话的持久化流状态
     */
    public String getStreamStatus(String conversationId) {
        ConversationEntity conv = conversationMapper.selectOne(
                new LambdaQueryWrapper<ConversationEntity>()
                        .eq(ConversationEntity::getConversationId, conversationId));
        return conv != null ? conv.getStreamStatus() : null;
    }

    private static final Path UPLOAD_ROOT = Paths.get("data", "chat-uploads");

    /**
     * 清理会话关联的附件文件
     */
    public void cleanAttachmentFiles(String conversationId) {
        Path dir;
        try {
            dir = UPLOAD_ROOT.resolve(conversationId);
        } catch (InvalidPathException e) {
            // Conversation id contains characters illegal on this filesystem
            // (e.g. ':' in cron:<jobId> on Windows). No attachments could
            // ever have been written under such an id on this OS, so there
            // is nothing to clean.
            log.debug("Skipping attachment cleanup for non-path-safe conversation id: {}", conversationId);
            return;
        }
        if (!Files.exists(dir)) {
            return;
        }
        try (Stream<Path> walk = Files.walk(dir)) {
            walk.sorted(Comparator.reverseOrder())
                    .forEach(p -> {
                        try {
                            Files.deleteIfExists(p);
                        } catch (IOException e) {
                            log.warn("Failed to delete attachment file: {}", p, e);
                        }
                    });
            log.info("Cleaned attachment files for conversation: {}", conversationId);
        } catch (IOException e) {
            log.warn("Failed to walk attachment directory for conversation: {}", conversationId, e);
        }
    }
}
