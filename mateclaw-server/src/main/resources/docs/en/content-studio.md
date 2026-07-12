# Content Studio

**One sentence in. A publishable post out.**

Content Studio (内容工作室) is MateClaw's first flagship *scene* — not a single tool, but an orchestrated pipeline that turns *"write me something about X"* into a finished, on-platform artifact: a **WeChat Official Account (公众号)** image-text article sitting in your draft box, or a **Xiaohongshu (小红书 / RED)** note packaged as ready-to-post vertical cards.

It's built entirely from MateClaw's own primitives — web search, page fetch, image generation, HTML-to-image rendering, structured memory, cron, and the skill runtime — stitched into a repeatable SOP. Everything below ships in **v1.8.0+**.

---

## The seeded employee

Content Studio ships as a **pre-seeded digital employee** named *Content Studio* / *内容工作室*. It comes bound to the right skills and tools, with a system prompt that fixes the seven-stage workflow and the "confirm before the outward, irreversible step" discipline. You don't assemble it — you talk to it:

> *"Write a 公众号 article about local LLM deployment, referencing these two: `<url1>` `<url2>`"*
>
> *"Give me a 小红书 note about a weekend coffee-shop crawl."*

From the second post on, it already knows your voice — persona, style, topic direction, banned words — because those live in **structured memory**, not in your prompt.

---

## The pipeline

```
① Topic → ② Research → ③ Draft → ④ Illustrate → ⑤ De-AI → ⑥ Layout → ⑦ Deliver
```

| Stage | What happens | Powered by |
|---|---|---|
| **① Topic** | Reads your long-term interests from memory + fresh web search; or comes from a daily "topic radar" cron | `recall_structured`, `web_search`, cron |
| **② Research** | Fetches reference articles, summarizes the angles so you differentiate rather than rehash | `wechat_article_extract`, `browser_use` |
| **③ Draft** | Writes to platform-native structure, honoring your persona & style memory | LLM + memory |
| **④ Illustrate** | Generates a cover and section images | `image_generate` |
| **⑤ De-AI** | Runs a measurable detect → rewrite → re-check loop (see below) | `deai_humanize` skill |
| **⑥ Layout** | Produces the platform artifact (inline-style HTML / vertical cards) | `render_html_image`, HTML templates |
| **⑦ Deliver** | Stops at the outward, irreversible step for your confirmation | `gzh_publish` (draft) / `xhs_package` |

**Templates are conversational.** Because layout is just HTML, the employee can create and refine templates by chatting — render a preview, look at the PNG, refine — and persist reusable custom templates into your own editable skill. Built-in skills stay immutable; your customizations live in a `custom` skill (see [Skills](./skills)).

---

## Two platforms, first-class

### WeChat Official Account (公众号) — `gzh_article`

- **Inline-styled HTML.** The WeChat editor ignores `<style>` blocks, so every style is inline. Starter templates ship (`gzh_layout_minimal`, `gzh_layout_business`), and the AI can author its own.
- **Platform-native structure** — a hook intro, 3–5 titled sections with concrete cases/data, a punchline, and a closing call-to-action.
- **Cover** sized for the header (≈ 2.35:1), plus section images.
- **Compliance self-check** against your banned words and platform sensitive terms.
- **Deliver** to paste manually, or push straight to your **draft box** via `gzh_publish`.

> Read `references/gzh_platform_rules.md` inside the skill for the real platform rules — cover sizing, title/summary limits, editor layout, induced-share/follow red lines, mass-send frequency, and the originality mechanism.

<p align="center">
  <img src="/images/content-studio/ui-gzh-article.png" alt="Content Studio producing a WeChat Official Account article" width="100%">
</p>
<p align="center"><sub><i>Content Studio producing a 公众号 article in the console — the article structure, a generated cover, and a one-tap offer to push it straight into your draft box (`gzh_publish action=draft`). The Run Overview rail (right) lists the generated files.</i></sub></p>

<p align="center">
  <img src="/images/content-studio/out-gzh-cover.png" alt="The 公众号 header cover it produced" width="88%">
</p>
<p align="center"><sub><i>The header cover it produced for that article — a real output artifact.</i></sub></p>

### Xiaohongshu (小红书 / RED) — `xhs_note`

Xiaohongshu is an **image-first** platform — readers swipe images first, text second.

- **At least 3 vertical 3:4 cards** (cover + content + closing), rendered from HTML templates: `xhs_card_cover`, `xhs_card_content`, `xhs_card_end`, plus a quote card `xhs_card_quote`. `xhs_package` **hard-validates** the ≥3-image rule and refuses to package fewer.
- **The four-part title** (number / suspense / emotion / contrast, ≤ 20 chars) + short-sentence body with emoji breaks + 3–8 topic tags (broad + mid + long-tail).
- **Online preview** — the rendered card PNGs are the preview; look, then finalize.

<p align="center">
  <img src="/images/content-studio/ui-xhs-note.png" alt="Content Studio producing a Xiaohongshu note" width="100%">
</p>
<p align="center"><sub><i>Content Studio producing a 小红书 note — the measurable de-AI score (10/100 → human-like), the auto-recorded content-calendar item, and the manual-upload publish steps. The Run Overview rail (right) lists the generated vertical cards.</i></sub></p>

<p align="center">
  <img src="/images/content-studio/out-xhs-01-cover.png" alt="Xiaohongshu cover card" width="30%">
  <img src="/images/content-studio/out-xhs-02-steps.png" alt="Xiaohongshu content card — steps" width="30%">
  <img src="/images/content-studio/out-xhs-03-tips.png" alt="Xiaohongshu content card — tips" width="30%">
</p>
<p align="center"><sub><i>The vertical 3:4 cards it produced — cover (four-part title) + content cards with structured points rendered as image.</i></sub></p>

---

## De-AI-ification, measured

The differentiator of the whole scene is that "de-AI" (`deai_humanize`) isn't a vibe — it's a **measurable loop**.

A heuristic script (`ai_trace_score`, pure Python, no LLM, deterministic and regressible) scores text **0–100** and returns the specific signals and spans:

| Signal | What it catches |
|---|---|
| **Burstiness** | Sentence-length variance too low → uniform = machine |
| **Connector density** | Template cadence ("首先/其次/然后/综上所述/值得注意的是…") |
| **Filler phrases** | Boilerplate ("在…的今天/让我们/随着…的发展/赋能…") |
| **List / dash abuse** | Over-structured, generated-looking layout |
| **Paragraph evenness** | Mechanically equal paragraph lengths |
| **Concreteness gap** | Too few numbers, names, first-person, time/place = vague |

The employee rewrites **against the signals** — colloquial, first-person, concrete detail, varied sentence length, filler cut, tone tuned per platform (公众号 measured, 小红书 lively) — and **re-scores**, looping until it clears the bar or hits **`max_rounds = 3`** (then it keeps the best version and reports the score).

> **De-AI-ification is a heuristic quality boost, not a guarantee of bypassing any AI detector.** The skill and the output both say so.

---

## The publish chain, hardened

Getting a draft out once is easy; running it every day for three months is where the real problems live. The v1.8.0 publish chain closes them:

- **Body images don't break.** WeChat doesn't fetch external images in article bodies, so the chain parses the HTML, **uploads each body image into WeChat**, and rewrites its `src`. A failed upload keeps its original `src` and is reported, rather than blocking the whole article.
- **Secrets encrypted at rest.** `weixinoa.app_secret` and other sensitive settings are **AES-GCM encrypted** (key from `MATECLAW_SETTING_KEY`, machine-derived fallback), ciphertext carries an `enc:v1:` prefix, and legacy plaintext is read transparently and upgraded on next write.
- **One service, one token.** The WeChat service instance is **cached per appId** with a **persisted access token**, so repeated calls and multi-instance deployments don't collide with WeChat's single-token-per-appId limit. Changing the secret invalidates the cache.
- **Retry + plain-language errors.** Transient WeChat error codes retry with backoff; known codes translate into actionable hints — e.g. *"add the server's public IP to the Official Account whitelist."*
- **A draft always has a cover.** If the cover can't be resolved, a **built-in placeholder cover** is rendered so the draft still lands, and the response says a placeholder was used.

**Draft-box-first.** Mass-send and publish are outward, irreversible, and rate-limited, so MateClaw drafts and you press "publish" in the Official Account backstage. The optional `publish` action is gated through the [approval](./security) flow.

---

## Content Calendar — deliver = scan + record

The weak spot in any "the model should also log this" design is that the model forgets. Compliance and bookkeeping are **welded into the delivery tools** themselves:

- **Deliver = scan + record.** `gzh_package` and `xhs_package` run a **server-side compliance scan** (extreme-claim / inducement / guaranteed-return terms) and **auto-record** the item to the calendar on successful delivery — no separate call to skip. High-risk hits surface in the response; a high-risk publish is blocked by default.
- **Topic-fingerprint dedup.** Each item carries a normalized **topic fingerprint**; `content_item check_recent` looks back over `packaged`/`published` items (ignoring `draft`/`failed`, excluding the just-recorded item) so the daily cron doesn't re-pick a subject you already covered.
- **Personal banned words merge in.** The scanner takes your structured-memory `banned_words` as an extra category alongside the built-in lexicon.
- **A read-only Content Calendar page.** Lists every item — platform, title, status, topic, preview link, created/published time — with status-count cards up top.

<p align="center">
  <img src="/images/content-studio/ui-content-calendar.png" alt="The Content Calendar page" width="100%">
</p>
<p align="center"><sub><i>The read-only Content Calendar — every 公众号 / 小红书 delivery auto-recorded with platform, title, status, topic, and time.</i></sub></p>

---

## Tools reference

| Tool | What it does |
|---|---|
| `wechat_article_extract` | Cleans a `mp.weixin.qq.com` article into `{title, author, time, body, images}` (SSRF-limited to that host) |
| `gzh_package` | Packages a 公众号 article (inline HTML + cover); runs compliance scan + records to the calendar |
| `gzh_publish` | Pushes the article to the WeChat **draft box** (`draft`); optional `publish` is approval-gated |
| `xhs_package` | Packages a 小红书 note; hard-validates ≥3 vertical cards; scans + records |
| `xhs_publish` | Best-effort browser-assisted upload (approval-gated) — see limitations below |
| `content_item` | Content calendar: `check_recent` (dedup), `record`, `mark_published` |
| `compliance_scan` | Server-side lexicon scan; optional `extraBannedWords` merges your personal terms |

Plus the skills: **`gzh_article`**, **`xhs_note`**, **`deai_humanize`**. See [Skills](./skills) and [Tools](./tools).

---

## Setup

To publish to WeChat Official Account:

1. Configure the Official Account `app_id` / `app_secret` in **Settings** (stored AES-GCM encrypted).
2. Set **`MATECLAW_SETTING_KEY`** to a stable secret and **back it up** — it decrypts existing ciphertext; losing it means re-entering secrets.
3. Add the server's **public IP** to the Official Account backstage whitelist. The publish chain will tell you if it's missing.

Xiaohongshu needs no API key — Content Studio produces a downloadable card package by default.

---

## Limitations & non-goals

- **No mass-send / one-click push to all followers.** Rate-limited and outward-irreversible; MateClaw drafts, you publish from the backstage.
- **No official Xiaohongshu publish API.** None exists. Content Studio produces a ready-to-post card package (default); browser-assisted upload is optional, approval-gated, and **does not bypass any risk control or human verification**.
- **De-AI-ification is heuristic**, not an adversarial guarantee against detectors.
- **No content laundering.** Reference fetching is for understanding existing angles and differentiating — output must be original and cite its references. Compliance responsibility rests with you.

---

## What to read next

- [Skills](./skills) — the SKILL.md protocol behind `gzh_article` / `xhs_note` / `deai_humanize`
- [Tools](./tools) — the built-in tool registry
- [Channels](./channels) — the WeChat Official Account transport
- [Security & Approval](./security) — the approval gate on outward publish actions
