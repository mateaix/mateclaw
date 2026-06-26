# MateClaw Desktop

MateClaw 的桌面客户端，基于 Electron 构建，自动集成 JRE 21 和后端服务，实现双击即用。

## 架构

```
Electron Shell
├── Splash Screen (Vue 3)     ← 启动加载界面
├── Bundled JRE 21            ← 自带 Java 运行时
├── mateclaw-server.jar       ← Spring Boot 后端 + Vue 前端
└── BrowserWindow → localhost:18088
```

**启动流程**: Electron 启动 → 显示 Splash → 用内置 JRE 启动 JAR → 等待后端就绪 → 加载主界面

## 快速开始

### 前置要求

- Node.js 18+
- pnpm (前端构建)
- Maven 3.9+ (后端构建)
- Java 21+ (仅构建时需要，运行时使用内置 JRE)

### 开发模式

```bash
# 1. 安装依赖
npm install

# 2. 构建后端 JAR（包含前端资源）
npm run setup:jar

# 3. 下载 JRE（当前平台）
npm run setup:jre

# 4. 启动开发模式
npm run dev
```

### 打包发布

```bash
# macOS (.dmg)
npm run package:mac

# Windows (.exe)
npm run package:win

# 全平台
npm run package:all
```

输出在 `release/` 目录。

## 目录结构

```
mateclaw-desktop/
├── electron/main/        # Electron 主进程（Java 生命周期管理）
├── electron/preload/     # 预加载脚本（安全 IPC 桥接）
├── src/                  # Splash Screen（Vue 3 加载页面）
├── build/                # 应用图标和 macOS entitlements
├── scripts/              # 构建脚本
│   ├── download-jre.sh   # 下载 Adoptium JRE 21
│   └── build.sh          # 构建前端 + 后端 JAR
└── resources/            # 运行时资源（JRE + JAR，不提交到 Git）
```

## 环境变量

桌面应用**不需要任何环境变量**就能启动——LLM 供应商 Key 在 UI 里加。

以下是可选的环境变量（桌面应用会继承系统环境）：

| 变量 | 必须 | 说明 |
|------|------|------|
| `SERPER_API_KEY` | ❌ | Google Serper 搜索 API（搜索工具暂未迁到 UI） |
| `TAVILY_API_KEY` | ❌ | Tavily 搜索 API |

> 💡 DashScope / OpenAI / Anthropic / DeepSeek / Kimi / Ollama 等 LLM 供应商 Key 启动后在「设置 → 模型 → 添加供应商」里粘进去，加密存到本地 H2 数据库。

## 自动升级

应用内置 `electron-updater` 自动升级，更新产物托管在 [GitHub Releases](https://github.com/matevip/mateclaw/releases)。

**升级流程**：启动时检查 → Splash Screen 底部通知 → 用户点击下载 → 下载完成点击重启 → 自动停止 Java 后端 → 安装新版本

| 平台 | 更新包格式 | 元数据文件 | 签名要求 |
|------|-----------|-----------|---------|
| Windows | NSIS `.exe` | `latest.yml` | 可选（不签名会触发 SmartScreen） |
| macOS | `.zip` | `latest-mac.yml` | **必须签名+公证**（否则只能手动 DMG 安装） |

## 发布操作手册

### 第一步：配置 GitHub Token

`electron-builder` 使用 `github` provider，需要 GitHub Personal Access Token 来创建 Release 并上传产物。

1. 前往 https://github.com/settings/tokens → **Generate new token (classic)**
2. 勾选 `repo` 权限（需要完整 repo 访问才能创建 Release）
3. 生成后保存 token

```bash
# 设置环境变量（建议写入 ~/.zshrc 或 CI Secret）
export GH_TOKEN=ghp_xxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxx
```

### 第二步：版本号管理

每次发布前必须更新 `package.json` 中的 `version` 字段。`electron-updater` 客户端通过对比本地版本号和 `latest.yml` 中的版本号来判断是否有更新。

```bash
# 编辑版本号
cd mateclaw-desktop
vim package.json  # 修改 "version": "1.0.0" → "1.1.0"
```

版本号遵循 [SemVer](https://semver.org/)：
- 修复 bug → `1.0.0` → `1.0.1`
- 新功能 → `1.0.0` → `1.1.0`
- 破坏性变更 → `1.0.0` → `2.0.0`

### 第三步：构建并发布

```bash
cd mateclaw-desktop

# 一键构建全平台 + 自动上传到 GitHub Releases
export GH_TOKEN=ghp_xxxxxxxxxxxx
bash scripts/build-all-platforms.sh --all --publish=always
```

这会自动：
1. 构建后端 JAR
2. 下载各平台 JRE
3. 编译前端
4. 打包 macOS（DMG + ZIP）和 Windows（NSIS）
5. 生成 `latest.yml` 和 `latest-mac.yml`
6. 创建 GitHub Draft Release 并上传所有产物

完成后前往 https://github.com/matevip/mateclaw/releases ，找到 Draft Release：
- 填写 Release Notes（更新说明）
- 点击 **Publish release** 正式发布

也可以仅构建特定平台：

```bash
bash scripts/build-all-platforms.sh --mac-only --publish=always   # 仅 macOS
bash scripts/build-all-platforms.sh --win-only --publish=always   # 仅 Windows
```

### 第四步（可选）：手动发布

如果不想用 `--publish=always` 自动上传：

```bash
# 1. 仅构建，不上传
bash scripts/build-all-platforms.sh --all

# 2. 查看生成的产物
ls -la release/
# 产物包括：
#   MateClaw_1.1.0_arm64.dmg           macOS ARM64 安装包
#   MateClaw_1.1.0_x64.dmg             macOS x64 安装包
#   MateClaw_1.1.0_arm64.zip           macOS ARM64 更新包（升级用）
#   MateClaw_1.1.0_x64.zip             macOS x64 更新包（升级用）
#   MateClaw_1.1.0_x64_Setup.exe       Windows x64 安装包
#   MateClaw_1.1.0_arm64_Setup.exe     Windows ARM64 安装包
#   MateClaw_1.1.0_*.blockmap          差分下载支持文件
#   latest.yml                         Windows 更新元数据
#   latest-mac.yml                     macOS 更新元数据

# 3. 在 GitHub 手动创建 Release
#    Tag: v1.1.0
#    上传 release/ 目录中的所有 .exe .zip .dmg .blockmap .yml 文件
```

> **注意**：`latest.yml` 和 `latest-mac.yml` 必须上传，客户端靠它们检测新版本。

---

## macOS 代码签名与公证

macOS 自动升级**必须**签名+公证，否则 Gatekeeper 会阻止更新后的应用启动。未签名时 macOS 用户只能手动下载 DMG 安装。

> **证书创建完整指南**：首次配置或证书过期时，参见 [CODESIGNING.md](./CODESIGNING.md)（含 CSR 生成、证书创建、.p12 导出、公证配置等完整步骤）。

### 本地签名构建（推荐）

证书安装到本地钥匙串后，**不需要设置 `CSC_LINK`**，electron-builder 会自动发现证书：

```bash
# 只需设置公证相关变量
export APPLE_ID=your@apple.id
export APPLE_APP_SPECIFIC_PASSWORD=xxxx-xxxx-xxxx-xxxx   # 在 appleid.apple.com 生成
export APPLE_TEAM_ID=XXXXXXXXXX                           # 10 位团队 ID

bash scripts/build-all-platforms.sh --mac-only --publish=always
```

`electron-builder` 会自动完成签名 → 公证 → 装订（staple）→ 上传。

> **注意**：不要设置 `CSC_LINK` 环境变量，否则 electron-builder 会创建临时钥匙串，可能导致签名卡死。详见 [CODESIGNING.md](./CODESIGNING.md) 故障排查章节。

### CI/CD 签名构建

CI 环境无本地钥匙串，需通过 `CSC_LINK` 指定 `.p12` 文件（Base64 编码存入 GitHub Secret）：

```bash
export CSC_LINK=base64_encoded_p12_content
export CSC_KEY_PASSWORD=your_certificate_password
export APPLE_ID=your@apple.id
export APPLE_APP_SPECIFIC_PASSWORD=xxxx-xxxx-xxxx-xxxx
export APPLE_TEAM_ID=XXXXXXXXXX

bash scripts/build-all-platforms.sh --mac-only --publish=always
```

### 公证超时处理

如果公证上传超时（`deadlineExceeded`），可先跳过公证构建，再用 `xcrun notarytool` 手动公证：

```bash
# 1. 去掉公证变量，仅签名出包
unset APPLE_ID APPLE_APP_SPECIFIC_PASSWORD APPLE_TEAM_ID
bash scripts/build-all-platforms.sh --mac-only

# 2. 手动公证（支持断点续传）
xcrun notarytool submit release/MateClaw_*.zip \
  --apple-id your@apple.id \
  --password "app专用密码" \
  --team-id XXXXXXXXXX \
  --wait

# 3. 装订公证票据
xcrun stapler staple release/MateClaw_*.dmg
```

### 跳过签名（开发/测试用）

```bash
export CSC_IDENTITY_AUTO_DISCOVERY=false
bash scripts/build-all-platforms.sh --mac-only
```

---

## Windows 代码签名（可选）

未签名的 Windows 安装包会触发 SmartScreen 警告（"Windows 已保护你的电脑"），用户可以点击"仍要运行"。签名可消除此警告。

### EV 代码签名证书

推荐使用 EV（Extended Validation）证书，可立即获得 SmartScreen 信誉，无需积累安装量。

证书提供商（参考）：
- [DigiCert](https://www.digicert.com/signing/code-signing-certificates) — 需硬件 token
- [SSL.com](https://www.ssl.com/certificates/ev-code-signing/) — 支持云签名
- [Certum](https://shop.certum.eu/code-signing-certificates/) — 较便宜的选项

### 配置

```bash
# PFX 文件签名
export WIN_CSC_LINK=/path/to/windows-cert.pfx
export WIN_CSC_KEY_PASSWORD=password

# 或使用 signtool（需要硬件 token 的 EV 证书）
# 在 electron-builder.json 的 win 节中配置：
# "signingHashAlgorithms": ["sha256"],
# "sign": "./scripts/sign.js"
```

---

## CI/CD 自动发布（GitHub Actions）

以下为 GitHub Actions 完整示例，实现 Git tag 推送时自动构建全平台并发布：

```yaml
# .github/workflows/release.yml
name: Release Desktop

on:
  push:
    tags:
      - 'v*'  # 推送 v1.0.0 等 tag 时触发

jobs:
  release-mac:
    runs-on: macos-latest
    steps:
      - uses: actions/checkout@v4

      - uses: actions/setup-node@v4
        with:
          node-version: 20

      - uses: actions/setup-java@v4
        with:
          distribution: temurin
          java-version: 21

      - name: Build and publish macOS
        env:
          GH_TOKEN: ${{ secrets.GITHUB_TOKEN }}
          CSC_LINK: ${{ secrets.MAC_CSC_LINK }}
          CSC_KEY_PASSWORD: ${{ secrets.MAC_CSC_KEY_PASSWORD }}
          APPLE_ID: ${{ secrets.APPLE_ID }}
          APPLE_APP_SPECIFIC_PASSWORD: ${{ secrets.APPLE_APP_SPECIFIC_PASSWORD }}
          APPLE_TEAM_ID: ${{ secrets.APPLE_TEAM_ID }}
        run: |
          cd mateclaw-desktop
          npm install
          bash scripts/build-all-platforms.sh --mac-only --publish=always

  release-win:
    runs-on: windows-latest
    steps:
      - uses: actions/checkout@v4

      - uses: actions/setup-node@v4
        with:
          node-version: 20

      - uses: actions/setup-java@v4
        with:
          distribution: temurin
          java-version: 21

      - name: Build and publish Windows
        env:
          GH_TOKEN: ${{ secrets.GITHUB_TOKEN }}
        run: |
          cd mateclaw-desktop
          npm install
          bash scripts/build-all-platforms.sh --win-only --publish=always
```

### 配置 CI Secrets

在 GitHub 仓库 → Settings → Secrets and variables → Actions → New repository secret：

| Secret 名称 | 说明 |
|-------------|------|
| `MAC_CSC_LINK` | macOS 签名证书 .p12 的 Base64 编码：`base64 -i cert.p12 \| tr -d '\n'` |
| `MAC_CSC_KEY_PASSWORD` | .p12 证书密码 |
| `APPLE_ID` | Apple ID 邮箱 |
| `APPLE_APP_SPECIFIC_PASSWORD` | App 专用密码 |
| `APPLE_TEAM_ID` | 10 位开发者团队 ID |
| `GITHUB_TOKEN` | 自动提供，无需手动配置 |

### 发布流程（CI 方式）

```bash
# 1. 更新版本号
cd mateclaw-desktop
vim package.json  # "version": "1.1.0"

# 2. 提交并打 tag
git add -A && git commit -m "release: v1.1.0"
git tag v1.1.0
git push origin main --tags

# 3. GitHub Actions 自动构建并创建 Draft Release
# 4. 前往 GitHub Releases 确认并发布
```

---

## 本地测试自动升级

### 方式一：开发模式 + dev-app-update.yml

在开发模式下测试 updater 流程（不需要打包）：

```bash
# 1. 在 mateclaw-desktop/ 根目录创建 dev-app-update.yml
cat > dev-app-update.yml << 'EOF'
provider: generic
url: http://localhost:8080/
EOF

# 2. 构建一个"新版本"的产物
#    先把 package.json 的 version 改为更高版本（如 9.9.9）
#    然后构建：
npm run build
npx electron-builder --mac --publish=never  # 或 --win
#    构建完成后把 version 改回原值

# 3. 启动本地文件服务器
cd release && python3 -m http.server 8080

# 4. 另一个终端启动开发模式
cd mateclaw-desktop && npm run dev
# updater 会从 localhost:8080 检查更新并发现"新版本"
```

> 开发模式下 `quitAndInstall()` 不会真正安装，但可验证检查→发现→下载的完整流程。

### 方式二：打包后端到端测试（推荐）

```bash
# 1. 打包 v1.0.0 并安装到系统
# 2. 修改 package.json version 为 v1.1.0
# 3. 重新构建，产物上传到 GitHub Release（或本地服务器）
# 4. 启动已安装的 v1.0.0，观察完整升级流程：
#    检查更新 → 发现 v1.1.0 → 下载 → 重启安装
```

---

## 发布检查单

- [ ] `package.json` 版本号已更新
- [ ] 后端 JAR 已构建（`npm run setup:jar`）
- [ ] 各平台 JRE 已下载
- [ ] `npm run build` 编译通过
- [ ] `GH_TOKEN` 环境变量已设置
- [ ] macOS 签名证书环境变量已设置（若需要签名）
- [ ] `bash scripts/build-all-platforms.sh --all --publish=always` 执行成功
- [ ] GitHub Draft Release 已确认发布
- [ ] 在旧版本应用上验证升级通知正常

## 技术栈

- **Electron** - 桌面应用框架
- **Vite + Vue 3** - Splash Screen 构建
- **electron-builder** + **electron-updater** - 跨平台打包与自动升级
- **Adoptium JRE 21** - 内置 Java 运行时
