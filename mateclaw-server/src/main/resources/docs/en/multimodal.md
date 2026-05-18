# Multimodal

Speech, music, images, video — all first-class in MateClaw, not tacked on.

Most AI products treat multimodal generation as a plugin you bolt on later. MateClaw ships with it as core infrastructure: **six image providers, four video providers, three TTS backends, three STT backends, and two music providers**, all unified behind a single tool interface so agents can call any of them without knowing which vendor is underneath.

Configure once. Use everywhere.

---

## What's in the box

### Image generation — six providers

| Provider | Model family | Notes |
|----------|--------------|-------|
| **DashScope** | Wanxiang | Alibaba's image model, default cloud option |
| **OpenAI** | DALL-E 3 | Standard DALL-E endpoint |
| **fal.ai** | Flux | Fast Flux inference via fal.ai |
| **Google Imagen** | Imagen 3 | Google Cloud credentials required |
| **Zhipu** | CogView | Native Chinese prompt support |
| **MiniMax** | — | Sync and async both supported |

The image-generation tool auto-picks the provider configured as default, or you can force a specific one per call. Async generation returns a job id the agent polls; when the image lands, it attaches to the **original assistant message**, not a new one.

::: tip New in 1.3.0
DashScope Wanxiang plugged into the **unified multimodal-generation endpoint** (`multimodal-generation/generation`) in v1.3.0, adding 14 image models — 6 of which **support image editing**. See [Image edit](#image-edit) below.
:::

#### Image edit

::: tip New in 1.3.0
Image editing (image-to-image) is supported from v1.3.0. In v1.2.0 and earlier, `image_generate` was text-to-image only.
:::

The `image_generate` tool gains two parameters: `image` and `images`:

| Parameter | Shape | Description |
|---|---|---|
| `image` | Single reference image | String: path / `file://` / `data:image/...` / `http(s)://` / `msg:<id>:<idx>` |
| `images` | Multiple reference images (up to 5) | Array of the same forms |

The tool normalizes all five reference forms into in-memory buffers internally before forwarding to the provider. **Five reference forms**:

1. **Local path** — `/abs/path.png` / `~/x.png` / `./rel.png`
2. **`file://` URL** — absolute-path variant
3. **`data:image/png;base64,...`** — inline base64 / percent-encoded body
4. **`http(s)://...`** — with SSRF guard (rejects internal hosts)
5. **`msg:<messageId>[:<partIdx>]`** — references an image attachment from a message in the same conversation. **Works for non-vision models too** — the agent doesn't need to "see" the bytes; merely having seen the messageId in conversation history is enough

```text
User: (uploads a sunset image, messageId=12345) Replace the background with a forest.
Agent: image_generate(prompt="replace background with forest",
                     image="msg:12345:0",
                     model="qwen-image-edit")
```

**Models that support image editing** (DashScope Wanxiang):
- `wan2.7-image` / `wan2.7-image-pro` (**T2I + edit**)
- `qwen-image-edit` / `qwen-image-edit-plus` / `qwen-image-edit-max` (**edit-only**)

A fuller model catalog lives in [Models](./models#two-dashscope-variants).

### Video generation — six providers

- **DashScope** — Tongyi Wanxiang video
- **Runway** — Gen-2 / Gen-3 via API
- **MiniMax (Hailuo)** — text-to-video and image-to-video
- **Fal** — fast inference pipeline
- **CogVideo** — Zhipu CogVideoX
- **Kling** — Kuaishou Kling video generation

Same async-attach model as image generation. Videos appear inline in the chat once rendering finishes — in the same bubble where the agent first said "working on it".

### Music generation — two providers

- **Google Lyria** — high-quality music generation
- **MiniMax** — music generation with lyrics + style prompts

The music-generation tool takes a prompt, an optional style tag, and optional lyrics. Output is an MP3 attached to the message.

### 3D model generation — one provider

- **Tencent Hunyuan 3D** — `HY-3D-3.1` / `HY-3D-3.0` (Pro, supports PBR / multi-view / white-model) / `HY-3D-Express` (rapid)

Text-to-3D and image-to-3D both work; output is a `.glb` rendered inline by `<model-viewer>` for drag-to-rotate preview. Full setup walkthrough: **[3D Model Generation](./model3d.md)**.

### Text-to-speech (TTS) — three providers

- **DashScope CosyVoice** — Chinese + English, natural prosody
- **OpenAI TTS** — alloy, echo, fable, onyx, nova, shimmer
- **MiniMax T2A** — Chinese voices with emotion tags

Click the speaker icon on any assistant message to read it aloud. The voice is whichever TTS provider is active in Settings.

### Speech-to-text (STT) — two providers

- **DashScope Paraformer** — Chinese-first, low latency
- **OpenAI Whisper** — the standard multilingual benchmark

Hold the mic button in the chat input to speak. Release to transcribe. Edit the result before sending if you want to.

---

## Configuration

All multimodal providers live under `Settings → Models → [category]`. Add a provider once with its API key, then mark it as default for its category.

```yaml
# application.yml — minimal example
mate:
  image:
    default-provider: dashscope
  video:
    default-provider: dashscope
  tts:
    default-provider: cosyvoice
  stt:
    default-provider: paraformer
  music:
    default-provider: dashscope
```

Per-agent overrides are available if you want a specific agent to always use, say, Flux for images and CosyVoice for voice.

---

## How agents use it

Every multimodal capability is exposed as a tool:

| Tool | Signature |
|------|-----------|
| `image_generate` | `(prompt, style?, size?)` |
| `image_edit` | `(image_id, prompt)` — where the provider supports it |
| `video_generate` | `(prompt, duration?)` |
| `video_from_image` | `(image_id, prompt)` |
| `music_generate` | `(prompt, style?, lyrics?)` |
| `tts_synthesize` | `(text, voice?)` |
| `stt_transcribe` | `(audio_id, language?)` |

Agents call them exactly like any other tool. The tool layer handles provider selection, retries, async polling, and attachment binding.

---

## Async generation and message binding

Image and video generation often takes longer than a normal agent turn. MateClaw handles this cleanly:

1. Agent calls the generate tool.
2. Tool returns immediately with a job id and a placeholder attachment.
3. Backend polls the provider in the background.
4. When the result lands, it's attached to the **original assistant message** — not a new one.

It works the way you'd expect: the image appears inside the same bubble where the agent first said "working on it" — not floating in a new message.

---

## Where it shows up in the product

- **Chat** — drag an image into the input for vision models; press-and-hold the mic to dictate; click the speaker on any response to read aloud; generated media appears inline.
- **Agents** — enable or disable specific multimodal tools per agent.
- **Tools page** — every provider has a test button so you can verify a key before using it in production.
- **Desktop app** — everything above, plus local filesystem access for batch operations.

---

## When to use what

- **Image** — documentation illustrations, slide graphics, concept visualization, marketing. Start with DashScope or Flux; DALL-E 3 when you need tight text rendering.
- **Video** — short-form demos, social content, product animations. Runway for quality, MiniMax for Chinese scenarios, DashScope for cloud-local.
- **Music** — background tracks, demo jingles, creative exploration. Two providers today; expect the surface to evolve.
- **TTS** — accessibility, audiobook-style reading, multilingual content. CosyVoice for Chinese, OpenAI for English variety.
- **STT** — voice-first input, meeting transcription, dictation workflows. Paraformer for Chinese, Whisper for everything else.

---

## Multimodal input: primary doesn't speak it? Use a sidecar

::: tip Added in 1.3.0
This page is about **generation (output)**. The **input** side — uploading an image to a text-only primary model — runs through a separate "multimodal sidecar" path. See [Chat → Primary model can't see images?](./chat#primary-model-cant-see-images-multimodal-sidecar-routing) and [Models → Multimodal sidecar (system-wide)](./models#multimodal-sidecar-system-wide).
:::

In short: configure a vision model under **Settings → Models → Multimodal sidecar**. When the primary model can't handle an uploaded image, the runtime captions it via the sidecar first and feeds the description to the primary chat. Primary stays cheap; the routing decision is fully visible in the chat UI (badge on the bubble, hint above the input box).

---

## Next

- [Chat & Messaging](./chat) — attachment input, multimodal sidecar routing, how generated media attaches to messages
- [Models](./models) — provider configuration UI, multimodal sidecar settings
- [Tools](./tools) — the tool system that hosts multimodal generation
