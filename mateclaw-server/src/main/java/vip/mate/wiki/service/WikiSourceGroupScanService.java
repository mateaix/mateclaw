package vip.mate.wiki.service;

import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import vip.mate.exception.MateClawException;
import vip.mate.wiki.model.WikiSourceGroupEntity;
import vip.mate.wiki.service.WikiDirectoryScanService.ScanResult;

/**
 * 按来源分组扫描的薄封装：校验分组归属，委托扫描逻辑给 {@link WikiDirectoryScanService}，
 * 扫描完成后回写分组的 lastScanAt。
 *
 * <p><b>不加 @Transactional</b>：scanGroup 内部逐文件 ingest/reprocess，每个文件独立提交。
 * 如果整个扫描包在一个事务里，200 文件的全量扫描会产生持续几十秒的长事务，锁定大量 raw
 * 行并增加死锁风险。group 归属校验 + tagGroupIfNeeded 只在 groupId 为 null 时补写，
 * 不覆盖已有归属，足以保证一致性。
 *
 * <p><b>并发与删除竞态</b>：{@code delete} 在独立事务内做 reassign→tombstone→soft-delete
 * 三步。极端竞态下（delete 已 reassign 但未提交时 scan 读取）可能把刚清空的 raw 重新打标。
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
