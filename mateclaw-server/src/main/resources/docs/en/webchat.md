# Web / API Access (WebChat) Guide

MateClaw's WebChat channel lets external websites reach the conversation engine over plain HTTP / SSE, with no JWT. Visitor identity is isolated under a shared API Key via `visitorId + visitorToken` (HMAC-signed).

There are two integration paths:

- **Embeddable widget** — drop in one JS file, call `init(...)` once, and a chat bubble appears in the corner. Fastest to ship; ideal for marketing sites / landing-page support.
- **Custom HTTP / SSE integration** — call the REST + SSE endpoints below and render your own UI. For deeply customized experiences.

## Embeddable widget (mateclaw-webchat)

The widget is a zero-dependency browser library shipped in both UMD (`<script>` tag) and ESM (npm) formats.

**Option 1: script tag (UMD)**

```html
<script src="https://<your-deployment>/mateclaw-webchat.umd.js"></script>
<script>
  MateClawWebChat.init({
    apiKey: 'your-channel-api-key',   // from the channel edit page
    server: 'https://<your-deployment>',
    title: 'Support',
    placeholder: 'Type a message...'
  })
</script>
```

**Option 2: npm (ESM)**

```bash
npm install @mateclaw/webchat
```

```ts
import { init } from '@mateclaw/webchat'

init({ apiKey: 'your-channel-api-key', server: 'https://<your-deployment>' })
```

**Config options**

| Field | Required | Default | Notes |
|---|---|---|---|
| `apiKey` | yes | — | Channel API Key |
| `server` | yes | — | MateClaw server URL (no trailing slash) |
| `position` | no | `bottom-right` | Bubble position: `bottom-right` / `bottom-left` |
| `primaryColor` | no | `#D97757` | Primary color (any CSS color) |
| `title` | no | `MateClaw` | Panel title |
| `placeholder` | no | `Type a message...` | Input placeholder |

**Behavior**

- The visitor ID is generated on first open and persisted in `localStorage` (key `mc-webchat-visitor`), then reused — you don't manage it yourself.
- The panel is themed entirely through CSS variables (`--mc-primary` / `--mc-bg-elevated` / ...); the host page can override them under `:root`.
- The widget consumes the `/stream` SSE protocol described below. For richer interactions (session list, attachments, revocation), call the HTTP endpoints directly and build your own UI.

## Custom integration: basics

- **Base URL**: `https://<your-MateClaw-deployment>/api/v1/channels/webchat`
- **Auth**: every endpoint requires the header `X-MC-Key: <API Key>` (from the channel edit page).
- **Session-management endpoints** additionally require `X-MC-Visitor-Token: <HMAC>` (issued by the server and returned on the first `/stream` call).
- **Response envelope**: `R<T>` → `{"code": 200, "msg": "...", "data": T}`; anything other than 200 is an error.
- **Charset**: UTF-8. The SSE stream uses `text/event-stream; charset=UTF-8`.

## Endpoint list

| Method | Path | Auth | Purpose |
|---|---|---|---|
| POST | `/stream` | API Key | SSE streaming chat (issues visitorToken) |
| GET | `/config` | API Key | Get channel config (title/placeholder/...) |
| POST | `/sessions` | API Key | Explicitly create an empty session thread |
| GET | `/sessions` | + visitorToken | List sessions (excludes archived by default) |
| GET | `/sessions/page` | + visitorToken | Paginated + keyword search |
| PUT | `/sessions/title` | + visitorToken | Rename |
| PUT | `/sessions/pinned` | + visitorToken | Pin / unpin |
| PUT | `/sessions/archive` | + visitorToken | Archive / unarchive |
| DELETE | `/sessions` | + visitorToken | Delete |
| POST | `/sessions/stop` | + visitorToken | Stop an in-flight stream |
| POST | `/sessions/regenerate` | + visitorToken | Regenerate the last assistant reply |
| GET | `/sessions/messages` | + visitorToken | Message list (paginated) |
| POST | `/upload` | + visitorToken | Upload an attachment (returns fileId) |
| GET | `/files` | + visitorToken | Download a file (uploaded or agent-generated) |

Admin-level (require a MateClaw JWT, outside the permitAll set above):

| Method | Path | Purpose |
|---|---|---|
| POST | `/api/v1/admin/webchat/revoked-visitor` | Revoke a visitor's management token |
| DELETE | `/api/v1/admin/webchat/revoked-visitor` | Un-revoke |

> In the admin console, the "Conversations" list hides WebChat visitor sessions from regular admins by default — only a global admin sees them. This is a cross-workspace isolation and visitor-privacy guard.

## Auth flow

```text
┌──────────┐  POST /stream {visitorId:"v1", message:"hi"}
│  Client  │ ─────────────────────────────────────────────► ┌──────────┐
└──────────┘                                                  │ MateClaw │
   ▲                                                          └──────────┘
   │  SSE meta event: {sessionId, conversationId, visitorToken}
   │  SSE content_delta events: {text}
   │  SSE done event
   └─────────────────────────────────────────────────────────
                                                              │
┌──────────┐  GET /sessions X-MC-Visitor-Token: <visitorToken>│
│  Client  │ ─────────────────────────────────────────────► │
└──────────┘ ◄──── 200 {code:200, data:[...]}                │
```

`visitorToken` is valid for 7 days by default; re-issue it through any `/stream` call once it expires. Every `/stream` call (even with a still-valid token) returns a fresh token in the meta event — the client should keep updating its stored copy.

## Error codes

| HTTP | When |
|---|---|
| 400 | Invalid parameter (visitorId / sessionId charset, title length, etc.) |
| 401 | Invalid API Key / missing, expired, or revoked visitorToken |
| 404 | The given sessionId does not exist or does not belong to the visitor |
| 409 | More than 5 inactive empty sessions |

The error message is in `R.msg` and can be shown directly to the user.

## SSE event protocol

`/stream` and `/sessions/regenerate` return `text/event-stream`:

```
event: meta
data: {"sessionId":"s1","conversationId":"webchat:abc123:v1:s1","visitorToken":"xxx.yyy"}

event: phase
data: {"phase":"planning","timestamp":1716700000000}

event: tool_start
data: {"tool":"web_search"}

event: tool_end
data: {"tool":"web_search","success":true}

event: plan
data: {"steps":["search the web","summarize"]}

event: content_delta
data: {"text":"He"}

event: content_delta
data: {"text":"llo"}

event: thinking_delta
data: {"text":"..."}    (optional, reasoning trace)

event: done
data: {"status":"completed"}

event: error
data: {"message":"..."}  (on failure)
```

> The SSE spec requires clients to ignore unknown event types. The server may emit internal events prefixed with an underscore (e.g. `_usage_final`); these carry no contract for visitors and can be safely ignored.

### Optional real-time progress events

`phase` / `tool_start` / `tool_end` / `plan` are **optional** events — used to show an "AI is typing…" bubble, tool-execution badges ("Searching…"), or a Plan-and-Execute step checklist in your SDK. The SDK can ignore them all and still render the full reply from `content_delta` alone.

| Event | Triggered when | Data fields |
|---|---|---|
| `phase` | the agent enters a new execution phase (planning / generating / summarizing / ...) | `phase`, `timestamp` |
| `tool_start` | the agent calls a tool | `tool` (tool name) |
| `tool_end` | a tool call finishes | `tool`, `success` |
| `plan` | a Plan-and-Execute agent breaks work into steps | `steps` (string array) |

**Note**: `tool_start` / `tool_end` carry **only the tool name**, never the call arguments or results — agent tool calls may involve PII (file paths, user queries, credentials), which would leak if forwarded to a third-party website frontend. The SDK should map tool names to localized labels (`web_search` → "Searching…").

## File upload / download

1. `POST /upload` (multipart): returns `{fileId, fileName, contentType, size}`.
2. Add the fileId to the `attachmentIds` array in the body of the next `/stream` call. Unknown / expired / foreign fileIds are silently dropped (only the text part is sent, no error).
3. The agent reads server-side files directly; the `fileUrl` in a message is a relative download path (`/api/v1/channels/webchat/files?storedName=...`) — the client appends auth headers to download.
4. Agent-generated files (PDF/DOCX/...) appear in assistant replies as `/api/v1/files/generated/<uuid>` URLs, downloadable **without auth**, with a 7-day TTL.

## Session lifecycle: pin / archive / delete

- **Pin** (`PUT /sessions/pinned`): sorted first in the `/sessions` list.
- **Archive** (`PUT /sessions/archive`): a soft close — the thread stays in the DB (history queryable, addressable by sessionId, files downloadable) but is hidden from `/sessions` by default (pass `includeArchived=true` to return it), and no longer counts against the "≤ 5 inactive empty sessions" quota.
- **Delete** (`DELETE /sessions`): permanent, unrecoverable.

Each session returned by `/sessions` includes: `sessionId`, `title`, `lastActiveTime`, `messageCount`, `pinned`, `archived`, `streamStatus` (`running` / `idle`).

## visitorToken revocation (admin)

A visitor abusing the channel? An admin calls:

```bash
curl -X POST https://mate.example.com/api/v1/admin/webchat/revoked-visitor \
  -H "Authorization: Bearer <admin JWT>" \
  -H "Content-Type: application/json" \
  -d '{"channelId":123, "visitorId":"v1", "reason":"abuse"}'
```

After revocation, all of that visitor's management endpoints return 401 (`/stream` is unaffected and can re-issue a fresh token). Revocation state is briefly cached, so under a multi-instance deployment it takes up to ~10 minutes to fully propagate. Un-revoke via `DELETE` on the same endpoint.

## curl examples

**Step 1: send the first message**

```bash
curl -N -X POST https://mate.example.com/api/v1/channels/webchat/stream \
  -H "X-MC-Key: your-api-key" \
  -H "Content-Type: application/json" \
  -d '{"visitorId":"v1","message":"hi"}'
```

Save the `visitorToken` and `sessionId` from the meta event.

**Step 2: list sessions**

```bash
curl https://mate.example.com/api/v1/channels/webchat/sessions?visitorId=v1 \
  -H "X-MC-Key: your-api-key" \
  -H "X-MC-Visitor-Token: <from step 1>"
```

**Step 3: upload an attachment and send**

```bash
# upload
curl -X POST https://mate.example.com/api/v1/channels/webchat/upload \
  -H "X-MC-Key: your-api-key" \
  -H "X-MC-Visitor-Token: <token>" \
  -F "visitorId=v1" \
  -F "file=@report.pdf"
# returns {"fileId":"abc-uuid", ...}

# send a message with the attachment
curl -N -X POST https://mate.example.com/api/v1/channels/webchat/stream \
  -H "X-MC-Key: your-api-key" \
  -H "Content-Type: application/json" \
  -d '{"visitorId":"v1","sessionId":"<sid>","message":"take a look at this report","attachmentIds":["abc-uuid"]}'
```

## Limits

- Inactive empty sessions per visitor ≤ 5 (creation is rejected past 5 — send a message or delete an old session first)
- Upload: single file ≤ configured cap, extension + MIME dual whitelist; ≤ 50 files / 200 MB per session (configurable)
- visitorToken expires in 7 days; agent-generated file URLs have a 7-day TTL
- Currently a single-instance deployment (staging registry + streamTracker are both in-memory). Multi-instance support is on the roadmap.

## Related

- Upstream epic issue: https://github.com/mateaix/mateclaw/issues/355
