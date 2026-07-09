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
- 4-layer memory: session context + workspace memory + post-chat extraction + 2 AM consolidation
- DREAMS.md consolidation diary: human-readable audit of memory changes
- Workspace isolation: every agent / skill / wiki / conversation / memory belongs to a workspace
- ChatGPT OAuth + Anthropic Claude Code OAuth: log in with your subscription, no API key
- LLM Wiki + RAG: raw files become structured pages with bidirectional links and summaries

### v1.2 — It's your coworker ✅ Released (2026-05-05)

Renamed "agents" to **digital employees** — not vocabulary purism, a worldview shift.

- **Digital employees** with Role / Goal / Backstory — not a cold system prompt
- **5 career templates**: product researcher / customer support / knowledge curator / data analyst / executive assistant
- **Skills are backbones**: each skill has its own SKILL.md + LESSONS.md + workspace filesystem
- **ACP bridge**: Claude Code, Codex, Gemini CLI plug in as employees
- **Backstage runtime console**: for the first time you can **see what each employee is doing right now**
- Onboarding wizard + Dashboard + Doctor

Full story: [v1.2.0 release notes](./releases/1.2.0.md).

### v1.3 — It orchestrates business flows ✅ Released (2026-05-13)

Graduating from a chatbot framework to a business-process OS — a flow is no longer several employees chatting separately, but a publishable, triggerable, replayable **linear-step DSL**.

- **Workflow**: 7 step modes (sequential / fan_out / collect / conditional / await_approval / dispatch_channel / write_memory) + Pebble expressions + JSON-first authoring + integer revisions + run history
- **Natural language → workflow draft**: describe the flow, an agent emits graph_json, a human reviews before publish
- **Triggers**: 6 pattern types (cron / webhook / channel_message / agent_lifecycle / content_match / workflow_completion), event governance on by default (dedup / rate limit / recursion guard)
- **Persistent `await_approval` pause**: survives service restarts
- Image editing, 4 document-generation tools (Docx/Xlsx/Pptx/Pdf), MCP per-agent tool binding, multimodal sidecar routing

Full story: [v1.3.0 release notes](./releases/1.3.0.md).

### v1.4 — It's more autonomous and leads teams ✅ Released (2026-05-23)

Flows were scripted by you, but the employee itself still "answered one round and stopped." This release puts the focus back on the employee.

- **Persistent goals**: say it once — the employee locks the goal, self-checks every round, and keeps itself going until done or out of budget
- **Sub-employee delegation tree**: recursive delegation up to 3 levels deep, with sync / parallel fan-out / async delegation tools; the Employee Builder spins up a whole team from one sentence
- **Progressive tool/skill disclosure**: core tier always visible, extension tier activated on demand via `enable_tool` / `load_skill` — pile on tools without blowing the context
- **Workspace RBAC**: Owner / Admin / Member / Viewer roles + capability gates — MateClaw is usable by a team for the first time
- **Feishu as a first-class citizen**: interactive cards, approval cards, streaming cards, voice transcription, file/audio/video I/O, channel-native tools
- Native Gemini, xAI / Grok, per-conversation model pinning, structured context compaction, rate-limit failover

Full story: [v1.4.0 release notes](./releases/1.4.0.md).

### v1.5 — It's verifiable, knowledge self-maintains, memory knows its owner ✅ Released (2026-06-04)

Make autonomy **verifiable**, knowledge **self-maintaining**, and memory **owner-aware**.

- **Goal checklists**: goals decompose into independently verifiable criteria; the evaluator checks them off one by one — **all checked or it's not done**. No "95% is close enough"
- **Self-maintaining Wiki**: `[[wikilink]]` page interlinking + rename/delete cascade rewrites + broken-link lint; fact vs. experience knowledge layers with staleness propagation; pageType profiles + per-agent permissions; event-triggered processing pipelines; local directories mounted as knowledge sources with incremental sync
- **Per-owner memory isolation**: every memory carries an owner_key and visibility scope (personal / team / global) — one employee serves a whole group without cross-talk; APIs pass through `endUserId`
- Primary KB per employee, preferred-provider routing that actually applies, generated files persisted to disk

Full story: [v1.5.0 release notes](./releases/1.5.0.md).

### v1.6 — It meets you where you are ✅ Released (2026-06-22)

Where it can run, what it can do with hands and eyes, and how directly you shape who it is.

- **KingbaseES + PostgreSQL as first-class citizens**: the PostgreSQL family shares one migration tree; regulated / domestic-procurement environments covered; MySQL and desktop H2 untouched
- **Images persist across turns**: the screenshot you sent three messages ago is still visible on follow-up; `image_analyze` re-reads on demand
- **`execute_code`**: the employee writes code and runs it — arithmetic, file conversion, verification become real actions instead of guesses
- **Shape the employee's identity**: a real editor for AGENTS.md and other context files (modal + section reorder); an About You identity block; the employee knows which model it runs on
- **Scoped KB access** + Wiki Sources tab (multi-path + glob + per-KB auto-sync)
- Global outbound proxy, deterministic Markdown normalization of final answers

Full story: [v1.6.0 release notes](./releases/1.6.0.md).

### v1.7 — It's ready for production ✅ Released (2026-07-04)

A **productionization pass**: once you put it into real collaboration, the places that go invisible, un-closable, out of reach, oversized for the window, and walled off — all fixed.

- **All three approval paths closed end-to-end**: workflow `await_approval` actually pushes to channels and resolves → resumes execution; the WebChat (API-key) channel can approve/deny and replay; Feishu/WeCom card buttons directly resolve workflow approvals
- **Long tasks are visible**: an always-on Run Overview rail (step progress + live delegated sub-agent tree) + a per-turn token breakdown (cache hit/miss/write + reasoning split) + sub-agent cost rolled up + one-click generated-file download
- **Fits the real model window**: local-model context-window probing, a unified token budget for prefix injection, small-context degradation, and tool-schema budget gating — no more "guess 32K" pre-flight rejections or silent truncation
- **Opens up**: a knowledge-base + Deep Research open API (API-key + rate limit + SSE), a pluggable search Provider SPI, and MCP identity forwarding (carry the authenticated user's identity into a STDIO MCP)
- **Reaches further**: desktop local-embedded / remote-centralized dual mode + multi-server switching + the `mateclaw-desktop` source opened; a LAN deployment mode opens controlled intranet access
- **One-click operational data export**: 9-sheet Excel from the Dashboard + a CLI for offline export
- Wiki processing-failure visibility, per-employee model chains, OpenAPI / Swagger directly debuggable, chat back-to-bottom floating button

Full story: [v1.7.0 release notes](./releases/1.7.0.md).

---

## Next: v1.8 — Agent Team & Agent Loop

> "Great things in business are never done by one person. They're done by a team of people."

Look back along the line: v1.2 gave employees an identity, v1.3 made flows orchestratable, v1.4 made employees follow goals and spin up delegation trees, v1.5 made autonomy verifiable, v1.7 made long tasks visible.

But today's MateClaw still has two "stops":

**Collaboration is one-shot.** The v1.4 delegation tree is powerful, but it's **task-scoped** — parent delegates child, the task ends, the tree dissolves. The next task starts from zero. Teams have no name, no roster, no accumulated experience — like hiring a fresh batch of temps for every project.

**Employees are reactive.** Goal auto-followup only lives **within a single run**; cron and triggers can wake an employee up, but every wake-up is an isolated response. No employee is truly **on duty** — continuously watching its area of responsibility and deciding for itself when to act.

v1.8 turns both stops into continuity.

### Agent Team — from "temp hires" to "standing roster"

A team is no longer a tree that sprouts at delegation time and vanishes when the task ends. It becomes a **persistent organizational unit**:

- [ ] **Team entity**: a team = name + leader + member roster + charter — persisted, reusable, exportable and shareable
- [ ] **Team charter (TEAM.md)**: division of labor, collaboration rules, escalation paths — shapes the team the way AGENTS.md shapes an individual
- [ ] **Leader dispatch**: tasks come in, the leader decomposes, assigns to the best-fit member, and reviews the result; what it can't handle gets escalated instead of improvised
- [ ] **Peer review**: critical deliverables can require another member's sign-off before shipping
- [ ] **Shared team memory**: built on v1.5's TEAM scope — members share team memory and a team file space while personal memories stay isolated
- [ ] **Team-level goals**: one goal decomposes into member sub-goals; the checklist aggregates across members — hover the leader's avatar to see what the whole team still owes
- [ ] **Team-to-channel binding**: bind a Feishu / DingTalk group to a team; @ the team in the group, the leader decides who takes it
- [ ] **Team retrospectives**: task wrap-up auto-generates a retrospective into the team's LESSONS.md — this team does better next time
- [ ] **Employee Builder upgrade**: v1.4 builds a batch of employees from one sentence; v1.8 makes it emit a **standing team with a charter**
- [ ] **Run Overview becomes a team view**: each member on-duty / busy / idle at a glance; click through to see what it's working on

### Agent Loop — from "answers then stops" to "on duty"

A new state for employees: **on duty**. Not waiting for you to speak, but cycling autonomously on a heartbeat — **wake → check inbox and goals → decide whether to act → act → journal → sleep**:

- [ ] **Resident loop runtime**: an employee can be set "on duty," waking on a configurable heartbeat (minutes to days) to check its area of responsibility
- [ ] **Task inbox**: channel messages, trigger events, delegations from other employees, to-dos you toss over — one queue, consumed by priority on each wake-up
- [ ] **Cross-session goal continuation**: v1.4/v1.5 auto-followup lives inside a single run; the loop carries goals across sessions and across days until every criterion is checked
- [ ] **Budgets and circuit breakers**: per-loop token / cost / turn budgets; consecutive failures trip the breaker into sleep pending your decision; ToolGuard approval gates still intercept sensitive actions — autonomy is not loss of control
- [ ] **Loop journal**: what it did each wake-up, why it chose not to act, what it spent — human-readable and replayable, what DREAMS.md is to memory
- [ ] **Pause / resume / clock-out**: controllable from the UI and from channel commands; the Run Overview sidebar shows every on-duty employee's loop state
- [ ] **Quiet hours and interruption policy**: silent accumulation at night, proactive reporting for what matters — integrated with the nudge system, it knows what's worth waking you for

### Where they converge: a department that runs itself

A leader on a loop, members summoned on demand — that's a **self-running digital department**:

- Morning-report department: the leader wakes at 7:00, dispatches data collection, analysis, and writing to members, peer-reviews, posts to the group — you wake up to results
- Support department: a ticket lands in the inbox, the leader classifies, assigns the right member, escalates to you what it can't handle
- Intelligence department: a monitoring employee loops over sources, wakes the analyst only when something changed, notifies you only when it's worth interrupting

**Workflows own the deterministic processes; teams + loops own the unpredictable everyday.** They complement each other — none replaces another.

### Advancing in parallel

- [ ] **Workflow `loop` / `invoke_skill` step modes**: per-item array iteration / call a skill without going through an employee
- [ ] **Workflow canvas editing**: from read-only chain rendering to drag-to-edit
- [ ] **Run replay view**: trace timeline + input/output diff on any node
- [ ] **Scenario templates and marketplace**: package "employees + team + workflow + triggers + KB structure" into one-click-importable scenario bundles

---

## What we deliberately don't do

> "I'm as proud of the things we haven't done as the things we have done."

| Cut | Why | When it might return |
|-----|-----|---------------------|
| **Fine-grained RBAC beyond four roles** | v1.4's Owner / Admin / Member / Viewer + capability gates cover real team needs. Button-level permissions and custom role composition belong to enterprise management platforms | When real multi-team SaaS customers need fine-grained permissions |
| **Multi-tenancy** | Premature multi-tenancy is architectural cancer. Workspace isolation already covers multiple teams in one org | When there's a clear SaaS commercialization path |
| **SSO / LDAP / SAML** | Enterprise integration is a bottomless pit | When paying enterprise customers explicitly ask |
| **30+ node visual workflow editor** | 7 step modes already cover 90% of real-world scenarios; the rest is pushed to natural-language generation | When a user case actually needs 30+ nodes (rare) |
| **Native mobile app** | 8 IM channels + desktop (now with remote connect) + Web already cover it. On your phone, you use MateClaw via DingTalk / Feishu / Telegram | When Web / IM channels can't deliver an irreplaceable mobile-only feature |
| **Replacing ReAct / Plan-Execute** | Workflows, teams, and loops **collaborate** with those two engines, not replace them — single-agent multi-turn reasoning still lives there | Never replaces |
| **Unbudgeted full autonomy** | Agent Loop always ships with budgets, circuit breakers, and approval gates. "Run until the money's gone" isn't autonomy, it's loss of control | Never |

---

## Version milestones

| Version | One line | User experience goal | Status |
|---------|----------|----------------------|--------|
| **v1.0** | It thinks and acts | An AI assistant that uses tools to solve problems | ✅ Released |
| **v1.1** | It's everywhere | 8 channels + 4-layer memory + workspaces + LLM Wiki | ✅ Released |
| **v1.2** | It's your coworker | Digital employees + career templates + backbone skills + ACP bridge + Backstage | ✅ Released |
| **v1.3** | It orchestrates business flows | Workflow + triggers + document generation + per-agent tool binding | ✅ Released |
| **v1.4** | It's more autonomous and leads teams | Persistent goals + delegation tree + progressive disclosure + RBAC + first-class Feishu | ✅ Released |
| **v1.5** | It's verifiable | Goal checklists + self-maintaining Wiki + owner-aware memory | ✅ Released |
| **v1.6** | It meets you where you are | Domestic databases + persistent vision + code execution + identity shaping | ✅ Released |
| **v1.7** | It's ready for production | Approval paths closed + Run Overview & cost visibility + context/token budgeting + open API/Deep Research + desktop remote/LAN + operational export | ✅ Released |
| **v1.8** | **It's on duty** | **Agent Team standing rosters + Agent Loop resident cycles = a department that runs itself** | 📋 Planned |

---

## One More Thing

We're not building MateClaw to chase ChatGPT, not to be the next Dify, not to add another buzzword to a funding deck.

We're building it because we believe one thing:

**AI shouldn't be a chat box on a webpage. It should be your second brain.**

It lives in your DingTalk, your Feishu, your Telegram. It's read every document you have. It remembers what you said three months ago. It uses your company's internal tools. It consolidates memory while you sleep. It runs an entire business flow on your behalf. **Soon it will lead a standing team, stay on duty, and watch over the things you can't get to.**

Someday, you'll forget it's a program.

**That's the day we win.**

---

*Stay hungry. Stay foolish.*
