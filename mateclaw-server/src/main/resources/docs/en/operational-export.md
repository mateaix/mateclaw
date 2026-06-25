# Operational Data Export

Export a cross-cutting operational report as Excel (`.xlsx` packaged as `.zip`) in one shot, for ops, audit, and reconciliation offline. Two entry points: a **Dashboard one-click** export (GUI) and a **command-line** export (no UI, works via `docker exec`).

> **Global admin only.** The report contains sensitive cross-workspace data — conversation contents, token usage, audit records, etc.

## The 9 sheets

| # | Sheet | Contents |
|---|---|---|
| 1 | Overview | Interval KPIs, system snapshot, 7-day trend, period comparison, model details, top-10 agent activity |
| 2 | Token Usage | Daily × `runtime_provider` breakdown with avg tokens/conversation |
| 3 | Skill Stats | Skill list + usage count, last-call time, bound agents |
| 4 | User Stats | Per-(workspace, user) aggregated tokens, duration, last active |
| 5 | User Conversations | Detail rows pairing user–assistant messages |
| 6 | Security & Audit | Unified view across 6 sources (guard rules, audit logs, approvals, grants, config, business audit events) |
| 7 | Channel Stats | Per-channel conversation count, tokens, unique users |
| 8 | Model Config | Enabled + API-key-configured models with parameters |
| 9 | Cron Jobs | Execution records with duration and token usage |

## Entry 1: Dashboard one-click

1. Open the **Dashboard** and click **"Export operational data"** in the top-right (next to the database chip) — visible to global admins only.
2. In the dialog pick a **date range** — use the quick "Last 7 / 30 / 90 days" presets or a custom range; **90-day max**, no future dates.
3. Click **"Generate report"** — a circular progress ring shows the 9 steps (Overview → … → Cron Jobs).
4. When done, "Report is ready" appears; click **"Download"** to get `ops_data_<start>_<end>.zip`.

**Security & lifecycle:**

- The generate / progress / download endpoints are all `@RequireGlobalAdmin` gated — a non-admin call returns 403.
- Only one generation runs at a time (concurrent calls get 409 busy), with a 5-minute frontend deadline.
- The download token is **atomically single-use** — a second download with the same token returns 410.
- The generated file auto-cleans **after 24h or on download**.

## Entry 2: Command line

For large, no-timeout, scripted, `docker exec` scenarios. A project-level CLI framework was added; the export command is `--cli.command=export`:

```bash
# local jar
java -jar app.jar --cli.command=export \
  --cli.start=2026-01-01 --cli.end=2026-06-30 > report.zip

# inside a container
docker exec <container> java -jar /app/app.jar --cli.command=export \
  --cli.start=2026-01-01 --cli.end=2026-06-30 > report.zip

# dry run (no actual generation)
java -jar app.jar --cli.command=export --cli.start=... --cli.end=... --cli.dry-run

# list all commands
java -jar app.jar --cli.command=help
```

| Option | Required | Meaning |
|---|---|---|
| `--cli.command=export` | yes | run the export command |
| `--cli.start=YYYY-MM-DD` | yes | start date (inclusive) |
| `--cli.end=YYYY-MM-DD` | yes | end date (inclusive) |
| `--cli.dry-run` | no | dry-run, no actual generation |

Key points:

- The ZIP bytes go straight to **stdout** (redirect with `> report.zip`); diagnostics go to **stderr**, so the redirect captures a clean binary.
- The backend entry point has **no 90-day cap and no timeout** — suitable for large offline ranges.
- The CLI is operator-only (local / `docker exec`), **never over HTTP**, and does not bypass the admin gate.
- On a normal web start (no `--cli.command`) the CLI stays inert and does not affect startup.

## Notes

- 19-digit Snowflake IDs are written to Excel as **text** to avoid the spreadsheet's numeric precision (2^53) truncating them or showing scientific notation.
- The report grows with your data; when exporting a large range from the CLI, redirect straight to a file rather than piping to another program.

## See also

- [Backstage Runtime Console](./backstage) — see live agents / sub-agents
- [Security & Approval](./security) — Tool Guard and audit logs (one source of Sheet 6)
