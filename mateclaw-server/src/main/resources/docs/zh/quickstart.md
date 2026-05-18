# 快速开始

60 秒到第一条消息。**只有一条路：桌面端。**

Docker 和源码启动在 [配置说明](./config) 和 [贡献指南](./contributing) 里。这一页只做一件事——用尽可能快的速度，把你从"什么都没有"送到"一个能工作的 Agent"面前。

---

## 1. 下载

去 [GitHub Releases](https://github.com/matevip/mateclaw/releases) 拿最新安装包。

- **Windows**——`MateClaw-Setup-x.y.z.exe`
- **macOS**——`MateClaw-x.y.z.dmg`
- **Linux**——`MateClaw-x.y.z.AppImage`

不用装 Java。不用装 Node。不用装 Maven。桌面端已经把 JRE 21 和后端 JAR 打包好了。

## 2. 启动、登录

双击。首次启动要 10 到 30 秒，后端在后台起来。

账号 `admin`，密码 `admin123`。**进去之后第一件事**：`设置 → 安全` 里改密码。现在就改。

## 3. 配一个模型

`设置 → 模型 → 添加供应商`。

挑一个就行，别贪多：

- **DashScope**——云上最省事的起点，去阿里云控制台复制 Key 粘进来
- **OpenAI / Anthropic**——手头有 Key 就直接填
- **Ollama**——本地 GPU 用户，会自动识别 `localhost:11434`
- **ChatGPT OAuth**——有 Plus 或 Pro 账号，走浏览器登录一下，就能直接用 GPT-4o、o3、o4-mini

保存。模型会立刻出现在 Chat 页面的模型选择器里。

## 4. 打个招呼

左侧导航点 `聊天`。选一个 Agent。选刚配好的模型。输入：

> *你好。你现在能做什么？*

回车。看 token 流出来。

看到回答了——**系统活了，你已经在产品里了**。接下来所有事情都是在让它**对你有用**，而不是在让它"能跑"。

---

## 接下来先试这几件事

系统装好了。然后呢？

**试一次带工具的对话。** 输入："帮我搜一下 Spring Boot 最新版本，总结一下里面的 breaking changes。" 看 Agent 自己去调搜索工具、执行、观察结果、返回答案——这就是 ReAct 在工作。

**建一个自己的 Agent。** `Agents → 新建 Agent`，从模板开始（模板是开箱即用的），重命名、改 system prompt、勾选允许的工具、保存。Agent 是你从"一个聊天窗口"升级到"一整支 AI 团队"的方法。

**建一个知识库。** `Wiki → 新建知识库`，扔一份 PDF 或者指一个本地文件夹。等它消化完（每条 raw material 上都有进度条）。消化完之后把这个 KB 绑到一个 Agent 上，问里面的内容。底下到底发生了什么看 [LLM Wiki](./wiki)。

**接一个聊天渠道。** `渠道` 里选钉钉、Telegram 或者八个支持的平台里随便一个，贴 Bot 凭证。同一个 Agent 会立刻开始在那个渠道里回复——**带着它在你桌面端的全部记忆**。

每一件事在侧边栏里都有独立的文档页，想深入时再去读。

---

## 出了问题？

第一次跑通本应该很顺。如果没跑通——

- **安装器打不开**——Windows 下右键 → 属性 → 解除锁定；macOS 下去"系统设置 → 隐私与安全性"允许未签名应用。
- **后端起不来**——看 `~/.mateclaw/logs/app.log`（Windows：`%USERPROFILE%\.mateclaw\logs\`）。十有八九是 18088 端口被占了。
- **模型调用报错**——API Key 填错了，或者网络不通。回设置里检查，或者换一家试试。
- **界面白屏**——Ctrl/Cmd + Shift + R 强刷。Electron 的缓存比较顽固。
- **还是不行**——去 [GitHub Issues](https://github.com/matevip/mateclaw/issues) 开一个 Issue，把 `app.log` 的尾巴贴上。我们真的会看。

---

## 其他部署方式

- **Docker**——`cp .env.example .env` 填好密码，`docker compose up -d --build`。完整的前置要求、Maven 镜像选择（中国 / 美国）、浏览器工具自检、升级流程看 [Docker 部署](./docker-deploy)。
- **从源码跑**——`mateclaw-server/` 里 `mvn spring-boot:run`，`mateclaw-ui/` 里 `pnpm dev`。细节在 [贡献指南](./contributing)。
- **桌面端内部**——打包、签名、自动更新。看 [桌面应用](./desktop)。

---

下一步：去 [项目介绍](./intro) 看"为什么有这个东西"，或者直接跳到 [Agent 引擎](./agents) 看产品本身。
