# Doctor

**The Doctor page answers one question: is this thing actually working right now?**

MateClaw has a lot of moving parts — the backend, the database, model providers, MCP servers, IM channels, cron jobs, memory consolidation, wiki digestion. When something goes sideways, the symptom ("my agent isn't responding") usually has a specific cause ("the DashScope API key expired yesterday") buried several layers away from where you'd notice. Doctor is a single page that runs every check at once and tells you what's green, what's yellow, and what's red.

Open it with `Settings → Doctor` or just navigate to `/doctor`.

---

## What it checks

Each check runs independently and reports one of three states:

- **✅ OK** — everything is working as expected
- **⚠️ Warning** — working but degraded (e.g., using a fallback provider, nearing a quota, a non-critical cron job is paused)
- **❌ Error** — broken in a way you need to fix

### Core infrastructure

| Check | What it verifies |
|-------|-----------------|
| **Backend version** | MateClaw is running and reports its version |
| **Database connection** | The configured datasource is reachable and queries succeed |
| **Database schema** | All expected `mate_*` tables exist; migration state is clean |
| **Disk usage** | The data directory has enough free space (warns under 20%, errors under 5%) |
| **H2 console exposure** | Warns if the H2 console is enabled in production profile |
| **JWT secret strength** | Warns if the default JWT secret is still in use |

### Models

| Check | What it verifies |
|-------|-----------------|
| **Active model** | A default model config exists and is enabled |
| **Provider connectivity** | Each enabled provider has passed a recent connection test |
| **API key presence** | Keys are configured for every cloud provider marked enabled |
| **Ollama reachability** | If Ollama is configured, the local instance is reachable |

### Agents & tools

| Check | What it verifies |
|-------|-----------------|
| **Tool registry** | Built-in and MCP tools are loaded without errors |
| **Tool Guard config** | At least one Tool Guard rule exists (warns if `default-policy: allow` is used) |
| **Default agent** | The default agent exists and is enabled |
| **Agent templates** | Built-in templates are present and loadable |

### Memory & wiki

| Check | What it verifies |
|-------|-----------------|
| **Memory consolidation cron** | Per-agent consolidation cron jobs exist and are enabled |
| **Last consolidation run** | Warns if no consolidation has run in the past 7 days |
| **Wiki digestion queue** | No stuck `pending` or `processing` raw materials |
| **Wiki schema** | `mate_wiki_*` tables exist and are queryable |

### Channels

| Check | What it verifies |
|-------|-----------------|
| **Channel health monitor** | Every enabled channel reports `connected` or is actively reconnecting |
| **Per-channel status** | For each IM channel, connection state and last error |
| **Webhook URL reachability** | Warns if a webhook-mode channel has no public URL configured in production |

### MCP

| Check | What it verifies |
|-------|-----------------|
| **Enabled MCP servers** | Every enabled MCP server is `connected` |
| **Tool count** | Each connected server reports at least one tool |
| **Orphaned subprocesses** | No stdio subprocesses outlive their parent client |

### Cron & async

| Check | What it verifies |
|-------|-----------------|
| **Cron engine** | The scheduled-task executor is running |
| **Overdue jobs** | Warns if any job is more than 24 hours overdue |
| **Async task queue** | `mate_async_task` queue length is within normal bounds |

---

## How checks run

Doctor runs two ways:

### On demand

Click **Run All Checks** on the Doctor page. The button fires off every check in parallel; the UI streams results back as each finishes. Most checks complete in under a second; the slowest (MCP server connection tests) can take 10–30 seconds.

### On a schedule

Doctor also runs **automatically every 15 minutes** in the background. Results are cached in memory and persisted to `mate_doctor_check` so the page loads instantly when you open it — you're seeing the last cached state until you click **Run All Checks**.

You can tune the schedule in `application.yml`:

```yaml
mateclaw:
  doctor:
    enabled: true
    schedule-minutes: 15
    cache-ttl-minutes: 10
```

---

## Reading results

Each check returns:

```json
{
  "name": "DashScope Provider Connectivity",
  "category": "Models",
  "status": "ok",
  "message": "Connection test succeeded (latency: 240ms)",
  "lastChecked": "2026-04-11T14:30:22",
  "details": {
    "provider": "dashscope",
    "baseUrl": "https://dashscope.aliyuncs.com",
    "latencyMs": 240
  },
  "fixUrl": "/settings/models"
}
```

The UI renders:

- **Category tabs** at the top — Infrastructure, Models, Agents, Memory, Wiki, Channels, MCP, Cron
- **Status counters** — green / yellow / red
- **Check list** — name, status, message, time since last check, "View details" expand, optional "Fix" button that navigates to the relevant settings page
- **History graph** — (for each check) a sparkline of the last 50 runs so you can see flapping checks at a glance

---

## Fix buttons

For actionable checks, the Doctor row includes a **Fix** button that navigates directly to the relevant settings page:

- Model provider failure → `Settings → Models`
- Tool Guard `default-policy: allow` → `Settings → Security & Approval`
- H2 console in production → `Settings → System` (or show a config snippet to copy)
- JWT default secret → `Settings → System` (or show a config snippet)
- MCP server disconnected → `Tools → MCP Servers`
- Stuck wiki digestion → `Wiki → [KB] → Raw Material`

Clicking Fix takes you to the exact page where you can address the issue. When possible, the target page is pre-filtered to highlight the failing item.

---

## Doctor API

```bash
# Run all checks (synchronous)
curl http://localhost:18088/api/v1/doctor/run \
  -H "Authorization: Bearer <token>"

# Get the cached check results
curl http://localhost:18088/api/v1/doctor/checks \
  -H "Authorization: Bearer <token>"

# Run a specific category only
curl http://localhost:18088/api/v1/doctor/run?category=models \
  -H "Authorization: Bearer <token>"

# Historical results
curl "http://localhost:18088/api/v1/doctor/history?check=dashscope-connectivity&limit=50" \
  -H "Authorization: Bearer <token>"
```

---

## Using Doctor in operations

### As a health endpoint for uptime monitoring

Point your external uptime monitor (UptimeRobot, Pingdom, internal Prometheus) at:

```
GET /api/v1/doctor/checks
```

The endpoint returns HTTP 200 with JSON summary — aggregate pass/fail counts and per-category breakdown. Your monitor should alert when `errorCount > 0`.

For a simpler health check, use:

```
GET /actuator/health
```

which follows Spring Boot's standard format.

### During upgrades

After deploying a new MateClaw version, run Doctor to verify nothing regressed:

1. Open `/doctor`
2. Click **Run All Checks**
3. Look for any yellows or reds that weren't there before
4. Pay special attention to **Database schema** — a mismatched schema after an upgrade usually means a migration didn't run

### When something's broken

Doctor is the first place to look when a user reports "it's not working". Open the page, see which check is red, click **Fix**, solve the problem. If no check is red but the user still has an issue, it's probably something Doctor doesn't cover yet — file it as a [GitHub issue](https://github.com/matevip/mateclaw/issues) so we can add a check.

---

## Data model

**`mate_doctor_check`**

| Column | Purpose |
|--------|---------|
| `id` | Primary key |
| `name` | Check name |
| `category` | Check category |
| `status` | `ok` / `warning` / `error` |
| `message` | Human-readable message |
| `details` | JSON blob of extra detail |
| `last_checked` | When it last ran |
| `run_duration_ms` | How long the check took |
| `workspace_id` | Scoping (nullable for global checks) |

Historical results go into `mate_doctor_check_history` with the same columns plus a retention cleanup job.

---

## Next

- [Admin Console](./console) — the UI Doctor lives in
- [Configuration](./config) — things you might configure based on Doctor warnings
- [Security & Approval](./security) — what Doctor checks in Tool Guard
- [Contributing](./contributing) — add a new Doctor check if something's missing
