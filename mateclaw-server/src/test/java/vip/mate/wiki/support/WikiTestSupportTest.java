package vip.mate.wiki.support;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.prompt.Prompt;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Sanity checks for the wiki test-support helpers.
 *
 * <p>Plain JUnit (no Spring boot) — these run as part of the regular
 * unit-test sweep and verify that the support layer itself compiles and
 * behaves as documented.
 */
class WikiTestSupportTest {

    @Test
    @DisplayName("kb() returns a non-null entity with sensible defaults populated")
    void kbFactoryDefaults() {
        var kb = WikiTestSupport.kb();

        assertThat(kb.getName()).startsWith("test-kb-");
        assertThat(kb.getStatus()).isEqualTo("active");
        assertThat(kb.getWorkspaceId()).isEqualTo(1L);
        assertThat(kb.getDeleted()).isZero();
    }

    @Test
    @DisplayName("Each kb() call produces a unique name")
    void kbFactoryUnique() {
        var a = WikiTestSupport.kb();
        var b = WikiTestSupport.kb();
        assertThat(a.getName()).isNotEqualTo(b.getName());
    }

    @Test
    @DisplayName("raw() preserves the supplied content and sets sourceType=text by default")
    void rawFactory() {
        var raw = WikiTestSupport.raw(7L, "Title A", "body content here");

        assertThat(raw.getKbId()).isEqualTo(7L);
        assertThat(raw.getTitle()).isEqualTo("Title A");
        assertThat(raw.getOriginalContent()).isEqualTo("body content here");
        assertThat(raw.getSourceType()).isEqualTo("text");
        assertThat(raw.getProcessingStatus()).isEqualTo("pending");
    }

    @Test
    @DisplayName("page() truncates long content into the summary field")
    void pageFactorySummary() {
        String body = "x".repeat(200);
        var page = WikiTestSupport.page(1L, "long-page", "Long Page", body);
        assertThat(page.getSummary()).hasSize(80);
    }

    @Test
    @DisplayName("relation() converts the score to BigDecimal and sets defaults")
    void relationFactory() {
        var rel = WikiTestSupport.relation(1L, 100L, 200L, 7.5);

        assertThat(rel.getKbId()).isEqualTo(1L);
        assertThat(rel.getPageAId()).isEqualTo(100L);
        assertThat(rel.getPageBId()).isEqualTo(200L);
        assertThat(rel.getTotalScore().doubleValue()).isEqualTo(7.5);
        assertThat(rel.getDeleted()).isZero();
    }

    @Test
    @DisplayName("MockLlmChatModel returns the longest-prefix-matching fixture")
    void mockLlmLongestPrefixWins() {
        var mock = new MockLlmChatModel(Map.of(
                "Generate", "short prefix response",
                "Generate a structured analysis", "long prefix response",
                "__default__", "fallback"));

        ChatResponse resp = mock.call(new Prompt(List.of(
                new UserMessage("Generate a structured analysis of this document"))));

        assertThat(resp.getResult().getOutput().getText()).isEqualTo("long prefix response");
    }

    @Test
    @DisplayName("MockLlmChatModel falls back to __default__ when nothing matches")
    void mockLlmDefaultFallback() {
        var mock = new MockLlmChatModel(Map.of(
                "ExpectedPrefix", "specific",
                "__default__", "default response"));

        ChatResponse resp = mock.call(new Prompt(List.of(
                new UserMessage("Totally unrelated prompt"))));

        assertThat(resp.getResult().getOutput().getText()).isEqualTo("default response");
    }

    @Test
    @DisplayName("MockLlmChatModel.fromClasspath loads the bundled fixtures file successfully")
    void mockLlmFromClasspath() {
        var mock = MockLlmChatModel.fromClasspath("/fixtures/llm-responses.json");

        ChatResponse resp = mock.call(new Prompt(List.of(
                new UserMessage("Plan which wiki concepts to create or update"))));

        // The fixture for "Plan which wiki concepts" returns a JSON object skeleton.
        assertThat(resp.getResult().getOutput().getText())
                .contains("create").contains("update").contains("crossLinks");
    }

    @Test
    @DisplayName("MockLlmChatModel.empty() returns the documented placeholder for any prompt")
    void mockLlmEmptyPlaceholder() {
        var mock = MockLlmChatModel.empty();

        ChatResponse resp = mock.call(new Prompt(List.of(
                new UserMessage("anything goes here"))));

        assertThat(resp.getResult().getOutput().getText()).contains("[mock]");
    }
}
