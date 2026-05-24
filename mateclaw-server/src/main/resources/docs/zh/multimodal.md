# 多模态创作

语音、音乐、图像、视频——MateClaw 一开始就把它们当一等公民，不是事后贴上去的贴纸。

市面上大多数 AI 产品把多模态生成当插件处理：需要的时候装一个。MateClaw 反过来——它从第一天起就把多模态当作核心基础设施：**六个图像供应商、六个视频供应商、三个 TTS、两个 STT、两个音乐后端**，全部在同一套工具接口后面统一，Agent 调用时不用关心底下跑的是谁。

配一次，所有地方都能用。

---

## 盒子里有什么

### 图像生成 —— 六个供应商

| 供应商 | 模型家族 | 说明 |
|--------|----------|------|
| **DashScope** | 通义万相 | 阿里的图像模型，默认云端选项 |
| **OpenAI** | DALL-E 3 | 标准 DALL-E 端点 |
| **fal.ai** | Flux | 通过 fal.ai 跑 Flux，快 |
| **Google（Nano Banana）** | gemini-3-pro-image-preview、gemini-2.5-flash-image | 走原生 Gemini 路径；**支持图像编辑**——见下方 [Nano Banana](#nano-banana) |
| **智谱** | CogView | 对中文 prompt 原生支持 |
| **MiniMax** | —— | 同步异步都可以 |

图像生成工具会自动挑默认供应商，也可以在调用时强制指定某一家。异步生成返回一个 job id，Agent 轮询；图片落地后会挂到**原来的那条消息上**，不是创建一条新的。

::: tip 1.3.0 新增
DashScope 通义万相在 v1.3.0 起接入了**统一多模态生成端点**（`multimodal-generation/generation`），新增 14 个图像模型（含 6 个**支持图像编辑**的模型）。详见下方 [图像编辑](#image-edit) 一节。
:::

#### Image edit

::: tip 1.3.0 新增
图像编辑（图生图）自 v1.3.0 起支持。在 v1.2.0 及更早版本里，`image_generate` 工具只能做"文生图"。
:::

`image_generate` 工具新增 `image` / `images` 两个参数：

| 参数 | 形态 | 说明 |
|---|---|---|
| `image` | 单张参考图 | 字符串：路径 / `file://` / `data:image/...` / `http(s)://` / `msg:<id>:<idx>` |
| `images` | 多张参考图（最多 5 张） | 数组：每个元素同上 |

工具内部统一把这五种引用形式归一化为内存 buffer 后丢给 provider。**5 种引用形式**：

1. **本地路径** —— `/abs/path.png` / `~/x.png` / `./rel.png`
2. **`file://` URL** —— 绝对路径变体
3. **`data:image/png;base64,...`** —— 内联 base64 / 百分号编码
4. **`http(s)://...`** —— 带 SSRF 校验，禁止内网地址
5. **`msg:<messageId>[:<partIdx>]`** —— 引用同会话内某条消息上的图片附件，**非视觉模型也能直接用**——agent 不需要"看见"图片字节，只要在对话历史里见过这个 messageId 即可

```text
用户：（上传一张日落图，messageId=12345）把背景改成森林
Agent：image_generate(prompt="把背景改成森林",
                     image="msg:12345:0",
                     model="qwen-image-edit")
```

**支持图像编辑的模型**（DashScope 通义万相）：
- `wan2.7-image` / `wan2.7-image-pro`（**T2I + 编辑**）
- `qwen-image-edit` / `qwen-image-edit-plus` / `qwen-image-edit-max`（**纯编辑**）

在 [模型配置](./models#两个-dashscope-区别) 文档里有更全的模型清单。

#### Nano Banana

::: tip 1.4.0 新增
Google 的图像生成走 **Nano Banana Pro**（`gemini-3-pro-image-preview`），通过[原生 Gemini 路径](./models#原生-gemini)调用，不经过 OpenAI 兼容层。
:::

因为走的是原生 `generateContent` 端点，图像工具会把输入图片作为**内联 part**直接传给模型——所以 Nano Banana 不只是文生图，**还支持图像编辑**（图生图）。用法和上面的 [Image edit](#image-edit) 完全一致：传 `image` / `images` 参数引用一张或多张参考图即可。

- **Nano Banana Pro** —— `gemini-3-pro-image-preview`（默认）
- **Nano Banana** —— `gemini-2.5-flash-image`（另一个 Google 图像模型）

### 视频生成 —— 六个供应商

- **DashScope**——通义万相视频
- **Runway**——API 调 Gen-2 / Gen-3
- **MiniMax（Hailuo）**——文生视频 + 图生视频
- **Fal**——快速推理管线
- **CogVideo**——智谱 CogVideoX
- **Kling**——快手可灵视频生成

异步挂载逻辑和图像一样。视频渲染完成后直接出现在 Agent 当初说"正在处理"的那个气泡里。

### 音乐生成 —— 两个供应商

- **Google Lyria**——高质量音乐生成
- **MiniMax**——支持歌词 + 风格 prompt

音乐生成工具接收 prompt、可选风格标签、可选歌词。输出是一条 MP3 挂在消息上。

### 3D 模型生成 —— 一个供应商

- **腾讯混元 3D**——`HY-3D-3.1` / `HY-3D-3.0`（Pro，支持 PBR / 多视角 / 白模）/ `HY-3D-Express`（极速版）

文生 3D 与图生 3D 双模式，输出 `.glb`，前端 `<model-viewer>` 直接渲染可拖拽预览。完整配置步骤见 **[3D 模型生成](./model3d.md)**。

### 语音合成（TTS）—— 三个供应商

- **DashScope CosyVoice**——中英文，韵律自然
- **OpenAI TTS**——alloy、echo、fable、onyx、nova、shimmer 六种音色
- **MiniMax T2A**——中文音色，带情感标签

任何 Assistant 消息上都有一个喇叭图标，点一下就朗读出来。用哪个声音取决于你在设置里激活的 TTS 供应商。

### 语音识别（STT）—— 两个供应商

- **DashScope Paraformer**——中文优先，低延迟
- **OpenAI Whisper**——多语言行业基准

在聊天输入框按住麦克风图标讲话，松手转文本。识别结果可以在发送前再改一遍。

---

## 怎么配

所有多模态供应商都在 `设置 → 模型 → [类别]` 里。添加一次 API Key，然后把它标记为这个类别的默认。

```yaml
# application.yml —— 最小配置示例
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

如果你想让某个 Agent 总是用 Flux 出图、CosyVoice 发声，可以在 Agent 级别单独覆盖。

---

## Agent 怎么用

每一个多模态能力都是一个工具：

| 工具 | 签名 |
|------|------|
| `image_generate` | `(prompt, style?, size?)` |
| `video_generate` | `(prompt, duration?)` |
| `music_generate` | `(prompt, style?, lyrics?)` |

Agent 调用它们和调用任何其他工具一样。工具层负责供应商选择、重试、异步轮询、附件挂载。

---

## 异步生成 + 消息挂载

图像和视频生成往往比一个普通的 Agent 回合要慢。MateClaw 处理这件事的方式：

1. Agent 调用生成工具。
2. 工具立刻返回一个 job id 和占位附件。
3. 后端在后台轮询供应商。
4. 结果落地后，挂到**原来的那条 Assistant 消息上**，不是新建一条。

它工作得很干净：图片会出现在 Agent 当初说"正在处理"的那个气泡里，不是飘在一条新消息里。

---

## 产品里的哪些地方能看到

- **聊天**——拖图片进输入框给视觉模型用；按住麦克风语音输入；点任何回答上的喇叭朗读；生成的媒体直接内嵌。
- **Agents**——可以单独开启或关闭某个 Agent 的多模态工具。
- **工具页**——每个供应商都有一个测试按钮，方便在上线前验证 Key。
- **桌面端**——上面所有功能，外加本地文件系统访问用于批处理。

---

## 什么时候用什么

- **图像**——文档配图、幻灯片、概念可视化、营销素材。起步用 DashScope 或 Flux；需要精确的文字渲染就用 DALL-E 3。
- **视频**——短视频 demo、社交内容、产品动画。追求质量用 Runway，中文场景用 MiniMax，想本地云就 DashScope。
- **音乐**——背景音乐、Demo 音效、创意尝试。目前两家，后面还会扩。
- **TTS**——无障碍朗读、有声书式阅读、多语言内容。中文用 CosyVoice，英语要多样化就 OpenAI。
- **STT**——语音输入、会议转写、口述工作流。中文用 Paraformer，其他语言用 Whisper。

---

## 多模态输入：主模型不支持？走旁路

::: tip 1.3.0 新增
本页讲的是**生成（输出）**。**输入侧**的多模态——上传图片给纯文本主模型——走另一套路径：「多模态旁路」(sidecar)。详见 [聊天与消息 → 主模型不支持图片？走"多模态旁路"](./chat#主模型不支持图片走多模态旁路) 和 [模型配置 → 多模态旁路（系统级）](./models#多模态旁路-系统级)。
:::

简而言之：在「设置 → 模型 → 多模态旁路」配一个视觉模型，主模型不支持图片时系统会**自动把图片转描述**再喂给主对话模型，主模型保持便宜，路由全程在聊天 UI 可见（路由徽章 + 输入框上方提示条）。

---

## 下一步

- [聊天与消息](./chat)——附件输入、多模态旁路路由、生成的媒体如何挂载到消息上
- [模型配置](./models)——供应商配置 UI、多模态旁路设置
- [工具系统](./tools)——承载多模态生成的工具层
