package vip.mate.tool.browser;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;

import java.util.Collection;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Shared URL safety checks for browser navigation surfaces beyond action=open.
 */
public final class BrowserNavigationGuard {

    private static final Pattern URL_LITERAL =
            Pattern.compile("['\"](https?://[^'\"\\s)]+)['\"]", Pattern.CASE_INSENSITIVE);

    private static final Pattern EVAL_NAVIGATION_INTENT = Pattern.compile(
            "\\b(location(?:\\.href|\\.assign|\\.replace)?|window\\.open|fetch|XMLHttpRequest)\\b",
            Pattern.CASE_INSENSITIVE);

    private BrowserNavigationGuard() {
    }

    public static void checkCdp(String method, JsonObject params, Collection<String> allowlist,
                                boolean allowPrivateNetwork) {
        if (!"Page.navigate".equals(method) || params == null || !params.has("url")) {
            return;
        }
        JsonElement el = params.get("url");
        if (el == null || !el.isJsonPrimitive()) {
            return;
        }
        UrlSafetyChecker.check(el.getAsString(), allowlist, allowPrivateNetwork);
    }

    public static void checkEval(String code, Collection<String> allowlist, boolean allowPrivateNetwork) {
        if (code == null || code.isBlank()) {
            return;
        }
        if (!EVAL_NAVIGATION_INTENT.matcher(code).find()) {
            return;
        }
        Matcher matcher = URL_LITERAL.matcher(code);
        while (matcher.find()) {
            UrlSafetyChecker.check(matcher.group(1), allowlist, allowPrivateNetwork);
        }
    }
}
