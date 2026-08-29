package vip.mate.interop.a2a;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.time.Duration;

@Configuration
public class A2aTaskStoreConfiguration {

    @Bean
    public A2aTaskStore a2aTaskStore(A2aProperties properties) {
        return new A2aTaskStore(properties.getMaxTasks(), Duration.ofSeconds(properties.getTaskTtlSeconds()));
    }
}
