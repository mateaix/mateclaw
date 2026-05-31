# FAQ

Common questions and real answers. If your question isn't here, check the relevant feature page or open a [GitHub issue](https://github.com/matevip/mateclaw/issues).

---

## Installation & setup

### What Java version do I need?

**Java 17 or higher.** MateClaw uses features introduced in Java 17 (sealed classes, text blocks, records, pattern matching). Verify with `java -version`.

If you're using the desktop app, **you don't need Java installed at all** — the installer bundles JRE 21.

### Do I need a cloud API key to start?

No. Three keyless paths:

- **Ollama** — local GPU inference; MateClaw auto-detects it on `localhost:11434` at startup
- **ChatGPT OAuth** — if you have a ChatGPT Plus or Pro subscription, log in through the browser flow — your subscription is used directly, no API key needed
- **OpenRouter free tier** — 200+ free models, one OpenRouter key gives you access

**You also don't need to set any API key as an environment variable to start MateClaw.** All provider configuration is done through the UI at `Settings → Models` after startup.

### How do I get a DashScope API key?

1. Go to the [Alibaba Cloud DashScope console](https://dashscope.console.aliyun.com/)
2. Sign up or log in
3. Create an API key
4. In MateClaw, go to `Settings → Models → DashScope` and paste it

### The backend won't start — port 18088 is in use

Either stop the other process or change the port:

```bash
mvn spring-boot:run -Dspring-boot.run.arguments="--server.port=19090"
```

The **desktop app picks a free port dynamically**, so you don't see this error there.

### H2 database lock error on startup

```bash
rm -f data/mateclaw.mv.db.lock
```

Or wipe the data directory to start fresh:

```bash
rm -rf data/
```

---

## Authentication

### What are the default credentials?

Username `admin`, password `admin123`. **Change it immediately in any real deployment.**

### My JWT token keeps expiring

MateClaw implements **sliding-window renewal** — when a token is within 25% of expiry, the server issues a new one in the `X-New-Token` response header. The frontend handles this automatically.

If you're calling the API manually (curl, Postman), read the `X-New-Token` header and use the new value for subsequent requests.

### How do I change the admin password?

Through `Settings → Security` in the UI is the easiest path. Or directly in the database (BCrypt-encoded):

```sql
UPDATE mate_user SET password = '$2a$10$...' WHERE username = 'admin';
```

---

## Models

### How do I configure models?

**All through the UI.** `Settings → Models → Add Provider`. Pick a provider, paste your API key (or OAuth in for ChatGPT Plus, or skip for Ollama), save, test. Model configuration is 100% UI-driven — no `spring.ai.*` YAML blocks to edit.

LLM API keys are not read from environment variables — setting `DASHSCOPE_API_KEY` and friends has no effect. The container starts with zero providers; sign in and add the first one in the UI.

### How do I use GPT-4 with MateClaw?

`Settings → Models → Add Provider`. Either paste your OpenAI API key, or use **OpenAI OAuth** if you have ChatGPT Plus/Pro — a browser window opens for you to log in. After saving, pick `gpt-4o` (or whichever model) from the model picker.

### Ollama models are slow

Local model performance depends on hardware:

- Use smaller models (7B instead of 14B) on less RAM
- Ensure Ollama has GPU access (`ollama ps` should show GPU)
- Increase Ollama's memory limit if available
- `qwen2.5:7b` or `qwen3:latest` is a good speed/quality balance

### Can I use multiple providers at once?

Yes. Configure multiple providers and assign different model configs to different agents. Each agent can use its own model — or inherit the global default. Switch the global active model at runtime without restart.

### How do I pick a cheap model for some agents and a reasoning model for others?

- Set the **globally active model** to your cheap general-purpose one (e.g., `qwen-plus`, `gpt-4o-mini`)
- Per-agent override: on a reasoning-heavy agent, bind it to `o3` or `qwen-max` specifically
- The grouped model picker in chat lets you switch per-conversation too

---

## Tools & search

### How do I switch the search provider?

`Settings → System → Search Service`. Pick from Serper, Tavily, DuckDuckGo, or SearXNG. Enable **fallback** so failures fall through the chain. Takes effect immediately.

Keyless options (DuckDuckGo, SearXNG) let you have working web search without any API keys.

### How do I add a custom tool?

Write a Spring `@Component` with `@Tool`-annotated methods:

```java
@Component
public class MyCustomTool {

    @Tool(description = "Get weather information")
    public String getWeather(@ToolParam(description = "City name") String city) {
        return "Sunny, 25C";
    }
}
```

Auto-registered on startup. See [Tools](./tools).

**If the tool does anything dangerous, add a Tool Guard rule for it.**

### WebSearchTool returns empty results

Configure a search provider in `Settings → System → Search Service`. Keyless options (DuckDuckGo, SearXNG) work without API keys.

### Tool Guard keeps blocking my tool calls

This is **by design** — dangerous tools require approval. Three ways to loosen it:

1. **Add a specific allow rule** for the exact pattern you need (`Settings → Security & Approval → Tool Guard Rules`). Example: `ShellExecuteTool` with arg pattern `^(ls|cat|grep|find)\s` → `allow`.
2. **Lower the default policy** in `application.yml`:
   ```yaml
   mateclaw:
     tool:
       guard:
         default-policy: allow   # Not recommended in production
   ```
3. **Disable Tool Guard entirely** (only for dev):
   ```yaml
   mateclaw:
     tool:
       guard:
         enabled: false
   ```

**Production-safe:** keep `default-policy: require_approval` and add targeted allow rules for specific patterns you trust.

### How do I configure MCP servers?

`Tools → MCP Servers` in the UI. Three transport modes: stdio, streamable_http, sse. Config changes take effect without restart. See [MCP](./mcp).

---

## LLM Wiki

### What's the difference between Wiki and Memory?

**Wiki is deliberate. Memory is passive.**

- **Wiki** — you drop documents in, the system digests them into structured pages, agents read those pages. You build it. You edit it. You review it.
- **Memory** — built automatically as a byproduct of conversations. Agent extracts what seems memorable, consolidates patterns nightly.

Wiki for **source material you want to make queryable** (product specs, design docs, past decisions). Memory for **context that accumulates** (your preferences, what you're working on).

### Why does the agent still guess things when it has a knowledge base?

Because you haven't bound the agent to the KB. `Agents → [your agent] → Knowledge` — bind the KB there. Until then, the wiki tools don't get injected.

### Digestion is slow

Tune `mate.wiki.digestion-concurrency` in `application.yml`. Default is 2 — bump to 4 or 8 if your LLM quota allows.

---

## Memory

### Memory is not working

1. **Confirm auto-extraction is enabled** — check `mate.memory.auto-summarize-enabled` in config
2. **Verify conversation meets thresholds** — `min-messages-for-summarize` (default 4), `min-user-message-length` (default 10)
3. **Check cooldown** — same agent can't trigger extraction more than once every `cooldown-minutes` (default 5)
4. **Read the logs** — `vip.mate.memory` at DEBUG level shows every attempt

### Memory consolidation tasks aren't running

Consolidation is driven by seed data in `mate_cron_job`, scheduled for 2 AM daily per agent. Check:

- Is `enabled` set to `1`?
- Are seed cron jobs present? (`SELECT * FROM mate_cron_job WHERE task_type = 'memory_emergence'`)

### I don't like what the agent remembered about me

Edit `PROFILE.md` or `MEMORY.md` directly in the agent workspace view. Lock pages you've edited. See [Memory](./memory).

---

## Approvals

### I approved a tool call but the agent didn't resume

1. Is `AWAITING_APPROVAL` still set? (`GET /api/v1/agents/{id}`)
2. Did the approval actually persist? (`GET /api/v1/approvals/{id}`)
3. Are there errors in the agent log around the replay attempt?
4. If replay failed, the agent should surface an error in the chat

### I want to batch-approve future tool calls from this agent

You want an **allow rule**, not a blanket approval. `Settings → Security & Approval → Tool Guard Rules → Add Rule`.

### How long do pending approvals stay pending?

Default 10 minutes, then they expire and become `rejected`. Configure with `mateclaw.tool.guard.approval-timeout-seconds`.

---

## Agents

### Agent stuck in RUNNING state

Common causes:

1. **Tool call timeout** — a tool is waiting for external service that's hung
2. **Max iterations exceeded** — `MAX_ITERATIONS_REACHED` handler forces a best-effort answer
3. **Awaiting approval** — Tool Guard paused execution
4. **Look at the logs**:
   ```bash
   mvn spring-boot:run -Dspring-boot.run.arguments="--logging.level.vip.mate.agent=DEBUG"
   ```

### How do I tell if my agent is using the right tools?

Expand the chat interface's **thinking panel**. You see every tool call, arguments, and result. If the agent is calling the wrong tool, tighten the system prompt.

---

## Channels

### DingTalk / Feishu webhook isn't receiving messages

1. Server not publicly reachable
2. HTTPS required
3. Wrong verification token
4. Bot not added to group or missing permissions

**Easier:** use **stream / long-connection / WebSocket mode** instead of webhook. DingTalk Stream, Feishu WebSocket, Telegram Long-Polling, Discord Gateway, Slack Socket mode — none need a public IP.

### Can I use multiple channels at once?

Yes. Each channel is independent and binds to one agent. Run a web console, DingTalk bot, and Telegram bot simultaneously, all with different agents (or the same one — your call).

### Telegram / Discord can't reach the API (China network)

Configure `http_proxy` in the channel config:

```json
{
  "bot_token": "...",
  "http_proxy": "http://127.0.0.1:7890"
}
```

---

## Data backup

### How do I back up my data?

**H2 (development / desktop):** stop, copy `./data/mateclaw.mv.db`:

```bash
cp ./data/mateclaw.mv.db ./backup/mateclaw-$(date +%Y%m%d).mv.db
```

**MySQL (production):**

```bash
mysqldump -u root -p mateclaw > mateclaw-backup-$(date +%Y%m%d).sql
```

**Docker:**

```bash
docker exec mateclaw-mysql mysqldump -u root -p${MYSQL_ROOT_PASSWORD} mateclaw > backup.sql
```

**Desktop** data lives in the per-user directory:

- macOS: `~/Library/Application Support/MateClaw/`
- Windows: `%APPDATA%/MateClaw/`
- Linux: `~/.local/share/MateClaw/`

---

## Desktop app

### Desktop app won't start

The installer bundles JRE 21. Check the logs:

- macOS: `~/Library/Logs/MateClaw/`
- Windows: `%APPDATA%/MateClaw/logs/`
- Linux: `~/.local/share/MateClaw/logs/`

Try launching from a terminal. On Windows, right-click → Unblock. On macOS, allow the unsigned app in System Settings → Privacy.

### How do I update the desktop app?

**Auto-updates** via electron-updater. On startup, checks GitHub Releases and prompts you when a new version is available. Manual download also available from [Releases](https://github.com/matevip/mateclaw/releases).

---

## Docker

### Docker containers fail to start

```bash
docker compose logs mateclaw-server
docker compose logs mateclaw-mysql
```

Common:

- MySQL not ready yet
- Port conflicts (18080, 3306)
- Missing `.env` — copy from `.env.example`

### How do I access the database in Docker?

```bash
docker exec -it mateclaw-mysql mysql -u root -p mateclaw
```

---

## Debugging

### How do I enable DEBUG logging?

```yaml
logging:
  level:
    vip.mate: DEBUG
    vip.mate.agent: DEBUG
    vip.mate.agent.graph: DEBUG
    org.springframework.ai: DEBUG
```

Or:

```bash
mvn spring-boot:run -Dspring-boot.run.arguments="--logging.level.vip.mate=DEBUG"
```

### How do I access the H2 console?

1. Visit `http://localhost:18088/h2-console`
2. JDBC URL: `jdbc:h2:file:./data/mateclaw`
3. Username: `sa`
4. Password: (empty)

**Disable in production.**

### How do I inspect SSE streaming events?

Browser DevTools → Network → filter `EventStream`. Or:

```bash
curl -N -X POST 'http://localhost:18088/api/v1/chat/stream' \
  -H "Authorization: Bearer <token>" \
  -H "Content-Type: application/json" \
  -H "Accept: text/event-stream" \
  -d '{"agentId":1, "message":"test", "conversationId":"1"}'
```

---

## Frontend

### Frontend shows a blank page after build

```bash
cd mateclaw-ui
pnpm build
ls ../mateclaw-server/src/main/resources/static/
# Should contain index.html and asset files
```

### Dark mode isn't persisting

Stored in `localStorage`. Clearing browser data wipes it.

### The UI feels sluggish

- Turn logs back to INFO
- Check `java -Xmx` settings
- Click **Clear messages** on old conversations

---

## Next

- [Quick Start](./quickstart) — setup walkthrough
- [Configuration](./config) — full configuration reference
- [Contributing](./contributing) — how to report bugs and request features
- [GitHub Issues](https://github.com/matevip/mateclaw/issues) — when the docs don't answer your question
