# 更新日志

每个 MateClaw 版本的发布说明。最新的文档始终以 `docs/zh/` 为单一事实源——这些说明讲的是每个版本里**改了什么**。

历史 diff 看对应的 git tag。功能背后的"为什么"点进完整的发布说明。

---

## 发布列表

| 版本 | 日期 | 亮点 |
|------|------|------|
| [v1.7.0](./releases/1.7.0) | 2026-06-25 | 审批体系打通三条链路（工作流审批渠道通知 + resolve→resume 桥接 · WebChat/API-Key 渠道审批 resolve+replay · 飞书/企微卡片点击 resolve 工作流审批） · 长任务「运行总览」侧栏（计划进度 + 子 Agent 实时状态 · 窄屏抽屉） · 运营数据一键导出（Dashboard 9 表 Excel + CLI 命令行 + 项目级 CLI 框架） · 桌面端远程 Server 连接 + `mateclaw-desktop` 源码开源 · OpenAPI/Swagger 可调试 · 聊天回到底部浮动按钮 |
| [v1.6.0](./releases/1.6.0) | 2026-06-22 | 跑在国产数据库上 —— KingbaseES(人大金仓)+ PostgreSQL（共用一套 PostgreSQL 家族迁移树 · 按需金仓驱动 · Docker 最小权限角色） · 新感官与双手（图片跨轮次留在上下文 + `image_analyze` · `execute_code` 运行员工编写的代码） · 你来塑造员工（AGENTS.md 编辑器 + About You 身份 + 运行时模型身份 + KB 范围绑定 + 花名册标签） · Wiki Sources 标签（素材与监听合并、按 KB 自动同步、多路径/glob、pageType 表单编辑器） · 全局出站 HTTP/SOCKS 代理 · 确定性 Markdown 回答 · Claude Fable 5 |
| [v1.5.0](./releases/1.5.0) | 2026-06-04 | 目标长出清单——从"打个分"到"逐条勾"（checklist + Evaluator SPI + 确定性完成判定） · Wiki 学会自维护（`[[wikilink]]` 互联 + 改名/删页级联修链 + 坏链体检 · 事实/经验分层 + 失效传播 · pageType 档案与 per-agent 权限 · 处理流水线 · 本地目录知识源定时增量同步） · 记忆按主人隔离（owner_key + 个人/团队/全局可见性 + 第三方 endUserId 透传） · 每个员工绑主知识库 · 偏好提供商决定主模型 + Claude Opus 4.8 |
| [v1.4.0](./releases/1.4.0) | 2026-05-23 | 持久化目标——员工锁住目标自己跟到完成 · 子员工委派变成一棵树（递归 3 层 + 异步 + 数字员工构建器） · 渐进式工具/技能披露（`enable_tool` + `load_skill`） · 工作空间 RBAC（四级角色 + 能力门禁） · 飞书做成一等公民（互动/审批/流式卡片 + 语音/文件音视频 + 渠道原生工具） |
| [v1.3.0](./releases/1.3.0) | 2026-05-13 | 工作流元年——7 种 step mode 把员工组装成业务流程 · 触发器 6 种 pattern 让事件自动启动流程 · Wiki 从搜索索引升级为处理流水线（用户模板 + 跨材料聚合 + reverse-citation） · MCP per-agent 工具绑定 + 多模态旁路路由 · 4 个 JVM 原生文档生成工具 + 图像编辑 |
| [v1.2.0](./releases/1.2.0) | 2026-05-05 | 智能体改名"数字员工"（角色 / 目标 / 背景故事 + 5 职业模板） · 技能成了骨架（manifest + 模板向导 + LESSONS 自我进化） · ACP 接入：Claude Code / Codex 变成你的员工 · Admin 运行时控制台让你看见每个员工正在干什么 |
| [v1.1.137](./releases/1.1.137) | 2026-04-29 | 它会从昨天学习了 · 一个模型坏了不会整体掉线 · "差一点就好"的地方现在好了 · 知识库变成了一座图书馆 |
| [v1.1.0](./releases/1.1.0) | 2026-04-17 | Agent 自动技能合成、多 agent 并行委派、Wiki 语义搜索 + 两阶段摘要、深度思考、Anthropic prompt 缓存、声明式 Hook、插件 SDK、全渠道语音、ChatConsole 多渠道实时同步、微信稳定性重建 |
| [v1.0.418](./releases/1.0.418) | 2026-04-11 | 后端国际化 (i18n)、Flyway 数据库迁移框架、WorkspacePathGuard 路径沙箱、CronJobTool 定时任务、Skill ZIP 导入、安全加固 |
| [v1.0.314](./releases/1.0.314) | 2026-04-08 | LLM Wiki 知识库、TTS/STT、音乐生成、图像/视频升级、带 keyless fallback 的搜索系统、ChatGPT OAuth 登录、Agent 运行时增强、数据库 schema 统一 |
| [v1.0.108](./releases/1.0.108) | 2026-04-06 | 数据源 SQL 查询、多模态增强、桌面动态端口、OpenRouter 免费模型 |
| [v1.0.101](./releases/1.0.101) | 2026-04-05 | 移动端布局、Ollama 启动时自动探测、模型分组、GitHub MCP、拖拽文件上传、多 Agent 协作 |
| [v1.0.0](./releases/1.0.0) | 2026-03-20 | 首次发布——ReAct + Plan-Execute Agent、12 个内置工具、MCP 协议、6 个渠道适配器、Vue 3 管理控制台 |

---

## 接下来读什么

- [路线图](./roadmap)——计划中的、进行中的、已完成的
- [项目介绍](./intro)——MateClaw 为什么存在
- [贡献指南](./contributing)——怎么帮忙发布下一个版本
