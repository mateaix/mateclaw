package vip.mate.wiki.sse;

import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.io.IOException;
import java.util.Iterator;
import java.util.List;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * RFC-012 M3：Wiki 处理进度事件总线。
 * <p>
 * 维护 {kbId → SseEmitter[]} 订阅表，把后端处理过程中的关键事件
 * （raw.started / chunk.done / raw.completed / raw.failed）实时推送给所有
 * 订阅者（一个 KB 可被多 Tab / 多用户同时打开）。
 * <p>
 * 设计要点：
 * <ul>
 *   <li>订阅者列表用 {@link CopyOnWriteArrayList}，broadcast 时无需加锁；</li>
 *   <li>事件是 best-effort，不持久化、不重发——客户端断线后由前端 60s 兜底
 *       拉取（{@code GET .../processing-status} 走 DB）补齐；</li>
 *   <li>broadcast 时若某个 emitter send 失败，立即从订阅表中剔除并 complete。</li>
 * </ul>
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class WikiProgressBus {

    /** 事件名常量（前端按 EventSource.addEventListener 名称匹配） */
    public static final String EVENT_RAW_STARTED   = "raw.started";
    public static final String EVENT_ROUTE_DONE    = "route.done";
    public static final String EVENT_CHUNK_DONE    = "chunk.done";
    public static final String EVENT_RAW_COMPLETED = "raw.completed";
    public static final String EVENT_RAW_FAILED    = "raw.failed";
    /** Non-blocking warning on an otherwise-completed material (async sub-step failed). */
    public static final String EVENT_RAW_WARNING   = "raw.warning";
    public static final String EVENT_HEARTBEAT     = "heartbeat";

    private final ObjectMapper objectMapper;

    /** kbId → 订阅者列表 */
    private final ConcurrentHashMap<Long, CopyOnWriteArrayList<SseEmitter>> subscribers = new ConcurrentHashMap<>();

    /**
     * 订阅指定 KB 的处理进度事件。
     */
    public void subscribe(Long kbId, SseEmitter emitter) {
        if (kbId == null || emitter == null) return;
        subscribers.computeIfAbsent(kbId, k -> new CopyOnWriteArrayList<>()).add(emitter);
        log.debug("[WikiProgress] subscribe kbId={}, total={}", kbId, subscribers.get(kbId).size());
    }

    /**
     * 反订阅；通常由 emitter 的 onCompletion / onTimeout / onError 回调触发。
     */
    public void unsubscribe(Long kbId, SseEmitter emitter) {
        if (kbId == null || emitter == null) return;
        CopyOnWriteArrayList<SseEmitter> list = subscribers.get(kbId);
        if (list != null) {
            list.remove(emitter);
            if (list.isEmpty()) subscribers.remove(kbId, list);
        }
        log.debug("[WikiProgress] unsubscribe kbId={}", kbId);
    }

    /**
     * 向 kb 下所有订阅者广播一个事件。本方法对内部失败完全静默——
     * 处理管线不应被订阅端 IO 影响。
     */
    public void broadcast(Long kbId, String eventName, Object data) {
        if (kbId == null || eventName == null) return;
        CopyOnWriteArrayList<SseEmitter> list = subscribers.get(kbId);
        if (list == null || list.isEmpty()) return;

        String payload;
        try {
            payload = objectMapper.writeValueAsString(data);
        } catch (Exception e) {
            payload = "{\"message\":\"serialization_error\"}";
        }

        Iterator<SseEmitter> it = list.iterator();
        while (it.hasNext()) {
            SseEmitter emitter = it.next();
            try {
                emitter.send(SseEmitter.event().name(eventName).data(payload));
            } catch (IOException | IllegalStateException e) {
                // 客户端早断、emitter 已 complete 等：直接踢出，避免后续广播继续踩雷
                list.remove(emitter);
                try { emitter.complete(); } catch (Exception ignore) { /* best-effort */ }
                log.debug("[WikiProgress] dropped emitter for kbId={}: {}", kbId, e.getMessage());
            }
        }
    }

    /**
     * 仅供监控/测试：当前订阅者数（所有 KB 之和）。
     */
    public int totalSubscribers() {
        int total = 0;
        for (List<SseEmitter> list : subscribers.values()) total += list.size();
        return total;
    }

    /**
     * 心跳广播：在没有真实事件流动时定期发一个 heartbeat，
     * 防止反向代理（如 nginx 默认 60s idle）切断长连接。由调用方按需触发。
     */
    public void heartbeat(Long kbId) {
        broadcast(kbId, EVENT_HEARTBEAT, java.util.Map.of("ts", System.currentTimeMillis()));
    }
}
