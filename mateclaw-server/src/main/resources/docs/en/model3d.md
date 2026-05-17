# 3D Model Generation

Text-to-3D and image-to-3D in one tool call. Configure the credentials once and the agent can call `model3d_generate` to produce a `.glb` model that lands directly in the chat bubble — interactive preview, drag to rotate.

---

## What's in the box

| Aspect | Detail |
|---|---|
| **Current provider** | Tencent Hunyuan 3D (`ai3d.tencentcloudapi.com`, region `ap-guangzhou`) |
| **Available models** | `HY-3D-3.1` / `HY-3D-3.0` / `HY-3D-Express` |
| **Output format** | `.glb` (binary GLTF, single file with embedded textures, rendered inline by `<model-viewer>`) |
| **Latency** | 1–3 minutes (Pro slower, Rapid faster) |
| **Auth scheme** | TC3-HMAC-SHA256 (SecretId + SecretKey) |

Routing is automatic based on the `model` argument:

| Model | Backend Action | Notes |
|---|---|---|
| **HY-3D-3.1** (default) | `SubmitHunyuanTo3DProJob` | Highest quality. Supports PBR materials, multi-view input, white-model (`GenerateType=Geometry`) |
| **HY-3D-3.0** | same | Older Pro variant, shares the call site |
| **HY-3D-Express** | `SubmitHunyuanTo3DRapidJob` | Fastest. Accepts only `Prompt` or `ImageUrl` |

---

## 1. Get Tencent Cloud credentials

The Hunyuan 3D API requires traditional CAM credentials (**not** the OpenAI-style `sk-xxx` Bearer keys). You need a SecretId + SecretKey pair.

1. Open **[Cloud Access Management → API Keys](https://console.cloud.tencent.com/cam/capi)**
2. Click **Create Key**. Tencent gives you both:
   - `SecretId` (starts with `AKID`, ~36 chars)
   - `SecretKey` (~32 chars)
3. **Save both** — the SecretKey is shown only once and cannot be retrieved later.

::: tip About `sk-xxx` keys from "API Key 管理"
The Tencent console has another page called "API Key Management" that issues single `sk-` prefixed Bearer tokens. **Those are scoped to TokenHub** (`tokenhub.tencentmaas.com`) for OpenAI-compatible chat completions and **cannot be used for Hunyuan 3D**. 3D requires the SecretId + SecretKey pair from CAM above.
:::

## 2. Activate the Hunyuan 3D service

Open **[Hunyuan 3D Console](https://console.cloud.tencent.com/ai3d)**. The first visit asks you to accept the service agreement / activate the free tier.

Skipping this step results in:

```
[Hunyuan3D] SubmitHunyuanTo3DProJob failed: ResourceInsufficient
```

Some variants (notably `HY-3D-3.1`) may require a separate quota application or paid plan — check the console's quota dashboard.

## 3. Configure credentials in MateClaw

1. Open the **Models & Credentials** page and locate the **Tencent Hunyuan 3D** card (auto-registered by the V71 migration).
2. Click **Update** / **Configure**.
3. Paste the API Key as **`SecretId:SecretKey`** (single colon, no spaces):
   ```
   AKIDxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxx:abcdefghijklmnopqrstuvwxyz123456
   ```
4. Leave **Base URL** at the default `https://ai3d.tencentcloudapi.com` (the system handles regional routing automatically).
5. Save.

::: warning Single-input compromise
The provider card currently exposes a single API Key field. A future improvement will split this into separate SecretId / SecretKey inputs that auto-join with `:` on save.
:::

## 4. Enable the 3D feature

Go to **Settings → 3D Generation**:

- **Enable 3D model generation**: on
- **Preferred 3D provider**: pick `Tencent Hunyuan 3D`
- **Provider fallback**: leave on (only one provider exists today, but the toggle is forward-looking)

Click **Save System Settings**.

## 5. Try it in chat

Speak naturally — the agent picks `model3d_generate` automatically:

```
Generate a 3D model: a cute cartoon dinosaur, green, with big round eyes
```

```
Quickly generate a 3D model of a red apple    ← LLM selects HY-3D-Express
```

```
Generate a 3D model from this image: https://example.com/foo.png    ← image-to-3D
```

```
Generate a white-model 3D (no textures): a mechanical gear    ← Geometry mode
```

Expected flow:

1. **Tool returns immediately** (milliseconds) with a `taskId=xxx`.
2. **Backend worker polls Tencent every 8 seconds** asynchronously.
3. **1–3 minutes later** the `async_task_completed` SSE event lands in the conversation.
4. **`<model-viewer>` renders the `.glb`** inline — drag, rotate, zoom.

---

## Tool parameters (`model3d_generate`)

| Param | Type | Required | Description |
|---|---|---|---|
| `prompt` | String | Yes\* | Text description, up to 1024 UTF-8 chars |
| `imageUrl` | String | Yes\* | Reference image URL (image-to-3D mode) |
| `model` | String | No | `HY-3D-3.1` (default) / `HY-3D-3.0` / `HY-3D-Express` |
| `enableTexture` | Boolean | No | `true` (default) / `false` (white-model, Pro only) |
| `enablePbr` | Boolean | No | `true` enables PBR materials (richer rendering, Pro only, default `false`) |

\* Either `prompt` or `imageUrl` is required (XOR — Pro doesn't accept both, except in Sketch mode which is not exposed yet).

---

## Troubleshooting

### 1. `3D 模型生成功能未启用，请在系统设置中开启` / "3D generation is not enabled"

→ The feature toggle is off. Go to **Settings → 3D Generation** and enable it.

### 2. `Provider api_key must be "SecretId:SecretKey" (colon-joined)`

→ Wrong credential format. You probably saved one of:
- A single SecretId (no SecretKey appended)
- A single SecretKey
- A single `sk-xxx` token (that's a TokenHub key, not for 3D)
- Used a space instead of `:`

Correct: `AKIDxxxx...:zzzz...` — exactly one ASCII colon, no whitespace.

### 3. `ResourceInsufficient` (资源不足)

→ Tencent-side business error. Possible causes:
- Hunyuan 3D service not activated yet
- Free quota exhausted
- The selected model (especially `HY-3D-3.1`) requires approval / a paid plan

Check the [Hunyuan 3D Console](https://console.cloud.tencent.com/ai3d) for quota status.

### 4. `invalid params, first_frame_image`

→ Tencent couldn't fetch your `imageUrl`. The URL must be reachable from the public internet — `localhost`, internal IPs, and signed URLs that have expired won't work. Confirm:
- The URL opens directly in an incognito browser window
- File type is `jpg/png/jpeg/webp`, resolution 128–5000px per side, ≤8MB

### 5. Task hangs for 15 minutes

→ The worker times out at 15 min by default. Check backend logs:

```bash
grep '\[Hunyuan3D\]\|\[Model3dGen\]' logs/mateclaw.log | tail -10
```

If polling is stuck on `RUN`/`WAIT`, restart the backend (in-flight tasks get marked failed on startup).

### 6. Generation succeeds but the bubble shows only a download link, no interactive preview

→ Tencent returned an OBJ bundle (.zip with OBJ + textures + MTL) instead of a single GLB. Our code prefers GLB (`pickBestResultFile`), but if Tencent only returns OBJ for that request, the frontend falls back to a download link. **Defaulting to `HY-3D-3.1` usually yields a GLB.**

---

## Architecture (one-pager)

```
[ user ] ── natural language ─▶ [ agent ] ─▶ model3d_generate
                                                │
                                  (route on model field)
                                                │
                ┌───────────────────────────────┴──────────────┐
                ▼                                              ▼
        SubmitHunyuanTo3DProJob               SubmitHunyuanTo3DRapidJob
        (HY-3D-3.1 / HY-3D-3.0)                (HY-3D-Express)
                │                                              │
                └──────────────── ai3d.tencentcloudapi.com ────┘
                                                │
                                  returns JobId (24-h URL)
                                                │
            AsyncTaskService polls Query{Pro,Rapid}HunyuanTo3DJob every 8 s
                                                │
                              status → DONE? ─▶ ResultFile3Ds[]
                                                │
                                    pick best: GLB > FBX > OBJ
                                                │
                                    download to data/chat-uploads/
                                                │
                                    write mate_message (type=model3d)
                                                │
                            broadcast SSE async_task_completed
                                                │
                ▼
        frontend useChat detects modelUrl ─▶ MessageBubble bridges virtual attachment
                                                │
                                    <model-viewer> renders the .glb
```

---

## Log markers for a successful run

```
[ToolExecutor] Executing tool: model3d_generate
[Hunyuan3D] SubmitHunyuanTo3DProJob submitted job: 1441791994... (model=HY-3D-3.1)
[AsyncTask] Created task d73723f14c7c4167 (providerTaskId=pro:1441791994...)
[AsyncTask] Started polling for task d73723f14c7c4167 (interval=8s, timeout=15min)
[ToolExecutor] Tool model3d_generate returned 80 chars
…1–3 minutes…
[Model3dDownloader] Downloading 3D model from https://hunyuan-prod-….cos.../...glb to data/chat-uploads/.../model_d73723f14c7c4167.glb
[Model3dDownloader] Downloaded NNNN bytes
[Model3dGen] Task d73723f14c7c4167 completed, model saved: /api/v1/chat/files/.../model_d73723f14c7c4167.glb
```

---

## See also

- [Multimodal Overview](./multimodal.md)
- [Models & Credentials](./models.md)
- [Tools System](./tools.md)
- Design RFC: `rfcs/202605/01-generative-async-pipeline.md`
