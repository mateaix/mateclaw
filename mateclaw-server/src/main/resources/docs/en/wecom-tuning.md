# WeCom Deep Tuning

**A bot that actually works for a group of 50 internal employees needs much more than "just connecting".**

The [Channels → WeCom](./channels#wecom) section covers wiring up the channel; this document covers what MateClaw does **after** the channel is up — every non-obvious optimization, every platform corner the adapter handles, and why.

Audience:

- Operators who already have the WeCom channel running and want to understand "why is the group experience like this"
- Developers planning new features who need the platform constraints first
- Tech leads evaluating the bot for a real business team

---

## Platform in one sentence

**WeCom AI Bot is a "looks-like-a-chat-SDK, actually-an-event-callback" platform.**

It gives you three primitives:

1. **Receive events** — long-poll WebSocket or webhook delivers user @-mentions
2. **Reply** (within the same conversation) — `aibot_respond_msg` "attaches" your answer to a specific inbound frame
3. **Push proactively** (not in reply to anything) — `aibot_send_msg`, but **single chats only**

**The hidden rule that matters most**: primitive #2 and #3 behave differently in groups vs. single chats. Every optimization below is scaffolded around that matrix.

---

## Group multi-user collaboration

### Default platform behavior

When users A, B, C all @ the bot in one group, the platform delivers each as a separate frame, but all keyed to the **same chatId**.

If you naively partition conversations by chatId (the obvious approach), you get:

- Persisted history is just `user: ...` with no sender prefix — the model sees an unattributed wall when reading prior turns
- The debounce window (500ms / 2.5s adaptive) merges A's and B's rapid messages into one
- A asks "I want X", B follows with "I want Y", and the model thinks "user asked two unrelated things"

### MateClaw's fix

**Two layers**:

**1. Sender-boundary debounce.** When two messages land in the same conversation back-to-back, check senderId first:

- Same sender → merge (typical case: paste-split fragments)
- Different sender → flush the existing pending immediately, start a new window for the new sender

The decision lives in [`ChannelMessageRouter.isSameSender`](https://github.com/anthropics/mateclaw/blob/main/mateclaw-server/src/main/java/vip/mate/channel/ChannelMessageRouter.java). Null-defensive: if either senderId is missing, refuse to merge — better to flush twice than to mis-attribute one fragment.

**2. `[@sender]` prefix on persisted content + prompt.** Every group message (`chatId != null`) gets wrapped before save and before the LLM call:

```
[@XuZhanFu] @MateClawBot I want to query X
[@xuzf] @MateClawBot I want to query Y
```

So:

- The 30th historical message still tells the model who said it
- The persisted timeline reads as `[@A] ...; [@B] ...; [@A] ...`, the model can disambiguate follow-ups, quote-replies, mutual corrections
- Single chats (`chatId == null`) are zero-overhead, behavior unchanged

`senderName` takes priority over `senderId` (friendlier display); both null → return null (no `[@null]` garbage tag).

### What you'll see in logs

```
[wecom] Sender boundary in conversation wecom:{chatId}: flushing pending from sender=A, accepting new sender=B
```

In the DB, `mate_message.content` literally has `[@xxx]` prefix.

---

## Upload constraint matrix

WeCom enforces **hard size limits** at the chunk-finish step (after all bytes are uploaded). UX without a pre-check: "uploaded for three minutes, nothing came out the other side".

### Limits

| Type | Max size | Format requirement |
|------|---------|---------|
| File | **20 MB** | any |
| Image | **10 MB** | any common format |
| Video | **10 MB** | any common format |
| Voice | **2 MB** | **must be AMR** (other formats rejected by platform) |
| Global | **20 MB** | absolute ceiling |

### MateClaw's handling

**Client-side pre-check** to avoid pointless uploads. `applyWeComUploadLimits(fileSize, mediaType, contentType)` returns:

- File > 20 MB → reject, tell user "exceeds 20MB limit"
- Image > 10 MB → downgrade to file upload (still visible as attachment, just no thumbnail)
- Video > 10 MB → downgrade to file upload
- Voice > 2 MB **or** mime ≠ `audio/amr` → downgrade to file upload
- Anything > 20 MB → reject (absolute ceiling, no exception)

The downgrade carries a friendly note ("image > 10MB, sent as file attachment"), so the user knows what just happened.

### Magic-byte filename recovery

WeCom-forwarded files often arrive **without a filename field**. Saving them as `file.bin` breaks every downstream tool that dispatches by extension (PDF readers, DOCX parsers, etc).

Fix: magic-byte sniff:

- `%PDF` → `.pdf`
- `PK\x03\x04` is a ZIP container; peek inside the first few entries to distinguish `.docx` / `.xlsx` / `.pptx` / `.odt` / `.epub` / `.jar`
- Other common formats (PNG / JPEG / MP4 / MP3 / WAV) all recognized
- Truly unknown → keep `.bin`, don't pretend it's something else

Implemented in `WeComChannelAdapter.sniffMagic()` + `refineZipKind()`.

---

## Quoted messages

Users quoting a previous message (image, file, text, voice, miniprogram) and then asking a new question is **the most common group interaction pattern**.

### Supported quote types

| Quote type | What the bot sees | Further processing |
|----------|------------|------------------|
| Text | `[Quote: prior text]\nuser's new question` | ✅ text passed to model |
| Voice | `[Quote: [voice] ASR transcript]\nuser's new question` | ✅ ASR result as context |
| Image | `[Quote: [image]]\nuser's new question` + image attached part | ✅ vision sidecar reads it |
| File | `[Quote: [file: report.pdf]]\nuser's new question` + file attached part | ✅ file tool can read |
| Mixed | Each sub-type expanded by the rules above | ✅ |

### Implementation notes

- **Media is downloaded too**: a quoted image/file isn't just a marker string — it's actually downloaded, AES-256-CBC decrypted, persisted to `data/chat-uploads/{conversationId}/...`, and attached as a MessageContentPart for the agent
- **Path alignment**: the conversationId used for media must match the conversationId in `mate_conversation`, otherwise `/api/v1/chat/files/{convId}/{name}` 403s on `isConversationOwner` and the frontend `<img>` shows broken-icon

Historical bug: an early version's `inboundConversationId()` added a `wecom:group:` infix for groups, but the router persisted as `wecom:{chatId}` without the infix — every group-quoted image was broken until both sides aligned. Fixed.

---

## appmsg message types

`msgtype=appmsg` is WeCom's extension point for rich-media cards. Four common subtypes:

| Variant | What it is | Bot handling |
|------|-----------|--------------|
| `appmsg.file` | Forwarded file (PDF / Word / Excel) | Full download pipeline, equivalent to `msgtype=file` |
| `appmsg.image` | Image card | Full download pipeline, equivalent to `msgtype=image` |
| `appmsg.url` | **Public-account article / external link** | See next section |
| `appmsg.miniprogram` | Mini-program | Title surfaced to model; payload not retrievable |

Unknown subtypes fall back to `[appmsg: title]` so the model at least knows "user shared some kind of rich media".

### Public-account articles

mp.weixin.qq.com articles are served as **captcha-gated SSR** — no LLM tool can fetch the body. If the bot pretends it can read it, the model **invents content from the title** (production-observed: "the article makes three points..." — pure hallucination).

When MateClaw detects `mp.weixin.qq.com` in the link branch, it appends a directive to the model:

> (Hint: this link is a public-account article. The body needs to be opened in WeChat and pasted by the user. Please ask the user to paste the article text rather than guessing from the title.)

Effect: the model stops fabricating and asks the user to paste the body. Other normal URLs (github, wikipedia, generic external links) **don't** trigger the hint, since their bodies are fetchable by ordinary tools.

---

## Group proactive push (aibot_send_msg vs aibot_respond_msg)

### Platform rules

```
Single chat:  aibot_send_msg ✓     aibot_respond_msg ✓
Group:        aibot_send_msg ✗     aibot_respond_msg ✓ (must bind to a prior frame's reqId)
```

In groups, any proactive message from the bot (cron summaries, async-task completions, image-generation results) must **piggyback** on a prior user inbound's frameReqId. Otherwise the platform rejects it.

### MateClaw's handling

**LRU cache of recent inbound reqIds**. `lastChatReqIds: ConcurrentHashMap<chatId, latest-reqId>` is updated on every group inbound, capped at 1000 chats.

**Unified outbound `sendOutboundFrame(chatId, body)`**:

- Cache hit → `aibot_respond_msg` + cached reqId
- Cache miss → fall back to `aibot_send_msg` (single chat or new chat)

This way:

- Cron summaries → group has prior activity → respond succeeds; never any → degrade to send_msg, still fails but doesn't blanket-fail
- Async tasks (image / music / video generation) completing → `AsyncTaskMediaDispatcher` calls the unified outbound
- Multi-chunk LLM reply → same reqId reused

### What you'll see in logs

```
[wecom] Group send via aibot_respond_msg: chatId=..., reqId=...
```

---

## Async-task forwarding

Image generation (`image_generate`) / music generation (`music_generate`) / video generation (`video_generate`) / 3D model generation (`model3d_generate`) are all **async tasks** — the agent returns a task id immediately; the actual artifact arrives 30 seconds to several minutes later.

Earlier bug: artifacts only showed up in the Web console's history view, **invisible in the WeCom group**.

Fix: `AsyncTaskMediaDispatcher.forwardToImIfBound(conversationId, parts)`:

- After task completion, look up the conversation's bound channel via `ChannelSessionStore`
- Skip `web` / `webchat` (SSE already covers them)
- Call the channel adapter's `sendContentParts(targetId, parts)`
- WeCom: image / audio / video / file all supported as native attachments
- Slack: via `filesUploadV2` (see [Slack channel](./channels#slack))
- Channels without `sendContentParts` (QQ, etc.): catch UnsupportedOperationException + log; one unsupported channel doesn't block the rest

Files live at `data/chat-uploads/{conversationId}/`, served at `/api/v1/chat/files/{conversationId}/{storedName}`. Frontend and channel attachment views all read by this URL.

---

## Model behavior: faking tool calls

Observation: **qwen3.6-plus** sometimes "lazes out" in long-context, tool-call-heavy scenarios — it produces a Markdown code block that **mimics** a tool call, but `toolCallCount=0`:

````
🎵 《Title》 generation task submitted!
⏳ ETA 1-2 minutes, audio will be pushed when ready...

```json
{ "prompt": "...", "lyrics": "..." }
```
````

Backend never sees a tool_call → music generation never starts → user never receives the song.

**Current mitigation**: switch to a model that executes tool_calls reliably (kimi-for-coding, claude-sonnet-4.5, deepseek-r1). Change the agent's default model in [Models](./models).

Possible future: server-side detection of "task submitted + toolCallCount=0" patterns, with a corrective system message and retry.

---

## Model behavior: self-arguing loops

Another sporadic failure: the model gets stuck in a "thinking-output" loop, repeating the same Chinese answer dozens of times until max_tokens (16384) runs out. Production-observed pattern:

```
"Wait, I should X." → write Chinese answer → "Done." → write same answer → "Wait, Y." → same again → ...
```

Users stare at "generating..." for tens of seconds to minutes, finally receive a wall of duplicates.

### MateClaw's handling

**Two-layer guard**:

1. **Detection**: [`hasRepeatingSuffix`](https://github.com/anthropics/mateclaw/blob/main/mateclaw-server/src/main/java/vip/mate/agent/graph/NodeStreamingChatHelper.java) checks if the buffer ends with the same 24-240 character unit repeated 4+ times consecutively → immediately disposes the upstream subscription
2. **Dedup + flag**: `dedupTrailingRepeats` collapses N trailing copies to 1; ReasoningNode sets finishReason to `INCOMPLETE`; the frontend renders a truncation banner with a "regenerate" button

Why not just emit a warning: the user already saw the duplicates in the SSE stream (one-way push, can't unsend), but the **DB-persisted finalAnswer** and **WeCom outbound** both use `finalAnswer` — so the IM group only sees one clean copy of the answer + an INCOMPLETE banner.

The threshold is **deliberately narrow** (4 verbatim consecutive copies) to avoid false-positives on legitimate "TL;DR / body / TL;DR" three-stage outputs.

---

## Network resilience

### TLS / socket transient retry

DashScope / OpenAI / various LLM gateways occasionally produce on the public internet:

- `bad_record_mac` (TLS RFC 5246 §7.2.2 fatal alert 20)
- `SSLHandshakeException`
- `SocketException: Connection reset by peer`
- `Premature close` / `Broken pipe`

Previously these would surface as `LLM call failed` red text with no retry.

Fix: classify all of these as `SERVER_ERROR`, route through the existing exponential-backoff retry: 3s → 6s → 12s (with jitter) up to 5 attempts. See [Agent engine](./agents#error-recovery).

### Keepalive

Group replies via `aibot_respond_msg` have a **60-second TTL** per stream — no new data within 60s and the platform drops the slot, the eventual real reply is silently rejected.

Agents handling complex tasks (multi-tool + LLM reasoning) often exceed 60s. `WeComKeepaliveScheduler` sends a noop "processing..." heartbeat every 30 seconds; the slot never expires. A 180-second hard cap force-finishes the stream so a genuinely-stuck task doesn't keep keepalive ticking forever.

### Reconnect with exponential backoff

When the WeCom long-connection drops (NAT timeout, network blip), the adapter reconnects: 2s → 4s → 8s → 16s → 30s cap. **Never gives up** — as long as the process is alive, it'll resume message reception when the network does.

The control panel's health view shows current reconnect count, ops can read it directly.

---

## Platform-level constraints (not bugs, just limits)

These are **WeCom platform** constraints, can't be worked around in code, only in configuration:

### Data permission lock

API-mode bot ticking **any data permission** in the WeCom admin (e.g. "read messages", "get group info") **auto-restricts the bot to creator only**. Other members' messages get ignored.

**Fix**: in the admin panel, **uncheck** all 7 data permissions. The bot becomes available to all authorized members. MateClaw uses webhooks for messages, doesn't need data permissions.

### Visibility × data-permission matrix

| Visibility | Data permission | Effective |
|---------|---------|---------|
| All staff | All checked | **Creator only** (data lock overrides visibility) |
| All staff | All unchecked | All staff (recommended) |
| Specific dept | All unchecked | Members in those depts |
| Specific people | All unchecked | Listed users |

### Group requires @bot

The bot in a WeCom group must be `@`-mentioned to receive a message. Direct messages (1:1) don't need `@`. Platform behavior, no workaround. MateClaw doesn't broadcast-listen to all group messages (and couldn't if it tried).

---

## Debugging tips

### Verify group attribution

```sql
SELECT content FROM mate_message
WHERE conversation_id = 'wecom:{chatId}' AND role = 'user'
ORDER BY id DESC LIMIT 5;
```

Expect: every user message starts with `[@username]`.

### Verify media path

```bash
ls data/chat-uploads/wecom:{chatId}/
```

There **should not** be any `wecom:group:{chatId}` directories with the `group:` infix (early-bug residue, manually clean up).

### Verify group push routing

In server logs:

```
[wecom] Group send via aibot_respond_msg: chatId=..., reqId=...
```

If the group doesn't see the bot's reply but logs show this line with a non-null reqId, the message reached the platform but was rejected (usually: reqId already consumed, or bot kicked from group).

### Verify keepalive

```bash
grep "wecom-keepalive" logs/mateclaw.log | tail
```

Expect periodic "Heartbeat sent" + "Heartbeat ACK received", with occasional "force-finished stream" hard-finishes.

---

## Known corner cases

| Scenario | Current behavior | Possible future |
|------|---------|---------|
| First group message is a cron push (no prior chat activity) | Cache empty, falls back to `aibot_send_msg`, platform rejects | Ring-buffer multi-reqId cache (limited gain, not implementing) |
| Model "lazes out" in long sessions | User retries / switch model | Server-side detection + corrective inject |
| 3 different senders concurrent in same group | Serial processing, each user gets own window (works) | — |
| User refuses to paste public-account body | Bot politely guides | — |
| OOXML magic-byte misclassification (very rare) | Falls back to `.zip` | ZIP entry peek covers 90% |

---

## At-a-glance

```
                 ┌─────────────────────┐
                 │  WeCom group user    │
                 └──────────┬──────────┘
                            │ inbound (with chatId)
                            ▼
      ┌────────────────────────────────────────┐
      │  WeComChannelAdapter                    │
      │  ├─ chunk upload pre-check (4 categories)│
      │  ├─ magic-byte sniff (OOXML peek)       │
      │  ├─ AES decrypt + chat-uploads/{convId}/ │
      │  ├─ quote parsing (5 sub-types)         │
      │  ├─ appmsg parsing (4 sub-types + hint) │
      │  └─ cache lastChatReqIds[chatId]        │
      └──────────────┬─────────────────────────┘
                     │ ChannelMessage(content="[@xxx] ...")
                     ▼
      ┌────────────────────────────────────────┐
      │  ChannelMessageRouter                   │
      │  ├─ adaptive debounce (500ms / 2.5s)    │
      │  ├─ sender boundary cut (group critical) │
      │  ├─ applyGroupTag → DB + LLM            │
      │  └─ queue + sessionLock serialize       │
      └──────────────┬─────────────────────────┘
                     │
                     ▼
                ┌──────────┐
                │  Agent   │  ← StateGraph + ReAct
                └─────┬────┘
                     │ finalAnswer / tool_calls
                     ▼
      ┌────────────────────────────────────────┐
      │  sendOutboundFrame(chatId, body)        │
      │  ├─ cache hit → aibot_respond_msg       │
      │  ├─ cache miss → aibot_send_msg         │
      │  ├─ keepalive (60s TTL extend)          │
      │  └─ reconnect backoff (NAT/blip self-heal)│
      └────────────────────────────────────────┘
```

---

## Related reading

- [Channels](./channels) — overview of all 9 channels + setup
- [Agent engine](./agents) — TLS retry, error classification, self-loop detection
- [Models](./models) — switching default model, failover chain
- [Security & approval](./security) — approval flow for high-risk tools in groups
- [Doctor](./doctor) — diagnostic commands for channel troubleshooting
