---
title: LLM Wiki 知识库 — 结构化知识引擎，不是向量检索
description: LLM Wiki 把原始文档消化成结构化知识页面，带双向链接、摘要和溯源。Agent 自动注入知识。支持 lazy 入库（先入索引、按需出页面）和 eager 入库（上传即生成完整 Wiki）。
head:
  - - meta
    - name: keywords
      content: LLM Wiki,知识库,知识引擎,双向链接,结构化知识,RAG替代,知识图谱,lazy ingest,按需编译,语义搜索
---

# LLM Wiki 知识库

知识库不是一个让你搜索的地方，是一个让你**读**的地方。

市面上大多数 AI 知识系统只做一件事：把文件切块、向量化、查询时返回片段。你拿到的是碎片。你看不到它的全貌。你问它之前，你永远不知道它到底"知道"什么。没有任何东西是**完成**的。

MateClaw 的 LLM Wiki 做的事不一样。你把原始材料扔进知识库，系统会把它读一遍，消化一遍，然后写出结构化的 Wiki 页面——每一页有摘要、反向链接、通往原文段落的来源指针。你可以打开任何一页直接读。你可以编辑。Agent 自动读摘要，按需取全文。

**是一本书，不是一个向量库。**

::: tip 它和那些「LLM Wiki」开源仿品有什么不一样
2026 年 4 月，Andrej Karpathy 用一个 GitHub Gist 把 "LLM Wiki" 这个想法推到台面：扔给 AI 的资料应该被读一遍、写成可读的 wiki，而不是当成查询时才翻一遍的向量碎片。一个月内，GitHub 上冒出至少 9 个 `llm-wiki` 单文件实现——好用、本地、个人级。

MateClaw 的 LLM Wiki **是同一个想法长成的产品**：

- 不是一个人的笔记本，是**团队共享**的知识库——多用户、权限、审计、归档
- 不是跑完就完的脚本，是 **Agent 一直在用**的能力——记忆、检索、引用全打通
- 不是只有 eager 模式，**lazy 模式按需出页面**——量大时省 90%+ 的 LLM 调用
- 不是裸文件输出，是**带溯源 / 双向链接 / 人工编辑保护 / 归档恢复**的页面层
- 不是孤立工具，是 **MateClaw Agent 操作系统的知识层**——和记忆、Agent、渠道交付串成一根链

> 他们做了克隆。我们做了一个家。
:::

---

## 三层模型

一个知识库是三层结构叠起来的：

1. **原始材料层**——你扔进去的文件。PDF、Word、Excel、PowerPoint、HTML、Markdown、纯文本（含 CSV），或者桌面端扫描整个本地目录。系统保留原文不动；Wiki 里的任何一句话都能回溯到它出自哪段原文。
2. **Wiki 页面层**——AI 从原始材料里写出的结构化文章。每一页有标题、摘要、正文、指向相关页面的双向链接（`[[像这样]]`，也支持 `[[target|展示文字]]` alias 形式）、以及通往原文的来源指针。
3. **Agent 表层**——Agent 调用 wiki 工具时，系统会把相关页面的摘要自动注入 prompt，正文按需读取。Agent **不读原文**，它读这本书。

这事很重要，是因为 Agent 的上下文窗口再也不用浪费在反复读原文上了。Token 都花在思考上，不是重复阅读上。

---

## 建一个知识库

`Wiki → 新建知识库`。起名按"里面装的是什么"，不是"属于谁"。"产品规格"比"Alpha 组的 KB"好。

建好之后加材料：

- **上传文件**——把 PDF、Word、Excel、PowerPoint、HTML、Markdown、纯文本（含 CSV）拖进上传区。每个文件成为一条 raw material。
- **扫描本地目录**——桌面端专属。指一个文件夹，MateClaw 递归走完整个树，尊重 `.gitignore`，把能读出文本的全都导入。
- **粘贴文本**——适合短片段或对话记录。

材料一进来，系统就开始入库。每条 raw material 上都有状态：`pending → processing → completed`。如果中间几块失败了，状态会变 `partial`——你拿到的是"除了坏块之外都成功了"，不是"一块坏了整份全挂"。

---

## 两种入库模式：要不要让 AI 立刻写页面

`Wiki → 配置 → 入库模式` 二选一：

- **Eager（立即生成页面）**——上传后立刻跑完整流水线，把材料消化成结构化 Wiki 页面。适合"我就是要一份现成可读的 Wiki"。代价：每次上传烧 N 次 LLM 调用，慢、贵。
- **Lazy（先入索引）**——上传只做抽取、清洗、切片、向量化。**0 次页面生成 LLM 调用**。立刻可搜，页面在有人真要读时按需生成。适合"先把资料倒进去，回头按需取"。

旧 KB 默认走 eager 不变，新建的 KB 想省钱就在配置页切到 lazy。两种模式可以混着用——同一个 KB 的旧页面继续在，再上传的材料按当前模式处理。

> Lazy 下"页面数 = 0"是**完成**态，不再被标记 failed。这条改动专门解决之前抽不出文本的材料一律变红的尴尬。

---

## 消化到底在干嘛

### Eager 模式：完整流水线

对每一份原始材料，按顺序：

1. **切块**——把原文切成带重叠的段落，附带 chunk 级元数据：页码（PDF）、标题路径（Markdown / HTML 经过 jsoup 清洗）、章节标识、token 估算。
2. **抽取概念**——让 LLM 在每一块里识别出实体、决策、事实、未解问题。
3. **聚类成稿**——把相关的抽取聚在一起，生成一批候选 Wiki 页面的结构化草稿。
4. **建链**——在页面之间找双向引用（`[[concept]]` 和 `[[concept|展示文字]]`），计算反向链接。
5. **落库**——把页面写进 `mate_wiki_page`，引文（citation）指回原文段落。

入库是幂等的。同一份材料再跑一遍，系统更新已有页面而不是复制一份。人工编辑过的内容会被保护——`locked` 标记告诉 digester"这段是人写的，别动"；要让 AI 重写就显式解锁。

#### 两阶段消化

eager 模式分两阶段，速度提了一个数量级：

- **阶段 A（路由）**——抽取元信息和概念路由，决定每段原文会流向哪些页面。
- **阶段 B（合并）**——按页并行生成，60+ 页同时跑。每条原始素材有自己的**独立进度条**——不再盯着"处理中…"猜进度。

**可恢复**：中途断了？点"重新处理"，只重跑未完成的页面，已生成的不动。超过模型上下文限制的文档，系统自动做 mean-pool 子段切分——你不用管。

### Lazy 模式：先入索引，按需出页面

链路缩成四步：

1. **抽取** → 拿到原始文本（PDF/DOCX/...）。
2. **预处理** → jsoup 清掉 HTML 噪声，识别 markdown 标题层级和 PDF `--- Page N ---` 标记。
3. **切片 + 元数据** → 每个 chunk 自带 `page_number`、`header_breadcrumb`（如 `Intro / Setup / Linux`）、`source_section`、`token_count`。
4. **向量化** → embedding 异步入库，立刻可搜。

页面什么时候出？不出。等你或者 agent 真的需要的时候，按需编译一次——只取检索到的 evidence chunks，引文也只绑到这几个 chunk 上。

---

## 系统页：overview 和 log

每个 KB 自动有两条**系统页**：

- `slug=overview` —— 知识库的门面。范围、最近更新、覆盖率统计的摘要。
- `slug=log` —— 入库 / 编译 / 编辑活动的可审计记录。

两者 `page_type=system, locked=1`：

- 删除（单条 / 批量 / 重处理时的旧页清理）**删不掉**——会拿到清晰的拒绝理由。
- 列表、关键词搜索、语义搜索、关联推荐**默认过滤掉**它们，避免污染检索结果和上下文窗口。
- 但 agent 直接按 slug 读（`wiki_read_page("overview")`）仍然可以——想看就显式读。

> 普通用户也可以给手写的页面打上 `locked=1`，AI 工具就不会动它，逻辑跟 `lastUpdatedBy="manual"` 是叠加的。

---

## 加工器（Transformations）：让知识库可编程

::: tip 1.3.0 新增
Transformations 引擎自 v1.3.0 起提供。v1.2.0 及更早版本里，Wiki 只能被动检索——把原料切块、向量化、等召回；这一版起 Wiki **学会主动加工**：用户自定义模板、跨原料聚合、reverse-citation、JSON 输出、对页面跑模板、cancel/re-run 等能力全部到位。详细 release 故事见 [v1.3.0 release notes](./releases/1.3.0)。
:::

Wiki 默认把原始材料消化成它认为重要的页面 —— 但"重要"是它定义的，不是你定义的。**加工器**翻转了这件事：你写 prompt 模板，告诉系统"我想从材料里抽什么"，引擎替你跑、落库、维护。

`Wiki → [任一知识库] → 加工器` 进入面板。每个模板由这几样组成：

- **标识名** —— 短的小写 slug，Agent 调用时用它指名（如 `contract-risk-extract`）
- **显示名 / 描述** —— 给人看的
- **提示词模板** —— 你的指令，支持 `{input_text}` 和 `{title}` 占位符
- **模型** —— 默认走 KB 默认 chat 模型；也可以把单个模板钉到一个特定模型上
- **默认运行** —— 勾上 → 每次新材料处理完，自动跑这个模板
- **输出去向** —— `不保存`（只留运行历史） / `保存为 Wiki 页面`（自动产生 synthesis 页）
- **输出格式** —— `Markdown` 或 `JSON`（带可选 Schema 校验）

### 开箱即用的 7 个企业模板

新建 KB 直接看到，覆盖典型企业场景：

| 模板 | 用途 |
|---|---|
| `contract-risk-extract` | 合同条款级风险提取（高 / 中 / 低）+ AI 建议改写 |
| `meeting-action-items` | 会议纪要 → 决议 + 行动项（owner / 截止日 / 验收标准）|
| `customer-profile` | 客户邮件 / CRM 记录 → 结构化客户画像 |
| `competitor-update` | 公开信号 → 竞品动态简报 |
| `resume-structured-extract` | 简历 → 标准档案（教育 / 工作 / 技能 / 亮点）|
| `incident-postmortem` | 事故报告 → 5-Why 链 + 整改清单 + 相似事故 |
| `paper-imrad` | 论文 → IMRaD 摘要 + 关键术语 |

### 四种触发方式

| 触发 | 怎么发起 | 用在哪 |
|---|---|---|
| **手动** | UI 选材料 + 点「运行」 | 调 prompt、单次试运行 |
| **默认运行** | 模板开关 + 上传新材料 | "每份新合同都自动跑一次" |
| **Agent 工具** | 数字员工调 `wiki_apply_transformation(name, rawId)` | Agent 自己决定要跑哪个 |
| **跨原料聚合** | 卡片上的「聚合所有运行」按钮 / `wiki_aggregate_transformation` | 把 N 份材料的 per-source 输出 map-reduce 成一份 KB 级合成页 |

### 输入：原始材料 / 现有页面

模板不止能跑在 raw material 上，也能直接对现有 wiki 页面运行（Agent 端工具：`wiki_apply_transformation_to_page(name, slug)`）。这让你把模板串起来 —— 先用 A 把原料做成 synthesis 页，再用 B 对那个页面跑出新的视图。

### 输出去哪：留在历史还是变成 Wiki 页面

- **不保存** —— 运行结果只在「加工器」tab 的运行历史里可查，不进 Wiki。适合一次性临时输出。
- **保存为 Wiki 页面** —— 每次成功运行 → 自动 upsert 到一个固定 slug 的 synthesis 页（`<模板名>-<材料标题>`）。重跑只更新这页，不复制。**而且**：
  - 自动建 page-level embedding，进语义搜索
  - 反向解析输出里的「第 N 题 / 第 X 页」标记 → 回写 chunk-级 citation，绑回原文
  - 进关系图、热缓存、Agent 直接可读

### JSON 输出 + Schema 校验

输出格式选 JSON 时：

1. 注入严格 system prompt（"只返回 JSON 对象，前后无文字"）
2. 解析失败 → 自动重试一次，retry 时附上具体错误提示
3. 模板上可选填一个 JSON Schema，executor 在解析后检查 required 字段是否齐
4. 仍然失败 → run 标记 failed，错误写进历史

成功时 JSON 以 fenced ```json 块的形式存进 page，下游程序可直接 grep + parse。

### 跨原料聚合

KB 里 10 份合同每份都跑了 `contract-risk-extract`，怎么看整体？卡片上点「聚合所有运行」：

- 系统读取该模板对该 KB 的所有 completed run
- 按 source 去重（每份原料只取最新一次）
- LLM 做 merge + dedupe：去重相同条款类型、合并 source 引用、保留分歧
- 产出 KB 级合成页 `<模板名>-aggregate`
- 自动嵌入语义搜索

这是把 "per-source extract" 升级成 "KB-level synthesis" 的关键 —— 不用一份份对比，AI 帮你看全局。

### 运行历史 + 可观测性

每条 run 都记录：

- 状态：`pending / running / completed / failed / cancelled`
- 耗时、模型、触发方式（manual / apply_default / agent_tool / aggregate）
- 上行 / 下行 token 消耗（`8.2k↑ / 1.1k↓`），跨重试累加
- 关联输出页面（如果 output_target=page）
- 完整输出 / 错误信息

UI 上能做：

- **取消** —— 标记一个正在跑的 run 为 cancelled（LLM 调用仍在 provider 端走完，但执行器会丢弃结果）
- **重试** —— 失败 / 完成的 run 一键再跑一次（同输入）
- **对比** —— 勾两条 completed run → 「对比所选」→ side-by-side 模态框，左旧右新，调 prompt 时看差异最方便

### 典型场景

| 场景 | 配方 |
|---|---|
| 法务自动化 | `contract-risk-extract` + 默认运行 + 保存为页 → 每份合同自动生成风险报告页 |
| 销售情报 | `customer-profile` + 默认运行 → 每份客户材料合成画像页 |
| 研发记忆 | `meeting-action-items` + `incident-postmortem` 一起用 → 决策史和事故知识沉淀 |
| 研究综述 | `paper-imrad` 跑一批论文 → 「聚合所有运行」生成主题综述 |
| 程序化下游 | JSON 输出 + Schema → 把 wiki 当成结构化数据源 |

### REST 端点（基础路径 `/api/v1/wiki/transformations`）

| Method | Path | 作用 |
|---|---|---|
| `GET` / `POST` / `PUT` / `DELETE` | `/`、`/{id}` | 模板 CRUD |
| `POST` | `/{id}/apply?sync=true` | 跑一次（body 给 `rawId` 或 `pageId` 二选一）|
| `POST` | `/{id}/aggregate?kbId=X` | 跨原料聚合 |
| `GET` | `/runs?rawId=` 或 `?kbId=` 或 `?transformationId=` | 查运行历史 |
| `POST` | `/runs/{runId}/save-as-page` | 手动把一条 run 落成 wiki 页面 |
| `POST` | `/runs/{runId}/cancel` | 标记 cancelled |

---

## Agent 怎么用 Wiki

在 `Agents → 某个 Agent → 知识库` 里绑定一个知识库。从那一刻起：

- Agent 的 system prompt 里自动注入这个 KB 顶层页面的压缩摘要。
- Agent 的工具箱里多了这些 wiki 工具：

| 工具 | 用途 |
|---|---|
| `wiki_search_pages` | 混合检索（关键词 + 语义），页面级 |
| `wiki_semantic_search` | chunk 级语义搜索，命中带 `pageNumber` 和 `section` 字段 |
| `wiki_read_page` | 读单页，可按 section 或字符上限截取 |
| `wiki_read_many` | **新**：一次拿多个 slug 的内容，最多 10 个，每页可设上限。替代多轮 `wiki_read_page` |
| `wiki_compile_page` | **新**：lazy 模式下按主题生成单页。引文只绑搜到的 evidence chunks |
| `wiki_trace_source` | 跟踪某个 wiki 页面来自哪些原文 |
| `wiki_related_pages` | 关联页面（共享 chunk / 共享原文 / 双向链 / 语义近邻） |
| `wiki_explain_relation` | 详细拆解两页之间的关联强度和原因 |
| `wiki_create_page` / `wiki_delete_page` | 直接维护页面（删除受 locked / system 保护） |
| `wiki_archive_page` / `wiki_unarchive_page` | 软归档：从默认 list/search/related 隐藏，但保留页面与引文，可恢复。系统页不能归档。 |
| `wiki_list_transformations` | 列出当前 KB 可用的加工器模板（名称、用途、是否默认运行）|
| `wiki_apply_transformation` | 对一份**原始材料**运行一个模板，返回输出（runId / output / 落页信息）|
| `wiki_apply_transformation_to_page` | 对一个**现有 wiki 页面**运行模板（接 slug，无需数字 ID）|
| `wiki_aggregate_transformation` | 跨 KB 内所有 raw 的同模板 run，合成一份 KB 级 synthesis 页 |

`kbId` 参数根据绑定自动解析——Agent 不需要猜。

一个典型的 Agent 回合：

> **用户**："上个季度我们关于重试策略是怎么决定的？"
>
> **Agent**：*（读到注入的摘要里有"重试策略"页，直接打开"重试策略"页，返回决策内容和原文来源链接。）*

那不是一次向量查询。是字面意义上的"打开这一页"——因为**这一页真实存在**。

### 热缓存：每次系统提示里都有一份"最近活跃"快照

绑定的 KB 不光给 Agent 注入摘要，还会注入一份**热缓存**——可以理解为 Agent 每一轮开局都先翻一遍的那一页：

- **最近更新**——最近一次入库 / 页面编辑
- **关键近期事实**——重建器筛出的高信号要点
- **最近变更**——上次重建以来新生成 / 重新编译的页面
- **悬而未决的话题**——开放问题和未结论的决策

重建在每次会话结束（`ConversationCompletedEvent`）异步触发，配合一个可配置的去抖窗口（默认约 30 秒），短轮次密集发生时不会把 LLM 打爆。Admin 也可以手动触发重建——手动路径会绕开去抖。

注入受 `wiki.hot_cache.enabled` 特性开关控制（关闭 → 注入空字符串），并按 KB 优先级最多挑前两个，避免系统提示被撑爆。

#### 在 KB 详情抽屉里管理

`Wiki → [你的 KB] → 热缓存` 面板里：

- **重新生成**——异步手动重建；点完面板会在几秒后轮询并刷新
- **重置**——软删除当前行，下一次 `ConversationCompletedEvent` 时重建
- 元数据栅格：上次更新时间、更新原因（`AUTO` / `MANUAL` / `EVENT`）、重建次数、上次耗时（毫秒）
- 上次重建失败时显示错误条
- 渲染后的 Markdown 内容预览

#### 运维端点

基础路径 `/api/v1/wiki/hot-cache`：

| Method | Path | 作用 |
|---|---|---|
| `GET` | `/{kbId}` | 拿当前快照 + 元数据 |
| `POST` | `/{kbId}/regenerate` | 手动重建（异步，跳过去抖） |
| `DELETE` | `/{kbId}` | 软删除；下次事件触发重建 |

热缓存数据落在 `mate_wiki_hot_cache`——具体列见下面的 **底层数据** 一节。

### Lazy 上传后的典型流程

```
用户上传 product-manual.pdf            （lazy 模式：0 次页面生成 LLM 调用）
       ↓
Agent: wiki_semantic_search("error code 500 retry")
       → 命中 chunk #1234，page=12，section "Error Handling / Retries"
       ↓
Agent: wiki_compile_page(topic="500 retry policy", maxEvidenceChunks=5)
       → 生成 slug=500-retry-policy，引文绑到这 5 个 chunk
       ↓
Agent: wiki_read_page("500-retry-policy")
       → 返回结构化页面 + 来源 chunk 列表
```

整条路径只在最后一步烧了一次 LLM 调用，且只针对真正相关的 5 个 chunk。

---

## 页面的阅读和编辑

每一个生成出来的页面都是一等公民的文档，你可以在 Wiki 视图里直接打开：

- Markdown 渲染带语法高亮
- 侧边栏列出反向链接——看看有哪些别的页面引用了它
- 每个说法上都有"来源"按钮，点一下跳到 Wiki 所依据的原文段落
- 编辑模式下可以直接重写页面的文字
- system / locked 页面的删除按钮是禁用状态

AI 写错了就改。你的修改在下一次入库时会被保留——`locked` 标记告诉 digester 别碰这段人写的内容。要让 AI 重新起草就显式解锁。

---

## Wikilink 与死链治理

页面之间用 `[[slug]]` 写跨页引用，是 Wiki 这种长寿命知识资产的核心粘合剂。RFC 55 把这一层从 "[[Title]] 写起来好像也行、点了 404 才发现" 改成 **写入即校验、删除自动清理、死链显式可见**。

### Wikilink 语法

只承认一种契约：

- `[[slug]]` —— 显示文本默认用目标页的 title
- `[[slug|显示文本]]` —— 自定义显示文本，slug 仍是跳转目标

slug 必须是真实存在页面的 slug。LLM 生成内容时索引里给的就是 slug-first 列表（`- [[slug]] — Title — Summary`），prompt 显式禁止发明索引外的 slug，并明示 `[[页面标题]]` / `[[Title]]` 这种早期写法会被识别为死链。

跨大小写命中：`[[STATEGRAPH]]` 和 `[[stategraph]]` 一视同仁，都按 lowercased exact match 匹配 slug。

### 同事务校验：`outgoing_links` + `broken_links`

每次页面保存（手工编辑、AI 生成、合并、级联重写）的**同一个事务**内：

1. 从正文里抽出所有 `[[...]]`（跳过 fenced 代码块、inline 代码）
2. 写 `mate_wiki_page.outgoing_links`（去重、lowercased 字符串数组）
3. 拿当前 KB 的活跃 slug 集合（不含 archived）做差集 → 写 `broken_links`
4. 写 `broken_links_scanned_at` 时间戳

效果：写完页面**立刻**就知道哪些 `[[...]]` 是死链，不需要等扫描。代码块和反引号里的 `[[...]]` 是讲解 wiki 语法的示例，被严格保留为字面，不进入 outgoing。

### KB 级死链 lint

进入任一 KB，顶部 banner 会显示当前死链状态。按"扫描死链"启动一次全 KB job：

| Method | Path | 说明 |
|---|---|---|
| `POST /api/v1/wiki/knowledge-bases/{kbId}/lint/broken-links` | 启动 job（job-based 异步），返回 `{jobId, status, startedAt}`；同 KB 已有 running job 时幂等返回 |
| `GET .../lint/broken-links` | 拉最近一次 completed 扫描的聚合结果 |
| `GET .../lint/broken-links/jobs/{jobId}` | 查单次 job 状态 |

聚合结果按页列出，每条带 `pageId / slug / title / brokenRefs`。前端 banner 把"已扫描 X 页，无死链"和"发现 N 条死链分布在 M 页"区分显示，点"查看"打开详情面板，可一键跳到出错的源页面去手工修。

job 执行时间：100 页 KB 通常 1 秒以内；POST 入队 < 200ms。

### 删除 / 重命名的级联清理

**删页面**时，所有引用方的 `[[deleted-slug]]` 会在同一事务里被改写成纯文本，保留快照标题作为可读文字。带别名的 `[[deleted-slug|alias]]` 直接降级为 `alias`。引用方的 `outgoing_links` / `broken_links` 跟着重算。

**重命名页面**：`POST /api/v1/wiki/knowledge-bases/{kbId}/pages/{slug}/rename` body `{"newSlug":"new"}`。同一事务里：

- 自身 slug 更新为新值
- 所有引用方的 `[[oldSlug]]` 改写成 `[[newSlug]]`，`[[oldSlug|alias]]` 改写成 `[[newSlug|alias]]`（alias 字节一致保留）
- 引用方的 `outgoing_links` 同步更新

不接受空 slug、不接受和自身相同的 slug、不接受和**别的**页面冲突的 slug；保护页（system / locked）拒改。case-only rename（`foo → FOO`）允许，跨 H2 与 MySQL 行为一致。

每次 delete / rename 写一条 `mate_audit_event`（action `wiki.page.delete` / `wiki.page.rename`），`detailJson` 里带 `affectedPageIds` 列表，方便事后追溯影响面。

紧急 kill-switch：`mate.wiki.cascade-delete-enabled=false` 关闭级联，回到只删自身行的旧行为；正常状态下不需要开启。

### Chat 里点 wikilink 直接跳

Chat 渲染 agent 回复时，content 里的 `[[slug]]` / `[[slug|alias]]` 会渲染成带 `data-wiki-title` 的 `<a class="wiki-link">`。点一下：

1. App 级全局 click 委托抓到 click
2. 调 `GET /api/v1/wiki/pages/lookup?title=X&slug=X` —— 在用户可见的所有 KB 里搜（slug 命中优先，title fallback）
3. 1 hit → `router.push` 进 wiki 视图、自动选 KB、自动打开页面
4. 0 hit → toast "未找到匹配的 wiki 页面：X"
5. 多 hit → picker 让用户挑

不再需要先去 wiki 视图、再找 KB、再找页面——chat 里看到的引用直接跳。lookup 严格 case-insensitive exact，不做 canonical 模糊，所以 LLM 写错 slug 会通过 toast 让你看到，而不是悄悄跳到一个"看起来像的"页面。

### Phase 路线图（每个 phase 都已 land）

| Phase | 主要变更 |
|---|---|
| 1 | 前端渲染层 slug-first DOM postprocess + 危险字符 guard + 全量 `pages/refs` |
| 2 | V129 迁移 `broken_links` / `broken_links_scanned_at`，save 同事务写，KB 级 lint job + UI banner |
| 3 | 9 份 wiki prompt 统一 `[[slug]]` 契约，索引格式 slug-first，batch-create existing/planned 二分 |
| 4 | 删除 / 重命名级联清理，audit log，feature flag |
| 5 | analyze 阶段输出 slug 白名单 `related_pages`（服务端二次校验），enrich applier 跳代码块 + slug 白名单 gate |

完整设计 + 实测见 `rfcs/202605/55-wiki-link-resolution-overhaul.md` + `mateclaw-server/src/test/resources/e2e/wiki-link-overhaul-verification.md`（6 个 e2e pass section、50+ 条 live 断言、3 个测试中发现并修复的 bug 完整记录）。

---

## 搜索、来源追溯、语义检索

- **语义搜索**——问"我们关于 auth 决定了什么？"，直接返回那个决策，不是一堆包含"auth"的页面。chunk 级嵌入 + cosine 检索，**理解你问的是什么意思**。命中现在自带 `pageNumber` 和 `section`，agent 可以引用 "page 12, Setup / Linux" 而不是粘一段没头没尾的片段。
- **混合检索**——同时走全文匹配和语义匹配，取两者的交集优势。
- **全文搜索**——搜标题、摘要、正文、概念抽取。横跨你有权访问的所有 KB。
- **来源追溯**——任何页面的任何一句话都可以点回原文段落。Agent 也能做这件事。
- **反向链接**——每一页都显示有哪些别的页面引用了它。`[[concept|展示文字]]` 形式的 alias 链接现在解析正确了——只把 `concept` 当 slug，`展示文字` 只用于显示。
- **关联推荐**——跨四种信号（共享 chunk、共享原文、双向链、语义近邻）找相关页面，扩展时**不会**把 overview / log 拉进来当种子。
- **人工编辑保护**——locked / 编辑过的页面不会在再次入库时被覆盖；要重写就显式解锁。

---

## 视觉管线：图片也能被读出来

读不了图的 wiki 是半瞎的。PDF 尤其严重——一半的真信息往往就在那些图里。

打开 `wiki.ocr.enabled` 特性开关之后，MateClaw 会把每一张上传图片——以及**嵌在 PDF 页面里**的每一张图——都过一遍视觉管线，提取**配字描述**和**图中可见文字**，作为一等公民的 chunk 和上下文文字一起入库。检索找得到、Agent 引用得到，搜索结果里图片也会作为缩略图就地显示，点一下放大成灯箱。

### 工作流程

1. **哈希**图像字节（SHA-256）——缓存按内容寻址，所以同一张图换个 KB 重传，零成本。
2. **查 `mate_wiki_image_caption_cache`**——命中直接复用配字、`hit_count` 加 1。
3. 未命中就**按顺序走配置好的视觉 provider**，第一个返回非空 caption 的获胜。
4. **写回缓存**：caption + visible text + provider id + model + 耗时（race-tolerant insert，并发上传同样 OK）。
5. `VisionResult` 回到 chunker，作为该图所在页的额外内容。

### 支持的视觉 Provider

| Provider id | 模型 | 备注 |
|---|---|---|
| `dashscope-vision` | `qwen-vl-max` | DashScope 兼容模式，复用 UI 里配好的 DashScope provider |
| `zhipu-vision` | `glm-5v-turbo` | 智谱 BigModel，OpenAI 兼容 |
| `volcano-doubao-vision` | 可配置 | 字节跳动火山豆包视觉 |

Provider 按 order 自动选用。Key / base URL 都在 `Settings → 模型` 里像普通 provider 那样配，视觉管线会从那里取凭证。

### 切换开关

`Settings → Feature Flags → wiki.ocr.enabled`。轻量部署默认关；至少配好一个视觉 provider 之后再打开。

开关**关闭**时管线会短路——上传仍然成功，只是图像 chunk 没有 caption。这些图的 `extracted_text` 缓存是**延后**而不是被污染，所以你下次重新打开开关，新上传的图会自动配字，老图也不用强制重建。

### UI 上看得到的变化

- 命中含图片证据的搜索结果会内联显示缩略图，点开就是全分辨率灯箱。
- 原始材料详情抽屉里每张抽出来的图片旁边显示 caption，方便你对照模型到底"看到了"什么。

---

## 健康感知的 LLM 降级

Wiki 入库本身就很烧 LLM 调用，过去任何一个 provider 卡住都会拖死整批。现在每一个 wiki 步骤（`route` / `create_page` / `merge_page` / `enrich` …）都走**健康感知**的降级链：主模型报错或超时，就把 KB `fallback` 列表上的下一个模型试一次。每个 provider 的健康度（成功 / 失败 / 延迟）会被独立追踪，抖动严重的 provider 会被自动降权，等它恢复再回来。

降级链在 `Wiki → 配置 → 模型策略` 里、按步选模型旁边配。

---

## 模型策略真生效

`Wiki → 配置 → 模型策略` 里给每个步骤选不同模型：

```text
heavy_ingest.route        → 便宜的小模型，做路由
heavy_ingest.create_page  → 强模型，写完整页面
heavy_ingest.merge_page   → 强模型，合并已有页面
light_enrich.enrich       → 便宜的小模型，标 wikilink
```

回退顺序：

```text
stepModels[step]   →   wikiDefaultModelId   →   系统默认模型
```

之前 UI 上的这套配置在 Java 端只是摆样子（字段没承接），现在每一次 LLM 调用真的按它选——route / create / merge / retry / repair / 文档分析 都是。

---

## 底层数据（如果你好奇）

九张表：

| 表名 | 用途 |
|------|------|
| `mate_wiki_knowledge_base` | 每个 KB 一行。owner、名字、描述、配置 JSON（含 `ingestMode` / `wikiDefaultModelId` / `stepModels` 等）。 |
| `mate_wiki_raw_material` | 每份上传一行。状态、byte hash、来源路径、上次成功处理时的 hash。 |
| `mate_wiki_page` | 每个生成页面一行。标题、摘要、正文、`source_raw_ids`（回指原文）、`page_type`、`locked`、版本号，外加 `embedding` / `embedding_model` / `embedding_text_version` 让 synthesis 页直接进语义搜索。 |
| `mate_wiki_chunk` | 每个 chunk 一行。content + hash + 偏移 + embedding，外加 `page_number` / `header_breadcrumb` / `source_section` / `token_count`。 |
| `mate_wiki_relation` | 缓存的页对页边（共享 chunk / 共享原文 / 直接链接 / 语义近邻），用于检索时的 1 跳关系 boost 和关联推荐工具。 |
| `mate_wiki_hot_cache` | 每个 KB 一行。渲染后的 Markdown 快照 + `last_updated` / `update_reason` / `rebuild_count` / `last_rebuild_duration_ms` / `last_rebuild_error`。 |
| `mate_wiki_image_caption_cache` | 视觉管线提取出的 caption 缓存，按 SHA-256 索引。`caption` / `visible_text` / `mime_type` / `capture_model` / `provider_id` / `duration_ms` / `hit_count`。 |
| `mate_wiki_transformation` | 每个加工器模板一行。`name` / `title` / `description` / `prompt_template` / `model_id` / `apply_default` / `output_target` / `output_format` / `output_schema`。`kb_id=NULL` = 工作区全局可用。 |
| `mate_wiki_transformation_run` | 每次模板运行一行。`status` / `output` / `error` / `duration_ms` / `model_id` / `triggered_by` / `input_tokens` / `output_tokens` / `total_tokens` / `output_page_id`。 |

`mate_wiki_page` 还带两个保护字段：

- `locked`（V40）—— 1 = 禁止 AI 工具 / 批量操作 / 重处理清理删改本页。系统页 `overview`/`log` 默认 `locked=1`，但用户也可以给任意手写页面打上。
- `archived`（V41）—— 1 = 软归档，从默认 list / search / related 结果消失，但页面、引文、反向链全保留。可恢复。

### 几个运维 endpoint

不靠 cron / 事件钩子兜底，想立刻刷一下的时候用：

| Endpoint | 作用 |
|---|---|
| `POST /api/v1/wiki/admin/kb/{kbId}/rebuild-overview` | 立即按当前数据重写 overview marker 区域 |
| `POST /api/v1/wiki/admin/backfill-tokens` | 立即跑一批 token_count 回填，返回 `pendingBefore/pendingAfter/filledThisBatch` |

`application.yml` 的 `mate.wiki` 配置块控制切块大小、并发度、auto-process 等全局参数；具体到每个 KB 的入库模式 / 模型策略 / 备选模型链，写在 KB 的 `configContent` JSON 里——前端配置页直接编辑。

> 旧 chunk 的 `token_count` 列允许 NULL；后台有个低频 cron `WikiChunkTokenBackfillJob` 用 `ceil(charCount / 4)` 按批回填，不影响主链路。

---

## 什么时候该用它

用 Wiki KB 当你有：

- 同一主题下不止几份文档
- 希望人也能读能改、不只是被检索的内容
- 需要跨 Agent、跨会话持续存在的信息
- "这个说法从哪来的"这件事很重要的资料

如果只是想把一份 PDF 扔进一次对话，在聊天里直接附件就好。Wiki 是给值得一个书架的材料准备的。

模式选择经验：

- 你**马上**要拿 Wiki 来人读 / 演示 / 分享：eager。
- 你只是先把资料倒进来，等 agent 用到才考虑要不要固化成页面：lazy + 按需编译。
- 量大、模型贵、不确定每篇都需要完整页面：lazy 默认更省。

---

## 下一步

- [Agent 引擎](./agents) —— 把 Agent 绑定到 KB
- [记忆系统](./memory) —— Wiki 和记忆的区别（提示：Wiki 是刻意的，记忆是被动的）
- [API 参考](./api) —— Wiki 的 REST 接口
