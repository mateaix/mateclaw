# Models

**Pick a model. Just one. Add more later.**

MateClaw doesn't care which LLM you use. It talks to every mainstream provider through five protocol adapters, supports 15+ cloud providers and 4 local runtimes, and lets you swap models at runtime without touching agent configuration. The only opinion MateClaw has is that you should **start with one and add more when you need them** — not configure everything on day one.

---

## What's supported

### Cloud providers

| Provider | Example models | Protocol | Notes |
|----------|---------------|----------|-------|
| **DashScope** (Alibaba) | Qwen-Max, Qwen-Plus, Qwen-Turbo, Qwen-VL, Qwen-Long | dashscope | Default out of the box |
| **DashScope (OpenAI-compatible)** | Qwen3.5-Plus, Qwen3.6-Plus, Qwen3 VL Plus, etc. (dot-versioned families) | openai | See "Two DashScope variants" below |
| **Bailian Token Plan** | Bailian token-bundle plan | dashscope | 7 seeded models; long tokens supported |
| **OpenAI** | GPT-4o, GPT-4o-mini, GPT-5.5, o1, o3, o4-mini | openai | Standard OpenAI API |
| **OpenAI OAuth (ChatGPT Plus/Pro)** | GPT-4o, o3, o4-mini via subscription | openai | Browser-based OAuth — no API key |
| **Anthropic** | Claude 4.7, Claude 4.6 Sonnet, Claude 4.5 Haiku | anthropic | Native Messages API |
| **Anthropic Claude Code OAuth** | Claude 4.7 / 4.6 via Claude Pro/Max/Team subscription | anthropic | Browser OAuth + manual-paste flow — no API key |
| **Google Gemini** _(native)_ | gemini-2.5-flash, gemini-3-pro-image-preview, gemini-2.5-flash-image | gemini | Native `generateContent` API (not OpenAI-compatible) — see "Native Gemini" below |
| **xAI / Grok** | Grok 3, Grok 4 | openai | OpenAI-compatible (base URL + API key); xAI brand icon in the UI |
| **DeepSeek** | deepseek-chat, deepseek-coder, **DeepSeek V4 flash + pro** (thinking-mode) | openai | OpenAI-compatible |
| **Kimi (Moonshot)** | moonshot-v1-8k/32k/128k | openai | OpenAI-compatible |
| **Zhipu AI** | GLM-5-Turbo, GLM-5V-Turbo, GLM-5, GLM-5.1 | openai | OpenAI-compatible |
| **MiniMax** | abab6.5, abab5.5; expanded video catalog + CN endpoint | openai | OpenAI-compatible |
| **SiliconFlow CN/INTL** | Routed inference across hosted models | openai | Two endpoints, OpenAI-compatible |
| **OpenCode** | Code-tuned routing | openai | OpenAI-compatible |
| **OpenRouter** | 200+ models with free tier | openai | Routes to any upstream with one key |
| **Any OpenAI-compatible** | Your own vLLM, etc. | openai | Custom base URL |

### Local runtimes

| Runtime | Example models | Protocol | Notes |
|---------|---------------|----------|-------|
| **Ollama** | Gemma 3/4, Qwen 3, Llama 3.1, DeepSeek R1, Mistral | ollama | **Auto-detected at startup** on `localhost:11434` |
| **LM Studio** | Any GGUF model | openai | OpenAI-compatible server |
| **llama.cpp** | Any GGUF model | openai | Via llama-server |
| **MLX** | Apple Silicon via mlx-lm | openai | mlx-lm's OpenAI-compatible server |

### Protocol adapters

Five protocols cover everything:

| Protocol | Used by |
|----------|---------|
| **OpenAI** | OpenAI, Kimi, DeepSeek, MiniMax, Zhipu, OpenRouter, LM Studio, llama.cpp, MLX |
| **Anthropic** | Claude family |
| **DashScope** | Qwen family |
| **Gemini** | Google Gemini family |
| **Ollama** | Locally hosted models via Ollama |

Any OpenAI-compatible service works — just point `base-url` at it.

---

## Two DashScope variants

Same `sk-` API key, **two endpoints** that ship different model families:

| Item | DashScope | DashScope (OpenAI-compatible) |
|---|---|---|
| Endpoint | `dashscope.aliyuncs.com/api/v1` (native) | `dashscope.aliyuncs.com/compatible-mode/v1` (OpenAI-compatible) |
| Protocol | DashScope native | OpenAI standard (same shape as GPT-4 / DeepSeek / Kimi) |
| Built-in web search (`enable_search`) | ✅ Supported | ❌ Not supported |
| Models | Qwen-Max / Plus / Turbo / Long, Qwen-VL, Qwen3-Max, DeepSeek-V3.2, etc. | **Dot-versioned** new families: Qwen3.5-Plus, Qwen3.6-Plus, Qwen3 VL-Plus, etc. |

**Why two providers**: Alibaba publishes the dot-versioned families (`qwen3.5-*` / `qwen3.6-*` / `qwen3-vl-*`) only on the OpenAI-compatible endpoint; the native protocol returns `400 InvalidParameter` for them. The two providers **share the same sk- key** — paste it once, it works for both.

**Which to pick**:
- Want Qwen-Max / Plus / Turbo + built-in search / DeepSeek-V3.2 → **DashScope**
- Want Qwen3.5-Plus / Qwen3.6-Plus / Qwen3 vision-language → **DashScope (OpenAI-compatible)**
- **Enable both** if you want — same key, models just appear under different cards

---

## Native Gemini

::: tip New in 1.4.0
Gemini no longer rides on an OpenAI-compatibility shim — MateClaw talks to Google's **native `generateContent` API** directly.
:::

Plenty of products bolt Gemini on as "just another OpenAI-compatible endpoint" and then hit walls around system instructions, function calling, and inline images. MateClaw speaks Gemini's own protocol instead:

- **Native chat builder** — maps `systemInstruction`, `functionCall` / `functionResponse` (tool-call turns), and inline image parts (multimodal input) correctly
- **Streaming SSE parsing** — parses Gemini's streaming response format chunk by chunk
- **JSON Schema sanitizing** — automatically strips JSON Schema keywords Gemini rejects, so tool definitions aren't refused
- **Startup liveness probe** — sends a lightweight request at startup to confirm the credentials and model are reachable

Configure it under `Settings → Models → Add Provider`, pick the **Gemini** provider, paste your API key. Example models: `gemini-2.5-flash`, `gemini-3-pro-image-preview`, `gemini-2.5-flash-image`. Image generation runs through the same native path — see [Multimodal → Image generation](./multimodal#image-generation-six-providers).

---

## Adding a provider

**A fresh MateClaw install has an empty provider list. That's deliberate.**

You don't need to see 16 providers. You need **one that works.**

`Settings → Models → Add Provider` opens a drawer with the full catalog. Local runtimes (Ollama, LM Studio, llama.cpp, MLX — no API key required) appear first; cloud providers (DashScope, OpenAI, Anthropic, DeepSeek, etc.) follow.

Three steps:

1. **Find the row you want and click Enable** — the provider joins your main list
2. **Fill in the base URL** (pre-filled for known providers) **and paste your API key** — encrypted at rest, masked in UI
3. **Save → Test Connection** — the system sends a lightweight request and reports success or error

Close the drawer and the main list shows only the providers you've enabled. **Model picker, chat page, agent editor — every place that surfaces models, surfaces only the ones you opted in.**

::: tip Existing installs (V55 migration)
Providers already in use are **not** turned off. V55 auto-marks a provider as enabled if any of these are true:
- Has a real API key configured
- Has an OAuth token
- Has been used by a chat session in the last 30 days
- Owns the current default model

Untouched, never-used placeholder providers go back into the drawer — flip them on the next time you need them.
:::

---

## Enabling / disabling a provider

Every provider card in the main list has an **Enable / Disable** toggle. **You must enable a provider before you can use it** — that's the core product contract from v1.1.0 onward.

- **Disable** — the provider disappears from the model picker, chat page, and agent editor immediately. **Configuration is preserved**; flip it back on and everything is exactly where you left it.
- **If you disable the provider that owns the current default model**, the system automatically promotes a model on a still-enabled provider as the new default — no broken next-message.
- **Enable** — the provider reappears everywhere. If it has never had an API key set, you'll be prompted to configure it.

This separates "I have a key for this provider but I'm not using it today" from "I don't have this provider." Switching providers temporarily no longer means deleting configuration.

### ChatGPT OAuth — no API key needed

Have a ChatGPT Plus or Pro account? MateClaw can talk to OpenAI's chat endpoint through **browser-based OAuth** — log in the way you normally would, your subscription is used directly. GPT-4o, o3, and o4-mini become available immediately.

`Settings → Models → Add Provider → OpenAI OAuth`. A browser window opens. Token exchange happens on the backend; **credentials never leave your machine**.

### Device authorization grant — for remote / headless deployments

Browser-callback OAuth needs the IDP's redirect to land back on a `localhost` port that *your* browser can reach. That's fine when MateClaw runs on your laptop and breaks the moment you put it on a server, in a container, or on a host that doesn't expose a loopback socket to your client.

For those cases, OpenAI OAuth automatically switches to **Device Authorization Grant (RFC 8628)** — the same flow ChatGPT desktop and `gh auth login` use. No callback, no port mapping.

`Settings → Models → Add Provider → OpenAI OAuth` on a non-localhost host pops a dialog showing:

- A short **user code** (monospace, copyable)
- A **verification URL** at `auth.openai.com/codex/device` — open it in any browser on any device
- A live **countdown** until the device code expires (default 15 min)

Enter the user code in your browser, authorize, and the dialog closes itself the moment the backend's poll loop sees `COMPLETED`.

**How MateClaw decides which flow to use:**

| `mateclaw.oauth.openai.deployment-mode` | Behaviour |
|---|---|
| `auto` *(default)* | `localhost` / `127.0.0.1` / `::1` → browser callback; everything else → device code |
| `local` | Force browser callback (loopback server) |
| `device_code` | Force device code |
| `manual_paste` | Force the legacy paste-the-callback-URL flow |

If `local` mode can't bind a loopback port (port in use, sandbox refused), it falls through to `manual_paste` automatically.

**Backend endpoints** (`/api/v1/oauth/openai/device`):

| Method | Path | Purpose |
|---|---|---|
| `POST` | `/start` | Begin a session — returns `deviceAuthId`, `userCode`, `verificationUrl`, `intervalSeconds`, `expiresInSeconds` |
| `POST` | `/poll` | Poll one session by `deviceAuthId` — returns `PENDING` / `COMPLETED` / `EXPIRED` |
| `POST` | `/cancel` | Drop the session (e.g. user closed the dialog) |

The frontend respects the `intervalSeconds` OpenAI returns (typically 5 s); the server enforces a min poll interval (default 3 s) to keep load bounded. Expired sessions are swept every 5 minutes.

Token persistence and refresh use the **same code path** as the browser-callback flow, so once the dialog closes there's no behavioural difference.

### Anthropic Claude Code OAuth

Same pattern, same outcome: have a Claude Pro / Max / Team subscription? Sign in with the **same OAuth flow Claude Code itself uses** — no `sk-ant-…` API key required. Claude 4.7 / 4.6 / 4.5 Haiku come online through your subscription.

`Settings → Models → Add Provider → Anthropic Claude Code OAuth`. Two flows are supported:

- **Browser callback** — local install, browser pops up, you click through, token lands in MateClaw
- **MANUAL_PASTE** — for remote-server deployments where the browser can't reach the backend, you complete the auth in your local browser and paste the token in

Anti-abuse-gate compliant: Claude Code identity is injected into the system prompt, the request shape (UA / accept headers / `system` array form / `mcp_` tool-name prefixes) matches Claude Code's wire format exactly so the requests aren't rejected.

---

## Model discovery

Providers that expose a model list (OpenAI, Ollama, LM Studio, OpenRouter, etc.) support **Model Discovery** — one click and MateClaw fetches every model the provider offers.

- `Settings → Models → [provider card] → Discover Models`
- System queries the provider's `/v1/models` endpoint
- Discovered models appear with name, context window, pricing
- Add them one by one or all at once

For OpenRouter specifically, Model Discovery surfaces the **200+ free-tier models** — pick a free model and you have a working setup with zero cost.

### Ollama auto-detection on startup

No manual configuration needed. On startup:

1. **Ping** `http://127.0.0.1:11434`
2. **Discover** — fetch pulled models via `/v1/models`
3. **Register** — add to `mate_model_config`
4. **Enable** — auto-enable matching pre-configured models
5. **Tag rewrite** — rewrites seed `:latest` tags to actual installed versions (`deepseek-r1:latest` → `deepseek-r1:7b`), no more `model not found` 404s

If Ollama isn't running, silently skipped.

::: tip Default behavior
- Models without tool support (`deepseek-r1`, `gemma*`, `phi3/4`, etc.) won't accidentally activate as default — they're blocklisted
- Models that are not callable on DashScope native protocol are auto-purged on startup; dot-versioned Qwen families now live on the DashScope (OpenAI-compatible) provider instead
- DashScope model discovery uses protocol-aware probing, skipping non-chat modalities
:::

**Pre-configured Ollama models** (disabled until discovered, then auto-enabled):

| Model | `model_name` |
|-------|-------------|
| Gemma 3 | `gemma3:latest` |
| Gemma 4 | `gemma4:latest` |
| Qwen 3 | `qwen3:latest` |
| Llama 3.1 | `llama3.1:latest` |
| DeepSeek R1 | `deepseek-r1:latest` |
| Mistral | `mistral:latest` |

Setup:

```bash
# Install Ollama from ollama.com, then:
ollama pull gemma3
ollama pull qwen3
```

Restart MateClaw. Auto-discovered, added, enabled.

---

## Database schema

### `mate_model_provider`

| Column | Purpose |
|--------|---------|
| `id` | Primary key |
| `name` | Provider identifier |
| `display_name` | Human-readable name |
| `protocol` | `dashscope` / `openai` / `ollama` / `anthropic` / `gemini` |
| `base_url` | API base URL |
| `api_key` | Encrypted API key |
| `oauth_tokens` | OAuth tokens (ChatGPT Plus/Pro) |
| `is_local` | True for local runtimes |
| `enabled` | Provider master switch — when off, hidden from every model picker; configuration is preserved (v1.1.0+) |

### `mate_model_config`

| Column | Purpose |
|--------|---------|
| `id` | Primary key |
| `provider_id` | FK to `mate_model_provider` |
| `model_name` | Actual model identifier |
| `display_name` | Human-readable name |
| `temperature` | Default temperature (0.0 – 2.0) |
| `max_tokens` | Max output tokens |
| `top_p` | Top-p sampling |
| `group_name` | UI grouping (e.g., "Reasoning", "Fast", "Vision") |
| `enabled` | Whether the model is available |

### Embedding models

No `EMBEDDING_API_KEY` env vars. Embedding models are regular rows in `mate_model_config` with `model_type='embedding'`. They show up alongside chat models in `Settings → Models`. Knowledge bases pick their embedding model from a dropdown.

::: tip New in 1.4.0 ([issue #79](https://github.com/matevip/mateclaw/issues/79))
**Embedding models from any provider.** In the embedding section of `Settings → Models`, configure an embedding model from any provider — it **reuses that provider's API key**, so there's no separate `EMBEDDING_API_KEY`. Each knowledge base picks its embedding model from a dropdown. Keyless local proxies use a no-op placeholder key; the protocol is resolved from the provider's chat-model / protocol setting, so you never hand-enter it.
:::

### Anthropic prompt caching

System prompts, agent personas, tool definitions — automatically marked with `cache_control: ephemeral` on Anthropic-compatible endpoints. First request warms the cache, every follow-up gets a cache hit. The Dashboard tracks `cache_read_tokens` / `cache_write_tokens` daily.

### Thinking depth / `reasoning_effort`

**Which models honor this parameter**: `reasoning_effort` is only valid for the OpenAI reasoning family (`gpt-5*` / `o1*` / `o3*` / `o4*`), and only when delivered through the OpenAI or Azure-OpenAI providers. Every other provider (DeepSeek, Kimi, DashScope, Ollama, self-hosted OpenAI-compatible gateways, etc.) will either error or behave oddly if this parameter reaches them.

**Three product contracts**:

1. **Chat models that don't support chain-of-thought** ignore the front-end "deep thinking = high" selector entirely — this is a capability property, not a UI setting. The thinking-depth selector automatically grays out when the current model is not reasoning-capable.
2. **`generateKwargs.reasoningEffort` at provider level** only takes effect on whitelisted providers. Setting it on DeepSeek / Kimi / other OpenAI-compatible providers is silently dropped with a WARN log; the parameter is never sent.
3. **Failover** re-checks at egress time: if the primary is GPT-5 and the fallback is DeepSeek, `reasoning_effort` is stripped before hitting DeepSeek, so leaked primary options can't 400 the fallback.

**How to enable DeepSeek thinking**: DeepSeek's thinking mode does **not** use `reasoning_effort`.

- `deepseek-reasoner`: thinking is on by default; no config needed.
- `deepseek-chat` with thinking: follow DeepSeek's official docs and set `{"thinking": {...}}` under the provider's `generateKwargs.extra_body`. **Do not** set `reasoningEffort`.

**Kimi K2.5 thinking**: the model activates thinking natively; don't set `reasoning_effort`.

**Multi-round tool calls + thinking**: thinking-capable models (DeepSeek-Reasoner / GPT-5 / Kimi K2.5 / Xiaomi MiMo) correctly round-trip historical `reasoning_content` during ReAct multi-round tool calls. Cross-user-turn history is cleared at the boundary, in-turn history is preserved — matching DeepSeek's "pass back within a turn, reset across turns" contract.

**Xiaomi MiMo thinking-mode multi-turn fix** ([issue #189](https://github.com/matevip/mateclaw/issues/189)): MiMo's `reasoning_content` is now kept correctly across turns in thinking mode, instead of being lost on subsequent turns.

---

## Grouped model selector

When your deployment has a lot of models configured, the chat model picker groups them by provider and tag. Searchable dropdown lets you filter by name, provider, or group — "all Qwen", "all reasoning models", "everything under 7B". Groups are defined in the `group_name` column.

Became a real thing when agents could be bound to different models per task — a reasoning model for Plan-Execute, a fast cheap model for Chat, a vision model for image understanding.

---

## Active model switching at runtime

MateClaw uses a single **active model** as the global default. Agents that don't specify their own use it.

- **UI:** `Settings → Models → [model card] → Set as Active`
- **API:** `PUT /api/v1/models/active`

Takes effect **immediately** — no restart. Next message uses the new model. In-flight conversations unaffected.

Per-agent override supported: bind a specific agent to a specific model config.

::: tip New in 1.4.0
- **Per-conversation model selection** ([issue #150](https://github.com/matevip/mateclaw/issues/150)): in the chat UI you can switch the model for **just the current conversation**, without touching the global active model or any other conversation. See [Chat & Messaging](./chat).
- **A single bad model id no longer evicts the whole provider**: when discovery / probing hits one invalid model identifier, only that model is skipped — the rest of the provider's models stay available.
:::

---

## Per-model testing

Every model card has a **Test** button. Click it, system sends a simple prompt, shows:

- Actual response text
- Latency
- Token usage
- Any error

Use it whenever you add a new provider or suspect a stale key.

---

## Multimodal sidecar (system-wide)

::: tip Added in 1.3.0
Lets a text-only primary model still answer questions about uploaded images. See [issue #87](https://github.com/matevip/mateclaw/issues/87).
:::

Entry point: **Settings → Models → Multimodal sidecar**. Two independent cards:

| Card | Purpose | Status |
|------|---------|--------|
| **Vision sidecar model** | Captions an uploaded image once, then hands the structured description to the primary chat model | Live |
| **Video sidecar model** | Same idea for video | Reserved (config persisted but not yet wired in v1) |

The setting stores `mate_model_config.id` rather than `model_name` — the same `model_name` can exist under multiple providers (e.g. `qwen-vl-max` lives on both DashScope and an OpenAI-Compatible custom row), so a name-keyed setting would collide. Two setting keys:

- `default.vision_model`
- `default.video_model`

The dropdown only lists models that **actually support the relevant modality** — filtered by `ModelCapabilityService.supports(...)` on the backend; disabled providers or models without a declared vision capability never appear. Each card has its own Save button, independent of the other.

When does it fire? `MultimodalRouter` ([source](https://github.com/matevip/mateclaw/blob/main/mateclaw-server/src/main/java/vip/mate/llm/routing/MultimodalRouter.java)) decides per turn:

- Primary already supports vision → no routing (native multimodal path)
- Primary lacks vision + vision sidecar configured → SIDECAR strategy, captions to text
- Primary lacks vision + no sidecar → skip the attachment + tell the user to configure one

For the end-user flow (badge, hint above the input box) see [Chat → Primary model can't see images? "Multimodal sidecar" routing](./chat#primary-model-cant-see-images-multimodal-sidecar-routing).

---

## Multi-model failover

::: tip OpenAI was down for 30 minutes. My AI didn't stop for a second.
During the last 30-minute DashScope rate-limit hiccup, our service uptime was 100%.

Users saw their answers come through cleanly — no red error toast, no "service unavailable, please try again." **Mid-answer, mid-token**, the runtime quietly rolled to the next healthy provider. The next token after the cut landed normally.

This isn't "automatic retry" in the engineering sense. It's **failover the user can't perceive**.
:::

Every provider you add joins an `AvailableProviderPool` that's probed at startup and re-probed on config change.

- **Automatic fallback** — if the primary provider returns an `AUTH_ERROR`, `BILLING`, `MODEL_NOT_FOUND`, `NETWORK`, or `5xx`, the runtime rolls forward to the next provider in the chain instead of bubbling up the error
- **Per-agent priority** — bind an agent to "OpenAI first, then Anthropic, then DashScope" via the drag-to-reorder editor in `Settings → Models`
- **Live pool state** — green / amber / red badges show each provider's health
- **4-protocol probe** — DashScope, OpenAI-compatible, Anthropic, Ollama-style
- **Manual reprobe + auto-reprobe on config change** — no restart after rotating a key
- **Egress sanitizer** — provider-specific options (e.g., `reasoning_effort` for OpenAI reasoning models) are stripped at egress when failing over to a provider that doesn't support them, so leaked options can't 400 the fallback
- **UI distinguishes 401 from session expiry** — provider auth errors and user session expiry now show different messages with different remediation

---

## Configuration via API

```bash
# List enabled providers (what the main list shows)
curl http://localhost:18088/api/v1/models \
  -H "Authorization: Bearer YOUR_JWT_TOKEN"

# List the full catalog (including disabled) — what the Add Provider drawer uses
curl http://localhost:18088/api/v1/models/catalog \
  -H "Authorization: Bearer YOUR_JWT_TOKEN"

# Enable a provider
curl -X POST http://localhost:18088/api/v1/models/{providerId}/enable \
  -H "Authorization: Bearer YOUR_JWT_TOKEN"

# Disable a provider (auto-switches default model if needed)
curl -X POST http://localhost:18088/api/v1/models/{providerId}/disable \
  -H "Authorization: Bearer YOUR_JWT_TOKEN"

# Add a model configuration
curl -X POST http://localhost:18088/api/v1/models \
  -H "Authorization: Bearer YOUR_JWT_TOKEN" \
  -H "Content-Type: application/json" \
  -d '{
    "providerId": 1,
    "modelName": "qwen-plus",
    "displayName": "Qwen Plus",
    "temperature": 0.7,
    "maxTokens": 4096,
    "groupName": "Fast",
    "enabled": true
  }'

# Set active model
curl -X PUT http://localhost:18088/api/v1/models/active \
  -H "Authorization: Bearer YOUR_JWT_TOKEN" \
  -H "Content-Type: application/json" \
  -d '{"providerId": "openai", "model": "gpt-4o"}'

# Discover models
curl -X POST http://localhost:18088/api/v1/models/{providerId}/discover \
  -H "Authorization: Bearer YOUR_JWT_TOKEN"

# Test connection
curl -X POST http://localhost:18088/api/v1/models/{providerId}/test-connection \
  -H "Authorization: Bearer YOUR_JWT_TOKEN"
```

---

## Everything goes through the UI

::: tip
**Model configuration is 100% UI-driven.** There's no `spring.ai.*` YAML you need to touch. All providers, all API keys, all model configs, all switching — it all lives in `Settings → Models`, backed by the `mate_model_provider` and `mate_model_config` database tables.
:::

The UI handles everything you'd otherwise do in YAML, plus several things YAML can't do:

- **Add a provider** — pick a type, paste a key, save. Encrypted at rest, masked in the UI.
- **Test connection** — verify a provider before you trust it in production.
- **Discover models** — for providers that support `/v1/models`, one click pulls the whole list.
- **Per-model test** — send a test prompt and see the exact response, latency, and token usage.
- **Switch active model at runtime** — no restart, no config reload, takes effect on the next message.
- **Per-agent override** — bind a specific agent to a specific model config.

LLM API keys are **no longer read from environment variables** — setting `DASHSCOPE_API_KEY` / `OPENAI_API_KEY` and similar has no effect. Every provider, key, and model lives in the UI. A fresh install starts with no providers configured; add your first one under `Settings → Models → Add Provider`.

### Reference: which Qwen model to pick

If you're on DashScope, here's the rough shape of the lineup:

| Model | Context | Best for |
|-------|---------|----------|
| `qwen-max` | 32K | Complex reasoning, analysis |
| `qwen-plus` | 32K | General-purpose |
| `qwen-turbo` | 8K | Fast responses |
| `qwen-vl-max` | 32K | Vision + language |
| `qwen-long` | 1M | Very long documents |

---

## Next

- [Configuration](./config) — full config reference
- [Agents](./agents) — how agents use models
- [Admin Console](./console) — UI for model management
