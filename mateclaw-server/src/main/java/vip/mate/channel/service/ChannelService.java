package vip.mate.channel.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Service;
import vip.mate.channel.feishu.FeishuClientFactory;
import vip.mate.channel.model.ChannelEntity;
import vip.mate.channel.repository.ChannelMapper;
import vip.mate.exception.MateClawException;

import java.security.SecureRandom;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 渠道业务服务
 * <p>
 * 负责渠道的 CRUD 管理。
 * 渠道的运行时生命周期由 ChannelManager 管理。
 *
 * @author MateClaw Team
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class ChannelService {

    private final ChannelMapper channelMapper;
    private final ObjectMapper objectMapper;
    /**
     * Cache-eviction hook for the Feishu SDK client. {@link ObjectProvider}
     * defers the lookup so this service stays usable in test contexts
     * that don't load the Feishu beans, and so a future cycle (Feishu
     * components transitively depending on this service) cannot crash
     * Spring's eager constructor wiring.
     */
    private final ObjectProvider<FeishuClientFactory> feishuClientFactoryProvider;
    /**
     * Reconcile hook for channel-native tools. {@link ObjectProvider}
     * for the same reasons as above — service still works in test
     * contexts that don't load the channel-tool subsystem.
     */
    private final ObjectProvider<vip.mate.channel.tool.ChannelToolService> channelToolServiceProvider;
    private static final SecureRandom SECURE_RANDOM = new SecureRandom();

    /**
     * 获取所有渠道列表
     */
    public List<ChannelEntity> listChannels() {
        return channelMapper.selectList(new LambdaQueryWrapper<ChannelEntity>()
                .orderByDesc(ChannelEntity::getEnabled)
                .orderByDesc(ChannelEntity::getCreateTime));
    }

    /**
     * 按工作区列出渠道
     */
    public List<ChannelEntity> listChannelsByWorkspace(Long workspaceId) {
        return channelMapper.selectList(new LambdaQueryWrapper<ChannelEntity>()
                .eq(ChannelEntity::getWorkspaceId, workspaceId)
                .orderByDesc(ChannelEntity::getEnabled)
                .orderByDesc(ChannelEntity::getCreateTime));
    }

    /**
     * 获取已启用的渠道列表（ChannelManager 启动时使用）
     */
    public List<ChannelEntity> listEnabledChannels() {
        return channelMapper.selectList(new LambdaQueryWrapper<ChannelEntity>()
                .eq(ChannelEntity::getEnabled, true)
                .orderByAsc(ChannelEntity::getChannelType));
    }

    /**
     * 按类型获取渠道列表（全局，向后兼容）
     */
    public List<ChannelEntity> listChannelsByType(String channelType) {
        return channelMapper.selectList(new LambdaQueryWrapper<ChannelEntity>()
                .eq(ChannelEntity::getChannelType, channelType)
                .orderByDesc(ChannelEntity::getCreateTime));
    }

    /**
     * 按类型和 workspace 获取渠道列表
     */
    public List<ChannelEntity> listChannelsByTypeAndWorkspace(String channelType, Long workspaceId) {
        return channelMapper.selectList(new LambdaQueryWrapper<ChannelEntity>()
                .eq(ChannelEntity::getChannelType, channelType)
                .eq(ChannelEntity::getWorkspaceId, workspaceId)
                .orderByDesc(ChannelEntity::getCreateTime));
    }

    /**
     * 获取渠道详情
     */
    public ChannelEntity getChannel(Long id) {
        ChannelEntity channel = channelMapper.selectById(id);
        if (channel == null) {
            throw new MateClawException("err.channel.not_found", "渠道不存在: " + id);
        }
        return channel;
    }

    /**
     * 创建渠道
     */
    public ChannelEntity createChannel(ChannelEntity channel) {
        // 验证名称
        if (channel.getName() == null || channel.getName().isBlank()) {
            throw new MateClawException("err.channel.name_required", "渠道名称不能为空");
        }
        if (channel.getChannelType() == null || channel.getChannelType().isBlank()) {
            throw new MateClawException("err.channel.type_required", "渠道类型不能为空");
        }
        if (channel.getEnabled() == null) {
            channel.setEnabled(false);
        }
        if ("webchat".equals(channel.getChannelType())) {
            channel.setConfigJson(enrichWebChatConfig(channel.getConfigJson(), null));
        }
        channelMapper.insert(channel);
        log.info("Created channel: {} (type={})", channel.getName(), channel.getChannelType());
        return channel;
    }

    /**
     * 更新渠道
     */
    public ChannelEntity updateChannel(ChannelEntity channel) {
        ChannelEntity existing = getChannel(channel.getId());
        if ("webchat".equals(channel.getChannelType())) {
            channel.setConfigJson(enrichWebChatConfig(channel.getConfigJson(), existing.getConfigJson()));
        }
        channelMapper.updateById(channel);
        invalidateChannelCaches(channel.getId(), channel.getChannelType());
        log.info("Updated channel: {}", existing.getName());
        return channel;
    }

    /**
     * 删除渠道
     */
    public void deleteChannel(Long id) {
        ChannelEntity channel = getChannel(id);
        channelMapper.deleteById(id);
        invalidateChannelCaches(id, channel.getChannelType());
        log.info("Deleted channel: {}", channel.getName());
    }

    /**
     * 启用/禁用渠道
     */
    public ChannelEntity toggleChannel(Long id, boolean enabled) {
        ChannelEntity channel = getChannel(id);
        channel.setEnabled(enabled);
        channelMapper.updateById(channel);
        invalidateChannelCaches(id, channel.getChannelType());
        log.info("Channel {} {}", channel.getName(), enabled ? "enabled" : "disabled");
        return channel;
    }

    /**
     * Drop any cached per-channel SDK clients / tool registrations
     * after a mutation. Today this only matters for Feishu (whose
     * {@link FeishuClientFactory} caches a client per channelId);
     * RFC 47's channel-tool reconcile service will hook in here too.
     */
    private void invalidateChannelCaches(Long channelId, String channelType) {
        if ("feishu".equals(channelType)) {
            FeishuClientFactory factory = feishuClientFactoryProvider.getIfAvailable();
            if (factory != null) {
                factory.evict(channelId);
            }
        }
        // Trigger an immediate channel-tool reconcile on this node so
        // newly-enabled / -disabled / -reconfigured channels' tool sets
        // align before the next reconcile tick. Other nodes catch up
        // within ChannelToolService.RECONCILE_INTERVAL_SECONDS.
        vip.mate.channel.tool.ChannelToolService cts =
                channelToolServiceProvider.getIfAvailable();
        if (cts != null) {
            try {
                cts.syncNow();
            } catch (Exception e) {
                log.debug("[ChannelService] syncNow() failed (non-fatal): {}", e.getMessage());
            }
        }
    }

    private String enrichWebChatConfig(String incomingConfigJson, String existingConfigJson) {
        Map<String, Object> incoming = parseConfig(incomingConfigJson);
        Map<String, Object> existing = parseConfig(existingConfigJson);

        String existingApiKey = asNonBlankString(existing.get("api_key"));
        incoming.put("api_key", existingApiKey != null ? existingApiKey : generateWebChatApiKey());

        if (!incoming.containsKey("title") && existing.containsKey("title")) {
            incoming.put("title", existing.get("title"));
        }
        if (!incoming.containsKey("placeholder") && existing.containsKey("placeholder")) {
            incoming.put("placeholder", existing.get("placeholder"));
        }
        if (!incoming.containsKey("primary_color") && existing.containsKey("primary_color")) {
            incoming.put("primary_color", existing.get("primary_color"));
        }
        if (!incoming.containsKey("welcome_message") && existing.containsKey("welcome_message")) {
            incoming.put("welcome_message", existing.get("welcome_message"));
        }
        if (!incoming.containsKey("allowed_origins") && existing.containsKey("allowed_origins")) {
            incoming.put("allowed_origins", existing.get("allowed_origins"));
        }

        try {
            return objectMapper.writeValueAsString(incoming);
        } catch (Exception e) {
            throw new MateClawException("WebChat 渠道配置序列化失败: " + e.getMessage());
        }
    }

    private Map<String, Object> parseConfig(String configJson) {
        if (configJson == null || configJson.isBlank()) {
            return new LinkedHashMap<>();
        }
        try {
            return objectMapper.readValue(configJson, new TypeReference<>() {});
        } catch (Exception e) {
            log.warn("Failed to parse channel configJson: {}", e.getMessage());
            return new LinkedHashMap<>();
        }
    }

    private String asNonBlankString(Object value) {
        if (value == null) return null;
        String text = String.valueOf(value).trim();
        return text.isEmpty() ? null : text;
    }

    private String generateWebChatApiKey() {
        byte[] random = new byte[18];
        SECURE_RANDOM.nextBytes(random);
        StringBuilder sb = new StringBuilder("mc_webchat_");
        for (byte b : random) {
            sb.append(Character.forDigit((b >> 4) & 0xF, 16));
            sb.append(Character.forDigit(b & 0xF, 16));
        }
        return sb.toString();
    }
}
