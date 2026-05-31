package vip.mate.workspace.document;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.metadata.TableInfoHelper;
import org.apache.ibatis.builder.MapperBuilderAssistant;
import org.apache.ibatis.session.Configuration;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import vip.mate.workspace.document.model.WorkspaceFileEntity;
import vip.mate.workspace.document.repository.WorkspaceFileMapper;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;

/**
 * Contract for {@link WorkspaceFileService#searchSnippets} — the back end
 * of the {@code search_workspace_memory} agent tool.
 * <p>
 * Tests run against a Mockito-stubbed {@link WorkspaceFileMapper}: the DB-side
 * AND-LIKE narrowing is MyBatis-Plus's problem, not this service's — what we
 * verify here is the post-fetch pipeline (tokenization, per-line extraction,
 * weighted scoring, snippet rendering) plus the wrapper construction so we
 * don't silently drop scope filters or term groups.
 */
@ExtendWith(MockitoExtension.class)
class WorkspaceMemorySearchTest {

    @Mock private WorkspaceFileMapper fileMapper;
    @Mock private org.springframework.context.ApplicationEventPublisher eventPublisher;
    private WorkspaceFileService service;

    @BeforeAll
    static void initMyBatisPlusCache() {
        // LambdaQueryWrapper resolves SFunction → column via TableInfoHelper.
        // Spring would init it during mapper scan; here we trigger it manually.
        TableInfoHelper.initTableInfo(
                new MapperBuilderAssistant(new Configuration(), ""),
                WorkspaceFileEntity.class);
    }

    @BeforeEach
    void setUp() {
        service = new WorkspaceFileService(fileMapper, eventPublisher);
    }

    @Test
    @DisplayName("saveFile publishes a change event so the cached agent instance is invalidated")
    void saveFilePublishesChangeEvent() {
        when(fileMapper.selectOne(any())).thenReturn(null); // new file path

        service.saveFile(1000000001L, "MEMORY.md", "## 稳定事实\n- 用户语言：简体中文");

        ArgumentCaptor<vip.mate.workspace.document.event.WorkspaceFileChangedEvent> captor =
                ArgumentCaptor.forClass(vip.mate.workspace.document.event.WorkspaceFileChangedEvent.class);
        org.mockito.Mockito.verify(eventPublisher).publishEvent(captor.capture());
        assertThat(captor.getValue().agentId()).isEqualTo(1000000001L);
        assertThat(captor.getValue().filename()).isEqualTo("MEMORY.md");
    }

    // ---------- tokenize ----------

    @Test
    @DisplayName("tokenize: null / blank / single-char yields empty list")
    void tokenizeShortInputs() {
        assertThat(WorkspaceFileService.tokenize(null)).isEmpty();
        assertThat(WorkspaceFileService.tokenize("")).isEmpty();
        assertThat(WorkspaceFileService.tokenize("   ")).isEmpty();
        // A single CJK char DOES produce one token — query-length guard at
        // searchSnippets level is what enforces the 2-char minimum.
        assertThat(WorkspaceFileService.tokenize("好")).containsExactly("好");
    }

    @Test
    @DisplayName("tokenize: long CJK runs split into non-overlapping 2-char windows (within cap)")
    void tokenizeCjkPairs() {
        // 10-char CJK run → 5 windows; all five survive under MAX_TERMS = 6.
        assertThat(WorkspaceFileService.tokenize("用户喜欢周日早上跑步"))
                .containsExactly("用户", "喜欢", "周日", "早上", "跑步");
    }

    @Test
    @DisplayName("tokenize: CJK/Latin boundary splits, Latin runs stay intact")
    void tokenizeMixedScripts() {
        // "用户Foo123跑步" → CJK run "用户" (2-char window) + Latin/digit run
        // "Foo123" + CJK run "跑步".
        assertThat(WorkspaceFileService.tokenize("用户Foo123跑步"))
                .containsExactly("用户", "Foo123", "跑步");
    }

    @Test
    @DisplayName("tokenize: dedupe + cap at 6")
    void tokenizeDedupeAndCap() {
        // Whitespace splits; "abc" repeats are dedup'd; only first 6 kept.
        // The 7th and 8th tokens ("ppp" / "qqq") get trimmed by the cap.
        assertThat(WorkspaceFileService.tokenize("abc abc def ghi jkl mno opq ppp qqq"))
                .containsExactly("abc", "def", "ghi", "jkl", "mno", "opq");
    }

    @Test
    @DisplayName("tokenize: a 12-char CJK run yields 6 windows and stops at the cap")
    void tokenizeLongCjkFitsCap() {
        // 12-char run → 6 windows; the 7th and 8th would be "测一" / "下下"
        // if we extended the query, but the cap stops at 6 either way.
        assertThat(WorkspaceFileService.tokenize("用户喜欢周日早上跑步公园"))
                .containsExactly("用户", "喜欢", "周日", "早上", "跑步", "公园");
    }

    // ---------- searchSnippets contract ----------

    @Test
    @DisplayName("Short query: returns empty without touching the mapper")
    void shortQueryReturnsEmpty() {
        assertThat(service.searchSnippets(1L, "a", null, 10)).isEmpty();
        assertThat(service.searchSnippets(1L, " ", null, 10)).isEmpty();
        assertThat(service.searchSnippets(1L, "", null, 10)).isEmpty();
        assertThat(service.searchSnippets(1L, null, null, 10)).isEmpty();
        // limit <= 0 short-circuits.
        assertThat(service.searchSnippets(1L, "running", null, 0)).isEmpty();
    }

    @Test
    @DisplayName("No candidate files: returns empty list")
    void noCandidateRowsReturnsEmpty() {
        when(fileMapper.selectList(any())).thenReturn(List.of());
        assertThat(service.searchSnippets(1L, "running", null, 10)).isEmpty();
    }

    @Test
    @DisplayName("Score ordering: MEMORY > memory/* > PROFILE > AGENTS for identical term-hit counts")
    void scoreOrdering() {
        List<WorkspaceFileEntity> candidates = List.of(
                file("AGENTS.md",         "Line about running.\n"),
                file("MEMORY.md",         "Line about running.\n"),
                file("PROFILE.md",        "Line about running.\n"),
                file("memory/2026-05-10.md", "Line about running.\n"));
        when(fileMapper.selectList(any())).thenReturn(candidates);

        List<MemorySearchHit> hits = service.searchSnippets(1L, "running", null, 10);

        assertThat(hits).extracting(MemorySearchHit::filename)
                .containsExactly("MEMORY.md", "memory/2026-05-10.md", "PROFILE.md", "AGENTS.md");
        assertThat(hits.get(0).score()).isGreaterThan(hits.get(1).score());
        assertThat(hits.get(1).score()).isGreaterThan(hits.get(2).score());
        assertThat(hits.get(2).score()).isGreaterThan(hits.get(3).score());
    }

    @Test
    @DisplayName("Term-hit count: line matching 2 terms scores 2× the per-file weight")
    void termHitCountAffectsScore() {
        List<WorkspaceFileEntity> candidates = List.of(
                file("MEMORY.md",
                        "Line with running and shoes.\n" +
                        "Line with only running.\n"));
        when(fileMapper.selectList(any())).thenReturn(candidates);

        List<MemorySearchHit> hits = service.searchSnippets(1L, "running shoes", null, 10);

        // Both lines surface; the 2-term line ranks first with score 2.0, the
        // 1-term line ranks second with score 1.0 (MEMORY weight = 1.0).
        assertThat(hits).hasSize(2);
        assertThat(hits.get(0).score()).isEqualTo(2.0);
        assertThat(hits.get(0).snippet()).contains("[[running]]").contains("[[shoes]]");
        assertThat(hits.get(1).score()).isEqualTo(1.0);
    }

    @Test
    @DisplayName("Per-file hit cap: a flood of matching lines in one file caps at 5")
    void perFileHitCap() {
        StringBuilder content = new StringBuilder();
        for (int i = 0; i < 12; i++) content.append("line ").append(i).append(" running\n");
        List<WorkspaceFileEntity> candidates = List.of(file("MEMORY.md", content.toString()));
        when(fileMapper.selectList(any())).thenReturn(candidates);

        List<MemorySearchHit> hits = service.searchSnippets(1L, "running", null, 30);

        assertThat(hits).hasSize(5);
        // First 5 lines only.
        assertThat(hits).extracting(MemorySearchHit::lineNumber)
                .containsExactly(1, 2, 3, 4, 5);
    }

    @Test
    @DisplayName("Limit caps the total result count after global ranking")
    void limitClampsResults() {
        StringBuilder mem = new StringBuilder();
        for (int i = 0; i < 5; i++) mem.append("MEM running\n");
        StringBuilder daily = new StringBuilder();
        for (int i = 0; i < 5; i++) daily.append("DAILY running\n");
        when(fileMapper.selectList(any())).thenReturn(List.of(
                file("MEMORY.md", mem.toString()),
                file("memory/2026-05-10.md", daily.toString())));

        List<MemorySearchHit> hits = service.searchSnippets(1L, "running", null, 3);

        assertThat(hits).hasSize(3);
        // Highest-weight hits come first — all from MEMORY.md.
        assertThat(hits).extracting(MemorySearchHit::filename)
                .containsExactly("MEMORY.md", "MEMORY.md", "MEMORY.md");
    }

    @Test
    @DisplayName("Snippet: each matched term is wrapped in [[...]] with term boundaries preserved")
    void snippetHighlightingTermBoundaries() {
        List<WorkspaceFileEntity> candidates = List.of(file("MEMORY.md",
                "用户喜欢在公园跑步\n"));
        when(fileMapper.selectList(any())).thenReturn(candidates);

        List<MemorySearchHit> hits = service.searchSnippets(1L, "用户喜欢跑步", null, 10);
        // tokenize("用户喜欢跑步") → ["用户","喜欢","跑步"]; line contains all three.
        assertThat(hits).hasSize(1);
        assertThat(hits.get(0).snippet())
                .contains("[[用户]]")
                .contains("[[喜欢]]")
                .contains("[[跑步]]")
                // Adjacent term matches keep their boundary, not merged into one bracket.
                .contains("[[用户]][[喜欢]]");
    }

    @Test
    @DisplayName("Snippet: long line is clipped to ±80 chars around the first match with ellipses")
    void snippetTruncation() {
        String lead = "x".repeat(200);
        String tail = "y".repeat(200);
        String line = lead + " running " + tail;
        when(fileMapper.selectList(any())).thenReturn(List.of(file("MEMORY.md", line + "\n")));

        List<MemorySearchHit> hits = service.searchSnippets(1L, "running", null, 10);

        assertThat(hits).hasSize(1);
        String snippet = hits.get(0).snippet();
        assertThat(snippet).startsWith("...");
        assertThat(snippet).endsWith("...");
        assertThat(snippet).contains("[[running]]");
        // Clip window is 80 chars on each side plus the match (7 chars) plus
        // two "..." markers (6 chars). Should be << original length.
        assertThat(snippet.length()).isLessThan(line.length());
    }

    @Test
    @DisplayName("Wrapper carries one content-LIKE per token plus the prefix group and LIMIT 50")
    void wrapperContainsTermsAndPrefixes() {
        when(fileMapper.selectList(any())).thenReturn(List.of());
        Set<String> prefixes = new LinkedHashSet<>(List.of("memory/", "MEMORY.md"));
        service.searchSnippets(42L, "running shoes 跑步", prefixes, 10);

        @SuppressWarnings("unchecked")
        ArgumentCaptor<LambdaQueryWrapper<WorkspaceFileEntity>> captor =
                ArgumentCaptor.forClass(LambdaQueryWrapper.class);
        org.mockito.Mockito.verify(fileMapper).selectList(captor.capture());
        LambdaQueryWrapper<WorkspaceFileEntity> wrapper = captor.getValue();

        // Force SQL rendering so paramNameValuePairs gets populated; assert
        // on the SQL shape (placeholders only — column-name casing depends
        // on global underscore-camelCase config not present in this unit
        // test) and on the bound literal values.
        String sql = wrapper.getTargetSql();
        // One equality on agent + two prefix LIKEs in an OR group + three
        // content LIKEs, ANDed together, suffixed with the candidate cap.
        assertThat(sql).contains("LIKE ? OR")
                .contains("AND content")
                .contains("LIMIT 50");
        assertThat(sql.chars().filter(ch -> ch == '?').count())
                .as("one agentId + two prefix LIKEs + three content LIKEs = 6 bind params")
                .isEqualTo(6);

        List<Object> values = new ArrayList<>(wrapper.getParamNameValuePairs().values());
        assertThat(values).contains(42L);
        // Each content-LIKE term gets %term% by MyBatis-Plus's like().
        assertThat(values).contains("%running%", "%shoes%", "%跑步%");
        // likeRight produces "prefix%" — confirms both prefixes were bound.
        assertThat(values).contains("memory/%", "MEMORY.md%");
    }

    // ---------- helpers ----------

    private static WorkspaceFileEntity file(String filename, String content) {
        WorkspaceFileEntity e = new WorkspaceFileEntity();
        e.setAgentId(1L);
        e.setFilename(filename);
        e.setContent(content);
        return e;
    }
}
