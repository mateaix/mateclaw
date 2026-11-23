# Docker Deployment

The only recommended production deployment outside the desktop app. One `docker compose up -d` brings up three containers: PostgreSQL, SearXNG, and mateclaw-server.

> **⚠️ Upgrading from the MySQL stack**: the Docker stack switched from MySQL to PostgreSQL 16. Pulling this change and running `up -d` on an existing host starts a **fresh, empty PostgreSQL volume** — the old `mysql_data` volume is not read (data is not lost, but the new stack cannot see it). To carry data across, `mysqldump` from the old stack first and import into PostgreSQL with a cross-engine tool such as pgloader; or stay on a pre-switch tag to keep running MySQL (the `mysql` Spring profile remains supported).

This page covers **requirements, steps, verification, and common gotchas**. For the full environment variable reference, see [Configuration](./config).

---

## Prerequisites

| Item | Minimum | Recommended | Notes |
|---|---|---|---|
| Docker Engine | 24.0+ | latest stable | `docker --version` |
| Docker Compose | v2.20+ | v2.30+ | `docker compose version` (the v2 plugin, not the legacy `docker-compose`) |
| Host RAM | 4 GB | 8 GB+ | Chromium consumes 1-2 GB when the browser tool is active |
| Disk | 6 GB | 20 GB+ | ~2 GB image + PostgreSQL data + workspace files |
| /dev/shm | default | compose sets 2 GB automatically | Chromium uses shared memory for rendering; the 64 MB default causes SIGBUS |
| Network | outbound | — | for pulling images and calling LLM APIs |

**Not required on the host**: Java, Node, Maven, Chrome, or Python — all live inside the image.

---

## The three containers

| Service | Image | Role | Exposed port |
|---|---|---|---|
| `postgres` | `postgres:16` | Business data | compose network only (no host port by default) |
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

The backend runtime stage (`mateclaw-server/Dockerfile` stage 3) is based on `mcr.microsoft.com/playwright:v1.62.0-noble` (Ubuntu Noble 24.04, glibc) and installs on top of it:

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
git clone https://github.com/mateaix/mateclaw.git
cd mateclaw

# 1. Fill in required values
cp .env.example .env
vi .env   # see table below
```

**Required** (compose refuses to start without these, so you can't accidentally ship default passwords):

| Variable | Notes |
|---|---|
| `DB_PASSWORD` | App DB password — 16+ chars, mixed case, digits, symbols |
| `DB_ADMIN_PASSWORD` | PostgreSQL bootstrap superuser password (init + admin only) — **must differ from the above** |

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
SearXNG either isn't up or the image default settings disabled JSON output. Our own `./docker/searxng/` build patches this; rebuild and recreate only the search container: `docker compose up -d --build --force-recreate searxng`.

**LLM responses show tofu boxes (□) for Chinese**
The image already installs `fonts-noto-cjk` and `fonts-noto-color-emoji`, so this isn't a server-side font issue. Check your frontend browser's locale / font settings.

---

## Upgrading

1. Pause new requests and Goal/Cron scheduling, let active tools finish, and record the current version, Compose configuration and images.
2. Stop application writes and back up the database, `server_data`, workspace, plugins and `.env`. Retain the JWT secret; rehearse restoration and migrations on a copy. Store backups outside the volumes being upgraded.
3. Select a published target tag and read its [upgrade notes](./changelog#upgrade-and-validation). For example, to build 3.1.0:

```sh
docker compose stop mateclaw-server
git fetch origin --tags
git checkout v3.1.0
docker compose build mateclaw-server
docker compose up -d mateclaw-server
docker compose logs --tail=100 mateclaw-server
```

The `postgres_data` volume survives rebuilds. Flyway validates history before incremental migration. **Checksum drift blocks startup by default; it does not self-heal.** Follow [Flyway recovery guidance](./config), inspect history and the actual schema, and enable repair only for a reviewed, controlled startup before restoring the default. The root `pom.xml` `<revision>` and git tag identify the version.

Verify login, model and tool calls, history, cancellation followed by another turn, search and configured channels before resuming scheduling. Rollback requires the old application and its matching pre-upgrade database and file backups; an old image alone cannot reverse migrations or external tool effects. An existing MySQL deployment cannot switch to this PostgreSQL Compose stack and expect automatic data migration.

---

## Offline 3.1.0 upgrade for an existing managed deployment

This workflow covers 3.0.0 on linux/amd64, PostgreSQL 16, Compose project `deploy`, with persistent database/application volumes and existing runtime-home/cache mounts. It replaces only the backend image, including its admin UI and WebChat. Sites/docs, search, proxy and database containers remain in place; reload the proxy after replacing the backend.

Internal deployment scripts and production receipts are not shipped in the public source repository. The commands below apply to a **reviewed 3.1.0 offline bundle supplied by the deployment maintainer**, not an ordinary source checkout. The historical 3.0.0 full-stack scripts pin that release and assume first-time runtime-directory creation; do not reuse them for this operation. Review other versions, platforms, databases and mount layouts separately.

### Bundle and build checks

Build the UI, WebChat and Java 21 backend, run appropriate regressions, then assemble the linux/amd64 image on the connected workstation. The production host imports images without building or pulling them.

| File | Purpose |
| --- | --- |
| `manifest.json` | Release 3.1.0, source commit, platform and all five services' image tags/IDs; replace only the backend entry |
| `images.tar.gz` | Backend image; the other pinned images must already exist on the host |
| `backend_upgrade.py` / `original_upgrade.py` | Backend-only entry point and dependency snapshot; do not invoke the snapshot's CLI directly |
| `acceptance.py` | Privileged pre-upgrade, validation and final acceptance checks |
| `migration/run.sh`, Java source and `.class` files | Standalone Flyway runner without a Spring application context |
| `SHA256SUMS` | Checksums covering images, manifest, operation scripts and migration entry point |

Verify JAR version 3.1.0, embedded UI/WebChat, Boot 4.1.2, Spring AI 2.0.2, AgentScope Core 2.0.5 and Playwright 1.62.0. Retain the reviewed runtime and existing DSH configuration. This deployment retained Node 22.23.4 rather than switching to the optional Node 24/DSH candidate. Exclude production environment files, signing keys, temporary tokens, databases and user files from the bundle.

### Maintenance sequence

Review the bundle's fixed version, deployment path, state path and image inventory first; this is not a general-purpose release upgrader. Pause incoming work, let tools finish and check active Goal leases. The lease check does not replace full traffic control or inspection of other active tools.

```bash
# A fresh, reviewed bundle directory on the host; existing Docker/DB administration access required
sha256sum -c SHA256SUMS
python3 backend_upgrade.py preflight
python3 acceptance.py before
python3 backend_upgrade.py rehearse
python3 backend_upgrade.py apply
```

1. **Preflight and baseline:** check architecture, image IDs, existing volumes/home/cache mounts, current JWT secret, image-only Compose and space. Size the actual backup; the script's minimum free-space threshold is not a capacity guarantee.
2. **Online-copy rehearsal:** take a custom-format `pg_dump`, restore into a separate PostgreSQL on an internal network, then run Flyway only. No application tasks/channels, automatic repair, baseline or clean.
3. **Stopped-writer backup:** stop the backend and retain the database, application data, workspace, skills/plugins, cache, environment, deployment/proxy configuration and certificates, and old image identities. Generate and verify per-file checksums.
4. **Second rehearsal:** restore and validate this new stopped-writer database backup before switching images. V203 remains the target; an existing V203 database still requires history validation even when no migration is pending.
5. **Validation startup:** save the original enabled Cron IDs, suspend Cron and Goal continuation, preserve JVM timezone/encoding options and recreate only the backend using `--no-build --pull never --no-deps`. Reload the proxy.

The outage includes file copying, checksum verification, database restoration and startup. About 6.7 GiB of runtime/skill files produced roughly 350,000 backup entries in this deployment; dump size alone is a poor estimate of the maintenance window. Do not start another operation or remove state while backup verification is running.

Successful `apply` means `validationReady=true` while phase remains `applying`; scheduling is still suspended. Continue only after reviewing business evidence:

```bash
python3 acceptance.py validation
# Inspect every JSON passed field and review model/tool/history evidence
python3 backend_upgrade.py resume
python3 acceptance.py final
python3 backend_upgrade.py verify
```

The acceptance script uses an existing administrator identity and JWT signing key to generate a short-lived test token; it does not change passwords or rotate application credentials. Run it only with existing administration rights in a restricted environment. It invokes configured models and creates a deployment-test conversation for constant text, a Shell `printf MATECLAW_TOOL_OK_31`, persistence and history follow-up. Calls incur usage and leave audit/test records; no business IM/email delivery is needed. Respect tool approval and policy boundaries; record and clean up incomplete test runs.

A zero script exit status does not prove every business check passed: inspect the report. Token expiration does not establish key rotation. Remove temporary tokens afterwards and keep credentials out of published reports. `resume` restores original scheduling/configuration and restarts the backend; only then does state become `live` with `schedulingRestored=true`. Finally verify task counts, health/readiness UP, retained environment settings, public pages and actual JS/CSS responses.

### Failure handling and rollback

PostgreSQL initialization can expose a temporary Unix socket before its final server is ready. Wait for `pg_isready -h 127.0.0.1` before restoring. An initial rehearsal restore failed in this deployment and passed after private diagnostics were added; the first failure lacked sufficient evidence to attribute its cause conclusively to readiness timing. Successful Flyway checks did not use repair.

Before switching, a failed validation must leave the old backend available while logs are reviewed. Internal post-switch checks attempt rollback, but its success must also be confirmed. Do not resume scheduling after failed business acceptance; after review use:

```bash
# The same verified, versioned bundle directory
python3 backend_upgrade.py rollback
```

Rollback requires retained old images and the complete stopped-writer backup and discards post-upgrade writes. Restore the matching database, files, environment and old backend together; an old image alone cannot undo migrations or external tool effects. Retain backups/images. Do not run `down -v`, prune, clear checkpoints or force Flyway repair. After interruption or failed rollback, inspect state and logs rather than assuming recovery or deleting live state to retry.

### Historical 3.1.0 acceptance and limits

Receipt time: 2026-11-23 04:34:33 UTC (2026-11-22 23:34:33 in New York). These are results for that controlled deployment, not certification of every platform or a statement of current production health.

| Check | Result |
| --- | --- |
| Complete backup | 352,110 manifest entries verified; rollback materials retained |
| Database | Two successful copy restorations and Flyway checks at V203; zero new migrations, no repair |
| Business acceptance | 17/17: authentication, pre-upgrade test token, configuration APIs, real chat, persistence, successful Shell event and history follow-up |
| After scheduling resumed | 14/14 API checks; original 33 Cron jobs and Goal scheduling restored |
| Runtime/configuration | Health/readiness UP, environment retained, other four containers not recreated |
| Public admin UI | Matches the built output; five JS/CSS resources checked; browser login page renders |
| Companion sites | Seven pages match the bundle; bilingual company attribution and company link verified |

Model discovery/connection probing can fail while generation works. A configured usable model passed real generation, tools and follow-up checks. Existing credential failures for some other providers remain unresolved; this is not a claim that all models or external channels are healthy. Delivery through every IM, all optional plugins and platform installers were outside this acceptance scope.

---

## Next steps

- [Configuration](./config) — every environment variable and runtime toggle
- [Doctor Health Check](./doctor) — the in-app diagnostics page
- [Security & Approval](./security) — pre-production hardening checklist
