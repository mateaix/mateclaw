package vip.mate.tool.search;

/**
 * 搜索查询参数封装
 *
 * @param query     搜索关键词（必须）
 * @param freshness 时间范围过滤：day / week / month / year（可选）
 * @param language  语言偏好：zh-CN / en / auto（可选）
 * @param count     最大结果数：1-10，默认 5（可选）
 *
 * @author MateClaw Team
 */
public record SearchQuery(
        String query,
        String freshness,
        String language,
        Integer count
) {
    private static final int DEFAULT_COUNT = 5;
    private static final int MAX_COUNT = 10;

    /** 从裸 query 字符串构建（向后兼容） */
    public static SearchQuery of(String query) {
        return new SearchQuery(query, null, null, null);
    }

    /** 获取 count，带默认值和上界限制 */
    public int resolvedCount() {
        if (count == null || count <= 0) return DEFAULT_COUNT;
        return Math.min(count, MAX_COUNT);
    }

    /** freshness 是否有效 */
    public boolean hasFreshness() {
        return freshness != null && !freshness.isBlank();
    }

    /** language 是否有效 */
    public boolean hasLanguage() {
        return language != null && !language.isBlank() && !"auto".equalsIgnoreCase(language);
    }
}
