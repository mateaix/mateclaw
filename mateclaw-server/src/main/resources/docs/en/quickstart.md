# Quick Start

Sixty seconds to first message. **One path. Desktop app.**

If you want Docker or local development instead, those live in [Configuration](./config) and [Contributing](./contributing). This page does one thing only — get you from zero to a working agent as fast as humanly possible.

---

## 1. Download

Grab the latest installer from [GitHub Releases](https://github.com/matevip/mateclaw/releases).

- **Windows** — `MateClaw-Setup-x.y.z.exe`
- **macOS** — `MateClaw-x.y.z.dmg`
- **Linux** — `MateClaw-x.y.z.AppImage`

No Java install. No Node install. No Maven. The desktop app bundles JRE 21 and the server JAR.

## 2. Launch and log in

Double-click. First launch takes 10 to 30 seconds while the backend boots.

Log in. Username `admin`, password `admin123`. Change the password from `Settings → Security` the moment you're inside. Do it now. Your future self will thank you.

## 3. Add one model

`Settings → Models → Add Provider`.

Pick one. Just one:

- **DashScope** — the simplest cloud start; paste your key from Alibaba Cloud
- **OpenAI** or **Anthropic** — if you already have a key, drop it in
- **Ollama** — local GPU users; MateClaw auto-detects `localhost:11434`
- **ChatGPT OAuth** — if you have a Plus or Pro account, log in through the browser flow and use GPT-4o, o3, or o4-mini directly

Save. The model appears in the chat screen's model picker.

## 4. Say hello

Click `Chat` in the left nav. Pick an agent. Pick the model you just configured. Type:

> *Hi. What can you do right now?*

Hit enter. Watch the tokens stream.

If you saw an answer come back, **the system is alive and you're in the product.** Everything from here is about making it useful to you, not about making it work.

---

## First useful moves

You've got a working install. Now what?

**Try a tool-using prompt.** Type *"Search the web for the latest Spring Boot release and summarize the breaking changes."* Watch the agent pick up a search tool, execute, observe the result, and come back with an answer. That's ReAct in action.

**Create your first agent.** `Agents → New Agent`. Start from a template — the templates ship ready to work. Rename it, tighten the system prompt, choose which tools it can use, save. Agents are how you go from one chat window to a whole workforce.

**Build your first knowledge base.** `Wiki → New Knowledge Base`. Drop in a PDF or point at a local folder. Wait for digestion (you'll see the progress bar on each raw material row). When it's done, bind the KB to an agent and ask a question about the content. See [LLM Wiki](./wiki) for what's happening under the hood.

**Connect a chat channel.** `Channels` → pick Telegram, DingTalk, or any of the eight supported platforms. Paste the bot credentials. The same agent starts answering in that channel with the same memory it has on your desktop.

Each of those has its own page in the sidebar when you're ready to go deeper.

---

## Something broke?

First run should Just Work. If it didn't:

- **Installer won't launch** — On Windows, right-click → Properties → Unblock. On macOS, allow the unsigned app in System Settings → Privacy & Security.
- **Backend never boots** — Check `~/.mateclaw/logs/app.log` (Windows: `%USERPROFILE%\.mateclaw\logs\`). Nine times out of ten it's a port conflict on 18088.
- **Model call fails** — Wrong API key or network can't reach the provider. Go back to Settings, re-verify the key, or try a different provider.
- **UI is blank** — Hard-refresh with Ctrl/Cmd+Shift+R. Electron caches aggressively.
- **Still broken** — Open an issue on [GitHub](https://github.com/matevip/mateclaw/issues) with the tail of `app.log`. We read them.

---

## Other ways to run MateClaw

- **Docker** — `cp .env.example .env`, set the passwords, then `docker compose up -d --build`. Full prerequisites, Maven mirror selection (China vs US), browser-tool self-check, and upgrade flow live in [Docker Deployment](./docker-deploy).
- **From source** — `mvn spring-boot:run` in `mateclaw-server/` and `pnpm dev` in `mateclaw-ui/`. See [Contributing](./contributing).
- **Desktop internals** — packaging, code signing, auto-update. See [Desktop App](./desktop).

---

Next: [Introduction](./intro) for the "why", or jump straight to [Agents](./agents) for the product itself.
