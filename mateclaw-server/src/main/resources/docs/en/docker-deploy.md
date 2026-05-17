# Docker Deployment

The only recommended production deployment outside the desktop app. One `docker compose up -d` brings up three containers: MySQL, SearXNG, and mateclaw-server.

This page covers **requirements, steps, verification, and common gotchas**. For the full environment variable reference, see [Configuration](./config).

---

## Prerequisites

| Item | Minimum | Recommended | Notes |
|---|---|---|---|
| Docker Engine | 24.0+ | latest stable | `docker --version` |
| Docker Compose | v2.20+ | v2.30+ | `docker compose version` (the v2 plugin, not the legacy `docker-compose`) |
| Host RAM | 4 GB | 8 GB+ | Chromium consumes 1-2 GB when the browser tool is active |
| Disk | 6 GB | 20 GB+ | ~2 GB image + MySQL data + workspace files |
| /dev/shm | default | compose sets 2 GB automatically | Chromium uses shared memory for rendering; the 64 MB default causes SIGBUS |
| Network | outbound | — | for pulling images and calling LLM APIs |

**Not required on the host**: Java, Node, Maven, Chrome, or Python — all live inside the image.

---

## The three containers

| Service | Image | Role | Exposed port |
|---|---|---|---|
| `mysql` | `mysql:8.0` | Business data | `3306` |
| `searxng` | Built from `./docker/searxng/` | Keyless search fallback | `8088` |
| `mateclaw-server` | Built from `mateclaw-server/Dockerfile` | Spring Boot backend + embedded browser | `18080` |

---

## SearXNG search service

### Why we build a custom image

`docker/searxng/Dockerfile` derives from upstream `searxng/searxng:latest` and **bakes our own `settings.yml` into `/etc/searxng/settings.yml`**. This isn't polish — it's mandatory:

- **Upstream ships with only `html` output enabled**, while mateclaw calls `GET /search?q=...&format=json`. The default image responds to JSON requests with an HTML error page, `SearXNGSearchProvider` fails to parse it, returns empty results, and the UI shows "search temporarily unavailable".
- **Upstream enables the anti-bot Limiter plugin by default**, which rejects server-side calls (no JS, no cookies) with HTTP 429.

Our `docker/searxng/settings.yml` changes three things:

1. `search.formats: [html, json]` — enable JSON output
2. `server.limiter: false` — disable anti-bot rate limiting
3. Trim the engine list to a reliable subset (DuckDuckGo / Bing / Brave / Wikipedia / Google / Startpage), dropping the dozens of niche engines the upstream enables

**Do not** switch this to a host bind-mount. An earlier version did, and deploys where the host directory didn't exist got an auto-created empty directory that shadowed the file — SearXNG started with no config at all. To tweak settings.yml, edit `docker/searxng/settings.yml` then:

```sh
docker compose build searxng
docker compose up -d searxng
```

### Search provider fallback chain

The backend `SearchProviderRegistry` picks a provider in this order:

1. Whatever the user explicitly set under `Settings → Search` (the `searchProvider` setting)
2. Walk `autoDetectOrder`, **preferring paid providers whose API key is configured** (Serper order=1, Tavily order=2)
3. Fall back to keyless — SearXNG (order=50) wins over DuckDuckGo (order=100)

On a fresh container with no API keys configured at all, **SearXNG handles every search call**.

### Verifying the SearXNG path

```sh
# 1. Hit the container directly
curl -s 'http://localhost:8088/search?q=test&format=json' | head -5
# Expect: {"query": ..., "results": [...]}
# If you get HTML back, settings.yml didn't take effect.

# 2. Hit it from inside the mateclaw-server container
docker exec mateclaw-server wget -qO- 'http://searxng:8080/search?q=test&format=json' | head -5
# If this fails, compose networking is the problem.

# 3. Ask an agent to search and tail backend logs
docker compose logs -f mateclaw-server | grep "搜索 provider"
# Expect: 搜索 provider 解析: searxng (source=keyless-fallback)
```

### Using an external SearXNG instance

If you're already running SearXNG elsewhere, point mateclaw at it via `.env`:

```properties
SEARXNG_BASE_URL=https://your-searxng.example.com
```

Then comment out the `searxng` service block in `docker-compose.yml`. Make sure **your external instance has the same JSON + Limiter settings** — otherwise you'll hit the same silent failure mode.

---

## Browser automation

### What the image actually contains

The backend runtime stage (`mateclaw-server/Dockerfile` stage 3) is based on `mcr.microsoft.com/playwright:v1.52.0-noble` (Ubuntu Noble 24.04, glibc) and installs on top of it:

- `openjdk-21-jre-headless` — runs the Spring Boot JAR
- `fonts-noto-cjk` — Chinese/Japanese/Korean rendering in screenshots
- `fonts-noto-color-emoji` — emoji glyphs
- `tzdata` — `Asia/Shanghai` timezone

Microsoft's base image already ships all three browsers in `/ms-playwright/`:

- `chromium-XXXX/chrome-linux/chrome` — the primary
- `firefox-XXXX/firefox/firefox`
- `webkit-XXXX/pw_run.sh`

Plus every system library Chromium needs (`libnss3`, `libgbm1`, `libasound2`, `libx11-xcb1`, `libxkbcommon`, …). **No `playwright install` is required, and the Alpine-vs-musl incompatibility that blocks most Playwright deployments is sidestepped entirely.**

The Dockerfile sets one environment variable explicitly:

```dockerfile
ENV PLAYWRIGHT_BROWSERS_PATH=/ms-playwright
```

This tells Playwright Java to use the pre-installed browsers and **not** try to download to `$HOME/.cache/ms-playwright` at runtime.

### BrowserLauncher's 7-strategy fallback

`vip.mate.tool.browser.BrowserLauncher` tries each strategy in order until one succeeds:

1. `CONFIG_CDP` — if `MATECLAW_BROWSER_CDP_URL` is set, attach to that running Chrome
2. `CONFIG_PATH` — if `MATECLAW_BROWSER_CHROME_PATH` or `CHROME_PATH` env is set, use that exe
3. `CONFIG_CHANNEL` — if `MATECLAW_BROWSER_CHANNEL=chrome|msedge`, use the Playwright channel
4. `AUTO_CHANNEL` — try `chrome` then `msedge` channel (this step always wins inside the Docker image)
5. `AUTO_PATH` — scan standard install paths (`/usr/bin/google-chrome`, `chromium-browser`, `/snap/bin/chromium`, `microsoft-edge`, `brave-browser`)
6. `BUNDLED` — Playwright bundled Chromium (also guaranteed to work inside the image)
7. `EXTERNAL_CDP` — last resort: fork a system Chrome with `--remote-debugging-port=0`, parse stderr for the DevTools URL, attach via `connectOverCDP` (the openfang pattern)

Inside the Docker image, **strategy 4 or 6 always hits** and no configuration is needed. If you need to attach to an external Chrome, use strategy 1. If you want a specific host-installed Chrome, use strategy 2.

### `/dev/shm` must be 2 GB

`docker-compose.yml` sets `shm_size: 2gb` for `mateclaw-server`. Docker defaults to 64 MB per container — Chromium uses shared memory for GPU compositing and page rendering, and three tabs is enough to SIGBUS the browser. Playwright surfaces this as `TargetClosedError: Target page, context or browser has been closed`. **Do not shrink this value.**

### SSRF protection

Before any `navigate` call, `BrowserUseTool` runs the URL through `UrlSafetyChecker`, which **hard-blocks** these hosts:

- `localhost`, `127.0.0.1`, `::1`, `0.0.0.0`
- `169.254.169.254` (AWS / GCP / Azure IMDS), `100.100.100.200` (Alibaba Cloud IMDS), `192.0.0.192` (Azure IMDS alternative)
- All link-local / private / multicast IP ranges

An LLM generating a malicious URL to dump cloud credentials is therefore a closed loop. If you genuinely need to scrape internal infrastructure from a specific host, either disable via `mateclaw.browser.ssrf-check-enabled` or edit the `UrlSafetyChecker` allowlist. **Think twice before doing this in production.**

### Verifying the browser path

```sh
# 1. Pre-flight diagnosis (doesn't actually launch a browser)
curl -s http://localhost:18080/api/v1/system/browser-health | jq .
# Expect: overall: "healthy", system.browsers found with chromium path

# 2. Drive it from an agent
#    browser_use(action="diagnose")  # returns the strategy-chain trace
#    browser_use(action="start")     # actually launches
#    browser_use(action="open", url="https://example.com")
#    browser_use(action="screenshot") # returns a base64 PNG
```

---

## First deployment

```sh
git clone https://github.com/matevip/mateclaw.git
cd mateclaw

# 1. Fill in required values
cp .env.example .env
vi .env   # see table below
```

**Required** (compose refuses to start without these, so you can't accidentally ship default passwords):

| Variable | Notes |
|---|---|
| `DB_PASSWORD` | App DB password — 16+ chars, mixed case, digits, symbols |
| `DB_ROOT_PASSWORD` | MySQL root password — **must differ from the above** |

**Strongly recommended** (not enforced, but startup logs WARN if missing):

| Variable | Notes |
|---|---|
| `JWT_SECRET` | JWT signing key — generate with `openssl rand -base64 48` |
| `MATECLAW_CORS_ALLOWED_ORIGINS` | Production allowlist, e.g. `https://mateclaw.example.com` |

Then bring the stack up:

```sh
docker compose up -d --build   # first build takes 3-10 minutes
docker compose logs -f mateclaw-server
```

First boot runs Flyway migrations (~5 s) and seeds default data (~3 s), then binds `0.0.0.0:18080`.

Open `http://localhost:18080`, sign in as `admin / admin123`, and **change the password immediately** under `Settings → Security`.

---

## Build-time performance

### US / EU servers

**Already optimal.** `mateclaw-server/pom.xml` lists repositories in the order `Maven Central → Google CDN → Aliyun`; Central direct is fastest over US/EU backbones.

### China servers

Flip to Aliyun-first. Either edit the `mvn` lines in `mateclaw-server/Dockerfile` to add `-Paliyun-first`, or (easier) expose it as a build arg:

```dockerfile
# from
RUN mvn dependency:go-offline -q
RUN mvn package -DskipTests -q

# to
ARG MAVEN_PROFILE=
RUN mvn dependency:go-offline -q ${MAVEN_PROFILE:+-P${MAVEN_PROFILE}}
RUN mvn package -DskipTests -q ${MAVEN_PROFILE:+-P${MAVEN_PROFILE}}
```

Then:

```sh
docker compose build --build-arg MAVEN_PROFILE=aliyun-first mateclaw-server
```

Aliyun's public + Spring mirrors are promoted to the top of the lookup chain, keeping traffic inside China.

---

## Optional overrides

All can be set in `.env` and are read as environment variables. **Leave them empty to accept the container defaults.**

| Variable | Default | Purpose |
|---|---|---|
| `SERPER_API_KEY` | — | Google Serper search API (paid, best quality) |
| `SEARXNG_SECRET` | built-in dev secret | Only fill when exposing port 8088 to the public internet |
| `SEARXNG_BASE_URL` | `http://searxng:8080` | Point at an external SearXNG instance |
| `MATECLAW_BROWSER_CDP_URL` | — | Attach to an external Chrome CDP sidecar |
| `MATECLAW_BROWSER_CHROME_PATH` | — | Override the bundled Chromium with a host-installed browser |
| `MATECLAW_BROWSER_CHANNEL` | — | Force a Playwright channel (`chrome`, `msedge`, ...) |

**LLM API keys (DashScope, OpenAI, Anthropic, DeepSeek, Kimi, etc.) are not read from `.env`** — add them after startup in the UI under `Settings → Models → Add Provider`. Hot-reload supported. The container starts with **zero LLM keys configured**; just log in and add your first provider on the Models page.

---

## Verification

Run these in order after `docker compose up -d`:

```sh
# 1. All three containers healthy
docker compose ps

# 2. Base health check
curl -s http://localhost:18080/api/v1/system/health | jq .

# 3. Browser tool self-diagnosis (the most common failure point on Linux hosts)
curl -s http://localhost:18080/api/v1/system/browser-health | jq .
# Expect overall: "healthy"

# 4. SearXNG returns JSON (not an HTML error page)
curl -s 'http://localhost:8088/search?q=hello&format=json' | head -5
```

If any of these fail, jump to the next section.

---

## Common gotchas

**Build stage `mvn dependency:go-offline` hangs**
US servers pulling through Aliyun is slow. The default `pom.xml` puts Maven Central first, so it should be fast. If it's still slow, the container has no outbound access — check your egress firewall.

**`mateclaw-server` stays unhealthy at startup**
`docker compose logs mateclaw-server` and look for Flyway migration errors. Nine times out of ten, a special character in `DB_PASSWORD` got eaten by the shell — wrap the value in double quotes in `.env`.

**Browser tool reports "Target page closed" or SIGBUS**
`shm_size: 2gb` didn't take effect. Check the actual value with `docker inspect mateclaw-server | grep ShmSize`. Upgrade Docker Engine to 24.0+ if it's still showing 64 MB.

**Search returns "Search temporarily unavailable"**
SearXNG either isn't up or the image default settings disabled JSON output. Our own `./docker/searxng/` build patches this; if you're reusing an old named volume, reset it: `docker compose down -v searxng && docker compose up -d searxng`.

**LLM responses show tofu boxes (□) for Chinese**
The image already installs `fonts-noto-cjk` and `fonts-noto-color-emoji`, so this isn't a server-side font issue. Check your frontend browser's locale / font settings.

---

## Upgrading

```sh
git pull
docker compose build mateclaw-server   # only rebuild the backend
docker compose up -d mateclaw-server
```

The `mysql_data` volume persists across rebuilds. Flyway runs incremental migrations automatically and self-heals checksum changes on restart. **Version is pinned in `mateclaw-server/pom.xml` and the git tag** — prefer pinning to a tag in production, not tracking `dev`.

---

## Next steps

- [Configuration](./config) — every environment variable and runtime toggle
- [Doctor Health Check](./doctor) — the in-app diagnostics page
- [Security & Approval](./security) — pre-production hardening checklist
