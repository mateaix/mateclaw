package vip.mate.architecture;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import vip.mate.memory.fact.model.FactContradictionEntity;
import vip.mate.memory.fact.model.FactEntity;
import vip.mate.memory.model.MorningCardSeenEntity;
import vip.mate.wiki.model.WikiHotCacheEntity;
import vip.mate.wiki.model.WikiImageCaptionCacheEntity;
import vip.mate.wiki.model.WikiRelationEntity;
import vip.mate.wiki.model.WikiTransformationEntity;
import vip.mate.wiki.model.WikiTransformationRunEntity;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;

/**
 * Contract test: these entities are backed by tables whose PostgreSQL-compatible
 * dialect defines the primary key as a plain BIGINT without an identity/sequence
 * default. {@code IdType.AUTO} makes MyBatis-Plus omit the id column from the
 * generated INSERT and rely on database-generated keys, which violates the
 * NOT NULL constraint on those databases. Application-assigned snowflake ids
 * ({@code IdType.ASSIGN_ID}) work across every supported dialect, since
 * auto-increment columns also accept explicit values.
 */
class AutoIncrementFreePrimaryKeyTest {

    @ParameterizedTest
    @ValueSource(classes = {
            FactEntity.class,
            FactContradictionEntity.class,
            MorningCardSeenEntity.class,
            WikiHotCacheEntity.class,
            WikiRelationEntity.class,
            WikiTransformationEntity.class,
            WikiTransformationRunEntity.class,
            WikiImageCaptionCacheEntity.class
    })
    void primaryKeyUsesApplicationAssignedId(Class<?> entityClass) throws NoSuchFieldException {
        TableId tableId = entityClass.getDeclaredField("id").getAnnotation(TableId.class);

        assertNotNull(tableId, entityClass.getSimpleName() + ".id must be annotated with @TableId");
        assertEquals(IdType.ASSIGN_ID, tableId.type(),
                entityClass.getSimpleName() + " must use application-assigned ids: its primary key"
                        + " column has no auto-increment default on PostgreSQL-compatible databases");
    }
}
