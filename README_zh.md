<div align="center">

<p align="center">
  <img src="mateclaw-ui/public/logo/mateclaw_logo_s.png" alt="MateClaw Logo" width="120">
</p>

# 太一（MateClaw）

<p align="center"><b>你的超级大脑</b></p>

<p align="center"><sub><b>Agent Harness · Spring Boot 内核 · 一个 JAR 交付</b></sub></p>

[![GitHub 仓库](https://img.shields.io/badge/GitHub-仓库-black.svg?logo=github)](https://github.com/matevip/mateclaw)
[![文档](https://img.shields.io/badge/文档-在线-green.svg?logo=readthedocs&label=Docs)](https://claw.mate.vip/docs)
[![在线演示](https://img.shields.io/badge/演示-在线-orange.svg?logo=vercel&label=Demo)](https://claw-demo.mate.vip)
[![官网](https://img.shields.io/badge/官网-claw.mate.vip-blue.svg?logo=googlechrome&label=Site)](https://claw.mate.vip)
[![Java 版本](https://img.shields.io/badge/Java-21+-blue.svg?logo=openjdk&label=Java)](https://adoptium.net/)
[![Spring Boot](https://img.shields.io/badge/Spring%20Boot-3.5-brightgreen.svg?logo=springboot)](https://spring.io/projects/spring-boot)
[![Vue](https://img.shields.io/badge/Vue-3-4FC08D.svg?logo=vuedotjs)](https://vuejs.org/)
[![最后提交](https://img.shields.io/github/last-commit/matevip/mateclaw)](https://github.com/matevip/mateclaw)
[![许可证](https://img.shields.io/badge/license-Apache--2.0-red.svg?logo=opensourceinitiative&label=License)](LICENSE)

[[官网](https://claw.mate.vip)] [[在线演示](https://claw-demo.mate.vip)] [[文档](https://claw.mate.vip/docs)] [[English](README.md)]

</div>

<p align="center">
  <img src="assets/images/preview.png" alt="MateClaw 预览" width="800">
</p>

---

> **别的 AI 助手是给一个人用的。MateClaw 是公司允许部署的那一个。**
>
> 多用户工作空间。敏感操作走审批。完整审计日志。Spring Boot Actuator 健康监控。单个渠道挂掉不影响其他渠道的错误隔离。一个 JAR 包跑在自己机器上，数据不出门。
>
> **底下是个真 agent harness。** ReAct + Plan-and-Execute 跑在 StateGraph 运行时上——不是一次 RAG 调用披件外套。工具 · 技能 · MCP · ACP 收敛进同一个注册表，每位员工独立绑定。敏感工具调用走可审计的审批闸门。多厂商故障转移让循环在某家供应商挂掉时也不停。

大多数 AI 工具一到厂商抽风那天就两手一摊。关一次标签页就忘了你是谁。给你一个聊天框，就敢叫产品。

**MateClaw 是完整的一整套。** 一次部署——推理、知识、记忆、工具、多渠道入口，从第一天就一起设计，不是事后拼接。主模型挂了，下一家接着把这句话说完。

---

## 三件让它与众不同的事

### 1 · 模型挂了，AI 不挂

Key 过期。厂商返回 401。网络抖动。配额耗尽。

别的工具丢你一张红色错误卡。MateClaw 自动切到下一家健康的供应商——DashScope、OpenAI、Anthropic、Gemini、DeepSeek、Kimi、Ollama、LM Studio、MLX，共 14+ 家——用户只会看到回答正常完成。内置的 **Provider Health Tracker** 会把连续失败的供应商放进冷却窗口，避免每一轮对话都白白撞壁。

你不用写重试脚本。在 **设置 → 模型** 里把供应商拖成你想要的优先顺序，健康面板实时亮起一排绿点——请求绕着故障流过去。

### 2 · 知识会自己长出链接

上传 PDF、一批 markdown、抓下来的网页——原始材料进去。

MateClaw 的 **LLM Wiki** 把它消化成结构化页面，页面之间自己长出 `[[链接]]`，每一句话都记得来自哪里。点开引用抽屉，就能看到原始 chunk。问一个问题，得到的页面是从对应片段拼出来的——带可核对的出处。

这是**仓库**和**图书馆**的区别。

### 3 · 一个产品，五个入口

| 入口 | 它是什么 |
|---|---|
| **Web 控制台** | 完整的管理后台——数字员工、模型、技能、知识、安全、定时任务、**运行时控制台**（看见每位员工正在干什么、一键回收） |
| **桌面端** | Electron + 内嵌 JRE 21，双击即用，无需装 Java |
| **网页嵌入式聊天** | 一个 `<script>` 标签就能嵌进任何网站 |
| **IM 渠道** | 钉钉 · 飞书 · 企业微信 · 微信 · Telegram · Discord · QQ · Slack |
| **插件 SDK** | Java 模块，供第三方扩展能力包 |

同一个大脑。同一份记忆。同一套工具。不同的门。

<p align="center"><b>$0 · 无 token 计费。无座位收费。你的服务器，你的数据，你的 Key。</b></p>

---

## 盒子里有什么

### 数字员工，不是聊天机器人
你雇佣员工，不是开聊天框。每位有**角色**、**目标**、**背景故事**，像素艺术头像、专属配色——5 个职业模板（产品研究员 · 客户支持 · 知识管理员 · 数据分析师 · 行政助理）开箱可用。**ReAct** 做迭代推理，**Plan-and-Execute** 做复杂多步任务，员工之间可以并行委派。动态上下文裁剪、智能截断、僵死流清理——让长对话真正能用的那些"不起眼"的基础设施。

### 知识与记忆
- **LLM Wiki** — 原始材料消化成有链接、带引用的结构化页面；**热点缓存**自动注入到员工的 system prompt。**加工器引擎**（1.3.0+）把 Wiki 从"搜索索引"升级为"处理流水线"
- **工作区记忆** — `AGENTS.md` / `SOUL.md` / `PROFILE.md` / `MEMORY.md` / 每日笔记
- **记忆生命周期** — 对话后自动提取 · 定时整理 · Dreaming 工作流。工作流也可以通过 `write_memory` step 直接写进员工的 `MEMORY.md`

### 技能 · MCP · ACP — 三种"接外部能力"的方式
- **SKILL.md 技能包** — 一份 manifest + prompt + 工具列表 + **LESSONS.md（用得越多越聪明）**。8 个起步模板 + 5 步创作向导，安装前自动跑 **Pre-flight 检查**告诉你缺什么
- **MCP** — stdio / SSE / Streamable HTTP 三种传输，接入任意外部工具服务器。**每位员工独立绑定**（1.3.0+）——一位员工装的工具不会渗到其他人的工具栏里
- **ACP** — 把 Claude Code、Codex 这种顶级编码 Agent 以"员工"身份接入，桥接成技能卡 + 包装工具
- **Tool Guard** — RBAC + 审批流 + 文件路径保护。能力必须有边界

### 业务流程编排（1.3.0+）
- **工作流（Workflow）** — 把多位员工 + 系统动作（审批 / 渠道分发 / 写记忆）按线性 step DSL 编排成一条可发布、可触发、可重放的业务流程。7 种 step mode（`sequential` / `fan_out` / `collect` / `conditional` / `await_approval` / `dispatch_channel` / `write_memory`）。JSON-first 编辑（Monaco + JSON schema + Pebble 静态检查），或者用一句话生成草稿
- **触发器（Trigger）** — 把"系统里发生的事"自动接到工作流或员工对话上。6 种 pattern type（`cron` / `webhook` / `channel_message` / `agent_lifecycle` / `content_match` / `workflow_completion`）。事件治理默认开：去重、per-trigger 限速、bot 自循环过滤、A→B→A 递归保护、未知 pattern fail-closed
- **Wiki 加工器** — Wiki 不再只是被动检索。用户自定义模板对原料或现有页面跑模板，跨原料 map-reduce 聚合，reverse-citation 绑定到源 chunk，JSON 输出 + 可选 JSON Schema，每个模板独立选模型

### 你看得见每位员工正在干什么
**Admin 运行时控制台**（`后台 → 系统 → 运行时`）——谁在跑、跑到哪一步、占多少 token、卡住了一键回收。流式分阶段显示（思考 / 工具 / 回答），SSE 每事件 ID 支持安全重连，多员工协作不打架，长任务必须有真实证据才回答。

### 多模态创作
语音合成 · 语音识别 · 图片 · 音乐 · 视频 · 3D。一等公民，不是附加插件。**多模态旁路**（1.3.0+）让纯文本主模型遇到图片附件时自动调用配置好的视觉模型转描述，主对话保持便宜。**图像编辑**也到位：用 `msg:<id>:<idx>` 引用会话里更早的某张图，让模型改色、改风格。**4 个文档生成工具**（`DocxRenderTool` / `XlsxRenderTool` / `PptxRenderTool` / `PdfRenderTool`）在 JVM 内把 Markdown 直接渲染成 Office 文件——不 fork 子进程、不依赖 npm、不需要装 Office。

### 企业就绪
RBAC + JWT。**Personal Access Token** 给无人值守脚本和 CI 用。**Webhook 出站 HMAC-SHA-256 签名**。**Cron 分布式锁**多实例不双发。完整审计事件流。Flyway 管理数据库 schema，升级时自愈。一个 JAR 交付。生产用 MySQL，开发用 H2，代码零改动。

---

## AI 正在变成基础设施

2026 年 3 月 2 日，Claude 全球宕机 **4 小时**——API、Web、移动端同时黑屏。三周后又来一次，**5 小时**。每一家把 AI 战略押在单一厂商身上的公司，那几个小时只能盯着红色错误卡。

这和 2010 年数据库走过的路、2018 年云走过的路**是同一个转弯**：赢的那一层，不再绑在一家供应商身上。**57% 的公司已经把 AI agent 推进生产**——没有一家希望某个厂商的坏日子变成自己的坏日子。

**MateClaw 就是那一层——用 Spring Boot 方式盖的。**

---

## 为什么选 MateClaw

| | MateClaw | [OpenClaw](https://github.com/openclaw/openclaw) | [Hermes Agent](https://github.com/NousResearch/hermes-agent) | [Claude Code](https://github.com/anthropics/claude-code) | [Cursor](https://cursor.com) |
|:---|:---:|:---:|:---:|:---:|:---:|
| **多厂商失败转移** | **Chain + 健康追踪 + 冷却** | 切换供应商（改配置） | 内置编排重试 | 仅 Anthropic | 单模型 |
| **知识消化式加工** | **Wiki + 页面级引用溯源** | Canvas + 记忆 | Skills Hub + 记忆 | — | 代码索引 |
| **多用户管理** | **RBAC + 审批流 + 审计 + 运行时控制台** | 配置文件优先 | 单用户 CLI | 企业版 | 团队版 |
| **能力扩展接口** | **技能 (LESSONS) + MCP + ACP** | — | — | MCP | MCP |
| **用户触点** | Web 管理台 + 桌面 + 嵌入 + SDK + 8 IM | 25+ 聊天渠道 | 15+ 渠道（CLI 为主） | 3 IM（预览） | 仅 IDE |
| **技术栈** | **Java（Spring Boot）** | TypeScript | Python | TypeScript | Electron/TS |
| **许可 / 定价** | **Apache 2.0 · 免费** | MIT · 免费 | MIT · 免费 | 闭源 · $20–200/月 | 闭源 · $0–200/月 |

**OpenClaw 和 Hermes Agent 是优秀的个人 AI 平台**——如果你是一个人、一台笔记本、习惯从 CLI 搭自己的 agent、所有东西都靠手工配置文件调优，选它们没问题。两家的社区规模今天都大于 MateClaw。

**MateClaw 是那个给团队用的版本。** 每位数字员工、每个模型、每个工具都有 RBAC。危险动作自动暂停等审批。完整审计事件流。Admin 运行时控制台让一个运维能实时看到 50 位员工跑在 14 家供应商上的状态——卡住了一键回收。底座是 Spring Boot——任何一家已经在生产跑 Java 服务的公司可以直接并入。

**同一套"完整一整套"哲学，不同的重心。**

---

## 快速开始

```bash
# 后端
cd mateclaw-server
mvn spring-boot:run           # http://localhost:18088

# 前端
cd mateclaw-ui
pnpm install && pnpm dev      # http://localhost:5173
```

默认登录：`admin` / `admin123`

### Docker 部署

```bash
cp .env.example .env
docker compose up -d          # http://localhost:18080
```

### 桌面端

从 [GitHub Releases](https://github.com/matevip/mateclaw/releases) 下载安装包。内嵌 JRE 21，无需额外装 Java。

---

## 架构全景

<p align="center">
  <img src="assets/architecture-biz-zh.svg" alt="业务架构" width="800">
</p>

<details>
<summary><b>技术架构</b></summary>
<p align="center">
  <img src="assets/architecture-tech-zh.svg" alt="技术架构" width="800">
</p>
</details>

---

## 项目结构

```
mateclaw/
├── mateclaw-server/        Spring Boot 3.5 后端（Spring AI Alibaba · StateGraph 运行时）
├── mateclaw-ui/            Vue 3 + TypeScript 管理 SPA（构建产物打进后端 JAR）
├── mateclaw-webchat/       网页嵌入式聊天组件（UMD / ES bundle）
├── mateclaw-plugin-api/    第三方能力插件的 Java SDK
├── mateclaw-plugin-sample/ 参考插件实现
├── docker-compose.yml
└── .env.example
```

桌面端安装包通过 [GitHub Releases](https://github.com/matevip/mateclaw/releases) 分发，内嵌 JRE 21——无需安装 Java。

## 技术栈

| 层次 | 技术 |
|---|---|
| 后端 | Spring Boot 3.5 · Spring AI Alibaba 1.1 · MyBatis Plus · Flyway |
| 数字员工运行时 | StateGraph · ReAct + Plan-Execute · 角色 / 目标 / 背景故事 · LESSONS 自我进化 |
| 业务编排 | 工作流（7 step mode · Pebble DSL）· 触发器（6 pattern type · 事件治理）· Wiki 加工器（1.3.0+）|
| 能力扩展 | SKILL.md 包 · MCP（stdio / SSE / HTTP · per-agent 绑定）· ACP 桥接（Claude Code / Codex） |
| 数据库 | H2（开发）· MySQL 8.0+（生产）|
| 认证 | Spring Security + JWT |
| 前端 | Vue 3 · TypeScript · Vite · Element Plus · TailwindCSS 4 |
| 桌面端 | Electron · electron-updater · 内嵌 JRE 21 |
| Webchat | Vite library 模式 · UMD + ES bundle |

---

## 文档

完整文档 **[claw.mate.vip/docs](https://claw.mate.vip/docs)**——安装、架构、各子系统、API 参考。

## 路线图

**v1.3.0（2026-05-13 发布）** — 工作流引擎 · 6 种 pattern 触发器 · Wiki 加工器 · 每员工独立 MCP 绑定 · 多模态旁路路由 · 4 个 JVM 原生文档生成工具 · 图像编辑。完整故事见 [v1.3.0 release notes](https://claw.mate.vip/docs/zh/releases/1.3.0)。

**下一步** — 工作流画布可拖拉编辑 · 运行回放时间线 · `loop` / `invoke_skill` step mode · 触发器优先级 + 事件回放 · 行业场景应用市场 · 更多 ACP 上游集成。

## 参与贡献

```bash
git clone https://github.com/matevip/mateclaw.git
cd mateclaw
cd mateclaw-server && mvn clean compile
cd ../mateclaw-ui && pnpm install && pnpm dev
```

---

## 为什么叫这个名字

**Mate** 是陪伴。**Claw** 是能力。

一个陪在你身边的系统——也是一个真的能抓住工作、把它推向完成的系统。

## 许可证

[Apache License 2.0](LICENSE)。没有星号。
