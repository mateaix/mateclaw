package vip.mate.wiki.service;

import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import vip.mate.exception.MateClawException;
import vip.mate.wiki.model.WikiSourceGroupEntity;
import vip.mate.wiki.service.WikiDirectoryScanService.ScanResult;

/**
 * 按来源分组扫描的薄封装：校验分组归属，委托扫描逻辑给 {@link WikiDirectoryScanService}，
 * 扫描完成后回写分组的 lastScanAt。
 *
 * <p><b>并发与删除竞态</b>：{@code delete} 在独立事务内做 reassign→tombstone→soft-delete
 * 三步。本方法虽然标记了 {@code @Transactional}，但与 delete 事务之间没有悲观锁，
 * 极端竞态下（delete 已 reassign 但未提交时 scan 读取）可能把刚清空的 raw 重新打标。
 * 实际影响有限：tagGroupIfNeeded 只在 groupId 为 null 时补写，不覆盖已有归属；
 * 且 delete 事务提交后 selectById 因 @TableLogic 返回 null → 404 拦截后续扫描。
 *
 * @author MateClaw Team
 */
@Service
@RequiredArgsConstructor
public class WikiSourceGroupScanService {

    private final WikiSourceGroupService groupService;
    private final WikiDirectoryScanService scanService;

    @Transactional
    public ScanResult scan(Long kbId, Long groupId, boolean full) {
        WikiSourceGroupEntity group = groupService.getById(groupId);
        if (group == null || !kbId.equals(group.getKbId())) {
            throw new MateClawException(404, "Source group not found in this knowledge base");
        }
        ScanResult result = scanService.scanGroup(kbId, group, full);
        groupService.touchLastScanAt(groupId);
        return result;
    }
}
