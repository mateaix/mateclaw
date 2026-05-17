---
title: Ambient AI — It Finds You. You Don't Open It.
description: Cron jobs + multi-channel delivery = ambient AI. Push your morning briefing to Feishu, alert your DingTalk when a competitor ships, let AI show up where it's needed without you remembering to ask.
head:
  - - meta
    - name: keywords
      content: ambient AI,proactive AI,cron jobs,multi-channel delivery,Feishu briefing,DingTalk push,Slack bot,scheduled agent,morning briefing
---

# Ambient AI

**It finds you. You don't open it.**

ChatGPT, Claude, Gemini — every AI assistant waits for you to open it. Open the browser, log in, click into the input box, type, wait. The AI is something you have to walk over to.

MateClaw isn't.

You can have any agent show up at any time, in any messenger you use, **and find you**.

```
Daily 9:00 AM: Briefing agent → Feishu engineering room
Weekly Mondays 10:00: Sales agent → Slack channel
4:00 AM job failure → Operations agent → DingTalk DM
```

This is the upgrade from **conversational AI** to **proactive AI**. We call it **Ambient AI** — it doesn't sit in a browser tab waiting for you. **It lives inside your workflow.**

---

## What's actually happening

Three things wired together:

1. **Cron jobs** — a scheduler that knows how to run agents (`mate_cron_job`, `mate_cron_job_run` tables)
2. **The agent runs** — when the trigger fires, the scheduler spins up an agent context and runs the full tool chain (search, scrape, read Wiki, query database, …)
3. **Result is delivered through a channel** — agent output flows through `CronJobCompletedEvent` → `CronDeliveryListener` → `ChannelCronResultDelivery`, landing in the channel you set

No one is watching. You set it once and the AI starts showing up to work on time.

---

## Three patterns that just work

### 1. Daily briefing

> Every day at 9:00 AM, a competitive intelligence agent runs: search for releases shipped yesterday, read 5 target companies' blogs, diff the last 24 hours, distill the three most important things into Markdown, push to the engineering room on Feishu.

`Cron 0 0 9 * * ?` · `Agent: CompIntel` · `Channel: Feishu — Engineering`

You sit on the subway, your phone vibrates, you read the three things. Before you reach your desk you already know what the day is about.

### 2. Weekly digest

> Every Monday at 10:00 AM, the sales assistant agent queries last week's order database, extracts key metrics, writes a digest with tables, pushes to a Slack channel.

Data comes through the [datasource tools](./config); the agent writes SQL and explains the result. If past digests live in your Wiki, they're auto-cited as comparison.

### 3. Event-triggered alerts

> A new email lands with the "Boss" label → an executive assistant agent → summarizes the email and drafts a reply → pushes to WeChat.

Event triggers ride a high-frequency cron with a condition check, or wire an external webhook through an [MCP tool](./mcp). The agent runs through the same delivery chain to a channel.

---

## Setting it up

`Console → Cron Jobs → New` — three steps:

1. **Cron expression** — when it runs (standard 6-field cron, with a UI builder)
2. **Agent** — pick an agent already configured with the right tools and system prompt
3. **Output channel** — pick a [channel](./channels) for the result (Feishu / DingTalk / Slack / WeCom / Telegram / any configured channel)

Save. Next time the trigger fires, the agent goes to work.

::: tip Don't configure 100 crons
Same rule as model management — you don't need 100 scheduled tasks, you need **one that actually helps**. Start with the 9 AM briefing. Run it for two weeks. See what you actually read and what's noise. Then add the second one.
:::

---

## Why this matters

The big chase across 2025–2026 in AI was the same thing — **make it so you don't have to open a screen.**

- **Vision Pro** tried to put AI into your field of view. Didn't land.
- **Humane AI Pin** tried to put AI on your body. Folded.
- **Echo / Alexa** tried to put AI in your home. Stalled at "what's the weather."

All of them tried to do it with new hardware.

MateClaw's answer is the opposite: **the messengers your team already uses are the hardware.**

Feishu, DingTalk, WeCom, Slack, Telegram, Discord, QQ — they're already open on your phone and your desktop. AI showing up where you're already looking is enough. **No new device. No new habit.**

---

## What's different from other AI products

| | Conversational AI | Ambient AI (MateClaw) |
|---|---|---|
| How it triggers | You open it | It shows up at the right time |
| Where it lives | A browser tab | The IM you already use |
| When it talks | Only when asked | When you need it |
| When you're offline | You miss it | Pushes when you come back |
| When something fails | Red error toast | Auto-reconnect, next push lands clean |

Only MateClaw fills out the right column completely, because only MateClaw has all of these at once:

- **Multi-agent runtime** (ReAct + Plan-Execute)
- **Cron scheduling + retry**
- **9 IM channel adapters** with exponential-backoff reconnect
- **Persistent memory** ([Memory](./memory) — Dreaming makes it know you better every day)
- **Wiki knowledge layer** ([LLM Wiki](./wiki) — gives the agent something to base research on)
- **Tool Guard** ([Security](./security) — sensitive ops still ask you first)

Cron is the last piece that ties the rest together — **time-triggered access** to all of it.

---

## Security considerations

Proactive = stricter permissions needed. A cron-triggered agent runs unattended. Whatever tool permissions it has, it'll use — there's no "let me double-check" moment.

So:

- **Cron agents do not bypass Tool Guard.** Tool calls that need approval still pause and wait for you to approve in your IM. See [Approval workflow](./security#审批工作流-人在回路).
- If you don't want to be interrupted, set sensitive tools to `deny` instead of `require_approval` — the agent will stop hard rather than send you an approval request.
- **Every cron run is in the audit log** (`mate_audit_event`) — which task at what time used which tool to do what.

---

## Underlying data (if you're curious)

| Table | Purpose |
|---|---|
| `mate_cron_job` | One row per scheduled task — agent ID, cron expression, target channel, timeout, enabled flag |
| `mate_cron_job_run` | One row per execution — start/end time, status, output summary, error if any |

Code:

- `vip.mate.cron.service.CronJobLifecycleService` — task lifecycle management
- `vip.mate.cron.service.CronJobRunner` — single execution
- `vip.mate.cron.delivery.ChannelCronResultDelivery` — routes agent output to the channel
- `vip.mate.cron.delivery.CronDeliveryListener` — listens to `CronJobCompletedEvent`
- `vip.mate.cron.CronChatOriginFactory` — tags conversations with "this came from cron job X" so sessions stay traceable

### API

```bash
# List all cron jobs
curl http://localhost:18088/api/v1/cron-jobs \
  -H "Authorization: Bearer <token>"

# Run once now (doesn't affect the next scheduled run)
curl -X POST http://localhost:18088/api/v1/cron-jobs/{id}/run-now \
  -H "Authorization: Bearer <token>"

# View execution history
curl http://localhost:18088/api/v1/cron-jobs/{id}/runs \
  -H "Authorization: Bearer <token>"
```

---

## Next

- [Channels](./channels) — where the AI output gets delivered
- [Agents](./agents) — cron schedules whatever agent you've configured
- [Security & Approval](./security) — keep cron from running sensitive ops unattended
- [LLM Wiki](./wiki) — give the cron agent a knowledge base to research with
