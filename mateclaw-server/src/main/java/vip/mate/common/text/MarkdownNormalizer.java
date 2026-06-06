package vip.mate.common.text;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 确定性 Markdown 规范化工具。
 *
 * <p>用于在 Agent 最终答案落库 / 发渠道前，修复 LLM 原始输出中常见的机械排版缺陷。纯本地正则处理，
 * 不调用任何模型（零 token）。设计目标是「修畸形而不改语义」，因此遵循以下原则：</p>
 *
 * <ul>
 *   <li><b>代码块感知</b>：先按 ``` / ~~~ 围栏切分，围栏内的内容原样保留，避免破坏代码里的
 *       {@code #} / {@code |} / {@code ---}。</li>
 *   <li><b>幂等</b>：{@code normalize(normalize(x)).equals(normalize(x))}。</li>
 *   <li><b>保守</b>：只在能高置信判断为畸形时才改写，散文中的散落管道符、行内 {@code #} 不动。</li>
 * </ul>
 *
 * <p>覆盖的修复：ATX 标题补空格、{@code ---} 与后续内容粘连时拆行、表格块单元格与分隔行对齐、
 * 标题与表格粘连时拆行、标题/表格块边界补空行。</p>
 *
 * <p>不在范围内（属语义判断，正则无法安全自动化，保留在提示词约束）：Emoji 位置、代码块语言标注补全。</p>
 */
public final class MarkdownNormalizer {

    private MarkdownNormalizer() {}

    /** 行首 ATX 标题但紧跟非空格、非 # 字符（缺少标题空格）。 */
    private static final Pattern HEADING_NO_SPACE = Pattern.compile("^(#{1,6})([^#\\s].*)$");

    /** 已规范的标题行：#{1,6} + 空白。用于块边界判断。 */
    private static final Pattern HEADING_LINE = Pattern.compile("^#{1,6}\\s.*$");

    /** 主题分隔线与后续内容粘连：行首 3+ 短横，后面紧跟非短横的可见内容。 */
    private static final Pattern HR_GLUED = Pattern.compile("^(-{3,})([^-\\s].*)$");

    /** 标题行尾粘连了表格：#{1,6} 标题文字（不含管道符）+ 管道符起始的尾部。 */
    private static final Pattern HEADING_TABLE = Pattern.compile("^(#{1,6}[^|\\n]*?)\\s*(\\|.+)$");

    /** GFM 表格分隔行：由短横/冒号组成的单元格，用管道符分隔（必须同时含 - 与 |）。 */
    private static final Pattern SEPARATOR_ROW =
            Pattern.compile("^\\s*\\|?\\s*:?-{1,}:?\\s*(\\|\\s*:?-{1,}:?\\s*)*\\|?\\s*$");

    /**
     * 规范化 Markdown 文本。{@code null} 或空串原样返回。
     */
    public static String normalize(String md) {
        if (md == null || md.isEmpty()) {
            return md;
        }
        String normalized = md.replace("\r\n", "\n").replace("\r", "\n");
        String[] lines = normalized.split("\n", -1);

        List<String> out = new ArrayList<>();
        List<String> textBuf = new ArrayList<>();
        boolean inFence = false;
        String fenceMarker = null;

        for (String line : lines) {
            String lead = line.stripLeading();
            if (!inFence && (lead.startsWith("```") || lead.startsWith("~~~"))) {
                flushText(textBuf, out);
                textBuf.clear();
                inFence = true;
                fenceMarker = lead.startsWith("```") ? "```" : "~~~";
                out.add(line);
            } else if (inFence && lead.startsWith(fenceMarker)) {
                inFence = false;
                fenceMarker = null;
                out.add(line);
            } else if (inFence) {
                out.add(line);
            } else {
                textBuf.add(line);
            }
        }
        flushText(textBuf, out);

        String result = String.join("\n", out);
        // 仅清理文档首尾多余空行，不触碰代码块内部
        return result.replaceAll("^\\n+", "").replaceAll("\\n+$", "");
    }

    // ==================== 非代码段处理 ====================

    private static void flushText(List<String> textLines, List<String> out) {
        if (textLines.isEmpty()) {
            return;
        }
        // 1. 行级展开：HR 粘连拆行、标题粘表格拆行、标题补空格
        List<String> expanded = new ArrayList<>();
        for (String l : textLines) {
            expanded.addAll(expandLine(l));
        }
        // 2. 表格块识别与规范化
        List<String> normalizedLines = new ArrayList<>();
        List<Boolean> isTable = new ArrayList<>();
        normalizeTables(expanded, normalizedLines, isTable);
        // 3. 标题/表格块边界补空行 + 折叠多余空行
        out.addAll(insertBoundaryBlanks(normalizedLines, isTable));
    }

    private static List<String> expandLine(String line) {
        List<String> result = new ArrayList<>();

        Matcher hr = HR_GLUED.matcher(line);
        if (hr.matches()) {
            result.add("---");
            result.add("");
            result.addAll(expandLine(hr.group(2)));
            return result;
        }

        Matcher ht = HEADING_TABLE.matcher(line);
        if (ht.matches() && countPipes(ht.group(2)) >= 2) {
            result.add(fixHeadingSpace(ht.group(1).strip()));
            result.add("");
            result.add(ht.group(2).strip());
            return result;
        }

        result.add(fixHeadingSpace(line));
        return result;
    }

    /**
     * 行首 ATX 标题缺空格时补一个空格。为避免误伤 {@code #5}、{@code #1} 这类引用，
     * 紧跟数字的不处理。
     */
    private static String fixHeadingSpace(String line) {
        Matcher m = HEADING_NO_SPACE.matcher(line);
        if (m.matches()) {
            String hashes = m.group(1);
            String rest = m.group(2);
            if (!Character.isDigit(rest.charAt(0))) {
                return hashes + " " + rest;
            }
        }
        return line;
    }

    // ==================== 表格规范化 ====================

    private static void normalizeTables(List<String> lines, List<String> out, List<Boolean> isTable) {
        int n = lines.size();
        boolean[] tbl = new boolean[n];
        for (int i = 0; i < n; i++) {
            if (isSeparatorRow(lines.get(i)) && i > 0 && containsPipe(lines.get(i - 1))) {
                int start = i - 1;
                int end = i;
                int j = i + 1;
                while (j < n && !lines.get(j).isBlank() && containsPipe(lines.get(j))
                        && !HEADING_LINE.matcher(lines.get(j)).matches()) {
                    end = j;
                    j++;
                }
                for (int k = start; k <= end; k++) {
                    tbl[k] = true;
                }
                i = end;
            }
        }
        for (int i = 0; i < n; i++) {
            if (tbl[i]) {
                out.add(normalizeTableRow(lines.get(i), isSeparatorRow(lines.get(i))));
            } else {
                out.add(lines.get(i));
            }
            isTable.add(tbl[i]);
        }
    }

    private static String normalizeTableRow(String line, boolean separator) {
        String s = line.strip();
        if (s.startsWith("|")) {
            s = s.substring(1);
        }
        if (s.endsWith("|")) {
            s = s.substring(0, s.length() - 1);
        }
        String[] cells = s.split("(?<!\\\\)\\|", -1);
        StringBuilder sb = new StringBuilder("|");
        for (String cell : cells) {
            String c = cell.strip();
            if (separator) {
                c = normalizeDelimiterCell(c);
            }
            sb.append(' ').append(c).append(" |");
        }
        return sb.toString();
    }

    private static String normalizeDelimiterCell(String cell) {
        boolean left = cell.startsWith(":");
        boolean right = cell.endsWith(":");
        return (left ? ":" : "") + "---" + (right ? ":" : "");
    }

    private static boolean isSeparatorRow(String line) {
        return line.indexOf('-') >= 0 && line.indexOf('|') >= 0
                && SEPARATOR_ROW.matcher(line).matches();
    }

    private static boolean containsPipe(String line) {
        return line.indexOf('|') >= 0;
    }

    private static int countPipes(String s) {
        int count = 0;
        for (int i = 0; i < s.length(); i++) {
            if (s.charAt(i) == '|') {
                count++;
            }
        }
        return count;
    }

    // ==================== 块边界空行 ====================

    private static final int BLANK = 0;
    private static final int HEADING = 1;
    private static final int TABLE = 2;
    private static final int OTHER = 3;
    private static final int NONE = -1;

    private static List<String> insertBoundaryBlanks(List<String> lines, List<Boolean> isTable) {
        List<String> res = new ArrayList<>();
        int lastType = NONE;
        for (int i = 0; i < lines.size(); i++) {
            String cur = lines.get(i);
            int curType = classify(cur, isTable.get(i));

            if (curType == BLANK) {
                if (lastType == BLANK || lastType == NONE) {
                    continue; // 折叠连续空行 / 去掉前导空行
                }
                res.add(cur);
                lastType = BLANK;
                continue;
            }

            if (lastType != NONE && lastType != BLANK && needsBlankBetween(lastType, curType)) {
                res.add("");
            }
            res.add(cur);
            lastType = curType;
        }
        return res;
    }

    private static boolean needsBlankBetween(int prev, int cur) {
        if (cur == HEADING || prev == HEADING) {
            return true;
        }
        if (cur == TABLE && prev != TABLE) {
            return true;
        }
        return prev == TABLE && cur != TABLE;
    }

    private static int classify(String line, boolean tableFlag) {
        if (line.isBlank()) {
            return BLANK;
        }
        if (tableFlag) {
            return TABLE;
        }
        if (HEADING_LINE.matcher(line).matches()) {
            return HEADING;
        }
        return OTHER;
    }
}
