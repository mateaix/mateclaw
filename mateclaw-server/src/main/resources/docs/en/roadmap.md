# Roadmap

> "People don't know what they want until you show it to them."
>
> This isn't a feature list. It's a manifesto about **how your AI assistant should exist.**

---

## What we believe

Everyone deserves an AI assistant that actually understands them.

Not a chat toy. Not a tech demo. A **digital counterpart** — one that knows how you work, connects to all your tools, thinks for you, executes for you, and remembers for you.

**MateClaw is that thing.**

---

## What we've shipped

### v1.0 — It thinks and acts ✅ Released

Make an AI assistant a coworker who uses tools, not a chat box.

- ReAct engine: reason, act, observe, reason again
- Plan-and-Execute orchestration: plan first, then execute step by step
- StateGraph architecture: state-graph-based agent orchestration
- DynamicAgent: loaded from database at runtime, adjustable without restart
- 20 built-in tools: search / shell / file I/O / delegate / multimodal generation / cron / SQL
- Tool Guard + File Guard + Audit Log: every tool call has approval, control, and a record
- SKILL.md skill system: install new capabilities into your AI like apps

### v1.1 — It's everywhere ✅ Released

Move AI out of the chat box on a webpage and into every IM your team actually uses.

- **8 channels**: Web / DingTalk / Feishu / WeCom / Telegram / Discord / QQ / WeChat Personal / Slack
- Session source tracking: every message knows which channel it came from
- 4-layer memory: session context + workspace memory + post-chat extraction + 2 AM consolidation
- DREAMS.md consolidation diary: human-readable audit of memory changes
- Workspace isolation: every agent / skill / wiki / conversation / memory belongs to a workspace
- ChatGPT OAuth + Anthropic Claude Code OAuth: log in with your subscription, no API key
- LLM Wiki + RAG: raw files become structured pages with bidirectional links and summaries

### v1.2 — It's your coworker ✅ Released (2026-05-05)

Renamed "agents" to **digital employees** — not vocabulary purism, a worldview shift.

- **Digital employees** with Role / Goal / Backstory — not a cold system prompt
- **5 career templates**: product researcher / customer support / knowledge curator / data analyst / executive assistant — open one, it works
- **Skills are no longer aliases for tools** — each skill is a backbone with its own SKILL.md + LESSONS.md + workspace filesystem
- **ACP bridge**: Claude Code, Codex, Gemini CLI plug in as employees
- **Backstage runtime console**: for the first time you can **see what each employee is doing right now** — who's running, on which step, how many tokens, kill them in one click
- **Onboarding wizard**: first-login four-step flow from zero to first message
- **Dashboard**: daily usage trend + top agents/tools
- **Doctor**: system health checks + one-click fix

Full story: [v1.2.0 release notes](./releases/1.2.0.md).

---

## v1.3 — The workflow year ✅ Shipped (2026-05-13)

> "Focus is about saying no to the hundred other good ideas that there are."

Each digital employee being able to do work is just the beginning. **Real collaboration needs orchestration.**

The v1.3 line is **graduating MateClaw from a chatbot framework to a business-process OS** — a flow is no longer the sum of several employees chatting separately, but a publishable, triggerable, replayable **linear-step DSL**.

Full story: [v1.3.0 release notes](./releases/1.3.0.md).

### Workflow

- [x] **7 step modes**: sequential / fan_out / collect / conditional / await_approval / dispatch_channel / write_memory
- [x] **Pebble expression subset** for conditionals + variable references (no side effects, no code execution)
- [x] **JSON-first authoring**: Monaco + JSON-schema validation + static Pebble checking + template dropdown
- [x] **Natural language → workflow draft** (`POST /workflows/draft/generate`): a user describes the flow, an agent emits `graph_json` + compile diagnostics; never publishes directly — a human still reviews
- [x] **Integer revisions**: publish writes a new immutable row; draft is split from published version
- [x] **Run history**: every step's input / output / duration / token / failure chain is recorded
- [x] **Internal payload storage**: large I/O goes through `payload://` URIs — doesn't blow out the DB
- [x] **Cross-workspace ACL**: publish-time validation rejects agent / channel / employeeId references outside the workspace
- [x] **Persistent `await_approval` pause**: survives service restarts

### Triggers

- [x] **6 pattern types**: cron / webhook / channel_message / agent_lifecycle / content_match / workflow_completion
- [x] **Event governance on by default**: dedup (60s window), per-trigger rate limit, bot-self-msg filter, A→B→A recursion guard
- [x] **CronDelegationPort**: shares ShedLock + Spring TaskScheduler with the legacy cron module without writing into mate_cron_job
- [x] **Cross-instance consistency**: `pattern_version` self-cancellation + periodic syncFromDatabase
- [x] **Structured forms**: each of the 6 pattern types has its own field UI — no need to hand-write patternJson

### Existing experience upgrades

- [x] **Image editing** (issue #75): `image_generate` gains `image` / `images` parameters with 5 reference forms (including `msg:<id>:<idx>` for in-conversation attachments)
- [x] **DashScope OpenAI-compatible variant**: same sk- key, reaches the dot-versioned families (qwen3.5-plus / qwen3.6-plus / qwen3-vl-plus etc.)
- [x] **New Wanxiang / Qwen-Image families**: 14 new image models, 3 new video models (including happyhorse-1.0-t2v)
- [x] **4 document-generation tools**: DocxRenderTool / XlsxRenderTool / PptxRenderTool / PdfRenderTool — Markdown rendered directly into Office files, no subprocess fork, no npm dependency
- [x] **MCP per-agent tool binding**: every employee binds MCP tools individually + status badges (connected / stale / unavailable / orphan) + namespace collisions auto-prefixed + server renames auto-followed
- [x] **Xiaomi MiMo provider**: MiMo V2.5 Pro / V2.5 / V2 Pro / V2 Omni / V2 Flash
- [x] **Multimodal sidecar routing** (issue #87): when a text-only primary model meets an image attachment, the configured vision model captions it first so the primary chat stays cheap; the old "do not call any tools" hard ban is gone, so user-built tools are no longer suppressed; routing badge on the bubble and a hint above the input box make every decision visible

### Still to do in v1.3

- [ ] **Canvas editor (v1)**: today's canvas is read-only chain rendering; the goal is `@vue-flow/core` drag-to-edit
- [ ] **Run replay view**: trace timeline + hover any node to diff input/output
- [ ] **`loop` mode**: iterate N times or per-item over an array
- [ ] **`invoke_skill` mode**: call a skill directly without going through an employee
- [ ] **Inter-trigger priority / dependency**: serial / parallel control when an event hits multiple triggers
- [ ] **Event replay**: a "redispatch" button on `mate_trigger_event` rows

---

## Next: v1.4 — The scenario-application year

> "When the tools are good enough, hide the tools and put the scenarios in front."

v1.0 → v1.3 builds out the infrastructure: employees, memory, knowledge bases, tools, skills, workflows, triggers, multimodal, channels. **The next move isn't another bolt** — it's assembling these parts into **scenarios users can drop in and use**.

The v1.4 keyword is **scenario applications**. Not "more features" — **letting normal users get value without learning 7 step modes and 6 trigger pattern types**.

### Industry scenario templates (workflow + trigger combos)

Each one is **a one-click-importable workflow template + trigger config + recommended employee bindings + recommended KB structure**:

- [ ] **Customer ticket triage**: WeCom / Feishu entry → digital-employee classification → route / escalate / auto-reply → write to customer record
- [ ] **Morning / weekly report automation**: cron trigger → multi-employee parallel data collection → data analyst summarizes → generate PDF/PPTX → multi-channel dispatch
- [ ] **Contract approval flow**: contract upload → legal-employee first review → approval wait → legal-employee revision suggestions → write to archived memory
- [ ] **Market intel monitoring**: webhook trigger (site change) → content_match filtering → business analyst summary → Feishu bot push
- [ ] **New employee onboarding**: webhook (HRIS hire event) → executive assistant pulls doc checklist → training-KB onboarding → multi-day follow-up triggers
- [ ] **Code PR review**: GitHub webhook → code-reviewer employee runs review → comments back to PR → flag critical changes through await_approval

### Scenario marketplace

- [ ] **Scenario package format**: one scenario = `workflow.json` + `triggers.json` + `agents/*.md` + `knowledge/*.md` + `README.md`, shareable / installable
- [ ] **Scenario marketplace UI**: browse / try-run / one-click install / ratings + reviews
- [ ] **Scenario package versioning**: upgrade prompts + diff preview + rollback

### Cross-scenario employee collaboration

- [ ] **Employee directory profile**: each employee auto-gains "good at / weak at" tags (based on history + skills + tool set)
- [ ] **Scenario suggestions**: user describes "I want a flow that does X" → recommend the closest scenario template + existing employees
- [ ] **Cross-scenario memory sharing**: customer ticket triage and contract approval see the same customer record

### Hide the infrastructure further

- [ ] **Natural language → full scenario package**: v1.3 already does "NL → workflow draft"; v1.4 extends it to **the whole scenario** — one sentence yields a draft of workflow + triggers + recommended employees + recommended KB structure
- [ ] **Self-diagnosis wizards**: typical issues like "my workflow stuck waiting on approval" become self-serve diagnostics
- [ ] **Scenario-level dashboards**: not "tokens spent today" but "average customer-ticket handling time today"

### Foundational capabilities advancing in parallel

- [ ] **Scenario-level ACL**: installing a scenario package atomically configures the required channel / agent / KB / tool allowlists
- [ ] **Cross-workspace scenario sharing**: scenario templates reusable across workspaces (clone + override)
- [ ] **Scenario cost estimation**: see expected tokens / API calls / trigger frequency before installing

---

## What we deliberately don't do

> "I'm as proud of the things we haven't done as the things we have done."

| Cut | Why | When it might return |
|-----|-----|---------------------|
| **Full RBAC permission model** | MateClaw is a digital-employee system, not an enterprise management platform. A single team doesn't need 100 permission combinations | When real multi-team SaaS customers need fine-grained permissions |
| **Multi-tenancy** | Same as above. Premature multi-tenancy is architectural cancer | When there's a clear SaaS commercialization path |
| **SSO / LDAP / SAML** | Enterprise integration is a bottomless pit | When paying enterprise customers explicitly ask |
| **30+ node visual workflow editor** | Most users won't reach for it. **v1.3's 7 step modes already cover 90% of real-world scenarios**; the rest is pushed to LLM natural-language generation | When a user case actually needs 30+ nodes (rare) |
| **Native mobile app** | 8 IM channels + desktop + Web already cover it. On your phone, you use MateClaw via DingTalk / Feishu / Telegram | When Web / IM channels can't deliver an irreplaceable mobile-only feature |
| **Replacing ReAct / Plan-Execute** | Workflow and those two engines **collaborate**, not replace — single-agent multi-turn reasoning still lives there | Never replaces |

---

## Version milestones

| Version | One line | User experience goal | Status |
|---------|----------|----------------------|--------|
| **v1.0** | It thinks and acts | An AI assistant that uses tools to solve problems | ✅ Released |
| **v1.1** | It's everywhere | 8 channels + 4-layer memory + workspaces + LLM Wiki | ✅ Released |
| **v1.2** | It's your coworker | Digital employees + 5 career templates + backbone-style skills + ACP bridge + Backstage runtime | ✅ Released |
| **v1.3** | It orchestrates business flows | Workflow + triggers + image editing + document generation + per-agent tool binding | ✅ Released |
| **v1.4** | **It lands real scenarios** | **Industry scenario templates + scenario marketplace + NL → workflow + cross-scenario employee profiling** | 📋 Planned |

---

## One More Thing

We're not building MateClaw to chase ChatGPT, not to be the next Dify, not to add another buzzword to a funding deck.

We're building it because we believe one thing:

**AI shouldn't be a chat box on a webpage. It should be your second brain.**

It lives in your DingTalk, your Feishu, your Telegram. It's read every document you have. It remembers what you said three months ago. It uses your company's internal tools. It consolidates memory while you sleep. **It runs an entire business flow on your behalf.**

Someday, you'll forget it's a program.

**That's the day we win.**

---

*Stay hungry. Stay foolish.*
