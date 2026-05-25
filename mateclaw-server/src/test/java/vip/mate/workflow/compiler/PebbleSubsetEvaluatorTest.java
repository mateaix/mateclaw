package vip.mate.workflow.compiler;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.HashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class PebbleSubsetEvaluatorTest {

    private PebbleSubsetEvaluator pebble;

    @BeforeEach
    void setUp() {
        pebble = new PebbleSubsetEvaluator();
    }

    @Test
    void parsesAndEvaluatesBareExpression() {
        var compiled = pebble.parseExpression("inputs.tier == 'enterprise'");
        Map<String, Object> ctx = new HashMap<>();
        ctx.put("inputs", Map.of("tier", "enterprise"));
        assertTrue(pebble.evaluateAsBoolean(compiled, ctx));
    }

    @Test
    void parsesWrappedExpression() {
        var compiled = pebble.parseExpression("{{ inputs.tier == 'enterprise' }}");
        Map<String, Object> ctx = new HashMap<>();
        ctx.put("inputs", Map.of("tier", "smb"));
        assertFalse(pebble.evaluateAsBoolean(compiled, ctx));
    }

    @Test
    void renderingTemplateConcatenatesLiteralAndPlaceholder() {
        var compiled = pebble.parseTemplate("Hello {{ user }}, your tier is {{ inputs.tier }}");
        Map<String, Object> ctx = Map.of(
                "user", "Alice",
                "inputs", Map.of("tier", "gold")
        );
        assertEquals("Hello Alice, your tier is gold", pebble.evaluateAsString(compiled, ctx));
    }

    @Test
    void includesTagRejected() {
        ExpressionException e = assertThrows(ExpressionException.class,
                () -> pebble.parseTemplate("{% include 'evil.peb' %}"));
        assertTrue(e.getMessage().toLowerCase().contains("blocked"),
                "include must be flagged as a blocked tag");
    }

    @Test
    void extendsTagRejected() {
        assertThrows(ExpressionException.class,
                () -> pebble.parseTemplate("{% extends 'base.peb' %}"));
    }

    @Test
    void macroTagRejected() {
        assertThrows(ExpressionException.class,
                () -> pebble.parseTemplate("{% macro foo() %}x{% endmacro %}"));
    }

    @Test
    void setTagRejected() {
        assertThrows(ExpressionException.class,
                () -> pebble.parseTemplate("{% set x = 1 %}"));
    }

    @Test
    void andOrNotComparisonsWork() {
        var compiled = pebble.parseExpression(
                "inputs.score > 80 and not (inputs.tier == 'free')");
        Map<String, Object> ctx = Map.of("inputs", Map.of("score", 90, "tier", "pro"));
        assertTrue(pebble.evaluateAsBoolean(compiled, ctx));
    }

    @Test
    void parseFailureBubblesUpAsExpressionException() {
        ExpressionException e = assertThrows(ExpressionException.class,
                () -> pebble.parseExpression("inputs.x ==="));
        assertTrue(e.getMessage().toLowerCase().contains("parse failed"));
    }
}
