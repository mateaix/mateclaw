package vip.mate.architecture;

import com.tngtech.archunit.core.domain.JavaClass;
import com.tngtech.archunit.core.domain.JavaClasses;
import com.tngtech.archunit.core.domain.JavaMethod;
import com.tngtech.archunit.core.importer.ClassFileImporter;
import com.tngtech.archunit.core.importer.ImportOption;
import com.tngtech.archunit.lang.ArchCondition;
import com.tngtech.archunit.lang.ConditionEvents;
import com.tngtech.archunit.lang.SimpleConditionEvent;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.model.ToolContext;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.transaction.annotation.Transactional;

import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.classes;
import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses;

/**
 * RFC-063r §2.3: every concrete {@link ToolCallback} implementation must
 * override {@code call(String, ToolContext)} so it cannot silently drop the
 * Spring AI {@link ToolContext} (which carries the {@code ChatOrigin}).
 *
 * <p>Background: the previous {@code LocaleAwareToolCallback} only overrode
 * {@code call(String)}; the framework default routed
 * {@code call(String, ToolContext)} back to {@code call(String)}, dropping the
 * context. This test pins the rule so a future regression fails CI.
 */
class ToolCallbackToolContextForwardArchTest {

    private static final JavaClasses MATECLAW_CLASSES = new ClassFileImporter()
            .withImportOption(ImportOption.Predefined.DO_NOT_INCLUDE_TESTS)
            .importPackages("vip.mate");

    @Test
    void everyToolCallbackImplementationMustOverrideCallWithToolContext() {
        classes()
                .that().implement(ToolCallback.class)
                .and().areNotInterfaces()
                .and().areNotAnnotations()
                .and(haveSimpleNameNot("ToolCallback"))
                .should(overrideCallWithToolContext())
                .check(MATECLAW_CLASSES);
    }

    /**
     * RFC-063r §5.2 hard rule: {@code CronJobRunner} must NEVER carry
     * {@code @Transactional} (class-level or method-level). The class is
     * the entry point for cron-tick execution; an inline transaction would
     * either swallow self-invocation calls or — worse — hold a DB connection
     * across the multi-minute LLM call inside {@code runAgent}, exhausting
     * the HikariCP pool under concurrent cron load.
     *
     * <p>The three transactional segments live on
     * {@code CronJobLifecycleService}; cross-bean invocation routes through
     * the Spring AOP proxy and works as designed. This test pins the rule.
     */
    @Test
    void cronJobRunnerMustNotCarryTransactional() {
        noClasses()
                .that().haveSimpleName("CronJobRunner")
                .and().resideInAPackage("vip.mate.cron..")
                .should(beAnnotatedOrHaveAnyMethodAnnotatedWith(Transactional.class))
                .because("RFC-063r §5.2: CronJobRunner.runAgent runs an LLM HTTP call (seconds-to-minutes); " +
                        "@Transactional would hold a DB connection during that call and exhaust HikariCP under " +
                        "concurrent cron load. Transactions must live on CronJobLifecycleService instead.")
                .check(MATECLAW_CLASSES);
    }

    private static com.tngtech.archunit.base.DescribedPredicate<JavaClass> haveSimpleNameNot(String simpleName) {
        return new com.tngtech.archunit.base.DescribedPredicate<>("simple name is not " + simpleName) {
            @Override
            public boolean test(JavaClass javaClass) {
                return !javaClass.getSimpleName().equals(simpleName);
            }
        };
    }

    private static ArchCondition<JavaClass> beAnnotatedOrHaveAnyMethodAnnotatedWith(
            Class<? extends java.lang.annotation.Annotation> annotation) {
        String desc = annotation.getName();
        return new ArchCondition<>("be annotated or have any method annotated with " + desc) {
            @Override
            public void check(JavaClass clazz, ConditionEvents events) {
                if (clazz.isAnnotatedWith(annotation)) {
                    events.add(SimpleConditionEvent.satisfied(clazz,
                            clazz.getFullName() + " is annotated with " + desc));
                    return;
                }
                for (JavaMethod m : clazz.getMethods()) {
                    if (m.isAnnotatedWith(annotation)) {
                        events.add(SimpleConditionEvent.satisfied(clazz,
                                clazz.getFullName() + "#" + m.getName() + " is annotated with " + desc));
                        return;
                    }
                }
            }
        };
    }

    private static ArchCondition<JavaClass> overrideCallWithToolContext() {
        return new ArchCondition<>("override call(String, ToolContext)") {
            @Override
            public void check(JavaClass clazz, ConditionEvents events) {
                boolean overrides = clazz.getMethods().stream().anyMatch(m ->
                        m.getName().equals("call")
                                && m.getRawParameterTypes().size() == 2
                                && m.getRawParameterTypes().get(0).getFullName().equals(String.class.getName())
                                && m.getRawParameterTypes().get(1).getFullName().equals(ToolContext.class.getName()));
                if (!overrides) {
                    events.add(SimpleConditionEvent.violated(clazz,
                            clazz.getFullName() + " does not override call(String, ToolContext); "
                                    + "the framework default would silently drop the ChatOrigin "
                                    + "(see RFC-063r §2.3)."));
                }
            }
        };
    }
}
