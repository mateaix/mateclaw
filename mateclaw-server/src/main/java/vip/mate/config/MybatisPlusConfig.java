package vip.mate.config;

import com.baomidou.mybatisplus.core.handlers.MetaObjectHandler;
import org.apache.ibatis.reflection.MetaObject;
import org.springframework.stereotype.Component;

import java.time.LocalDateTime;

/**
 * MyBatis Plus 自动填充配置
 *
 * @author MateClaw Team
 */
@Component
public class MybatisPlusConfig implements MetaObjectHandler {

    @Override
    public void insertFill(MetaObject metaObject) {
        this.strictInsertFill(metaObject, "createTime", LocalDateTime.class, LocalDateTime.now());
        this.strictInsertFill(metaObject, "updateTime", LocalDateTime.class, LocalDateTime.now());
    }

    @Override
    public void updateFill(MetaObject metaObject) {
        // Use setFieldValByName (unconditional) rather than strictUpdateFill,
        // which is a no-op when the field already has a value. After a row
        // is loaded from the DB its updateTime is non-null, so the strict
        // variant left the column frozen at insert time forever — every
        // mate_* table inherited this. Unconditional fill matches the
        // documented "updateTime moves on every update" expectation.
        this.setFieldValByName("updateTime", LocalDateTime.now(), metaObject);
    }
}
