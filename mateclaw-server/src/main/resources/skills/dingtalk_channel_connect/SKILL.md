---
name: dingtalk_channel_connect
version: "1.3.0"
optional: true
description: "使用可见浏览器自动完成 MateClaw 钉钉渠道接入。遇到登录页必须暂停等待用户手动登录后继续。"
dependencies:
  tools:
    - browser_use
    - execute_shell_command
    - read_file
---

# 钉钉渠道接入（可见浏览器）

通过可见浏览器自动化完成钉钉应用创建与 MateClaw 渠道绑定。

## 强制规则

1. **必须使用可见浏览器**：`browser_use(action="start", headed=true)`
2. **遇到登录页必须暂停**：检测到登录界面立即停止，提示用户手动登录，收到"继续"后再执行
3. **配置变更后必须发布**：任何机器人配置修改都要"创建新版本 + 发布"，否则不生效

## 执行前确认（必须先做）

开始自动化前向用户确认以下可定制项（未指定则使用默认值）：

| 配置项 | 默认值 |
|--------|--------|
| 应用名称 | `MateClaw` |
| 应用描述 | `Your personal AI assistant` |
| 机器人图标 | `https://img.alicdn.com/imgextra/i4/O1CN01M0iyHF1FVNzM9qjC0_!!6000000000492-2-tps-254-254.png` |
| 机器人消息预览图 | 同上 |

**图片规范（务必告知用户）**：
- 机器人图标：JPG/PNG，240×240px 以上，1:1 比例，2MB 以内
- 消息预览图：PNG/JPEG/JPG，不超过 2MB

## 图片上传策略

1. 用户提供本地路径 → 直接上传
2. 用户提供图片链接 → 先下载到本地临时文件，再上传

**下载图片（跨平台）：**

macOS / Linux：
```
execute_shell_command(
  command="curl -L -o /tmp/bot_icon.png \"<图片URL>\""
)
```

Windows：
```
execute_shell_command(
  command="powershell Invoke-WebRequest -Uri '<图片URL>' -OutFile 'C:\\Temp\\bot_icon.png'"
)
```

**上传步骤**（必须按此顺序）：
1. 先 `browser_use(action="click", selector="<上传入口>")` 触发文件选择器
2. 再用文件上传操作（MateClaw browser_use 支持 file input 的 `type` 操作传入路径）

## 自动化流程

### 步骤 1：打开钉钉开发者后台

```
browser_use(action="start", headed=true)
browser_use(action="open", url="https://open-dev.dingtalk.com/")
browser_use(action="snapshot")
```

若页面显示登录界面，**立即暂停**：
> 检测到需要登录钉钉开发者后台。请在弹出的浏览器中完成登录，完成后回复"继续"。

### 步骤 2：创建企业内部应用

用户确认登录后：

1. 导航路径：应用开发 → 企业内部应用 → 钉钉应用 → 创建应用
2. 填写应用名称、应用描述
3. 保存创建

```
# 每次关键操作后都要截快照确认状态
browser_use(action="snapshot")
```

### 步骤 3：添加机器人能力

1. 进入「应用能力」→「添加应用能力」→ 找到「机器人」并添加
2. 打开机器人配置开关
3. 填写机器人名称、简介、描述
4. 上传机器人图标（见图片上传策略）
5. 上传消息预览图
6. 确认消息接收模式为 **Stream 模式**
7. 点击发布 → 确认发布弹窗

**发布是必须步骤，未发布前配置不生效。**

### 步骤 4：创建版本并发布

1. 进入「应用发布」→「版本管理与发布」
2. 创建新版本，填写版本说明
3. 应用可见范围选「全部员工」
4. 确认发布（有二次确认弹窗，选确认）
5. 看到「发布成功」状态才继续

### 步骤 5：获取凭证并引导绑定

1. 进入「基础信息」→「凭证与基础信息」
2. 告知用户 `Client ID`（AppKey）和 `Client Secret`（AppSecret）的位置
3. 引导用户在 MateClaw 控制台绑定：

**方式 A — 控制台前端：**
> 进入 MateClaw 管理界面 → 渠道 → 新建渠道 → 选择钉钉 → 填入 Client ID 和 Client Secret

**方式 B — 配置文件：**
```json
"dingtalk": {
  "enabled": true,
  "client_id": "你的 Client ID",
  "client_secret": "你的 Client Secret"
}
```

**Agent 不主动修改 MateClaw 配置文件，只引导用户操作。**

## 稳定性策略

- 优先使用 `snapshot` 返回的 `ref` 定位元素
- 每次关键点击 / 页面跳转后重新 `snapshot` 确认状态
- 页面结构与预期不符时重新 `snapshot`，按可见文本重新定位
- 租户权限、管理员审批等阻塞时，说明卡点，请用户手动完成该步骤

## 完成后关闭浏览器

```
browser_use(action="stop")
```
