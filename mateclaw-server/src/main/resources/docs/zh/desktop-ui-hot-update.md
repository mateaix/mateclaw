# MateClaw Desktop UI 热更新设计

## 背景

当前 `mateclaw-desktop` 的运行链路不是“Electron 直接加载 `mateclaw-ui`”，而是：

1. Electron 启动并显示本地 Splash。
2. Electron 用内置 JRE 启动 `mateclaw-server.jar`。
3. `mateclaw-ui` 已提前构建到 `mateclaw-server/src/main/resources/static`。
4. `BrowserWindow` 最终加载 `http://localhost:18088`。

这意味着：

- 现在的 UI 资源和后端 JAR 强绑定。
- 任何 `mateclaw-ui` 改动，都要重新打包 `mateclaw-server.jar`，再跟着桌面安装包一起发布。
- 现有 `electron-updater` 只能做“整包升级”，不能做“仅 UI 升级”。

因此这里要解决的问题，不是开发态 HMR，而是生产态 OTA：让 `mateclaw-ui` 可以脱离桌面安装包独立更新。

## 目标

- 支持 `mateclaw-ui` 独立于 `mateclaw-desktop` 发布。
- UI 更新不要求用户下载新的桌面安装包。
- 保持当前本地 `localhost` 架构，不把桌面应用直接改成远程网站壳子。
- 更新失败可回滚到内置 UI。
- 不影响现有 `electron-updater` 的整包升级能力。

## 非目标

- 第一阶段不做后端 JAR 热更新。
- 第一阶段不允许 UI 任意突破当前后端 API 边界。
- 第一阶段不替换现有 Electron Splash / 自动升级通道。

## 现状约束

### 1. UI 当前被打进 Spring Boot JAR

`mateclaw-ui` 构建输出目录目前是：

- `../mateclaw-server/src/main/resources/static`

也就是 UI 构建产物直接进入后端静态资源目录，最终随 JAR 发布。

### 2. Desktop 最终加载的是后端地址

`mateclaw-desktop` 主窗口业务页当前加载：

- `http://localhost:18088`

所以 UI 热更新不能只改 Electron `dist`，必须让后端在运行时能切换静态资源来源。

### 3. 现有整包升级已存在

桌面端已经接入 `electron-updater`，并通过 GitHub Releases 发布整包升级。

因此新方案应当与它并存：

- Shell / JRE / JAR 升级：继续走 `electron-updater`
- 纯前端升级：新增 UI OTA 通道

## 方案结论

推荐采用：

**方案 A：外置 UI Bundle + 本地优先加载 + Manifest 驱动的 OTA 更新**

核心思路：

1. `mateclaw-ui` 产出独立的静态包（zip）。
2. `mateclaw-desktop` 在启动时检查 UI 更新 Manifest。
3. 下载并校验新的 UI 包后，解压到 `userData/ui-bundles/<version>/`。
4. Electron 启动后端时，通过环境变量把“当前启用的 UI 目录”传给 Spring Boot。
5. Spring Boot 优先从外部目录提供静态资源；若外部目录不存在或损坏，则回退到 JAR 内置 `classpath:/static/`。
6. UI 更新完成后刷新窗口即可生效，无需安装新桌面包。

这是对当前架构改动最小、兼容性最强的路径。

## 为什么不选其他方案

### 方案 B：Electron 直接加载远程站点

不推荐作为主方案。

缺点：

- 桌面应用退化成网站壳子，离线能力明显变差。
- 安全面更大，远程页面注入风险更高。
- 当前路由是 `history` 模式，本地与远程混用会增加协议、资源路径和鉴权处理复杂度。
- 对现有 `localhost + Spring Boot` 架构破坏过大。

### 方案 C：把 UI 更新继续塞进 JAR 差分包

不满足目标。

原因：

- UI 仍与后端耦合。
- 每次 UI 变更都要重新发 JAR 和桌面安装包。
- 无法做到真正的“仅 UI 热更新”。

## 目标架构

```text
Electron Shell
├── Splash UI（本地 dist）
├── UI Update Manager（新增）
├── Bundled JRE
├── mateclaw-server.jar
└── BrowserWindow → http://localhost:18088
                     ├── 优先读取 userData/ui-bundles/current/
                     └── fallback 到 classpath:/static/
```

运行时目录建议：

```text
~/Library/Application Support/MateClaw/        # macOS 示例
├── data/
├── ui-bundles/
│   ├── current.json
│   ├── 1.0.3+20260405/
│   │   ├── index.html
│   │   ├── assets/...
│   │   └── meta.json
│   └── 1.0.4+20260410/
└── logs/
```

## 关键设计

### 1. UI 包格式

每个 UI 发布产物建议包含：

- `index.html`
- `assets/*`
- `logo/*`
- `icons/*`
- `meta.json`

`meta.json` 示例：

```json
{
  "uiVersion": "1.0.4",
  "buildId": "20260410.1",
  "minDesktopVersion": "1.0.0",
  "minServerApiVersion": "1.0",
  "maxServerApiVersion": "1.x",
  "sha256": "..."
}
```

说明：

- `uiVersion`：前端语义版本。
- `buildId`：构建批次，便于排查。
- `minDesktopVersion`：限制旧 Electron shell。
- `minServerApiVersion` / `maxServerApiVersion`：约束 UI 与当前后端 API 兼容性。

### 2. 更新 Manifest

桌面端不直接猜测最新版本，而是请求一个 Manifest。

建议格式：

```json
{
  "channel": "stable",
  "latest": {
    "uiVersion": "1.0.4",
    "buildId": "20260410.1",
    "url": "https://download.example.com/mateclaw/ui/1.0.4/ui-bundle.zip",
    "sha256": "..."
  },
  "minimumDesktopVersion": "1.0.0",
  "compatibleServerApi": "1.x",
  "signature": "base64..."
}
```

Manifest 最好放在稳定的静态地址，不要依赖 GitHub API 动态查询 release 列表。

发布源建议优先级：

1. 自有 CDN / OSS / COS / R2
2. GitHub Releases 直链

如果主要用户在国内，建议不要把 GitHub 当唯一源。

### 3. Spring Boot 静态资源加载改造

需要在 `mateclaw-server` 中新增静态资源优先级：

1. 外部目录 `file:${mateclaw.ui.dir}/`
2. 内置资源 `classpath:/static/`

实现建议：

- 新增配置项 `mateclaw.ui.dir`
- 在 `WebMvcConfigurer` 中注册资源处理器
- 对 `/assets/**`、`/icons/**`、`/logo/**`、`/favicon.ico`、`/index.html` 和 SPA 路由统一转发
- 当外部目录不存在时自动回退内置资源

这样 BrowserWindow 仍然访问 `http://localhost:18088`，但内容已经可由外置 UI 包覆盖。

### 4. Electron 侧 UI Update Manager

新增一个独立的 UI 更新管理器，职责：

1. 读取当前启用 UI 版本。
2. 拉取远程 Manifest。
3. 判定兼容性。
4. 下载 zip。
5. 校验 `sha256` 与签名。
6. 解压到临时目录。
7. 原子切换 `current.json` 或 `current` 软链接。
8. 通知渲染层“有可用 UI 更新”或“更新已完成”。

建议时机：

- 启动后 3 到 10 秒后台检查
- 用户手动点击“检查前端更新”
- 设置页允许切换更新通道（stable / beta）

### 5. 激活策略

建议采用“两阶段激活”：

#### 启动前已下载完成

- Electron 在启动 Java 前先解析当前 UI 指针
- 将 `MATECLAW_UI_DIR` 注入到 Java 进程环境变量
- 本次启动直接加载新 UI

#### 运行中下载完成

- 下载成功后先不杀后端
- 标记“下次重启生效”是最稳妥方案
- 若要做到即时生效，可尝试：
  - 切换 `current` 指针
  - 通知前端 `window.location.reload()`

第一阶段推荐：

**下载后提示“重启应用以应用前端更新”**

原因是：

- 简化缓存一致性问题
- 避免运行态资源引用一半新一半旧
- 降低与长连接、SSE、登录态的耦合风险

### 6. 回滚策略

至少支持三层回退：

1. 下载失败：保持当前 UI
2. 解压或校验失败：丢弃新包，保持当前 UI
3. 新 UI 启动异常：回退到上一版本 UI，最差回退到 JAR 内置 UI

建议机制：

- `current.json` 记录当前版本、上一版本、状态
- UI 启动成功后，前端调用 `/api/v1/system/ui/boot-ok` 或通过 preload IPC 上报“本次版本已健康启动”
- 若启动后短时间内崩溃或白屏，下次启动自动回滚上一版本

### 7. 安全要求

UI 热更新本质上是在本地执行新的前端资源，必须做完整校验。

最低要求：

- HTTPS 下载
- `sha256` 校验
- Manifest 签名校验

推荐增加：

- 使用 Ed25519 公钥验签
- 公钥随桌面端内置
- 不允许跳过签名校验加载生产更新包

否则该通道会成为远程代码注入入口。

### 8. 缓存与资源路径

需要处理几个细节：

- `index.html` 不应长缓存
- `assets/*` 可使用内容 hash 长缓存
- 外部 UI 包目录最好按版本隔离
- `current` 只做版本指针，不直接覆盖旧目录

这和 Vite 的产物模式天然兼容。

### 9. 版本兼容规则

建议明确一条产品规则：

- 只涉及 UI 表现、交互、文案、前端容错的变更，可以走 UI OTA
- 需要新增/修改后端 API、数据库结构、JRE 资源、Electron 权限能力的变更，必须走桌面整包升级

否则很容易出现：

- UI 已升级
- 本地 JAR 太旧
- 页面调用了不存在的 API

所以 Manifest 中必须带兼容约束。

## 发布链路设计

### 当前链路

1. `mateclaw-ui` 构建到后端 `static/`
2. Maven 打 JAR
3. Electron 打包安装包
4. GitHub Releases 发布

### 新链路

#### 桌面整包发布

继续保持现状：

1. 构建 UI
2. 打进 JAR
3. 打包桌面安装包
4. 走 `electron-updater`

#### UI 独立发布

新增：

1. `mateclaw-ui` 单独构建到临时目录
2. 生成 `meta.json`
3. 打 zip
4. 计算 `sha256`
5. 生成并发布 `ui-manifest.json`
6. 上传到 CDN / Release 资产

这样：

- 新装用户依然有 JAR 内置 UI 可用
- 老用户可在后续自动收到 UI OTA

## 推荐实施阶段

### Phase 1：基础可用

目标：

- 支持下载 UI 包
- 支持启动时优先加载外部 UI
- 支持失败回退到内置 UI
- 下载完成后“下次重启生效”

需要改动：

- `mateclaw-server`
  - 支持外部静态目录优先级
  - 提供 SPA fallback
- `mateclaw-desktop`
  - 增加 UI Update Manager
  - 增加 Manifest 拉取、下载、校验、解压、指针切换
  - 将 UI 版本信息暴露给 Splash / 设置页
- `mateclaw-ui`
  - 构建时生成 `meta.json`
  - 设置页增加当前 UI 版本展示

这是最值得先落地的一版。

### Phase 2：产品化

目标：

- 设置页支持“检查前端更新”
- 提示更新说明
- 支持 stable / beta 通道
- 支持启动失败自动回滚

### Phase 3：增强体验

目标：

- 部分场景无需整应用重启即可刷新 UI
- 支持灰度发布
- 支持按平台分发不同 UI 包

## 建议新增模块

### `mateclaw-desktop`

建议新增：

- `electron/main/ui-updater.ts`
- `electron/main/ui-runtime.ts`

职责拆分：

- `ui-updater.ts`：远程检查、下载、校验、解压、切换
- `ui-runtime.ts`：读取当前 UI 指针、提供给 Java 进程环境变量

### `mateclaw-server`

建议新增：

- `vip.mate.config.ExternalUiProperties`
- `vip.mate.config.UiResourceConfig`
- `vip.mate.system.controller.DesktopRuntimeController`

职责：

- 配置外部 UI 目录
- 注册静态资源与 SPA fallback
- 对前端暴露当前运行版本信息

### `mateclaw-ui`

建议新增：

- 构建脚本：生成 `meta.json`
- 设置页：显示
  - Desktop 版本
  - UI 版本
  - 后端版本
  - 更新通道
  - 最近检查时间

## 接口建议

桌面 preload / IPC 可以新增：

- `uiUpdater.getState()`
- `uiUpdater.check()`
- `uiUpdater.download()`
- `uiUpdater.applyOnRestart()`

后端接口建议新增：

- `GET /api/v1/runtime/version`

返回示例：

```json
{
  "desktopVersion": "1.0.0",
  "serverVersion": "1.0.0",
  "serverApiVersion": "1.0",
  "uiVersion": "1.0.4",
  "uiSource": "external"
}
```

这样前端可以明确展示当前正在跑的是哪个 UI 包。

## 风险点

### 1. 前后端版本漂移

这是最大风险。

控制手段：

- UI Manifest 增加兼容性约束
- 约定 API 破坏性变更只能走整包升级

### 2. 白屏回滚不完善

如果只做下载和切换，不做启动成功确认，坏包可能导致用户持续白屏。

所以至少要有：

- 上一版本指针
- 启动健康上报

### 3. 更新源可达性

若继续完全依赖 GitHub Releases，国内网络环境下成功率可能不稳定。

建议尽快切到稳定 CDN。

### 4. 安全边界扩大

远程前端资源可执行，签名和 hash 校验是必须项，不是可选优化。

## 最小落地建议

如果现在就开始做，建议按下面顺序推进：

1. 先改 `mateclaw-server`，让它支持“外部目录覆盖 classpath static”。
2. 再改 `mateclaw-desktop`，把 UI 包下载到 `userData/ui-bundles/`，并通过环境变量传给 Java。
3. 再补 Manifest、签名校验和版本展示。
4. 最后再做“运行中更新提示”和“自动回滚”。

## 结论

对当前 MateClaw 架构，最合适的不是把桌面端改成远程站点壳，而是：

**保留 `Electron + localhost Spring Boot` 架构，引入“外置 UI Bundle 覆盖内置 static 资源”的 OTA 机制。**

这样可以：

- 保持离线可用
- 最大限度复用现有桌面端架构
- 将 UI 发布频率从桌面整包中解耦
- 把风险控制在“前端资源替换”这一层

第一阶段建议做到：

- 启动时自动检查 UI 更新
- 后台下载
- 校验后写入外部目录
- 下次重启生效
- 失败自动回退到 JAR 内置 UI

这版最稳，也最容易在现有代码上渐进落地。
