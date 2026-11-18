package vip.mate.llm.service;
import org.junit.jupiter.api.Test;
import java.util.Map;
import static org.junit.jupiter.api.Assertions.*;
class ModelParameterValidatorTest {
    @Test void acceptedLegacyAliasesStringsAndVendorExtensionsAreNotRewritten() {
        var kwargs = Map.<String,Object>of("chat_options", Map.of("temperature","3.2","top_p","0.8","maxTokens","4096"),
                "custom_vendor",Map.of("allowed",true));
        assertDoesNotThrow(() -> ModelParameterValidator.validateGenerateKwargs(kwargs));
        assertEquals("4096",((Map<?,?>)kwargs.get("chat_options")).get("maxTokens"));
        assertDoesNotThrow(() -> ModelParameterValidator.validateGenerateKwargs(Map.of("topP",0,"max_completion_tokens",Integer.MAX_VALUE)));
        assertDoesNotThrow(() -> ModelParameterValidator.validateGenerateKwargs(Map.of("maxTokens",new java.math.BigDecimal("4096.000"))));
        assertDoesNotThrow(() -> ModelParameterValidator.validateGenerateKwargs(null));
    }
    @Test void onlyEffectiveOptionsAreValidatedUsingRuntimePrecedence() {
        assertDoesNotThrow(() -> ModelParameterValidator.validateGenerateKwargs(Map.of("topP",.5,"top_p",2)));
        assertDoesNotThrow(() -> ModelParameterValidator.validateGenerateKwargs(Map.of("temperature",1,"chatOptions",Map.of("temperature",-1))));
    }
}
