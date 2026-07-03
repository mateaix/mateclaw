package vip.mate.wiki.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import vip.mate.wiki.model.WikiKnowledgeBaseEntity;
import vip.mate.wiki.model.WikiSourceGroupEntity;
import vip.mate.wiki.repository.WikiSourceGroupMapper;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;

/**
 * Wiki 来源分组服务：一个知识库的 sourceDirectory 拆分出的独立可管理路径/glob。
 *
 * @author MateClaw Team
 */
@Service
@RequiredArgsConstructor
public class WikiSourceGroupService {

    private final WikiSourceGroupMapper groupMapper;
    private final WikiKnowledgeBaseService kbService;
    private final WikiSourcePathValidator pathValidator;
    private final WikiRawMaterialService rawService;

    public List<WikiSourceGroupEntity> listByKbId(Long kbId) {
        return groupMapper.selectList(
                new LambdaQueryWrapper<WikiSourceGroupEntity>()
                        .eq(WikiSourceGroupEntity::getKbId, kbId)
                        .orderByAsc(WikiSourceGroupEntity::getCreateTime));
    }

    public WikiSourceGroupEntity getById(Long id) {
        return groupMapper.selectById(id);
    }

    /** 各分组下的 raw 数量，一次聚合查询避免 N+1。 */
    public Map<Long, Long> countRawByKbId(Long kbId) {
        return rawService.countRawByGroup(kbId);
    }

    @Transactional
    public WikiSourceGroupEntity create(Long kbId, String alias, String path, String fileFilter,
                                         String scanMode, String cronExpr, Boolean enabled) {
        pathValidator.validatePatternBase(path);
        WikiKnowledgeBaseEntity kb = kbService.getById(kbId);
        WikiSourceGroupEntity group = new WikiSourceGroupEntity();
        group.setKbId(kbId);
        group.setWorkspaceId(kb != null ? kb.getWorkspaceId() : null);
        group.setAlias(alias);
        group.setPath(path);
        group.setFileFilter(fileFilter);
        group.setScanMode(scanMode == null || scanMode.isBlank() ? "incremental" : scanMode);
        group.setCronExpr(cronExpr);
        group.setEnabled(enabled == null || enabled ? 1 : 0);
        groupMapper.insert(group);
        return group;
    }

    @Transactional
    public WikiSourceGroupEntity update(WikiSourceGroupEntity group, String alias, String path, String fileFilter,
                                         String scanMode, String cronExpr, Boolean enabled) {
        if (path != null) {
            pathValidator.validatePatternBase(path);
            group.setPath(path);
        }
        if (alias != null) {
            group.setAlias(alias);
        }
        if (fileFilter != null) {
            group.setFileFilter(fileFilter);
        }
        if (scanMode != null) {
            group.setScanMode(scanMode);
        }
        if (cronExpr != null) {
            group.setCronExpr(cronExpr);
        }
        if (enabled != null) {
            group.setEnabled(enabled ? 1 : 0);
        }
        groupMapper.updateById(group);
        return group;
    }

    /**
     * 删除分组。{@code reassignTo} 非 null 时把组下 raw 改挂到该分组，
     * 否则默认置为未分组（groupId = null）。
     */
    @Transactional
    public void delete(Long kbId, Long groupId, Long reassignTo) {
        rawService.reassignGroup(kbId, groupId, reassignTo);
        groupMapper.deleteById(groupId);
    }

    public void touchLastScanAt(Long groupId) {
        groupMapper.update(null, new LambdaUpdateWrapper<WikiSourceGroupEntity>()
                .eq(WikiSourceGroupEntity::getId, groupId)
                .set(WikiSourceGroupEntity::getLastScanAt, LocalDateTime.now()));
    }
}
