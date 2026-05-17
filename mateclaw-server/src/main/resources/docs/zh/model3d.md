# 3D 模型生成

文生 3D / 图生 3D 一键完成。配一次凭据，Agent 就可以通过 `model3d_generate` 工具生成 `.glb` 模型，结果直接挂回到对话气泡里，可拖拽旋转预览。

---

## 现状

| 维度 | 说明 |
|---|---|
| **当前 Provider** | 腾讯混元 3D（`ai3d.tencentcloudapi.com`，区域 `ap-guangzhou`）|
| **可用模型** | `HY-3D-3.1` / `HY-3D-3.0` / `HY-3D-Express` |
| **输出格式** | `.glb`（GLTF 二进制，单文件含贴图，前端 `<model-viewer>` 直接渲染）|
| **生成耗时** | 1-3 分钟（Pro 慢 / Rapid 快）|
| **鉴权方式** | TC3-HMAC-SHA256（SecretId + SecretKey）|

模型路由策略（自动按 model 字段决定接口）：

| 模型 | 调用 Action | 特性 |
|---|---|---|
| **HY-3D-3.1**（默认）| `SubmitHunyuanTo3DProJob` | 最高精度。支持 PBR 材质、多视角输入（多张图）、白模（GenerateType=Geometry）|
| **HY-3D-3.0** | 同上 | Pro 老一代，与 3.1 共享调用 |
| **HY-3D-Express** | `SubmitHunyuanTo3DRapidJob` | 极速版，仅支持 `Prompt` 或 `ImageUrl`，速度最快 |

---

## 一、获取腾讯云凭据

混元 3D 接口走传统 CAM 鉴权（**不是 OpenAI 风格的 sk-xxx Bearer Key**），需要 SecretId + SecretKey 一对。

1. 登录腾讯云控制台，打开 **[访问管理 → 访问密钥 → API 密钥管理](https://console.cloud.tencent.com/cam/capi)**
2. 点「新建密钥」，腾讯云一次性给出：
   - `SecretId`（`AKID` 开头，约 36 字符）
   - `SecretKey`（约 32 字符）
3. **务必两个都保存好** —— SecretKey 关闭页面后无法再次查看。

::: tip 关于「API Key 管理」页面的 sk-xxx
腾讯云控制台还有另一个「API Key 管理」页面，给的是单个 `sk-` 前缀的 Bearer token。**那个 key 是 TokenHub（聊天补全）服务用的**，端点是 `tokenhub.tencentmaas.com`，**不能用于混元 3D**。3D 必须用上面 CAM 拿到的 SecretId + SecretKey。
:::

## 二、开通混元 3D 服务

打开 **[腾讯云混元 3D 控制台](https://console.cloud.tencent.com/ai3d)**，首次进入会要求同意服务协议 / 开通免费体验。

如果跳过这一步，工具会立即返回错误：

```
[Hunyuan3D] SubmitHunyuanTo3DProJob failed: 资源不足。 (ResourceInsufficient)
```

部分模型（特别是 `HY-3D-3.1`）可能需要单独申请白名单 / 购买配额，看控制台首页的额度说明。

## 三、在 MateClaw 里配置凭据

1. 进入 **「模型与凭据」** 页，找到 **「腾讯混元 3D」** 卡片（V71 迁移自动注册）
2. 点 **「更换」** / **「配置」**
3. **API Key** 字段填入 **`SecretId:SecretKey`**（中间一个英文冒号，**没有空格**）：
   ```
   AKIDxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxx:abcdefghijklmnopqrstuvwxyz123456
   ```
4. **Base URL** 留默认 `https://ai3d.tencentcloudapi.com`（系统会自动附加 region 路由）
5. 保存

::: warning 单输入框的临时折中
当前模型与凭据卡片是通用 UI，只暴露一个 API Key 输入框。后续会拆成 SecretId / SecretKey 两个独立输入框（自动拼 `:`）。
:::

## 四、启用 3D 生成功能

进入 **「设置 → 3D 生成」**：

- **启用 3D 模型生成**：开启
- **首选 3D 提供商**：选 `腾讯混元 3D`
- **提供商回退**：默认开启即可（目前只有一个 provider，回退暂时无意义，但保留以便未来扩展）

点 **「保存系统设置」**。

## 五、在聊天里使用

直接说自然语言，Agent 会自动选用 `model3d_generate` 工具：

```
生成一个 3D 模型：可爱的卡通小恐龙，绿色，圆滚滚的眼睛
```

```
快速生成一个 3D 模型：一个红色苹果      ← LLM 会选 HY-3D-Express
```

```
根据这张图生成 3D 模型：https://example.com/foo.png    ← 图生 3D
```

```
生成一个白模 3D：机械齿轮（不要贴图）   ← Geometry 模式
```

预期流程：

1. **工具立即返回**（毫秒级），告诉你 `taskId=xxx`
2. **后端 worker 异步轮询腾讯云**，每 8 秒查一次状态
3. **1-3 分钟后** SSE 事件 `async_task_completed` 自动推到对话
4. **前端 `<model-viewer>`** 渲染 `.glb`，可拖拽 / 旋转 / 缩放

---

## 工具参数（`model3d_generate`）

| 参数 | 类型 | 必填 | 说明 |
|---|---|---|---|
| `prompt` | String | 是\* | 文本描述，最多 1024 字符 |
| `imageUrl` | String | 是\* | 参考图片 URL（图生 3D 模式）|
| `model` | String | 否 | `HY-3D-3.1` (默认) / `HY-3D-3.0` / `HY-3D-Express` |
| `enableTexture` | Boolean | 否 | `true`（默认）/ `false`（白模，仅 Pro 支持）|
| `enablePbr` | Boolean | 否 | `true` 启用 PBR 材质（更逼真，仅 Pro 支持，默认 `false`）|

\* `prompt` 和 `imageUrl` 二选一，不能同时为空。Pro 接口下两者也不能同时给（除非走 Sketch 模式，当前未暴露）。

---

## 故障排查

### 1. `3D 模型生成功能未启用，请在系统设置中开启`

→ 没启用功能开关。回到 **设置 → 3D 生成**，把「启用 3D 模型生成」打开并保存。

### 2. `Provider api_key must be "SecretId:SecretKey" (colon-joined)`

→ 凭据格式不对。可能填了：
- 单个 SecretId（没拼 SecretKey）
- 单个 SecretKey
- 单个 sk-xxx（那是 TokenHub 的，不能用）
- 中间用了空格而不是 `:`

正确格式：`AKIDxxxx...:zzzz...`（一个英文冒号）。

### 3. `资源不足。 (ResourceInsufficient)`

→ 腾讯云端业务错误，**跟代码无关**。常见原因：
- 混元 3D 服务还没开通
- 免费体验配额耗尽
- 当前模型（特别是 HY-3D-3.1）需要审批 / 付费

去 [混元 3D 控制台](https://console.cloud.tencent.com/ai3d) 检查配额。

### 4. `invalid params, first_frame_image`

→ 图生 3D 时 `imageUrl` 不可访问。腾讯需要从公网抓图，私有网络 / `localhost` URL 不行。检查：
- URL 在浏览器无痕模式能直接打开
- 域名 + 文件后缀符合腾讯要求（`jpg/png/jpeg/webp`，128-5000px，≤8MB）

### 5. 任务跑了 15 分钟还没回

→ Worker 默认 15 分钟超时。看后端日志：

```bash
grep '\[Hunyuan3D\]\|\[Model3dGen\]' logs/mateclaw.log | tail -10
```

查 polling 是不是被网络抖动卡死，或腾讯端长期 `RUN`/`WAIT` 没推进。可手动取消任务（重启后端 + 它会被标 failed）。

### 6. 模型生成出来了但前端只看到下载链接，不能拖拽

→ 腾讯返回的是 OBJ-zip 包（OBJ + 贴图 + MTL 多文件），不是单文件 GLB。代码已优先选 GLB 条目（`pickBestResultFile`），如果腾讯当次只返回 OBJ，前端会按 zip 显示。**切换到默认 `HY-3D-3.1` 通常会返回 GLB**。

---

## 架构（一图速览）

```
[ 用户 ] ─ 自然语言 ─▶ [ Agent ] ─▶ model3d_generate
                                          │
                            (model 字段路由)
                                          │
                ┌─────────────────────────┴──────────────────┐
                ▼                                            ▼
        SubmitHunyuanTo3DProJob              SubmitHunyuanTo3DRapidJob
        (HY-3D-3.1 / HY-3D-3.0)              (HY-3D-Express)
                │                                            │
                └──────────── ai3d.tencentcloudapi.com ──────┘
                                          │
                            返回 JobId (24h 有效)
                                          │
        AsyncTaskService 每 8s 轮询 Query{Pro,Rapid}HunyuanTo3DJob
                                          │
                          状态 → DONE? ──▶ 拿 ResultFile3Ds[]
                                          │
                            优先选 GLB > FBX > OBJ
                                          │
                          下载到 data/chat-uploads/
                                          │
                          写 mate_message (type=model3d)
                                          │
                  广播 SSE async_task_completed
                                          │
                ▼
        前端 useChat 识别 modelUrl ─▶ MessageBubble 桥接虚拟附件
                                          │
                              <model-viewer> 渲染 .glb
```

---

## 后台日志关键标记

正常一次完整流程的日志（按时间序）：

```
[ToolExecutor] Executing tool: model3d_generate
[Hunyuan3D] SubmitHunyuanTo3DProJob submitted job: 1441791994... (model=HY-3D-3.1)
[AsyncTask] Created task d73723f14c7c4167 (providerTaskId=pro:1441791994...)
[AsyncTask] Started polling for task d73723f14c7c4167 (interval=8s, timeout=15min)
[ToolExecutor] Tool model3d_generate returned 80 chars
…等 1-3 分钟…
[Model3dDownloader] Downloading 3D model from https://hunyuan-prod-….cos.../...glb to data/chat-uploads/.../model_d73723f14c7c4167.glb
[Model3dDownloader] Downloaded NNNN bytes
[Model3dGen] Task d73723f14c7c4167 completed, model saved: /api/v1/chat/files/.../model_d73723f14c7c4167.glb
```

---

## 相关文档

- [多模态创作总览](./multimodal.md)
- [模型与凭据配置](./models.md)
- [工具系统](./tools.md)
- 设计稿：`rfcs/202605/01-generative-async-pipeline.md`
