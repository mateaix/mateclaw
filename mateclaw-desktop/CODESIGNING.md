# macOS 代码签名证书操作指南

本文档详细说明如何创建、导出和配置 macOS **Developer ID Application** 证书，用于 MateClaw Desktop 的签名与公证。

---

## 前置条件

- [Apple Developer Program](https://developer.apple.com/programs/) 会员（$99/年）
- macOS 系统（需要钥匙串访问生成密钥对）

## Step 1: 撤销旧证书（如有）

如果本地证书已过期或私钥丢失，需先撤销线上旧证书：

1. 登录 https://developer.apple.com/account/resources/certificates/list
2. 找到旧的 `Developer ID Application` 证书 → 点击进入详情
3. 点击 **Revoke** → 确认撤销
4. 回到本地 **钥匙串访问** → 删除过期证书（右键 → 删除）

## Step 2: 生成 CSR（证书签名请求）

CSR 会在本地生成密钥对（私钥留在钥匙串，公钥随 CSR 提交给 Apple）。

1. 打开 **钥匙串访问**
2. 菜单栏 → 钥匙串访问 → **证书助理** → **从证书颁发机构请求证书…**
3. 填写：
   - **用户电子邮件地址**：你的 Apple ID 邮箱
   - **常用名称**：与开发者账号一致（如 `ZHANFU XU`）
   - **CA 电子邮件地址**：留空
   - **请求是**：选择 **存储到磁盘**
4. 保存 `CertificateSigningRequest.certSigningRequest` 到桌面

## Step 3: 创建 Developer ID Application 证书

1. 访问 https://developer.apple.com/account/resources/certificates/add
2. 在 **Software** 分类下，选择 **Developer ID Application**
3. 点击 **Continue**
4. 上传 Step 2 保存的 CSR 文件
5. 点击 **Continue** → **Download** 下载 `developerID_application.cer`
6. **双击**下载的 `.cer` 文件 → 自动安装到钥匙串

## Step 4: 验证安装

```bash
security find-identity -v -p codesigning | grep "Developer ID Application"
```

应输出类似：

```
"Developer ID Application: ZHANFU XU (MR97WAD978)"
```

在钥匙串访问 → 登录 → **我的证书**中，展开该证书应能看到关联的**私钥**（左侧三角展开）。

## Step 5: 导出 .p12 文件

`.p12` 文件包含证书 + 私钥，是 `electron-builder` 签名所需的文件。

1. 钥匙串访问 → 登录 → **我的证书**
2. 找到 `Developer ID Application: Your Name (TEAMID)`
3. 点左侧三角**展开**，确认包含私钥
4. **右键证书**（不是私钥）→ **导出…**
5. 格式选择：**个人信息交换 (.p12)**
6. 保存为 `developer_id_application.p12`
7. 设置一个强密码（后续用作 `CSC_KEY_PASSWORD` 环境变量）

> **安全提醒**：`.p12` 文件包含私钥，绝不要提交到 Git 仓库。

## Step 6: 创建 App 专用密码（公证用）

Apple 公证（notarization）需要通过 Apple ID 验证身份，使用 App 专用密码代替账号密码。

1. 访问 https://appleid.apple.com/account/manage
2. 登录 → **登录与安全** → **App 专用密码** → **生成**
3. 标签填：`mateclaw-notarize`
4. 记录生成的密码（格式如 `xxxx-xxxx-xxxx-xxxx`）

## Step 7: 查找 Team ID

```bash
security find-identity -v -p codesigning | grep "Developer ID Application"
```

输出中括号内的 10 位字母数字即为 Team ID（如 `MR97WAD978`）。

## Step 8: 配置环境变量并构建

### 方式 A：本地钥匙串自动发现（推荐）

证书已安装到本地钥匙串时，**不需要设置 `CSC_LINK` 和 `CSC_KEY_PASSWORD`**，electron-builder 会自动从钥匙串中发现 Developer ID Application 证书。

```bash
cd mateclaw-desktop

# 只需设置公证相关变量
export APPLE_ID="your@apple.id"
export APPLE_APP_SPECIFIC_PASSWORD="xxxx-xxxx-xxxx-xxxx"
export APPLE_TEAM_ID="XXXXXXXXXX"

# 执行签名+公证构建
bash scripts/build-all-platforms.sh --mac-only
```

> **为什么推荐这种方式？** 设置 `CSC_LINK` 时，electron-builder 会创建一个临时钥匙串来导入 `.p12` 文件，这可能导致签名过程静默卡死（无报错）。直接使用本地钥匙串可以避免此问题。

### 方式 B：指定 .p12 文件（CI/CD 专用）

在 CI/CD 环境或证书不在本地钥匙串时，需要通过环境变量指定 `.p12` 文件：

```bash
cd mateclaw-desktop

export CSC_LINK="$HOME/developer_id_application.p12"
export CSC_KEY_PASSWORD="你的p12密码"
export APPLE_ID="your@apple.id"
export APPLE_APP_SPECIFIC_PASSWORD="xxxx-xxxx-xxxx-xxxx"
export APPLE_TEAM_ID="XXXXXXXXXX"

bash scripts/build-all-platforms.sh --mac-only
```

> **注意**：`CSC_KEY_PASSWORD` 中如有特殊字符（`$`、`!`、`"`、`` ` ``），必须用**单引号**包裹，如 `export CSC_KEY_PASSWORD='pa$$w0rd!'`。

### GitHub Actions Secrets

将 `.p12` 文件 Base64 编码后存为 GitHub Secret：

```bash
base64 -i developer_id_application.p12 | pbcopy
# 粘贴到 GitHub Secret: MAC_CSC_LINK
```

| GitHub Secret | 值 |
|---|---|
| `MAC_CSC_LINK` | `.p12` 的 Base64 内容 |
| `MAC_CSC_KEY_PASSWORD` | `.p12` 密码 |
| `APPLE_ID` | Apple ID 邮箱 |
| `APPLE_APP_SPECIFIC_PASSWORD` | App 专用密码 |
| `APPLE_TEAM_ID` | 10 位 Team ID |

## Step 9: 验证签名和公证

构建完成后验证：

```bash
# 验证代码签名
codesign --verify --deep --strict release/mac-arm64/MateClaw.app

# 验证 Gatekeeper 公证状态
spctl --assess --type execute --verbose release/mac-arm64/MateClaw.app
# 期望输出: accepted, source=Developer ID

# 验证 DMG
spctl --assess --type open --context context:primary-signature release/MateClaw-*.dmg
```

---

## 故障排查

### 签名卡死（无报错）

**现象**：构建停在 `signing` 行不动，`ps aux | grep codesign` 无进程或进程短暂出现后消失。

**原因**：设置了 `CSC_LINK` 后，electron-builder 会创建临时钥匙串导入 `.p12`，临时钥匙串的访问权限可能导致 `codesign` 静默卡死。

**解决**：
```bash
# 方案一（推荐）：取消 CSC_LINK，使用本地钥匙串自动发现
unset CSC_LINK
unset CSC_KEY_PASSWORD

# 方案二：授权 codesign 访问钥匙串
security unlock-keychain -p "你的Mac登录密码" ~/Library/Keychains/login.keychain-db
security set-key-partition-list -S apple-tool:,apple:,codesign: -s -k "你的Mac登录密码" ~/Library/Keychains/login.keychain-db
```

### `Permission denied` (classes.jsa)

**现象**：`codesign` 报错 `Permission denied`，通常指向 JRE 中的 `classes.jsa` 文件。

**原因**：下载的 Adoptium JRE 中部分文件是只读的，`codesign --force` 需要写权限。

**解决**：`download-jre.sh` 已在解压后自动执行 `chmod -R u+w`。如果使用旧版 JRE，手动修复：
```bash
# 删除旧 JRE 重新下载（推荐）
rm -rf resources/jre/mac-arm64 resources/jre/mac-x64
npm run setup:jre

# 或手动修复权限
chmod -R u+w resources/jre/
```

### `MAC verification failed` (wrong password)

**现象**：`SecKeychainItemImport: MAC verification failed during PKCS12 import (wrong password?)`

**原因**：`CSC_KEY_PASSWORD` 与导出 `.p12` 时设置的密码不匹配。

**解决**：
```bash
# 验证密码是否正确
openssl pkcs12 -in ~/developer_id_application.p12 -nokeys -passin pass:"你的密码"

# 如果报错 mac verify failure，重新导出 .p12：
# 钥匙串访问 → 我的证书 → 右键 Developer ID Application → 导出 → 重新设置密码

# 注意特殊字符需用单引号包裹
export CSC_KEY_PASSWORD='pa$$w0rd!'
```

### 公证上传超时 (deadlineExceeded)

**现象**：`HTTPClientError.deadlineExceeded`，公证上传到 Apple S3 超时。

**原因**：网络到 Apple 服务器不稳定，700MB+ 的应用上传容易超时。

**解决**：先跳过公证构建，再用 `xcrun notarytool` 手动公证（支持断点续传，超时容忍度更高）：
```bash
# 1. 去掉公证变量，仅签名
unset APPLE_ID
unset APPLE_APP_SPECIFIC_PASSWORD
unset APPLE_TEAM_ID
bash scripts/build-all-platforms.sh --mac-only

# 2. 手动公证
xcrun notarytool submit release/MateClaw_1.0.0_arm64.zip \
  --apple-id "your@apple.id" \
  --password "app专用密码" \
  --team-id "XXXXXXXXXX" \
  --wait

xcrun notarytool submit release/MateClaw_1.0.0_x64.zip \
  --apple-id "your@apple.id" \
  --password "app专用密码" \
  --team-id "XXXXXXXXXX" \
  --wait

# 3. 装订公证票据到 DMG
xcrun stapler staple release/MateClaw_1.0.0_arm64.dmg
xcrun stapler staple release/MateClaw_1.0.0_x64.dmg
```

---

## 常见问题

### 证书过期了怎么办？

Developer ID Application 证书有效期 **5 年**。过期后需重复 Step 1 ~ Step 5 重新创建。Apple 会在自动轮换日期前通过邮件提醒。

### 导出 .p12 时没有"导出"选项？

说明本地钥匙串中没有该证书对应的私钥。私钥只存在于当初生成 CSR 的那台 Mac 上。解决方案：
- **方案 A**：在原 Mac 上导出 `.p12`，再导入到当前 Mac
- **方案 B**：撤销旧证书，在当前 Mac 重新创建（Step 1 ~ Step 5）

### 签名很慢正常吗？

正常。700MB+ 的应用（含 JRE + Electron Framework）签名需要 **15~30 分钟**，公证上传+审核需要额外 **5~15 分钟**。可以用以下命令监控签名进度：
```bash
watch -n 2 'ps aux | grep codesign | grep -v grep'
# macOS 需先安装：brew install watch
```

### 跳过签名（开发测试用）

```bash
export CSC_IDENTITY_AUTO_DISCOVERY=false
bash scripts/build-all-platforms.sh --mac-only
```

未签名的应用无法使用自动升级功能，macOS 用户需手动下载 DMG 安装。
