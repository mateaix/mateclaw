---
name: xhs_note
description: '小红书图文创作 / 笔记 / 种草文案 (xiaohongshu / red note) — 端到端：成文→图文卡片(HTML→图)→去AI化→交付。标题四件套 + 碎句正文 + 话题标签，配 3:4 竖版卡片。honors user persona & style memory.'
version: 1.0.0
tags:
- 小红书
- 图文
- 笔记
- 内容创作
- xiaohongshu
platforms:
  - macos
  - linux
  - windows
---

# 小红书图文创作

把一个主题做成可直接发布的小红书笔记：文案 + 竖版图文卡片。

## 开工前：读取共享人设记忆

先用 `recall_structured` 取回并全程遵守：

- `content_persona` — 人设 / 口吻
- `writing_style_xhs` — 小红书文风
- `topic_interests` — 选题方向
- `banned_words` — 禁用 / 敏感词
- `signature_blocks` — 固定开场 / 结尾段

取不到就用中性默认，不要编造。

## SOP

### 1. 成文（小红书文案公式）

**标题（≤20 字，四件套任选组合）**

- **数字**：「30天」「省了800块」「3个动作」
- **悬念**：「原来一直做错了……」
- **情绪**：😭 😮‍💨 🤯 直给情绪
- **对比 / 反转**：「从烂脸到裸妆出门」

**正文**

- 碎句、一句一断，用 emoji 分段。
- 开头**痛点共鸣**，戳中读者才往下看。
- 中段**干货清单**：可操作、具体、有数字。
- 全程 `writing_style_xhs` + `content_persona`，大量第一人称和"姐妹们 / 你"。

**话题标签（3–8 个）**

大词 + 中词 + 长尾组合，例：`#护肤` `#敏感肌护肤` `#学生党平价护肤`。

### 2. 图文卡片（HTML → 图）

**卡片模板库**（竖版 3:4，挑选组合或据用户口味自创）：
- `references/xhs_card_cover.html` — 封面 / 大标题。
- `references/xhs_card_content.html` — 干货清单。
- `references/xhs_card_end.html` — 关注 CTA。
- `references/xhs_card_quote.html` — 金句 / 大字引用卡。

想要别的视觉风格时，直接生成一份新的自包含 HTML 卡片（可参考现有模板结构），不必局限于现成几款。`render_html_image` 渲染出的 PNG **本身就是预览**——先把图给用户看，满意再进入第 4 步打包。

**填充占位符**：每个模板里有 `{{TITLE}}` `{{SUBTITLE}}` `{{POINTS}}` `{{CTA}}` 等占位 token。把第 1 步的文案填进去——`{{POINTS}}` 是清单，按模板注释里的格式（每条一个 `<li>`）注入。

**渲染成图**：用 `render_html_image` 把填好的 HTML 渲染成 PNG。两种传法：

- 写入临时文件后传 `filePath`：
  ```
  render_html_image(filePath="<填好的卡片.html>", filename="xhs_cover",
                    width=1080, height=1440, fullPage=false)
  ```
- 或直接内联传 `html="<填好的完整HTML字符串>"`，其余参数同上。

竖版 3:4 用 `width=1080 height=1440`。`fullPage=false` 保证输出严格 3:4，不因内容溢出而拉长。

**可选封面底图**：想要更精致的封面，可先用 `image_generate`（`aspectRatio=portrait`）生成一张背景图，再把其链接填进封面模板的背景占位处。

### 3. 去 AI 化

`load_skill deai_humanize`，对正文跑"打分→改写→复检"循环，`platform=xhs`，目标 `score ≤ 55`。小红书口吻要碎、要有情绪，别写成公众号。

### 4. 交付

用 `xhs_publish`（action=export）把**卡片图 + 正文 + 话题标签**打成一个 `.zip` 发布包，一键下载：

```
xhs_publish(action="export", title="<标题>", body="<正文>",
            tags="标签1,标签2,标签3",
            images="<封面卡的 render_html_image 下载链接>,<内容卡链接>,<结尾卡链接>")
```

`images` 按顺序传每张卡片的 `render_html_image` 返回链接（首图即封面）。工具会返回发布包下载链接 + 手动上传步骤。

小红书**没有官方发布 API**：由用户下载后到创作平台手动上传完成发布，**不自动上传、不绕过任何风控/人机验证**。发布属于对外动作，必须用户明确同意。

发布前对照 `banned_words` 扫一遍正文和标题，命中即标注替换。

## 保存自定义卡片模板 / 对话升级技能

用户满意某个自创卡片、想复用时，用 `skill_manage` 存成**自定义技能**（`builtin=false` 才能写）：
- `skill_manage(action="create", name="my_xhs_cards", content="<一份 SKILL.md>")`（首次）。
- `skill_manage(action="write_file", name="my_xhs_cards", filePath="references/<卡片名>.html", content="<HTML>")`。

> 本技能 `xhs_note` 是内置技能、不能被直接编辑；自定义卡片一律存到用户自己的自定义技能里。写入都会过安全扫描。

## 参考

- `references/xhs_card_cover.html` — 封面大标题卡（bold hero）。
- `references/xhs_card_content.html` — 干货清单卡（clean list）。
- `references/xhs_card_end.html` — 关注引导卡（follow CTA）。
- `references/xhs_card_quote.html` — 金句 / 大字引用卡。
