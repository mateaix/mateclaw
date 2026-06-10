package vip.mate.tool.document;

import org.springframework.ai.chat.model.ToolContext;
import org.springframework.lang.Nullable;

/**
 * Stash freshly-rendered bytes into the {@link GeneratedFileCache} and format
 * the markdown link the tool returns to the LLM.
 *
 * <p>Two locales are exposed because mateclaw's existing convention has the
 * inline render tools speak Chinese and the file-driven render tools speak
 * English. Each variant tells the model to echo the URL verbatim — neither
 * stripping nor inventing a host — because the URL may already be absolute
 * (when {@code mateclaw.server.public-base-url} is set or a request host is
 * resolvable) and models otherwise tamper with it when echoing it back.
 */
public final class GeneratedFileLink {

    private GeneratedFileLink() {}

    /**
     * Chinese-language tool result for inline render entry points
     * ({@code renderDocx} / {@code renderXlsx} / {@code renderPptx}).
     *
     * @param typeLabel "文档" / "工作簿" / "演示文稿"
     */
    public static String resultZh(byte[] bytes, String displayName, String mimeType,
                                  GeneratedFileCache cache, String typeLabel,
                                  @Nullable ToolContext ctx) {
        String url = stash(bytes, displayName, mimeType, cache, ctx);
        return typeLabel + "已生成：[" + displayName + "](" + url + ")（链接 "
                + GeneratedFileCache.TTL.toDays() + " 天内有效）。\n"
                + "重要：回答用户时**必须**使用上述 markdown 链接格式 [" + displayName + "](" + url + ")，"
                + "保持链接地址**原样照抄**，**不要**用反引号包裹，**不要**增删任何域名或 http(s):// 前缀。";
    }

    /**
     * English-language tool result for file-driven render entry points
     * ({@code renderDocxFromFile} / {@code renderDocxFromFiles} / etc.).
     *
     * @param typeLabel       "Document" / "Workbook" / "Presentation"
     * @param sourceFileCount number of source markdown files combined into the
     *                        artifact; values {@code > 1} produce a "from N files"
     *                        prefix, {@code 1} produces the plain "generated" prefix
     */
    public static String resultEn(byte[] bytes, String displayName, String mimeType,
                                  GeneratedFileCache cache, String typeLabel,
                                  int sourceFileCount, @Nullable ToolContext ctx) {
        String url = stash(bytes, displayName, mimeType, cache, ctx);
        String prefix = sourceFileCount > 1
                ? typeLabel + " generated from " + sourceFileCount + " files"
                : typeLabel + " generated";
        return prefix + ": [" + displayName + "](" + url + ") (link valid for "
                + GeneratedFileCache.TTL.toDays() + " days).\n"
                + "IMPORTANT: when replying to the user you **must** keep the markdown link form ["
                + displayName + "](" + url + ") above. Copy the URL verbatim — do **not** wrap it "
                + "in backticks and do **not** add or remove any https://, http:// or domain.";
    }

    private static String stash(byte[] bytes, String displayName, String mimeType,
                                GeneratedFileCache cache, @Nullable ToolContext ctx) {
        String id = cache.put(bytes, displayName, mimeType);
        return cache.downloadUrl(id, ctx);
    }
}
