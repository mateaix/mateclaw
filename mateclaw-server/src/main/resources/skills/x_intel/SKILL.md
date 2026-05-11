---
name: x_intel
description: "Read X (Twitter) posts, search, timelines and user profiles via the official xurl CLI."
nameZh: X 情报采集
nameEn: X Intel
version: 1.0.0
icon: 🐦
author: MateClaw
optional: true
tags:
  - x
  - twitter
  - social-media
  - research
  - xurl
platforms:
  - linux
  - macos
dependencies:
  commands:
    - xurl
  tools:
    - execute_shell_command
---

# x_intel — X (Twitter) information gathering

`x_intel` lets an agent pull posts, search results, timelines and user profiles from X (Twitter) through `xurl`, the X developer platform's official CLI. **This skill is read-only by design** — it intentionally omits posting, replying, deleting, DM-sending and any other write surface. For a separate publishing skill, see follow-up work.

Use this skill for:

- looking up a single post by ID or URL
- searching posts with the X search query syntax (`from:user`, `lang:en`, `#hashtag`, ...)
- reading the agent operator's home timeline, mentions, bookmarks, likes
- inspecting a user profile by handle
- walking the social graph (who someone follows / is followed by)
- raw read access to any X API v2 GET endpoint when the shortcuts don't fit

---

## Credential safety (mandatory)

Critical rules when invoked inside an agent session:

- **Never** read, print, parse, summarize, upload or quote `~/.xurl` into chat context. It is a YAML token store.
- **Never** ask the user to paste credentials/tokens into the conversation.
- **Never** suggest or run the auth commands with inline secrets in an agent session.
- **Never** pass `--verbose` / `-v` — it prints auth headers to stdout.
- The only credential-touching command this skill ever runs is `xurl auth status` (status only, no secrets).

Forbidden flags in any agent-issued command (each accepts inline secrets):
`--bearer-token`, `--consumer-key`, `--consumer-secret`, `--access-token`, `--token-secret`, `--client-id`, `--client-secret`.

App registration and the OAuth 2.0 PKCE flow must be performed by the user **outside** the agent session (see "User setup" below). Tokens persist in `~/.xurl` (YAML); OAuth 2.0 refreshes automatically.

---

## Install

The agent should verify, not install. Direct the user to install if missing.

```bash
# Shell script (Linux + macOS, installs to ~/.local/bin, no sudo)
curl -fsSL https://raw.githubusercontent.com/xdevplatform/xurl/main/install.sh | bash

# Homebrew (macOS)
brew install --cask xdevplatform/tap/xurl

# Go (cross-platform)
go install github.com/xdevplatform/xurl@latest
```

Verify:

```bash
xurl --help
xurl auth status
```

---

## User setup (user runs these, NOT the agent)

The agent must not perform these steps — they involve pasting secrets. Direct the user to this section verbatim.

1. Open the X developer dashboard: <https://developer.x.com/en/portal/dashboard>
2. In the app's User Authentication Settings, set the redirect URI to `http://localhost:8080/callback` and the app type to **Web app, automated app or bot**.
3. Copy the app's Client ID and Client Secret.
4. Register the app locally:
   ```bash
   xurl auth apps add my-app --client-id YOUR_CLIENT_ID --client-secret YOUR_CLIENT_SECRET
   ```
5. Authenticate (this opens a browser for OAuth 2.0 PKCE):
   ```bash
   xurl auth oauth2 --app my-app
   ```
   If X returns `UsernameNotFound` or a 403 on the post-OAuth `/2/users/me` lookup, pass the handle explicitly (xurl v1.1.0+):
   ```bash
   xurl auth oauth2 --app my-app YOUR_HANDLE
   ```
6. Mark this app as the default so all commands use it:
   ```bash
   xurl auth default my-app
   ```
7. Verify:
   ```bash
   xurl auth status
   xurl whoami
   ```

> **Most common mistake:** omitting `--app my-app` from `xurl auth oauth2`. The OAuth token then lands in the built-in `default` profile, which has no client-id/client-secret, and every later read fails. Re-run `xurl auth oauth2 --app my-app` and `xurl auth default my-app` to fix.

---

## Read-only command reference

All commands return JSON to stdout. The agent parses JSON directly; no extra tooling needed.

| Action | Command |
| --- | --- |
| Who is the bound account | `xurl whoami` |
| Look up a user | `xurl user @handle` |
| Read one post (ID or URL) | `xurl read POST_ID` |
| Search posts | `xurl search "QUERY" -n 10` |
| Home timeline | `xurl timeline -n 20` |
| Mentions of bound account | `xurl mentions -n 20` |
| Bookmarks list | `xurl bookmarks -n 20` |
| Likes list | `xurl likes -n 20` |
| Following list | `xurl following -n 50` |
| Followers list | `xurl followers -n 50` |
| Another user's graph | `xurl following --of HANDLE -n 20` |
| Auth status | `xurl auth status` |

Notes:

- `POST_ID` accepts a full `https://x.com/user/status/...` URL — xurl extracts the ID.
- Handles work with or without the leading `@`.

### Search query language

X's search supports operators inside the quoted query string:

```bash
xurl search "from:elonmusk -is:retweet" -n 20
xurl search "#buildinpublic lang:en since:2026-01-01" -n 25
xurl search "OR" -n 10                        # literal OR — must be quoted
xurl search "(rust OR go) lang:en" -n 10
xurl search "to:NASA -is:reply" -n 10
```

Common operators: `from:`, `to:`, `@`, `#`, `is:retweet`, `is:reply`, `is:quote`, `lang:`, `since:`, `until:`, `has:media`, `has:links`. See the X search syntax docs for the full list.

---

## Raw v2 read access

For anything beyond the shortcuts, hit any v2 GET endpoint directly:

```bash
# Public user fields
xurl /2/users/by/username/elonmusk?user.fields=public_metrics,description,verified

# Single tweet with metrics + author expansion
xurl /2/tweets/1234567890?tweet.fields=public_metrics,created_at&expansions=author_id

# Recent search with extra fields (paid tier)
xurl /2/tweets/search/recent?query=langchain&tweet.fields=created_at,public_metrics&max_results=25

# Full URLs also work
xurl https://api.x.com/2/users/me
```

Streaming endpoints are auto-detected; force with `-s` if needed. **Streaming endpoints can be expensive — do not start one without confirming intent with the user.**

---

## Common workflows

### Profile a user

```bash
xurl user @handle
xurl /2/users/by/username/handle?user.fields=public_metrics,description,verified,created_at
xurl following --of handle -n 20    # who they pay attention to
```

### Triage a trending term

```bash
xurl search "topic lang:en -is:retweet" -n 25
# Pick interesting IDs from the JSON, then drill in:
xurl read 1234567890
xurl user @ORIGINAL_POSTER
```

### Catch up on activity

```bash
xurl whoami
xurl mentions -n 20
xurl timeline -n 20
xurl bookmarks -n 10
```

### Conversation context

```bash
xurl read https://x.com/user/status/1234567890
# Conversation expansion via raw v2
xurl /2/tweets/search/recent?query=conversation_id:1234567890&max_results=25
```

---

## Output format

Every command emits X API v2 shape JSON to stdout:

```json
{
  "data": { "id": "1234567890", "text": "Hello world!" },
  "includes": { "users": [{ "id": "...", "username": "..." }] }
}
```

Errors are also JSON:

```json
{ "errors": [ { "message": "Not authorized", "code": 403 } ] }
```

The non-zero exit code distinguishes errors from empty results.

---

## Agent workflow

1. Verify prerequisites: `xurl --help` (the command exists) and `xurl auth status` (the user has at least one app with `oauth2` tokens, marked `▸` as default).
2. **Parse `auth status` output before any other command.** If the default app shows `oauth2: (none)` but a non-default app has valid tokens, instruct the user to run `xurl auth default <that-app>` — this is the most common config glitch and does not require a re-login.
3. If `auth status` shows no apps or no tokens, **stop**. Tell the user to follow the "User setup" section. Do not attempt to register apps or run any auth flow yourself.
4. Start with the cheapest read first (`xurl whoami` / `xurl user @handle` / `xurl search ... -n 3`) to confirm reachability and the request shape.
5. Treat 401 / 403 / 429 distinctly: 401 → re-auth needed, 403 → scope or plan, 429 → wait and retry (X rate-limits per-endpoint).
6. Never paste `~/.xurl` content back into the conversation, even when troubleshooting.
7. When in doubt about cost: X's API has paid tiers and per-endpoint rate limits. Do not run unbounded loops or streams without the user's explicit confirmation.

---

## Troubleshooting

| Symptom | Cause | Fix |
| --- | --- | --- |
| `auth status` shows `oauth2: (none)` on default | Token saved to built-in `default` profile (no client-id/secret) | Re-run `xurl auth oauth2 --app my-app` then `xurl auth default my-app` |
| `unauthorized_client` during OAuth | App type set to "Native App" in X dashboard | Change to "Web app, automated app or bot" |
| `UsernameNotFound` / 403 right after OAuth | X not returning username from `/2/users/me` | `xurl auth oauth2 --app my-app YOUR_HANDLE` (xurl v1.1.0+) |
| 401 on every read | Token expired or wrong default app | Check `xurl auth status` — verify `▸` points to the app with oauth2 tokens |
| `client-forbidden` / `client-not-enrolled` | X platform enrollment | Developer dashboard → Apps → Manage → Production environment |
| `CreditsDepleted` | $0 balance on X API | Buy credits in Developer Console → Billing |
| 429 on search/timeline | Hit per-endpoint rate limit | Pause, retry with smaller `-n`, or wait for the reset window |

---

## Notes

- **Cost:** X API access is paid for meaningful usage. Many failures are plan or rate-limit problems, not skill problems.
- **Scopes:** OAuth 2.0 tokens use broad scopes; a 403 on a specific read usually means the token is missing a scope — have the user re-run `xurl auth oauth2`.
- **Token refresh:** OAuth 2.0 tokens auto-refresh; nothing to do.
- **Multiple apps:** `xurl --app NAME ...` runs one read against a specific app without changing the default.
- **Token storage:** `~/.xurl` is YAML. Treat it like a private key. Never read or send it to LLM context.

---

## Attribution

- Underlying CLI: <https://github.com/xdevplatform/xurl> (X developer platform).
- This skill wraps the CLI's read commands and documents agent-side safety rules. No code is shipped beyond this SKILL.md.
