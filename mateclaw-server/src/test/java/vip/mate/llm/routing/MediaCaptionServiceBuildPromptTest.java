package vip.mate.llm.routing;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Method;
import java.util.Locale;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Pins the two-stage prompt contract of {@link MediaCaptionService}: when a user
 * question is supplied the vision model must be asked to answer it (so multi-turn
 * follow-ups get a tailored answer instead of a generic caption), and when no
 * question is supplied it must fall back to the factual full-description prompt.
 *
 * <p>{@code buildPrompt} is private — it is the smallest unit that captures the
 * branching, so it is exercised via reflection rather than driving a real
 * (networked) vision call.
 */
class MediaCaptionServiceBuildPromptTest {

    private static String buildPrompt(Locale locale, String fileName, String userQuestion) throws Exception {
        // Dependencies are unused by buildPrompt; null is fine for this unit.
        MediaCaptionService service = new MediaCaptionService(null, null);
        Method m = MediaCaptionService.class.getDeclaredMethod(
                "buildPrompt", Locale.class, String.class, String.class);
        m.setAccessible(true);
        return (String) m.invoke(service, locale, fileName, userQuestion);
    }

    @Test
    @DisplayName("Question + Chinese locale → question-aware prompt embedding the question")
    void chineseWithQuestion_isQuestionAware() throws Exception {
        String prompt = buildPrompt(Locale.SIMPLIFIED_CHINESE, "err.png", "图里的报错是什么");

        assertTrue(prompt.contains("图里的报错是什么"), "the user's question must be embedded verbatim");
        assertTrue(prompt.contains("回答用户的问题"), "must instruct the model to answer, not just describe");
        assertFalse(prompt.contains("不超过 300 字"),
                "question-aware prompt must not reuse the generic description template");
    }

    @Test
    @DisplayName("Question + English locale → English question-aware prompt")
    void englishWithQuestion_isQuestionAware() throws Exception {
        String prompt = buildPrompt(Locale.ENGLISH, "err.png", "What is the error message?");

        assertTrue(prompt.contains("What is the error message?"));
        assertTrue(prompt.contains("answer the user's question"));
    }

    @Test
    @DisplayName("Blank question → generic Chinese description prompt")
    void chineseNoQuestion_isGeneric() throws Exception {
        String prompt = buildPrompt(Locale.SIMPLIFIED_CHINESE, "photo.jpg", "  ");

        assertTrue(prompt.contains("请用一段简洁的中文描述这张图片"));
        assertFalse(prompt.contains("回答用户的问题"));
    }

    @Test
    @DisplayName("Null question + English → generic English description prompt")
    void englishNoQuestion_isGeneric() throws Exception {
        String prompt = buildPrompt(Locale.ENGLISH, null, null);

        assertTrue(prompt.contains("Describe this image"));
        assertFalse(prompt.contains("answer the user's question"));
    }
}
