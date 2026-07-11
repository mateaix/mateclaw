---
name: deai_humanize
description: '去AI味 / 去AI化 / 人味改写 — detect AI writing traces (AI痕迹/AI腔) with a measurable heuristic score, then rewrite text to sound human. Two tones: gzh (公众号沉稳克制) and xhs (小红书活泼碎句). humanize / de-ai / anti-ai-slop.'
version: 1.0.0
tags:
- 去AI化
- humanize
- writing
- rewrite
- chinese
- anti-ai-slop
dependencies:
  commands:
    - python3
  tools:
    - skillScriptTool
platforms:
  - macos
  - linux
  - windows
---

# 去 AI 化 · 人味改写

把一段"一眼 AI"的中文改写成像真人写的。做法是先**量化打分**找出 AI 痕迹，再**针对性改写**，然后**复检**，循环到达标为止。

> **重要说明**：本技能是一个**可解释的写作质量启发式**，用来指导改写、提升"人味"。它**不保证**能骗过任何第三方 AI 检测器，也不以"过检测"为目标——目标是文字读起来自然、具体、有个人声音。

## 何时触发

用户说"去 AI 味 / 去 AI 化 / 这段太 AI 了 / 帮我改得像人写的 / humanize / 让它不像机器写的"，或在公众号、小红书成文后需要润色时。另外两个创作技能（`gzh_article`、`xhs_note`）会用 `load_skill deai_humanize` 主动调用本技能。

## 开工前：读取共享人设记忆

先用 `recall_structured` 取回以下键（snake_case），改写时全程遵守：

- `content_persona` — 用户的人设 / 说话口吻
- `writing_style_gzh` — 公众号文风偏好
- `writing_style_xhs` — 小红书文风偏好
- `banned_words` — 禁用词 / 敏感词
- `signature_blocks` — 固定的开场 / 结尾 / 签名段

取不到就用中性默认，不要编造人设。

## 核心方法：打分 → 改写 → 复检 循环

### 第 1 步 · 打分

用 `run_skill_script` 执行本技能的 `scripts/ai_trace_score.py`，参数是一个 JSON 字符串：

```
run_skill_script(skill="deai_humanize", script="scripts/ai_trace_score.py",
                 args='{"text": "<待检测文本>", "platform": "gzh"}')
```

- `platform` 取 `gzh`（公众号，容忍连接词略多、句子偏长）或 `xhs`（小红书，鼓励碎句，句长方差权重更低）。
- 脚本返回 JSON：
  - `score`：0–100，**越高越像 AI**。
  - `signals`：每个维度的 `{name, value, weight, note}`（连接词密度、套话命中、具体度缺失、句长齐整度、清单/破折号滥用、段落齐整度）。
  - `spans`：命中的具体"扣分片段"（套话、连接词等），改写时优先干掉这些。
  - `verdict`：`human-like` | `some-ai` | `strong-ai`。

### 第 2 步 · 改写

若 `score` **高于阈值（建议 55）**，针对返回的 `signals` 里高 `value` 的维度、以及 `spans` 列出的片段动手改。改写原则见下。

### 第 3 步 · 复检并循环

改完再跑一次第 1 步。**循环直到 `score ≤ 55 或已改满 3 轮**。每轮都把这一版比上一版降了多少分、还剩哪些 `spans` 说清楚。3 轮仍不达标就停手，交出当前最佳版本并说明还剩哪些结构性问题（例如通篇是清单体、缺少真实经历）。

## 改写原则（AI 痕迹 → 人味）

1. **删升华套话**：干掉"赋能 / 让我们 / 综上所述 / 在……的今天 / 随着……的发展 / 保驾护航 / 数字化转型 / 打造……新生态"这类词。它们是打分里 `cliche_phrases` 的直接来源。
2. **拆连接词骨架**：不要"首先……其次……最后……"一条龙。真人靠语义和语气过渡，不靠标签。
3. **加具体**：塞进真实的**数字、时间、地点、人名、场景、亲身经历**。抽象的"很重要"换成"上周我因为这事多花了两个小时"。这是 `concreteness_deficit` 维度。
4. **句长参差**：长短句交替，敢用三五个字的短句，也敢用一个长句。别让每句都一样长（`sentence_burstiness`）。
5. **第一人称 + 口语化**：多用"我 / 我们 / 咱"，用日常说法，允许"其实、说白了、讲真"这类口头语。
6. **以具体代抽象**：能举例就别下定义，能讲故事就别讲道理。
7. **保留不完美感**：适度的口语停顿、自我修正、小情绪，比一板一眼的"完美"更像人。
8. **少用清单和破折号**：不是所有内容都要列点。能用段落叙述的就别拆成 bullet（`list_dash_overuse`）。

### 按平台调口吻

| 维度 | gzh（公众号） | xhs（小红书） |
|------|--------------|--------------|
| 句子 | 沉稳、可稍长，逻辑连贯 | 短、碎、跳，一句一断 |
| 语气 | 克制、有分寸、像老友聊深度话题 | 活泼、直给、带情绪和 emoji |
| 结构 | 段落叙述为主 | 痛点共鸣 + 干货碎句 |
| 人称 | "我 / 我们"，偶尔"你" | 大量"我""你""姐妹们" |

改写完务必对照 `banned_words` 再扫一遍，命中就替换或标注。

## 参考

- `scripts/ai_trace_score.py` — 打分脚本（纯 Python 标准库，确定性、无第三方依赖）。
- `references/rewrite_playbook.md` — gzh / xhs 两种口吻的 before/after 改写实例。
