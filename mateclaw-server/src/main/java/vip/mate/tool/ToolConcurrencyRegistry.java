package vip.mate.tool;

import jakarta.annotation.PostConstruct;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.beans.factory.config.BeanDefinition;
import org.springframework.beans.factory.config.ConfigurableListableBeanFactory;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.stereotype.Component;
import org.springframework.util.ClassUtils;

import java.lang.reflect.Method;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashSet;
import java.util.Set;

/**
 * Startup-scanned registry of tools that must run sequentially
 * ({@link ConcurrencyUnsafe}-annotated). Replaces the previous hardcoded
 * {@code DEFAULT_UNSAFE_TOOLS} set in {@code ToolExecutionExecutor}.
 *
 * <p>Discovery walks every bean <b>definition</b> and inspects the declared
 * class's methods for the {@link Tool} + {@link ConcurrencyUnsafe} pair.
 * Beans are <b>not instantiated</b> by this scan — we only resolve the bean
 * class name and load it via the class loader, which preserves {@code @Lazy}
 * semantics and avoids triggering ChatModel / DataSource / MCP-client
 * construction at registry init.</p>
 *
 * <p>Tool name resolution mirrors Spring AI's logic: {@code @Tool#name()}
 * when set, otherwise the method's simple name.</p>
 *
 * <p>The registry is immutable after {@link #scan()}; the unsafe set is
 * populated once and consulted on every tool execution. MCP tools are not
 * scanned (their {@link Tool} annotations live inside the MCP framework, not
 * on user-visible methods); MCP support is tracked as a follow-up.</p>
 */
@Slf4j
@Component
public class ToolConcurrencyRegistry {

    private final ConfigurableApplicationContext applicationContext;

    /** Populated once at startup; never mutated thereafter. */
    private volatile Set<String> unsafeNames = Collections.emptySet();

    public ToolConcurrencyRegistry(ConfigurableApplicationContext applicationContext) {
        this.applicationContext = applicationContext;
    }

    @PostConstruct
    void scan() {
        Set<String> discovered = new HashSet<>();
        ConfigurableListableBeanFactory factory = applicationContext.getBeanFactory();
        ClassLoader classLoader = applicationContext.getClassLoader();

        for (String beanName : factory.getBeanDefinitionNames()) {
            Class<?> beanClass = resolveBeanClassWithoutInstantiating(factory, beanName, classLoader);
            if (beanClass == null) continue;

            // Unwrap CGLIB subclasses (proxies) so we see user-declared methods.
            Class<?> userClass = ClassUtils.getUserClass(beanClass);
            for (Method method : userClass.getDeclaredMethods()) {
                Tool tool = method.getAnnotation(Tool.class);
                if (tool == null) continue;
                ConcurrencyUnsafe unsafe = method.getAnnotation(ConcurrencyUnsafe.class);
                if (unsafe == null) continue;
                String toolName = tool.name() != null && !tool.name().isEmpty() ? tool.name() : method.getName();
                discovered.add(toolName);
                log.info("[ToolConcurrencyRegistry] Marked tool '{}' as unsafe ({}#{}): {}",
                        toolName, userClass.getSimpleName(), method.getName(),
                        unsafe.value().isEmpty() ? "no reason given" : unsafe.value());
            }
        }
        // Keep the legacy hardcoded names so existing deployments without
        // annotations still see the same behavior. New code should rely on
        // the @ConcurrencyUnsafe annotation rather than this list.
        discovered.addAll(Arrays.asList("browser_use", "BrowserUseTool", "write_file", "append_file", "edit_file"));
        this.unsafeNames = Collections.unmodifiableSet(discovered);
        log.info("[ToolConcurrencyRegistry] Concurrency-unsafe tools ({}): {}",
                unsafeNames.size(), unsafeNames);
    }

    /**
     * Resolve a bean's class without instantiating it.
     * Preference order:
     * <ol>
     *   <li>{@link BeanDefinition#getBeanClassName()} → {@link Class#forName} via the context class loader
     *       (works for stereotype-scanned components).</li>
     *   <li>{@code factory.getType(beanName, false)} as a fallback for
     *       {@code @Bean}-defined or programmatically registered beans.
     *       The {@code false} flag forbids FactoryBean initialization.</li>
     * </ol>
     * Returns {@code null} when neither path yields a class — for example,
     * lambda-defined beans without a resolvable class name.
     */
    private static Class<?> resolveBeanClassWithoutInstantiating(ConfigurableListableBeanFactory factory,
                                                                  String beanName,
                                                                  ClassLoader classLoader) {
        try {
            BeanDefinition bd = factory.getBeanDefinition(beanName);
            String className = bd.getBeanClassName();
            if (className != null && !className.isEmpty()) {
                try {
                    return Class.forName(className, false, classLoader);
                } catch (ClassNotFoundException | LinkageError ignored) {
                    // Fall through to factory.getType fallback.
                }
            }
        } catch (Exception ignored) {
            // No bean definition (singleton registered programmatically); fall through.
        }
        try {
            return factory.getType(beanName, false);
        } catch (Exception ignored) {
            return null;
        }
    }

    /** {@code true} when the named tool must execute alone (no parallelism). */
    public boolean isUnsafe(String toolName) {
        return toolName != null && unsafeNames.contains(toolName);
    }

    /** Defensive copy for diagnostics / admin endpoints. */
    public Set<String> snapshot() {
        return unsafeNames;
    }
}
