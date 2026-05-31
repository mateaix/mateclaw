---
title: LLM Wiki — Structured Knowledge Engine, Not Vector Retrieval
description: LLM Wiki digests raw documents into structured knowledge pages with backlinks, summaries, and source tracing. Lazy ingest indexes uploads instantly and compiles pages on demand; eager ingest produces a full Wiki up front. Agents browse the library — they don't grep a vector store.
head:
  - - meta
    - name: keywords
      content: LLM Wiki,knowledge base,knowledge engine,backlinks,structured knowledge,RAG alternative,knowledge graph,lazy ingest,on-demand compile,semantic search
---

# LLM Wiki

A knowledge base isn't a place you search. It's a place you **read**.

Most AI knowledge systems do one thing: chunk your files, embed them, hand back fragments at query time. You get pieces. You can't browse them. You can't tell what the system "knows" without asking. Nothing is ever *finished*.

MateClaw's LLM Wiki does something different. Drop raw material into a knowledge base and the system reads it, digests it, and writes structured Wiki pages — each with a summary, backlinks, and provenance pointers back to the source passage. You can open any page and read it. You can edit it. Agents read summaries automatically and pull full pages on demand.

**It's a library, not a vector store.**

::: tip How it differs from the open-source "LLM Wiki" clones
In April 2026, Andrej Karpathy published a GitHub Gist that gave the idea a name: the material you feed an AI shouldn't be re-shredded into vector fragments at query time — it should be read once and written into a readable wiki. Within a month, at least nine `llm-wiki` single-file implementations had appeared on GitHub — useful, local, personal.

MateClaw's LLM Wiki **is the same idea, raised into a product**:

- Not one person's notebook — a **team-shared knowledge base** with multi-user access, permissions, audit, and archive
- Not a script that runs once — a capability **agents use continuously**, wired into memory, retrieval, and citation
- Not eager-only — **lazy mode compiles pages on demand**, saving 90%+ of LLM calls at scale
- Not raw markdown dumped on disk — a page layer with **provenance, bidirectional links, manual-edit protection, and reversible archive**
- Not an isolated tool — the **knowledge layer of MateClaw's agent operating system**, threaded through memory, agents, and channel delivery

> They built a clone. We built a home.
:::

---

## The three-layer model

A knowledge base is three layers stacked on top of each other:

1. **Raw material** — the files you dropped in. PDF, Word, Excel, PowerPoint, HTML, markdown, plain text (incl. CSV), or a whole local directory scanned in one go. The system keeps them intact; any claim in the Wiki traces back to the passage that produced it.
2. **Wiki pages** — structured articles the AI writes from the raw material. Each page has a title, a summary, a body, bidirectional links to related pages (`[[like this]]`, plus the alias form `[[target|display text]]`), and provenance pointers back into the raw layer.
3. **Agent surface** — when an agent calls a wiki tool, the system auto-injects the summaries of relevant pages into the prompt. Bodies are fetched on demand. Agents don't read raw files. They read the library.

This matters because the agent's context window stops getting wasted on re-reading source material every turn. Tokens go to thinking, not to reading the same paragraph for the fifth time.

---

## Creating a knowledge base

`Wiki → New Knowledge Base`. Name it after what's inside, not who owns it. "Product specs" beats "Team Alpha's KB".

Once it exists, add material:

- **Upload files** — drag PDF, Word, Excel, PowerPoint, HTML, markdown, or plain-text (incl. CSV) files into the upload area. Each file becomes a raw material row.
- **Scan a local directory** — desktop only. Point at a folder and MateClaw walks it recursively, respecting `.gitignore`, importing everything that looks like text.
- **Paste text** — for short excerpts or conversation transcripts.

The system starts indexing as soon as material arrives. You'll see a status indicator on each row: `pending → processing → completed`. If some chunks fail and others succeed, the row lands as `partial` — you keep what worked, instead of throwing the whole document away.

---

## Two ways to ingest: do you need pages right now?

`Wiki → Config → Ingest Mode` flips between:

- **Eager (compile pages on upload)** — runs the full LLM pipeline to produce a finished, browsable Wiki. Pick this when you want pages ready to read the moment ingest finishes. The cost is real: many LLM calls per upload, slow, expensive.
- **Lazy (index now, compile later)** — extracts, normalizes, chunks, and embeds. **Zero page-generation LLM calls.** Search works immediately; pages are produced on demand when an agent or user actually needs one.

Existing KBs default to eager so nothing changes underneath you. New KBs that don't need an instant Wiki should pick lazy — same retrieval quality, a fraction of the cost. The two modes coexist: pages an eager KB has already produced stay put; further uploads honor the current mode.

> Under lazy, "0 pages" is **success**, not failure. This finally fixes the long-standing annoyance where any upload that produced no pages went red.

---

## What ingestion actually does

### Eager: the full pipeline

For each raw material, in order:

1. **Chunk** — split the source into overlapping passages, attaching structural metadata to each chunk: page number (PDF / PPTX), heading breadcrumb (`Intro / Setup / Linux`), section identifier, and a token-count estimate.
2. **Extract concepts** — ask the LLM to identify entities, decisions, facts, and open questions in each chunk.
3. **Cluster and draft** — group related extractions into candidate Wiki pages and generate structured drafts with summaries.
4. **Link** — find bidirectional references between pages (`[[concept]]` and `[[concept|display text]]`) and compute backlinks.
5. **Persist** — write pages to `mate_wiki_page` with citations pointing back to the raw passages they came from.

Ingestion is idempotent. Re-run it on the same material and existing pages get updated rather than duplicated. Hand-edited content is protected — `locked` tells the digester to leave human prose alone, and you have to unlock explicitly to let the AI re-draft.

#### Two-phase digest

Eager ingest runs in two phases for an order-of-magnitude speedup:

- **Phase A (route)** — extracts metadata and concept routing, deciding which pages each chunk feeds into.
- **Phase B (merge)** — generates pages in parallel, 60+ at a time. Each raw material gets its own **progress bar** — no more staring at "processing…" wondering what's happening.

**Resumable**: interrupted mid-import? Hit "Reprocess" and only the unfinished pages re-run; everything already produced stays put. Documents larger than the embedding model's context get mean-pool sub-segmented automatically.

### Lazy: index now, compile later

The pipeline collapses to four steps:

1. **Extract** — pull text out of the binary (PDF / DOCX / …).
2. **Normalize** — jsoup strips HTML noise (nav / footer / ads); markdown heading levels and PDF `--- Page N ---` markers are detected for downstream metadata.
3. **Chunk + metadata** — every chunk carries `page_number`, `header_breadcrumb`, `source_section`, `token_count`.
4. **Embed** — embeddings land asynchronously and the row is marked `completed`.

When are pages produced? They aren't — until somebody asks. The system retrieves the relevant chunks, asks the LLM for one page, and binds the page's citations to the chunks it actually used. Nothing more.

---

## System pages: overview and log

Every KB ships with two **system pages**:

- `slug=overview` — the front door of the knowledge base. Scope, recent updates, coverage stats live here.
- `slug=log` — an append-friendly audit trail of ingest / compile / edit activity.

Both are flagged `page_type=system, locked=1`:

- Delete (single, batch, or the cleanup pass during reprocessing) **refuses to remove them** — you'll get a clean error, not a silent drop.
- List, keyword search, semantic search, and related-pages **filter them out by default** so they don't pollute search results or the agent's context window.
- Reading by slug (`wiki_read_page("overview")`) still works — agents can opt in whenever they want.

> The `locked=1` flag is also yours to set on any hand-curated page. The AI tool surface honors it the same way it honors `lastUpdatedBy="manual"` — they stack.

---

## Transformations: making the KB programmable

::: tip New in 1.3.0
The Transformations engine shipped in v1.3.0. In v1.2.0 and earlier, Wiki was retrieval-only — chunk, embed, recall. v1.3.0 teaches the Wiki to **actively process**: user-defined templates, cross-material aggregation, reverse-citation extraction, JSON output, page-as-input transformations, cancel/re-run. Full release story in the [v1.3.0 release notes](./releases/1.3.0).
:::

By default the Wiki digests raw materials into the pages **it** thinks matter — but "matter" is its judgment, not yours. **Transformations** flip that around: you author a prompt template that says "extract this shape from each source", and the engine runs it, persists the output, and keeps it in sync.

Open `Wiki → [any KB] → Transformations`. Each template is composed of:

- **Name** — short lowercase slug used by agent tools to address the template (e.g. `contract-risk-extract`)
- **Title / description** — human-readable
- **Prompt template** — the instruction text; supports `{input_text}` and `{title}` placeholders
- **Model** — defaults to the KB chat model; you can pin a single template to a specific model
- **Apply by default** — toggle on → every newly-ingested raw fires this template automatically
- **Output target** — `None` (stays in run history) or `Save as wiki page` (synthesis page auto-created)
- **Output format** — `Markdown` or `JSON` (with optional schema validation)

### Seven enterprise templates shipped out of the box

Available on every new KB; cover common enterprise jobs:

| Template | What it produces |
|---|---|
| `contract-risk-extract` | Clause-level risk extraction (high / medium / low) with AI-suggested rewrites |
| `meeting-action-items` | Decisions + action items (owner / due date / acceptance criteria) |
| `customer-profile` | Customer emails / CRM records → structured account profile |
| `competitor-update` | Public signals → competitor digest |
| `resume-structured-extract` | Resume → standardized record (education / experience / skills / highlights) |
| `incident-postmortem` | Incident report → 5-Whys + remediation list + similar-incident keywords |
| `paper-imrad` | Paper / tech report → IMRaD summary + key terminology |

### Four ways to trigger a run

| Trigger | How it fires | Where it shines |
|---|---|---|
| **Manual** | Pick a source in the UI, click Run | Single-shot prompt iteration |
| **Apply default** | Toggle on the template; upload a new material | "Every new contract gets risk-extracted on arrival" |
| **Agent tool** | Agent calls `wiki_apply_transformation(name, rawId)` | Digital employee decides which template to run |
| **Aggregate** | "Aggregate all runs" button / `wiki_aggregate_transformation` | Map-reduce N per-source outputs into one KB-level synthesis page |

### Input: raw materials or existing pages

A template doesn't have to read a raw material — it can also run against an existing wiki page (agent tool: `wiki_apply_transformation_to_page(name, slug)`). This lets you chain templates: A turns a source into a synthesis page, then B reads that page and produces a different view.

### Output target: run history, or a real wiki page

- **None** — the result lives only in the run history under the template card. Good for one-off output.
- **Save as wiki page** — every successful run **upserts** to a fixed slug `<template-name>-<source-title>`. Re-running updates the same page rather than spawning duplicates. And:
  - Page-level embedding is fired automatically so the synthesis page joins semantic search
  - The output is parsed for citation hints like "第 N 题 / page X" and chunk-level citations are written linking back to the source chunks
  - It joins the relation graph, the hot cache, and becomes directly readable by agents

### JSON output + Schema validation

When the format is JSON:

1. A strict system prompt is injected ("return one JSON document, no prose, no fences")
2. On parse failure the executor retries once with a specific error reminder
3. A JSON Schema can be stored on the template; after parsing, the executor checks required fields and the top-level type
4. Still invalid → the run is failed with the specific reason in the error column

Valid JSON is wrapped in a fenced ```json block so the existing markdown rendering and save-as-page contract continues to hold while downstream tools can still grep / parse the raw JSON.

### Cross-material aggregation

Have 10 contracts each processed by `contract-risk-extract`? Click "Aggregate all runs" on the card:

- The system loads every completed run of this template within this KB
- Deduplicates by source (only the most recent run per raw contributes)
- Sends them to an LLM with a merge + dedupe system prompt — same clause types are merged, source attributions are preserved, disagreements are surfaced instead of smoothed over
- Upserts the result to a deterministic slug `<template-name>-aggregate`
- Triggers page embedding so the aggregate joins semantic search

This is what turns per-source extracts into a KB-level synthesis — no more diff-by-eye across N contracts.

### Run history + observability

Every run records:

- Status: `pending / running / completed / failed / cancelled`
- Duration, model, trigger (manual / apply_default / agent_tool / aggregate)
- Input / output / total tokens reported by the provider (`8.2k↑ / 1.1k↓`), accumulated across retries
- Linked output page (when output_target=page)
- Full output / error message

The UI lets you:

- **Cancel** — flag a running run as cancelled. The LLM call still completes server-side because most providers don't support cancellation, but the executor drops the eventual output instead of overwriting that state.
- **Re-run** — one-click rerun against the same input. Works on completed, failed, and cancelled runs alike.
- **Compare** — tick two completed runs → the "Compare selected" button opens a side-by-side modal (older on the left, newer on the right) so prompt iteration finally has a real diff workflow.

### Typical recipes

| Scenario | Recipe |
|---|---|
| Legal automation | `contract-risk-extract` + apply default + save as page → every new contract auto-produces a risk report page |
| Sales intelligence | `customer-profile` + apply default → every account material becomes a profile page |
| Engineering memory | `meeting-action-items` + `incident-postmortem` together → decision history and incident lessons accumulate as KB pages |
| Research synthesis | Run `paper-imrad` across a batch, then aggregate → thematic survey page |
| Programmatic downstream | JSON format + schema → the wiki becomes a structured data source for dashboards / pipelines |

### REST endpoints (base path `/api/v1/wiki/transformations`)

| Method | Path | Purpose |
|---|---|---|
| `GET` / `POST` / `PUT` / `DELETE` | `/`, `/{id}` | Template CRUD |
| `POST` | `/{id}/apply?sync=true` | Run once (body carries either `rawId` or `pageId`) |
| `POST` | `/{id}/aggregate?kbId=X` | Cross-material aggregation |
| `GET` | `/runs?rawId=` or `?kbId=` or `?transformationId=` | Query run history |
| `POST` | `/runs/{runId}/save-as-page` | Promote a run manually into a wiki page |
| `POST` | `/runs/{runId}/cancel` | Mark a run cancelled |

---

## How agents use the Wiki

Bind an agent to a knowledge base from `Agents → [your agent] → Knowledge`. From that moment:

- The agent's system prompt automatically includes a compressed summary of the KB's top-level pages.
- The agent's toolbox grows these wiki tools:

| Tool | What it does |
|---|---|
| `wiki_search_pages` | Page-level hybrid retrieval (keyword + semantic). |
| `wiki_semantic_search` | Chunk-level semantic search. Hits include `pageNumber` and `section` when known, so the agent can cite "page 12, Setup / Linux" rather than a naked snippet. |
| `wiki_read_page` | Read a single page; trim by section heading or character cap. |
| `wiki_read_many` | **New.** Fetch multiple pages in one call (up to 10 slugs, with a per-page char cap). Replaces multi-turn `wiki_read_page` chains. |
| `wiki_compile_page` | **New.** On-demand page generation for a topic. Citations bind to the evidence chunks the prompt actually used — not to every chunk of the source raw. |
| `wiki_trace_source` | Trace a Wiki page back to its source raw materials. |
| `wiki_related_pages` | Related-page discovery across four signals (shared chunks, shared raws, direct links, semantic neighbors). |
| `wiki_explain_relation` | Score breakdown for the relationship between two pages. |
| `wiki_create_page` / `wiki_delete_page` | Direct page management; deletion respects `locked` / `system`. |
| `wiki_archive_page` / `wiki_unarchive_page` | Soft-archive: hide a page from default list/search/related results without destroying it. Citations and source lineage survive; recoverable. System pages can't be archived. |
| `wiki_list_transformations` | List the transformation templates available to this KB (name, intent, whether apply-default is on). |
| `wiki_apply_transformation` | Run a template against one **raw material**; returns the output, run id, and saved-page info. |
| `wiki_apply_transformation_to_page` | Run a template against an **existing wiki page** (takes a slug, not a numeric id). |
| `wiki_aggregate_transformation` | Map-reduce every completed run of a template across the KB into one synthesis wiki page. |

The `kbId` parameter resolves automatically from the bound agent — agents never have to guess it.

A typical agent turn:

> **User:** "What did we decide about the retry policy last quarter?"
>
> **Agent:** *(reads injected summary, sees a "Retry Policy" page exists, opens that page directly, returns the decision with a source link.)*

That isn't a vector query. It's literally opening the page — because the page exists.

### Hot cache: a recent-activity snapshot in every system prompt

The bound KB doesn't just contribute summaries — it also contributes a small, freshly-rebuilt **hot cache** that gets stitched into the system prompt. Think of it as the page the agent reads first, every turn:

- **Last updated** — the most recent ingest / page edit
- **Key recent facts** — bullets the rebuilder considers high-signal
- **Recent changes** — page creations and compilations since the last rebuild
- **Active threads** — open questions and unresolved decisions

The rebuilder fires asynchronously when a conversation ends (`ConversationCompletedEvent`), debounced inside a configurable window (default ~30 s) so a flurry of short turns doesn't churn LLM calls. An admin can also trigger a rebuild manually — that path bypasses the debounce.

The injection is gated by the `wiki.hot_cache.enabled` feature flag (off → empty injection) and is capped at the **two highest-priority KBs** per agent so the system prompt stays small.

#### Manage from the KB detail drawer

`Wiki → [your KB] → Hot cache` shows:

- **Regenerate** button — async manual rebuild; the panel polls a few seconds later and refreshes
- **Reset** button — soft-delete the row; the next `ConversationCompletedEvent` rebuilds it
- Meta grid: last updated, update reason (`AUTO` / `MANUAL` / `EVENT`), rebuild count, last duration in ms
- Error banner if the last rebuild failed
- The rendered Markdown content in a preview pane

#### Operator endpoints

Base path `/api/v1/wiki/hot-cache`:

| Method | Path | What it does |
|---|---|---|
| `GET` | `/{kbId}` | Current snapshot + meta |
| `POST` | `/{kbId}/regenerate` | Manual rebuild (async, ignores debounce) |
| `DELETE` | `/{kbId}` | Soft-delete; rebuilds on next event |

The hot cache lives in `mate_wiki_hot_cache` — see the **Data model** section below for the exact columns.

### A typical lazy turn

```
User uploads product-manual.pdf            (lazy mode: zero page-generation LLM calls)
       ↓
Agent: wiki_semantic_search("error code 500 retry")
       → hit on chunk #1234, page=12, section "Error Handling / Retries"
       ↓
Agent: wiki_compile_page(topic="500 retry policy", maxEvidenceChunks=5)
       → produces slug=500-retry-policy, citations bound to those 5 chunks only
       ↓
Agent: wiki_read_page("500-retry-policy")
       → returns the structured page with its source-chunk list
```

The whole path spends one LLM call, scoped to the five chunks that actually matter. The other 200 chunks in the manual cost nothing extra.

---

## Reading and editing pages

Every generated page is a first-class document you can open in the Wiki view:

- Markdown rendered with syntax highlighting.
- Backlinks in the sidebar — see what else references this page.
- A "source" button on every claim that jumps to the raw passage it came from.
- An edit mode where you can rewrite the page directly.
- The delete button is disabled on system / locked pages.

Edit when the AI got it wrong. Your edits survive the next ingest — `locked` tells the digester to leave human prose alone. Unlock explicitly when you want the AI to re-draft from the source.

---

## Wikilinks and broken-link care

Cross-page references via `[[slug]]` are the connective tissue of a
long-lived knowledge asset. RFC 55 turns this layer from "writing
`[[Title]]` looked fine until you clicked and got a 404" into **lint on
write, cascade on delete, broken links visible everywhere**.

### Wikilink syntax

Exactly one contract is honoured:

- `[[slug]]` — visible label defaults to the target page's title
- `[[slug|display text]]` — explicit label, the slug is still the
  navigation target

The slug must reference an existing page. The LLM page-generation
prompts give the model a slug-first index (`- [[slug]] — Title — Summary`),
forbid inventing slugs that aren't in the index, and explicitly warn
that older `[[Page Title]]` form will be flagged as a dead link by the
lint.

Case-insensitive: `[[STATEGRAPH]]` and `[[stategraph]]` both resolve
via lowercased exact match against `page.slug`.

### In-transaction lint: `outgoing_links` + `broken_links`

Every page save (manual edit, AI generation, merge, cascade rewrite)
runs in one transaction:

1. Extract every `[[...]]` from the body (skipping fenced and inline
   code blocks)
2. Write `mate_wiki_page.outgoing_links` (deduped, lowercased string
   array)
3. Diff against the KB's active slug set (archived pages excluded)
   to produce `broken_links`
4. Stamp `broken_links_scanned_at`

You see which `[[...]]` are dead the moment the page saves — no
batch scan required. Code blocks and inline `` `[[...]]` `` snippets
are preserved verbatim and never enter `outgoing_links` (so a page
that teaches wikilink syntax doesn't accidentally lint itself).

### KB-wide broken-link scan

Each KB shows a banner at the top of the workspace. Click "Scan dead
links" to start a job:

| Method | Path | What it does |
|---|---|---|
| `POST /api/v1/wiki/knowledge-bases/{kbId}/lint/broken-links` | Starts a job (async, job-based). Returns `{jobId, status, startedAt}`. Idempotent — repeat POSTs while a job is in flight return the same id |
| `GET .../lint/broken-links` | Returns the latest completed scan as a per-page aggregate |
| `GET .../lint/broken-links/jobs/{jobId}` | Status check for a specific job |

The aggregate carries `pageId / slug / title / brokenRefs` for each
affected page. The banner distinguishes "scanned X pages, no broken
links" from "found N broken links in M pages". Clicking "view" opens
a panel listing each broken ref with a jump-to-source-page action.

Performance: 100-page KB scans in well under a second; POST submit
latency under 200ms.

### Cascade delete and rename

**Delete a page**: every other page that linked to it gets its
`[[deleted-slug]]` rewritten to plain text (using the snapshot title
as the visible word). Aliased `[[deleted-slug|some alias]]` collapses
to just the alias. Referrers' `outgoing_links` and `broken_links` are
recomputed in the same transaction.

**Rename a page**: `POST /api/v1/wiki/knowledge-bases/{kbId}/pages/{slug}/rename`
with `{"newSlug":"new"}`. In one transaction:

- The page's own slug is updated
- Every referrer's `[[oldSlug]]` becomes `[[newSlug]]`, and
  `[[oldSlug|alias]]` becomes `[[newSlug|alias]]` (alias preserved
  byte-for-byte)
- Referrers' `outgoing_links` is updated

Rejected: empty slug, slug equal to the current slug, slug already
owned by another page in the same KB, target page is protected
(system / locked). Case-only renames (`foo → FOO`) are allowed and
behave the same on H2 and MySQL.

Each delete / rename writes an audit row to `mate_audit_event` with
`action=wiki.page.delete` or `wiki.page.rename`. `detailJson` carries
an `affectedPageIds` list so the cascade impact is queryable after
the fact.

Emergency kill-switch: set `mate.wiki.cascade-delete-enabled=false`
to revert to the legacy row-only delete (the rewrite is bypassed,
referrer wikilinks dangle). Default-on is the intended steady state.

### Click-through from chat

When the chat renders an agent reply, `[[slug]]` and `[[slug|alias]]`
tokens in the content become `<a class="wiki-link" data-wiki-title=...>`
anchors. Clicking one:

1. The app-level global click delegator catches the click
2. Calls `GET /api/v1/wiki/pages/lookup?title=X&slug=X` — searches
   every KB visible to the user (slug match first, title fallback)
3. 1 hit → `router.push` into the wiki view, auto-selects the KB,
   auto-opens the page
4. 0 hits → toast "未找到匹配的 wiki 页面：X"
5. >1 hits → picker offering to open the first match

No more navigating to the wiki view, finding the KB, finding the
page — clicking a `[[link]]` in chat gets you there directly. The
lookup is strict case-insensitive exact (no canonical fuzzing), so
if the LLM wrote a slug that doesn't exist you see the toast rather
than getting silently redirected to a similarly-named page.

### Phase roadmap (all phases landed)

| Phase | Key changes |
|---|---|
| 1 | Frontend slug-first DOM postprocess; dangerous-char guard; full `pages/refs` index decoupled from raw-material filter |
| 2 | V129 migration adds `broken_links` and `broken_links_scanned_at`; save-path writes them in the same transaction; KB-wide async lint job + banner |
| 3 | All 9 wiki prompt templates unified on `[[slug]]` contract; existing-pages index reformatted slug-first; batch-create splits existing pages from same-batch planned pages |
| 4 | Cascade delete and rename rewrite referrers in-transaction; audit log; feature flag |
| 5 | Analyze stage emits a `related_pages` slug whitelist (validated server-side); enrich applier skips code blocks and gates on the whitelist |

Full design + live verification: `rfcs/202605/55-wiki-link-resolution-overhaul.md`
and `mateclaw-server/src/test/resources/e2e/wiki-link-overhaul-verification.md`
(6 e2e passes, 50+ live assertions, 3 bugs caught and fixed during the
test).

---

## Search, source tracing, and semantic retrieval

- **Semantic search** — ask "what did we decide about auth?" and get the decision, not pages containing "auth". Chunk-level embeddings with cosine retrieval — it understands what you mean. Hits now include `pageNumber` and `section`, so the agent can quote "page 12, Setup / Linux" instead of a free-floating snippet.
- **Hybrid retrieval** — full-text and semantic matching run together, RRF-fused, with a 1-hop relation boost on the top seeds.
- **Full-text search** — covers titles, summaries, bodies, and concept extractions. Works across every KB you have access to.
- **Source tracing** — any claim, any page, has a source link. Click it, you land on the raw passage. Agents have the same capability.
- **Backlinks** — every page shows what other pages link to it. The `[[concept|display text]]` alias form is now parsed correctly: only `concept` becomes a slug, `display text` is purely visual.
- **Related pages** — blends shared-chunk, shared-raw, direct-link, and semantic-neighbor signals. The 1-hop expansion **never seeds from system pages**, so overview/log don't drag every page in the KB into your "related" list.
- **Edit protection** — locked or hand-edited pages aren't overwritten on re-ingest; unlock explicitly to re-draft.

---

## Vision pipeline: images become text

A wiki that can't read images is half-blind. PDFs are the worst offenders — half the actual information often lives inside the figures.

When the `wiki.ocr.enabled` feature flag is on, MateClaw runs every uploaded image — and every image *embedded in a PDF page* — through a vision pipeline that extracts a **caption** plus any **visible text** in the image. Those become first-class chunks alongside the surrounding prose, so retrieval finds them, agents quote them, and search results show inline thumbnails with a click-to-zoom lightbox.

### How it works

1. **Hash** the image bytes with SHA-256 — the cache is content-addressed, so re-uploading the same diagram in a different KB costs nothing.
2. **Probe `mate_wiki_image_caption_cache`** — on hit, reuse the caption immediately and increment `hit_count`.
3. On miss, walk the configured **vision providers in order** until one returns a non-null caption.
4. **Persist** the caption + visible text + provider id + model + duration into the cache (race-tolerant insert — concurrent uploads of the same bytes are fine).
5. The `VisionResult` flows back into the chunker as additional content for the page that contained the image.

### Supported providers

| Provider id | Model | Notes |
|---|---|---|
| `dashscope-vision` | `qwen-vl-max` | DashScope OpenAI-compatible endpoint; reuses the DashScope provider configured in the UI |
| `zhipu-vision` | `glm-5v-turbo` | Zhipu BigModel; OpenAI-compatible |
| `volcano-doubao-vision` | configurable | ByteDance Volcano Doubao vision |

Providers are auto-detected by order. Configure their keys / base URLs in `Settings → Models` like any other provider — the vision pipeline picks up the credentials from there.

### Toggling the pipeline

`Settings → Feature Flags → wiki.ocr.enabled`. Off by default in lightweight installs; on once you've configured at least one vision provider.

When the flag is **off**, the pipeline short-circuits — uploads still succeed, image chunks just don't carry captions. The `extracted_text` cache for those images is **deferred** rather than poisoned, so flipping the flag back on captures captions on the next upload without forcing a re-ingest.

### What you see in the UI

- Search hits that contain image evidence render the thumbnail inline; click to open a lightbox at full resolution.
- The raw-material detail drawer shows captions next to each extracted image so you can sanity-check what the model actually saw.

---

## Health-aware LLM fallback

Wiki ingest is LLM-heavy, and a single wedged provider used to mean a whole batch died. Now every wiki step (`route`, `create_page`, `merge_page`, `enrich`, …) hits the routing chain through a **health-aware fallback**: if the primary model errors or times out, the next model on the KB's `fallback` list is tried once. Health (success / error / latency) is tracked per provider, so a flapping provider gets demoted automatically until it recovers.

Configure the fallback list under `Wiki → Config → Model Strategy` next to the per-step picker.

---

## Per-step model selection actually works

In `Wiki → Config → Model Strategy` you can pick a different model per step:

```text
heavy_ingest.route        → small, cheap model for routing
heavy_ingest.create_page  → strong model for full-page authoring
heavy_ingest.merge_page   → strong model for content merging
light_enrich.enrich       → small, cheap model for wikilink annotation
```

Resolution order:

```text
stepModels[step]   →   wikiDefaultModelId   →   system default model
```

This UI used to be cosmetic — the Java side dropped the config on the floor and ran every step on the system default. Every LLM call inside the eager pipeline now consults the routing chain: route, create, merge, retry-create, repair, document analysis, light enrich.

---

## Data model (if you're curious)

Nine tables:

| Table | Purpose |
|---|---|
| `mate_wiki_knowledge_base` | One row per KB. Owner, name, description, config JSON (`ingestMode`, `wikiDefaultModelId`, `stepModels`, fallback chain). |
| `mate_wiki_raw_material` | One row per upload. Status, byte hash, source path, last successfully-processed hash. |
| `mate_wiki_page` | One row per generated page. Title, summary, body, `source_raw_ids` (provenance), `page_type`, `locked`, version, plus `embedding` / `embedding_model` / `embedding_text_version` so transformation synthesis pages enter semantic search directly. |
| `mate_wiki_chunk` | One row per chunk. content + hash + offsets + embedding, plus `page_number`, `header_breadcrumb`, `source_section`, `token_count`. |
| `mate_wiki_relation` | Cached page-to-page edges (shared chunks, shared raws, direct links, semantic neighbors) used to power the 1-hop retrieval boost and the related-pages tool. |
| `mate_wiki_hot_cache` | One row per KB. Rendered Markdown snapshot + `last_updated`, `update_reason`, `rebuild_count`, `last_rebuild_duration_ms`, `last_rebuild_error`. |
| `mate_wiki_image_caption_cache` | SHA-256 keyed cache of vision-extracted captions. `caption`, `visible_text`, `mime_type`, `capture_model`, `provider_id`, `duration_ms`, `hit_count`. |
| `mate_wiki_transformation` | One row per transformation template. `name`, `title`, `description`, `prompt_template`, `model_id`, `apply_default`, `output_target`, `output_format`, `output_schema`. `kb_id=NULL` = workspace-wide. |
| `mate_wiki_transformation_run` | One row per template execution. `status`, `output`, `error`, `duration_ms`, `model_id`, `triggered_by`, `input_tokens`, `output_tokens`, `total_tokens`, `output_page_id`. |

`mate_wiki_page` also carries two protection flags:

- `locked` (V40) — `1` blocks AI tools, batch ops, and re-ingest cleanup from modifying or deleting the page. The built-in `overview`/`log` system pages ship with `locked=1`; users can set it on any hand-curated page too.
- `archived` (V41) — `1` soft-archives the page: gone from default list/search/related results, but the page itself, its citations, and its backlinks are all preserved. Recoverable.

### Operator endpoints

For when you don't want to wait for the cron / event hooks to catch up:

| Endpoint | What it does |
|---|---|
| `POST /api/v1/wiki/admin/kb/{kbId}/rebuild-overview` | Force-rewrite the overview marker region from current stats. |
| `POST /api/v1/wiki/admin/backfill-tokens` | Run one batch of the token-count backfill now; returns `pendingBefore` / `pendingAfter` / `filledThisBatch`. |

The `mate.wiki` block in `application.yml` controls global knobs (chunk size, parallelism, auto-process-on-upload). Per-KB knobs (ingest mode, step models, fallback chain) live inside the KB's `configContent` JSON and are edited through the config UI.

> The `token_count` column is nullable for legacy chunks; a low-frequency cron `WikiChunkTokenBackfillJob` fills them with `ceil(charCount / 4)` over time, never blocking ingest.

---

## When to use it

Reach for a Wiki KB when you have:

- more than a handful of documents on the same topic
- material you want humans to read and edit, not just retrieve
- information that should outlive any single agent or conversation
- sources where "where did this come from" actually matters

If you just want to drop one PDF into one conversation, attach it in chat. Wikis are for material that earns its own shelf.

A short field guide to picking a mode:

- You need a finished, browsable Wiki **right now** (sharing, presenting, onboarding): eager.
- You're seeding a corpus and want pages produced only when an agent or user reaches for them: lazy + on-demand compile.
- Big corpus, expensive model, unclear whether every document needs a full Wiki page: lazy is the cheaper default.

---

## Next

- [Agents](./agents) — binding an agent to a KB
- [Memory](./memory) — how Wiki and memory differ (hint: Wiki is deliberate, memory is passive)
- [API Reference](./api) — wiki REST endpoints
