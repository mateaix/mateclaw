package vip.mate.wiki.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import lombok.RequiredArgsConstructor;
import org.springframework.scheduling.support.CronExpression;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import vip.mate.exception.MateClawException;
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
                                         String cronExpr, Boolean enabled) {
        validateAlias(alias);
        validateFileFilter(fileFilter);
        pathValidator.validatePatternBase(path);
        validateCronExpr(cronExpr);
        assertAliasAvailable(kbId, alias, null);
        WikiKnowledgeBaseEntity kb = kbService.getById(kbId);
        if (kb == null) {
            throw new MateClawException(404, "Knowledge base not found: " + kbId);
        }
        WikiSourceGroupEntity group = new WikiSourceGroupEntity();
        group.setKbId(kbId);
        group.setWorkspaceId(kb.getWorkspaceId());
        group.setAlias(alias);
        group.setPath(path);
        group.setFileFilter(fileFilter);
        group.setCronExpr(cronExpr);
        group.setEnabled(enabled == null || enabled ? 1 : 0);
        groupMapper.insert(group);
        return group;
    }

    @Transactional
    public WikiSourceGroupEntity update(WikiSourceGroupEntity group, String alias, String path, String fileFilter,
                                         String cronExpr, Boolean enabled) {
        if (path != null) {
            pathValidator.validatePatternBase(path);
            group.setPath(path);
        }
        if (alias != null) {
            validateAlias(alias);
            assertAliasAvailable(group.getKbId(), alias, group.getId());
            group.setAlias(alias);
        }
        // null = don't touch; non-null (incl. "") = validate then set — allows clearing
        if (fileFilter != null) {
            validateFileFilter(fileFilter);
            group.setFileFilter(fileFilter.isBlank() ? null : fileFilter);
        }
        if (cronExpr != null) {
            validateCronExpr(cronExpr);
            group.setCronExpr(cronExpr.isBlank() ? null : cronExpr);
        }
        if (enabled != null) {
            group.setEnabled(enabled ? 1 : 0);
        }
        groupMapper.updateById(group);
        return group;
    }

    private void validateCronExpr(String cronExpr) {
        if (cronExpr == null || cronExpr.isBlank()) {
            return;
        }
        try {
            CronExpression.parse(cronExpr);
        } catch (IllegalArgumentException e) {
            throw new MateClawException(400, "Cron 表达式非法: " + e.getMessage());
        }
    }

    /**
     * 校验 alias 非空、非纯空白，且不超过 DB 列长度（VARCHAR(128)）。
     */
    private void validateAlias(String alias) {
        if (alias == null || alias.isBlank()) {
            throw new MateClawException(400, "分组别名不能为空");
        }
        if (alias.length() > 128) {
            throw new MateClawException(400, "分组别名过长（最多 128 字符）");
        }
    }

    /**
     * 校验 fileFilter glob 语法，非法模式（如未闭合的 {@code [}）在 create/update 时
     * 就拒绝，而不是留到扫描时冒泡成 500。空字符串视为"清除过滤器"，合法。
     */
    private void validateFileFilter(String fileFilter) {
        if (fileFilter == null || fileFilter.isBlank()) {
            return;
        }
        try {
            java.nio.file.FileSystems.getDefault().getPathMatcher("glob:" + fileFilter);
        } catch (java.util.regex.PatternSyntaxException e) {
            throw new MateClawException(400, "文件过滤器语法非法: " + e.getMessage());
        }
    }

    private void assertAliasAvailable(Long kbId, String alias, Long excludeGroupId) {
        Long count = groupMapper.selectCount(new LambdaQueryWrapper<WikiSourceGroupEntity>()
                .eq(WikiSourceGroupEntity::getKbId, kbId)
                .eq(WikiSourceGroupEntity::getAlias, alias)
                .ne(excludeGroupId != null, WikiSourceGroupEntity::getId, excludeGroupId));
        if (count != null && count > 0) {
            throw new MateClawException(400, "分组别名已存在: " + alias);
        }
    }

    /**
     * 删除分组。{@code reassignTo} 非 null 时把组下 raw 改挂到该分组，
     * 否则默认置为未分组（groupId = null）。
     * <p>
     * 删除前把 alias 改写成墓碑名：{@code @TableLogic} 会让软删行对
     * {@link #assertAliasAvailable} 的查重不可见，若不改名，同名分组永远建不回来
     * （查重通过后 INSERT 撞 {@code uk_wiki_sgroup_kb_alias} 唯一索引，裸 500）。
     */
    @Transactional
    public void delete(Long kbId, Long groupId, Long reassignTo) {
        rawService.reassignGroup(kbId, groupId, reassignTo);
        WikiSourceGroupEntity group = groupMapper.selectById(groupId);
        if (group != null) {
            groupMapper.update(null, new LambdaUpdateWrapper<WikiSourceGroupEntity>()
                    .eq(WikiSourceGroupEntity::getId, groupId)
                    .set(WikiSourceGroupEntity::getAlias, group.getAlias() + "#del#" + groupId));
        }
        groupMapper.deleteById(groupId);
    }

    public void touchLastScanAt(Long groupId) {
        groupMapper.update(null, new LambdaUpdateWrapper<WikiSourceGroupEntity>()
                .eq(WikiSourceGroupEntity::getId, groupId)
                .set(WikiSourceGroupEntity::getLastScanAt, LocalDateTime.now()));
    }
}
