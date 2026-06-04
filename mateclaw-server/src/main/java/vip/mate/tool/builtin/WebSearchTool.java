package vip.mate.tool.builtin;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.tool.annotation.ToolParam;
import org.springframework.stereotype.Component;
import vip.mate.tool.search.SearchQuery;

/**
 * 内置工具：网页搜索
 * <p>通过 WebSearchService 动态路由至最佳搜索 provider（含 keyless fallback），
 * 支持 freshness / language / count 等高级搜索参数。
 *
 * @author MateClaw Team
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class WebSearchTool {

    private final WebSearchService webSearchService;

    // Tool name is pinned to "web_search" rather than the method-derived "search":
    // DashScope's native protocol reserves the function name "search" and rejects the
    // whole request with "InvalidParameter: Tool names are not allowed to be [search]",
    // which breaks tool use for every qwen/DashScope-native model that has this tool bound.
    @Tool(name = "web_search",
            description = "Search the internet for latest information. Use when querying real-time news, latest data, or uncertain facts. "
            + "Supports optional freshness, language, count parameters.")
    public String search(
            @ToolParam(description = "Search keywords") String query,
            @ToolParam(description = "Time range filter: day (today), week (this week), month (this month), year (this year)", required = false) String freshness,
            @ToolParam(description = "Language preference: zh-CN (Chinese), en (English)", required = false) String language,
            @ToolParam(description = "Max results: 1-10, default 5", required = false) Integer count
    ) {
        SearchQuery searchQuery = new SearchQuery(query, freshness, language, count);
        return webSearchService.search(searchQuery);
    }
}
