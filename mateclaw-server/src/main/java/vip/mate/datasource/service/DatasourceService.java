package vip.mate.datasource.service;

import cn.hutool.crypto.SecureUtil;
import cn.hutool.crypto.symmetric.AES;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import vip.mate.datasource.model.DatasourceEntity;
import vip.mate.datasource.repository.DatasourceMapper;
import vip.mate.exception.MateClawException;

import java.nio.charset.StandardCharsets;
import java.time.LocalDateTime;
import java.util.Arrays;
import java.util.List;

/**
 * 数据源业务服务
 *
 * @author MateClaw Team
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class DatasourceService {

    private final DatasourceMapper datasourceMapper;
    private final DatasourceConnectionManager connectionManager;

    @Value("${mateclaw.datasource.encrypt-key:MateClaw@2024Key!}")
    private String encryptKey;

    // ==================== CRUD ====================

    public List<DatasourceEntity> listAll() {
        List<DatasourceEntity> list = datasourceMapper.selectList(
                new LambdaQueryWrapper<DatasourceEntity>().orderByDesc(DatasourceEntity::getCreateTime));
        list.forEach(this::maskPassword);
        return list;
    }

    public List<DatasourceEntity> listEnabled() {
        return datasourceMapper.selectList(
                new LambdaQueryWrapper<DatasourceEntity>()
                        .eq(DatasourceEntity::getEnabled, true)
                        .orderByAsc(DatasourceEntity::getName));
    }

    public DatasourceEntity getById(Long id) {
        DatasourceEntity entity = datasourceMapper.selectById(id);
        if (entity == null) {
            throw new MateClawException("err.datasource.not_found", "数据源不存在: " + id);
        }
        return entity;
    }

    public DatasourceEntity getByIdMasked(Long id) {
        DatasourceEntity entity = getById(id);
        maskPassword(entity);
        return entity;
    }

    public DatasourceEntity create(DatasourceEntity entity) {
        if (entity.getEnabled() == null) {
            entity.setEnabled(true);
        }
        encryptPassword(entity);
        datasourceMapper.insert(entity);
        return entity;
    }

    public DatasourceEntity update(DatasourceEntity entity) {
        DatasourceEntity existing = getById(entity.getId());
        // 如果前端传回的密码是脱敏值，保留原密码
        if ("******".equals(entity.getPassword()) || entity.getPassword() == null) {
            entity.setPassword(existing.getPassword());
        } else {
            encryptPassword(entity);
        }
        datasourceMapper.updateById(entity);
        // 失效连接池缓存
        connectionManager.invalidate(entity.getId());
        return entity;
    }

    public void delete(Long id) {
        datasourceMapper.deleteById(id);
        connectionManager.invalidate(id);
    }

    public DatasourceEntity toggle(Long id, boolean enabled) {
        DatasourceEntity entity = getById(id);
        entity.setEnabled(enabled);
        datasourceMapper.updateById(entity);
        if (!enabled) {
            connectionManager.invalidate(id);
        }
        return entity;
    }

    // ==================== 连接测试 ====================

    public boolean testConnection(Long id) {
        DatasourceEntity entity = getById(id);
        decryptPassword(entity);
        boolean ok = connectionManager.testConnection(entity);
        // 更新测试结果
        entity.setLastTestTime(LocalDateTime.now());
        entity.setLastTestOk(ok);
        encryptPassword(entity);
        datasourceMapper.updateById(entity);
        return ok;
    }

    // ==================== 内部方法供 Tool 使用 ====================

    /**
     * 获取解密密码后的实体（供 Tool 层获取连接用）
     */
    public DatasourceEntity getDecrypted(Long id) {
        DatasourceEntity entity = getById(id);
        decryptPassword(entity);
        return entity;
    }

    // ==================== 加解密 ====================

    private AES getAes() {
        byte[] key = Arrays.copyOf(encryptKey.getBytes(StandardCharsets.UTF_8), 16);
        return SecureUtil.aes(key);
    }

    private void encryptPassword(DatasourceEntity entity) {
        if (entity.getPassword() != null && !entity.getPassword().isBlank()) {
            entity.setPassword(getAes().encryptHex(entity.getPassword()));
        }
    }

    private void decryptPassword(DatasourceEntity entity) {
        if (entity.getPassword() != null && !entity.getPassword().isBlank()) {
            try {
                entity.setPassword(getAes().decryptStr(entity.getPassword()));
            } catch (Exception e) {
                log.warn("密码解密失败（可能是明文存储的旧数据）: {}", entity.getName());
            }
        }
    }

    private void maskPassword(DatasourceEntity entity) {
        if (entity.getPassword() != null && !entity.getPassword().isBlank()) {
            entity.setPassword("******");
        }
    }
}
