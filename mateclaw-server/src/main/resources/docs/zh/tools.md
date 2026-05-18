# 工具系统

**一个工具就是 Agent 能伸出去的一只手。**

把语言模型单独放在那里，它只是一个包在文本里的模式匹配器。它不知道现在几点。它不知道你的文件里写了什么。它不能搜索网页、执行命令、看一份 PDF、把任务交给另一个 Agent、打开一个浏览器。它只能**谈论**做这些事。

工具是 MateClaw 解决这件事的方式。每一个工具是一个 Agent 被允许调用的具体操作——读文件、搜网页、执行 shell 命令、从 PDF 抽文字、把任务委托给另一个 Agent。Agent 判断需要某个工具时，发出一次**工具调用**，运行时执行它，结果作为**观察**回到 Agent 下一步推理里。

**二十个内置工具**开箱即用。无限多个可以通过 MCP 服务、自定义技能脚本、或者你自己写的 `@Tool` Spring bean 加进来。

---

## 一次工具调用实际发生了什么

```
Agent 判断需要一个工具
        │
        ▼
  发出工具调用：{"name": "WebSearchTool", "args": {"query": "..."}}
        │
        ▼
  ┌─────────────────────┐
  │    工具注册表       │  ← 按名字查工具
  └─────────────────────┘
        │
        ▼
  ┌─────────────────────┐
  │   Tool Guard        │  ← 基于规则的检查：allow / deny / 审批
  └─────────────────────┘
        │
   ┌────┴────┐
   │         │
   ▼         ▼
 允许     审批挂起 → 用户决定 → 允许 / 拒绝
   │
   ▼
  ┌─────────────────────┐
  │  执行（带超时）     │  ← 异步，按工具单独设超时
  └─────────────────────┘
        │
        ▼
  结果 → 观察 → Agent 下一步推理
```

Tool Guard 是守门员。超时是**每个工具独立**的（这样一个慢工具冻不了整个回合）。在一次 Action 阶段里，Agent 同时调多个独立工具时它们可以**并发执行**。

这整套对 Agent 的 prompt 是**不可见**的。

---

## 工具注册的三条路

**1. 内置工具。** MateClaw 出厂带的二十个工具，启动时自动注册到工具表里。

**2. MCP 服务。** 说 Model Context Protocol 的外部进程动态暴露工具。MateClaw 通过 `tools/list` 发现它们。见 [MCP 协议](./mcp)。

**3. 技能脚本。** 技能包可以带可执行脚本，运行时被包装成工具。见 [技能系统](./skills)。

工具发现是**黑名单式**的——默认所有可发现的工具都会被注册，需要排除哪个就显式排除。这样新加进来的工具不会因为白名单遗漏被默默忽略。

---

## 二十个内置工具

| 工具 | 作用 | 危险 |
|------|------|------|
| `DateTimeTool` | 获取任意时区的当前日期时间 | — |
| `WebSearchTool` | 通过搜索引擎链搜索（Serper / Tavily / DuckDuckGo / SearXNG） | — |
| `ReadFileTool` | 读文件 | — |
| `WriteFileTool` | 写内容到文件 | ⚠️ |
| `EditFileTool` | 查找替换编辑 | ⚠️ |
| `ShellExecuteTool` | 执行 shell 命令 | ⚠️ |
| `FileTypeDetectorTool` | 检测 MIME 类型和编码 | — |
| `DocumentExtractTool` | 从 PDF / DOCX / XLSX 抽文字 | — |
| `WorkspaceMemoryTool` | 读写 Agent 的工作空间记忆 | — |
| `SkillFileTool` | 读取和管理 `SKILL.md` 文件 | — |
| `SkillScriptTool` | 执行技能脚本 | ⚠️ |
| `SkillManageTool` | 创建 / 编辑 / 删除技能包 | ⚠️ |
| `BrowserUseTool` | 驱动无头浏览器 | ⚠️ |
| `DelegateAgentTool` | 把任务委托给另一个 Agent（支持并行） | — |
| `MateClawDocTool` | 读取内置项目文档 | — |
| `ImageGenerateTool` | 文生图 / **图生图（1.3.0+）** | — |
| `VideoGenerateTool` | 文生视频 / 图生视频 | — |
| `DocxRenderTool` | **1.3.0+** Markdown → .docx（Word 文档） | — |
| `XlsxRenderTool` | **1.3.0+** Markdown 表格 → .xlsx（Excel） | — |
| `PptxRenderTool` | **1.3.0+** Markdown（Marp 风格 `---` 分页） → .pptx | — |
| `PdfRenderTool` | **1.3.0+** Markdown → 出版级 PDF（中文字体内嵌） | — |
| `CronJobTool` | 创建和管理定时任务 | ⚠️ |
| `DatasourceTool` | 管理外部数据源连接 | ⚠️ |
| `SqlQueryTool` | 对已连接数据源执行 SQL 查询 | ⚠️ |

此外还有 [多模态创作](./multimodal) 的音乐生成工具 `MusicGenerateTool`。以及 [LLM Wiki](./wiki) 的 14 个 Wiki 工具：`wiki_read_page`、`wiki_read_many`、`wiki_list_pages`、`wiki_search_pages`、`wiki_semantic_search`、`wiki_compile_page`、`wiki_trace_source`、`wiki_create_page`、`wiki_delete_page`、`wiki_archive_page`、`wiki_unarchive_page`、`wiki_related_pages`、`wiki_explain_relation`、`wiki_enrich_page`。

### DateTimeTool

返回给定时区的当前日期时间。没有意外。

```
输入：{"timezone": "America/New_York"}
输出："2026-04-11T14:30:22"
```

### WebSearchTool

通过**供应商链**搜索——DuckDuckGo 和 SearXNG 作为无 Key 的 fallback，Serper 和 Tavily 在你有 Key 时启用。在 `设置 → 系统设置 → 搜索服务` 里配置，**改完即生效，不需要重启**。

```
输入：{"query": "Spring AI Alibaba 最新版本", "freshness": "month", "count": 5}
输出："Spring AI Alibaba 1.1 发布..."
```

特性：

- **供应商链**——主 provider 失败时链式 fallback 到下一个
- **高级参数**——`freshness`、`language`、`count`
- **结果缓存**——近期查询被缓存
- **安全包装**——结果返回前先净化
- **原生搜索 + 工具搜索共存**——自带搜索的模型用原生搜索，工具搜索作为 fallback

### ShellExecuteTool

跨平台 shell 执行。Linux/macOS 用 `/bin/sh -c`，Windows 用 `cmd.exe /D /S /C`。**每一次调用都过 Tool Guard。**

安全设计：

- **超时**——默认 60 秒，硬上限 300 秒
- **输出上限**——stdout 和 stderr 各自上限 10,000 字节
- **文件支撑输出**——写到临时文件，不是管道
- **结构化结果**——`{exitCode, stdout, stderr, timedOut}`
- **危险模式检测**——`find -delete`、`rm -rf /`、管道 bash 下载触发更高级别审批

### ReadFileTool / WriteFileTool / EditFileTool

读是安全的。写和编辑都过 Tool Guard。

### DocumentExtractTool

PDF、DOCX、XLSX 之类变成纯文本。扫描件在可用的地方降级到 OCR。

### Office 文档生成（1.3.0+）

四个新工具，把 Markdown 直接渲染为可下载的 Office 文件——**不 fork 子进程，不依赖 npm**。生成的字节缓存在内存里，返回一个一次性下载链接：

| 工具 | 适用 | 关键能力 |
|---|---|---|
| `DocxRenderTool.renderDocx` | 报告 / 备忘录 / 合同 / 简历 | 标题（# ## ###） / 加粗（**text**） / 列表 / 表格 / 图片（PNG/JPG/GIF/BMP/SVG → PNG） |
| `DocxRenderTool.renderDocxFromFile` | 同上，但 markdown 在工作区文件里 | 用于 LLM 不想把已经写好的大段 markdown 当 tool 参数再发一次 |
| `XlsxRenderTool.renderXlsx` | 财务表 / 数据导出 / 模板 | Markdown 表格语法 → 多 sheet（用 `## SheetName` 切分） |
| `PptxRenderTool.renderPptx` | 演讲稿 / 项目方案 / 简报 | Marp 风格 `---` 分页；`16:9`（默认）/ `4:3` 比例 |
| `PptxRenderTool.renderPptxFromFile` | 同上，markdown 在文件里 | 内容大于 5KB 时优选 |
| `PdfRenderTool.renderPdf` | 出版级文档 / 周报 / 制式文件 | 1in 边距 / 智能分页 / 页码 / 封面页 / 中英混排（CJK 字体内嵌） |

::: tip 跟原有 `skills/docx` 的关系
现成的 `skills/docx` skill **保留**——它擅长的是**编辑已有 .docx**（tracked changes / 复杂 XML 操作）和首次安装时跑 `npm install docx`。新四个工具专门处理"从零创建"路径，**没有 npm 启动延迟**。Agent 首选这四个 RenderTool；要修已有 .docx 才转回 skill。
:::

### ImageGenerateTool —— 1.3.0 起支持图像编辑

v1.2.0 时这个工具只能"文生图"。v1.3.0 起新增 `image` / `images` 两个参数，支持**多图输入的图像编辑**。详见 [多模态创作](./multimodal#image-edit)。

### WorkspaceMemoryTool

让 Agent 读、写、编辑自己的工作空间记忆文件——`workspace/{agentId}/` 下面的任何 `.md`。见 [记忆系统](./memory)。

### BrowserUseTool

驱动一个无头浏览器。每次调用都过 Tool Guard。

### DelegateAgentTool —— Agent 之间委托

一个 Agent 可以把子任务交给另一个 Agent：

- **`delegateToAgent(agentName, task)`**——按名字调用指定 Agent，在隔离会话里执行任务
- **`listAvailableAgents()`**——列出所有可用 Agent

```
用户：搜一下 Spring AI 的新闻，让 Writer 总结一下
Agent A：[调 WebSearchTool]
        [调 delegateToAgent(agentName="Writer", task="总结：...")]
        [收到 Writer 的回复]
        合并后回复用户
```

安全：

- **递归上限**——委托最多嵌套 3 层
- **隔离会话**——被委托的 Agent 跑在自己的会话里
- **结果截断**——委托结果上限 4000 字符

### MateClawDocTool

读取内置的 MateClaw 项目文档。让 Agent 回答"MateClaw 里 X 是怎么工作的"这种问题时，**去查真文档**而不是猜。

---

## Tool Guard —— 权限层

Tool Guard 是 MateClaw 不让强工具干蠢事的机制。它是**基于规则的**，不是一个扁平的"危险 / 不危险"清单。每条规则说：*对这个工具，带这些参数，在这个上下文里，做 X*——X 是 `allow`、`deny`、或 `require_approval`。

核心几张表：

- **`mate_tool_guard_rule`**——单条规则
- **`mate_tool_guard_config`**——全局配置
- **`mate_tool_guard_audit_log`**——每一次受守护的调用一条记录

示例规则：*`ShellExecuteTool`，命令以 `ls`、`cat`、`grep`、`find` 开头时允许。其他情况要求审批。*

```yaml
mateclaw:
  tool:
    guard:
      enabled: true
      default-policy: require_approval
      rules:
        - tool: ShellExecuteTool
          arg-pattern: "^(ls|cat|grep|find)\\s"
          action: allow
        - tool: WriteFileTool
          action: require_approval
```

或者在 `设置 → 安全与审批` 里可视化管理。规则判定为需要审批时，运行时会在 `mate_tool_approval` 持久化一条记录并把 Agent 回合挂起。完整机制在 [安全与审批](./security)。

### 声明式 Hook 系统

Tool Guard 规则是一种更通用机制的特例——**声明式 Hook 系统**。5 个生命周期钩子覆盖工具调用和 LLM 调用的全部关键时刻：

| Hook | 触发时机 | 典型用途 |
|------|----------|----------|
| `before_tool` | 工具执行前 | 参数脱敏、注入上下文、额外校验 |
| `after_tool` | 工具执行后 | 结果过滤、审计记录 |
| `before_llm` | LLM 调用前 | prompt 增强、缓存命中检查 |
| `after_llm` | LLM 返回后 | 输出过滤、token 统计 |
| `on_error` | 错误发生时 | 告警、降级策略 |

Hook 在进程内执行，可以改参数、改结果、脱敏、加审计日志。你可以用 Hook 做 Tool Guard 之外的事——比如在每次 LLM 调用前注入安全策略，或者在工具返回后自动脱敏敏感字段。

---

## 执行：并发、隔离、有界

- **并发执行**——一个回合里独立的工具调用并发跑。Guard 检查是顺序的，执行在安全的地方并发。
- **每工具超时**——每个工具有自己的超时。默认：快工具 30s，shell/browser 60s，生成类 300s。
- **段隔离**——回合中间需要审批时，段在审批边界处分裂。
- **观察截断**——结果超长会被自动截断。
- **错误隔离**——单个工具失败不会中止整个回合。

---

## API 管理

```bash
# 列出所有工具
curl http://localhost:18088/api/v1/tools \
  -H "Authorization: Bearer YOUR_JWT_TOKEN"

# 启用 / 禁用
curl -X PUT http://localhost:18088/api/v1/tools/1 \
  -H "Content-Type: application/json" \
  -H "Authorization: Bearer YOUR_JWT_TOKEN" \
  -d '{"enabled": false}'

# 直接测试一个工具
curl -X POST http://localhost:18088/api/v1/tools/WebSearchTool/test \
  -H "Content-Type: application/json" \
  -H "Authorization: Bearer YOUR_JWT_TOKEN" \
  -d '{"query": "Spring AI"}'
```

每个依赖 provider 的工具在 Tools 页面都有测试按钮。

---

## 自定义工具

### 路线 1：`@Tool` Spring bean

```java
@Component
public class FactorialTool {

    @Tool(description = "Calculate the factorial of a number")
    public String factorial(
            @ToolParam(description = "The number to compute factorial for") int n) {
        long result = 1;
        for (int i = 2; i <= n; i++) {
            result *= i;
        }
        return String.valueOf(result);
    }
}
```

- Spring `@Component`
- 每个 `@Tool` 方法变成一个可调用的工具
- 每个参数上用 `@ToolParam`——这是 LLM 读的描述
- 返回值就是 Agent 看到的观察
- **工具做任何危险的事情时，为它加一条 Tool Guard 规则**

重启后工具就活了。

### 路线 2：技能脚本

不想写 Java？把行为打包成一个技能包，带 `SKILL.md` 和脚本。见 [技能系统](./skills)。

### 路线 3：MCP 服务

能力已经以 MCP 服务形式存在？加个服务配置就行。见 [MCP 协议](./mcp)。

---

## 下一步

- [技能系统](./skills)——建立在工具之上的更高层能力
- [MCP 协议](./mcp)——外部工具提供者
- [安全与审批](./security)——Tool Guard 规则、审批流程、审计日志
- [多模态创作](./multimodal)——生成类工具
