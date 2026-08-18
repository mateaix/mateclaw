package vip.mate.skill.routine;

import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Configuration;

/**
 * Registers configuration for routine mining.
 *
 * @author MateClaw Team
 */
@Configuration
@EnableConfigurationProperties(SkillRoutineProperties.class)
public class SkillRoutineAutoConfiguration {
}
