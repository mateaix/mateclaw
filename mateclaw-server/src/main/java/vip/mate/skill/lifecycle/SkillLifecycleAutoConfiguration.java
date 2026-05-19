package vip.mate.skill.lifecycle;

import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Configuration;

/**
 * Auto-configuration for the skill lifecycle curator.
 *
 * @author MateClaw Team
 */
@Configuration
@EnableConfigurationProperties(SkillLifecycleProperties.class)
public class SkillLifecycleAutoConfiguration {
}
