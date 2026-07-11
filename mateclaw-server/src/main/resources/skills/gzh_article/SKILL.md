---
name: gzh_article
description: '公众号图文创作 / 推文 / 官方号文章 (official account article) — 端到端：选题→搜集→成文→配图→去AI化→公众号内联样式排版→交付/草稿箱。honors user persona & style memory.'
version: 1.0.0
tags:
- 公众号
- 图文
- 内容创作
- writing
- wechat
platforms:
  - macos
  - linux
  - windows
---

# 公众号图文创作

把一个选题做成可直接粘进公众号编辑器的图文推文。7 个阶段，每步都接到平台真实工具上。

## 开工前：读取共享人设记忆

先用 `recall_structured` 取回并全程遵守：

- `content_persona` — 人设 / 口吻
- `writing_style_gzh` — 公众号文风
- `topic_interests` — 选题方向
- `banned_words` — 禁用 / 敏感词
- `signature_blocks` — 固定开场 / 结尾 / 引导关注段

取不到就用中性默认，不要编造。

## 七步 SOP

### 1. 选题

用 `web_search`（`freshness=week`、`language=zh-CN`）围绕 `topic_interests` 找近期热点和角度，`count` 取 5–8。给用户 3–5 个候选选题（每个带一句话切入角度），让其确认或补充后再往下。

### 2. 搜集汇总（参考文章）

用户给出参考公众号链接时：

- **优先**用可能存在的 `wechat_article_extract` 工具直接抽正文（若该工具可用，用 `load_skill` 或工具列表确认）。
- 没有该工具，就用 `browser_use`：先 `action=open` 打开链接，再 `action=snapshot` 抓取页面可见正文。
- 把每篇参考的**核心观点、结构、可借鉴角度**提炼成要点。

> **红线**：本技能产出**原创**内容，参考只用于找角度、补事实，并在文末标注引用来源。**严禁洗稿 / 搬运 / 逐段改写**他人文章。

### 3. 成文

按公众号结构模板成文（详见 `references/gzh_structure.md`）：

1. **钩子引言** — 用具体场景 / 反常识数据 / 一个问题抓住读者。
2. **3–5 个小标题分节** — 每节一个论点，配**具体案例或数据**，不要空谈。
3. **金句** — 每节或结尾埋一句可摘录的话。
4. **结尾行动号召 + 引导关注** — 用 `signature_blocks` 里的固定收尾。

全程遵守 `writing_style_gzh` + `content_persona`。

### 4. 配图

用 `image_generate`（`action=generate`）：

- **封面头图**：`aspectRatio=landscape`。prompt 里写清主题、风格、留白、中文标题可读。
- **关键小节配图**：按需为 2–3 个重点小节各生成一张，风格与封面统一。

`image_generate` 只认 `landscape` / `portrait` / `square` 三种比例，其它比例映射到最近的一个（如 3:4 → portrait）。

### 5. 去 AI 化

`load_skill deai_humanize`，然后对全文跑它的"打分→改写→复检"循环，`platform=gzh`，目标 `score ≤ 55`。

### 6. 违禁词自查

对照 `banned_words` 和 `references/compliance_checklist.md`（广告法极限词、虚假宣传、敏感词、侵权风险），命中就**标注并给出替换建议**，不要静默通过。

### 7. 打包交付（gzh_package —— 在线预览 + 素材下载）

**默认用 `gzh_package` 交付**。你只需把成稿正文以 **Markdown** 形式传入，服务端会转成公众号内联样式 HTML（公众号不认 `<style>` 块和 class，一律内联），并产出「在线预览链接 + 素材 zip（article.html / article.md / 封面）+ 可粘贴的内联 HTML」：

```
gzh_package(title="<标题>", markdown="<正文 Markdown，含小标题/列表/引用/```代码块```/表格>",
            coverImageUrl="<第4步封面图链接>", author="<作者/来源>")
```

> ⚠️ **不要自己手写整段内联样式 HTML 再塞进 `write_file` 或 `render_html_image(html=...)`**。大段、转义密集的 HTML 作为单个工具参数在流式传输时会被截断成非法 JSON、导致工具连续失败并触发循环熔断。让 `gzh_package` 在服务端生成 HTML，你只传紧凑的 Markdown。

把 `gzh_package` 返回的**在线预览链接**发给用户预览；满意后：
- 直接进草稿箱：`gzh_publish(action="draft", ...)`（**先向用户确认**，发布是对外不可逆动作）。
- 或让用户下载素材 zip / 复制内联 HTML，手动粘贴进公众号编辑器。

`references/` 里的 `gzh_layout*.html` 是**服务端配色/排版的参考**（`gzh_package` 已内置同款风格）；只有当用户明确要**高度定制的特殊版式**、且篇幅不大时，才手写内联 HTML 并用 `render_html_image(html=...)` 出预览图，注意控制体量避免截断。

## 保存自定义模板 / 对话升级技能

当用户对某个自创模板满意、想以后复用时，用 `skill_manage` 把它**存成一个自定义技能**（`builtin=false` 才能写入）：

1. 首次：`skill_manage(action="create", name="my_gzh_templates", content="<一份 SKILL.md，说明这是我的公众号模板库>")`。
2. 存模板：`skill_manage(action="write_file", name="my_gzh_templates", filePath="references/<模板名>.html", content="<内联样式HTML>")`。
3. 以后：`readSkillFile(skillName="my_gzh_templates", filePath="references/<模板名>.html")` 取回复用。

> 本技能 `gzh_article` 是**内置技能，不能直接被编辑**（`skill_manage` 会拒绝写内置技能）；自定义模板一律存到用户自己的自定义技能里。每次写入都会过安全扫描。

## 参考

- `references/gzh_layout.html` — 移动端优先的公众号内联样式 HTML 模板（通用）。
- `references/gzh_layout_minimal.html` — 极简编辑风模板。
- `references/gzh_layout_business.html` — 商务专业风模板。
- `references/gzh_structure.md` — 文章结构 + 钩子 / 金句方法论。
- `references/compliance_checklist.md` — 合规自查清单（广告法禁用词、敏感词、侵权、极限词）。
