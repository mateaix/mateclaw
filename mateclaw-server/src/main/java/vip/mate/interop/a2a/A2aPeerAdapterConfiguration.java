package vip.mate.interop.a2a;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.time.Duration;

@Configuration
public class A2aPeerAdapterConfiguration {

    @Bean
    public A2aPeerAdapter a2aPeerAdapter(ObjectMapper objectMapper, A2aProperties properties) {
        A2aPeerAdapter.Policy policy = new A2aPeerAdapter.Policy(
                Duration.ofMillis(properties.getOutboundTimeoutMs()),
                properties.getMaxResponseBytes(),
                properties.isAllowPrivateOutbound()
        );
        return new A2aPeerAdapter(objectMapper, policy);
    }
}
