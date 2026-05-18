---
title: MateClaw Introduction — Self-hosted Multi-Agent AI Operating System
description: MateClaw is an open-source multi-agent AI OS built on Spring AI Alibaba. ReAct + Plan-and-Execute engines, LLM Wiki knowledge base, 4-layer memory lifecycle, MCP tool protocol, 8-channel integration. One JAR, zero data egress.
head:
  - - meta
    - name: keywords
      content: MateClaw,multi-agent AI,self-hosted AI,AI operating system,Spring AI Alibaba,ReAct,Plan-and-Execute,MCP,LLM Wiki,memory lifecycle,Tool Guard,open source
---

# MateClaw — Self-hosted Multi-Agent AI Operating System

**Your multi-agent AI. On your hardware. Under your rules.**

MateClaw is a full AI operating system you deploy yourself. One JAR. One login. Your data never leaves the room.

**Three things it does that other AI products can't:**

**Proactive** — It shows up when it's needed. Push the morning briefing to Feishu at 9 AM. Alert your DingTalk when a competitor ships. **Not waiting in a browser tab.** → [Ambient AI](./ambient-ai)

**It dreams** — While you sleep, a Dreaming pass consolidates the day's scattered conversations into a coherent understanding of you, writing it into `MEMORY.md`. The next morning **it picks up where yesterday ended** — not from scratch. → [Memory](./memory)

**Asks before it acts** — When the agent wants to delete a file, send an email, or write to the database — Tool Guard rules **pause the turn mid-flight** and push an approval to your IM. You tap approve, the agent resumes. **Agentic, but not autonomous.** → [Security](./security)

It lives on your desktop, in your browser, and inside the chat apps your team already uses — same brain, same memory, wherever you go.

Bring any model. DashScope. OpenAI. Anthropic. Gemini. DeepSeek. Kimi. MiniMax. Zhipu. OpenRouter. Ollama for a local GPU. Log in to your ChatGPT Plus account via OAuth if you have one. Pick one. Add more later.

---

## The problem MateClaw fights

Most AI products stop at one layer.

You get a chat box, but the memory resets every morning. You get a tool runtime, but no way to pause it when it's about to do something stupid. You get a knowledge base that retrieves fragments but can't tell you what it actually knows. You get a desktop app, but not the channels your team lives in. Or you get all of it — rented on someone else's cloud, with your data paying rent too.

MateClaw fights a different fight. It's **all of it, under one roof, on hardware you control.**

---

## What it actually does

**It completes work.** Plan-and-Execute breaks complex tasks into ordered steps, executes them one at a time, and adapts mid-flight when something fails. ReAct handles the smaller loops — think, act, observe, continue. You see the plan update as the agent works. You see the tool calls. You see the thinking. You see it finish.

**It remembers.** Session context, post-chat extraction, workspace memory files, scheduled consolidation, and a "dreaming" pass that connects yesterday's threads into today's understanding. Memory is not a feature bolted onto chat — it's how the system gets better at knowing you.

**It shapes knowledge.** Drop a PDF. Drop a folder. Drop a thousand markdown notes. The LLM Wiki digests them into structured, linked pages with summaries and backlinks — not a vector store you query, a library you can read. Agents auto-inject page summaries and fetch full bodies on demand.

**It holds real tools.** Built-in tools for search, file IO, time, shell, image, music, video, STT, and TTS. MCP servers for anything else. Skill packages you write in a `SKILL.md` and drop into a workspace. Everything gated by Tool Guard and optional human approval — strong hands, firm limits.

**It shows up everywhere.** Web console, a desktop app that bundles JRE 21 so your users don't install Java, and eight chat channels: DingTalk, Feishu, WeCom, WeChat, Telegram, Discord, QQ, Slack. The same agent answers a Slack thread, a Feishu DM, and a web chat — same memory, same skills, same personality.

---

## Why self-hosted changes the product

Running MateClaw on your own hardware is not a compliance checkbox. It changes what the product **is**.

**Your data stops paying rent.** Logs, conversations, documents, memory — none of it trains anyone else's model. None of it waits in a vendor's queue. None of it leaves your machines unless you point a channel at one.

**You own the roadmap.** Don't like how the memory consolidator works? Change it. Need a tool your vendor won't build? Add it. MateClaw is Apache 2.0 — not source-available, not "open core", not waiting on a quarterly product review.

**You pick the economics.** Start on DashScope. Swap to Ollama when your local GPU arrives. Put one agent on OpenAI and keep the rest cheap. Agent config and tool graphs don't care what's under the model interface.

**Your deployment surface is real.** One JAR. One Spring Boot process. No Python runtime chain. No Node dependency hell. The desktop app bundles everything. The Docker compose file is eighteen lines.

---

## What's under the hood

- **Backend** — Spring Boot 3.5 + Spring AI Alibaba 1.1. Agent runtime built on a StateGraph with nodes for reasoning, action, observation, plan generation, and step execution. MyBatis Plus for persistence. SSE for streaming — WebFlux is explicitly excluded.
- **Frontend** — Vue 3 + TypeScript. Pinia for state, Element Plus + Tailwind for UI, full dark mode. Built into the backend JAR's `static/` so one process serves both.
- **Desktop** — Electron with bundled JRE 21 and the packaged server JAR. Launches, initializes, and your users never know Java is underneath.
- **Channels** — Each channel is a `ChannelAdapter` SPI implementation. Web streams over SSE. IM channels run on their platform's long-connection or webhook mode.
- **Storage** — H2 file DB for development, MySQL 8 for production. Flyway manages schema migrations with dialect-specific scripts for each.

---

## Three ways to dive in

You probably want one of three things.

**Want to use it?** → [Quick Start](./quickstart) — 60 seconds to your first message on the desktop app.

**Want to understand it?** → Read [Agents](./agents) → [LLM Wiki](./wiki) → [Memory](./memory) → [Multimodal](./multimodal). Those four pages are the product.

**Want to build on it?** → [API Reference](./api) and [Contributing](./contributing).

---

Nothing on this page is non-negotiable. If something doesn't make sense, the docs are at fault — not you. Tell us on GitHub.
