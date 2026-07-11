package vip.mate.wiki.support;

import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.baomidou.mybatisplus.core.metadata.TableInfoHelper;
import org.apache.ibatis.builder.MapperBuilderAssistant;

/**
 * Initialises MyBatis-Plus lambda caches for entity classes so that pure
 * Mockito unit tests can build {@code LambdaQueryWrapper}/
 * {@code LambdaUpdateWrapper} instances without a Spring/MyBatis context.
 *
 * <p>Normally the caches are populated lazily when MyBatis processes a mapper
 * at startup. In a plain {@code @ExtendWith(MockitoExtension.class)} test the
 * production code inside a service still constructs lambda wrappers
 * ({@code WikiRawMaterialEntity::getId} etc.), which triggers
 * {@code AbstractLambdaWrapper.tryInitCache()} and throws
 * {@code MybatisPlusException: can not find lambda cache for this entity}.
 * Calling {@link #init(Class...)} from a {@code @BeforeAll} /
 * {@code @BeforeEach} pre-populates the cache so those wrappers resolve.
 *
 * <p>Idempotent — safe to call from multiple test classes.
 *
 * @author MateClaw Team
 */
public final class MybatisPlusLambdaCacheInitializer {

    private MybatisPlusLambdaCacheInitializer() {
        // static helper only
    }

    /**
     * Register lambda column caches for the given entity classes.
     *
     * @param entityClasses one or more {@code @TableName}-annotated entity classes
     */
    @SafeVarargs
    public static void init(Class<?>... entityClasses) {
        MybatisConfiguration configuration = new MybatisConfiguration();
        MapperBuilderAssistant assistant = new MapperBuilderAssistant(configuration, "");
        for (Class<?> entityClass : entityClasses) {
            // TableInfoHelper.initTableInfo is a no-op when the entity was
            // already registered by a prior test in the same JVM.
            TableInfoHelper.initTableInfo(assistant, entityClass);
        }
    }
}
