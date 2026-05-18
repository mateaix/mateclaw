# 路线图

> "人们不知道自己想要什么，直到你把它摆在他们面前。"
>
> 这不是一份功能清单。这是一个关于**你的 AI 助手应该如何存在**的宣言。

---

## 我们的信念

每个人都值得拥有一个真正理解自己的 AI 助手。

不是一个聊天玩具。不是一个技术 demo。而是一个**数字分身**——它知道你的工作方式，连接你的所有工具，替你思考、替你执行、替你记住。

MateClaw 就是这个东西。

---

## 我们已经做到了什么

### v1.0 —— 它能思考和行动 ✅ 已发布

让一个 AI 助手成为"会用工具的同事"，不是一个聊天框。

- ReAct 引擎：思考、行动、观察、再思考
- Plan-and-Execute 编排：先制定计划，再逐步执行
- StateGraph 架构：基于状态图的 Agent 编排
- DynamicAgent：从数据库加载配置，运行时随时调整
- 20 个内置工具：搜索 / Shell / 文件 / 委派 / 多模态生成 / 定时任务 / SQL 查询
- ToolGuard + FileGuard + AuditLog：每一次工具调用有审批、有控制、有记录
- SKILL.md 技能系统：像装 App 一样给 AI 装新能力

### v1.1 —— 它无处不在 ✅ 已发布

把 AI 从"网页上的对话框"搬进你团队真正在用的每一个 IM。

- **8 个渠道**：Web / 钉钉 / 飞书 / 企业微信 / Telegram / Discord / QQ / 微信个人 / Slack
- 会话来源追踪：每条消息都知道来自哪个渠道
- 4 层记忆：会话上下文 + 工作空间记忆 + 对话后提取 + 每天凌晨 2:00 自动整合
- DREAMS.md 整合日记：人类可读的记忆变更审计
- 工作空间隔离：每个 agent / skill / wiki / conversation / memory 都属于一个工作空间
- ChatGPT OAuth + Anthropic Claude Code OAuth：用订阅直接登录，不需要 API Key
- LLM Wiki 知识库 + RAG：把原始文件吃进去变成结构化、有双向链接、有摘要的知识页

### v1.2 —— 它是你的同事 ✅ 已发布（2026-05-05）

把"智能体"换成**数字员工**——这不是术语洁癖，是世界观换了。

- **数字员工**：每位有角色（Role）、目标（Goal）、背景故事（Backstory），不是冰冷的 system prompt
- **5 个职业模板**：产品研究员 / 客户支持 / 知识管理员 / 数据分析师 / 行政助理——开箱即用
- **技能不再是工具的别名，是骨架**：每个技能有自己的 SKILL.md + LESSONS.md + workspace 文件空间
- **ACP 桥接**：Claude Code、Codex、Gemini CLI 这些顶级编码 Agent 以"员工"身份接入
- **Backstage 运行时控制台**：你第一次能**看见每个员工正在干什么**——谁在跑、跑到哪一步、占多少 token、卡住了一键回收
- **Onboarding wizard**：首次登录四步从零到第一条消息
- **Dashboard**：日维度 usage 趋势 + 头部 agent / tool 排行
- **Doctor**：系统健康检查 + 一键修复

完整故事：[v1.2.0 Release Notes](./releases/1.2.0.md)。

---

## v1.3 —— 工作流元年 ✅ 已发布（2026-05-13）

> "聚焦不是对要关注的事情说 Yes。而是对其他一百个好点子说 No。"

数字员工各自能干活只是起点。**真正的协作需要编排**。

v1.3 的主线是**让 MateClaw 从"chatbot 框架"升级为"业务流程 OS"**——一条业务流不再是几个员工各自聊天的总和，而是一份可发布、可触发、可重放的**线性 step DSL**。

完整故事：[v1.3.0 Release Notes](./releases/1.3.0.md)。

### 工作流（Workflow）

- [x] **7 种 step mode**：sequential / fan_out / collect / conditional / await_approval / dispatch_channel / write_memory
- [x] **Pebble 表达式子集**作为条件判断 + 变量引用语言（不带副作用、不能跑代码）
- [x] **JSON-first 编辑**：Monaco + JSON schema 校验 + Pebble 静态检查 + 模板下拉
- [x] **自然语言 → 工作流草稿**（`POST /workflows/draft/generate`）：用户描述需求，agent 生成 graph_json + 编译诊断；不直接发布，仍要人工审阅
- [x] **整数 revision**：发布写新行不可变；草稿与已发布版本分离
- [x] **运行历史**：每个 step 的 input / output / 耗时 / token / 失败链路都被记录
- [x] **payload 内置存储**：大输入输出走 `payload://` URI，不撑库
- [x] **跨 workspace ACL**：发布期校验 agent / channel / employeeId 引用都在当前 workspace 内
- [x] **`await_approval` 持久化暂停**：服务重启不丢

### 触发器（Trigger）

- [x] **6 种 pattern type**：cron / webhook / channel_message / agent_lifecycle / content_match / workflow_completion
- [x] **事件治理默认开**：去重（60s 窗口）、per-trigger 限速、bot self-msg 过滤、A→B→A 递归切断
- [x] **CronDelegationPort**：和老 cron 模块共享 ShedLock + Spring TaskScheduler，不写 mate_cron_job
- [x] **跨实例一致性**：`pattern_version` 自取消机制 + 周期 syncFromDatabase
- [x] **结构化表单**：6 种 pattern 各自有专属字段输入，不需要手写 patternJson

### 升级现有体验

- [x] **图像编辑**（issue #75）：`image_generate` 工具新增 `image` / `images` 参数，支持 5 种引用形式（含 `msg:<id>:<idx>` 引用会话内附件）
- [x] **DashScope 兼容模式**：复用同一把 sk- Key 接通点号版本号系列（qwen3.5-plus / qwen3.6-plus / qwen3-vl-plus 等）
- [x] **新万相 / qwen-image 系列**：14 个新图像模型，3 个新视频模型（含 happyhorse-1.0-t2v）
- [x] **4 个文档生成工具**：DocxRenderTool / XlsxRenderTool / PptxRenderTool / PdfRenderTool —— Markdown 直接渲染为 Office 文件，不 fork 子进程不依赖 npm
- [x] **MCP per-agent 工具绑定**：每个员工独立绑定 MCP 工具 + 状态徽标（connected / stale / unavailable / orphan）+ 命名空间冲突自动前缀化 + server 改名自动跟随
- [x] **小米 MiMo provider**：MiMo V2.5 Pro / V2.5 / V2 Pro / V2 Omni / V2 Flash
- [x] **多模态旁路路由**（issue #87）：纯文本主模型遇到图片附件时自动调用配置好的视觉模型转描述，主对话保持便宜；硬禁令拆掉后用户自定义工具不再被压制；路由徽章 + 输入框提示让决策全程可见

### v1.3 还要做的

- [ ] **画布编辑器（v1）**：当前画布是只读链式渲染，目标是 `@vue-flow/core` 的可拖拉编辑
- [ ] **运行回放视图**：trace timeline + 任意节点 hover 看 input/output diff
- [ ] **`loop` mode**：迭代 N 次或对数组逐项处理
- [ ] **`invoke_skill` mode**：直接调 skill 不经过员工
- [ ] **trigger 间优先级 / 依赖**：同一事件命中多 trigger 时的串行 / 并行控制
- [ ] **事件回放**：`mate_trigger_event` 加 "重新派发"按钮

---

## 下一站：v1.4 —— 场景应用元年

> "当工具足够好，就把工具藏起来，把场景推到前面。"

v1.0 → v1.3 把基础设施做齐了：员工、记忆、知识库、工具、技能、工作流、触发器、多模态、多渠道。**下一步不是再造一颗螺丝**，是把这些零件组装成**用户一打开就能落地的场景**。

v1.4 的关键词是**场景应用**。不是"加更多功能"，是**让普通用户不用学 7 种 step mode、6 种 trigger pattern 就能直接用**。

### 行业场景模板（Workflow + Trigger 联动）

每一个都是一份**可一键导入的工作流模板 + 触发器配置 + 推荐员工绑定 + 推荐知识库结构**：

- [ ] **客户工单分流**：企业微信 / 飞书入口 → 数字员工分类 → 路由 / 升级 / 自动回复 → 写进客户档案
- [ ] **晨报 / 周报自动化**：cron trigger → 多员工并行采数 → 数据分析员工汇总 → 生成 PDF/PPTX → 多渠道分发
- [ ] **合同审批流**：上传合同 → 法务员工初审 → 审批等待 → 法务员工修订建议 → 写归档记忆
- [ ] **市场情报监控**：webhook trigger（站点变更）→ 内容判断（content_match）→ 商业分析员工总结 → 飞书机器人推送
- [ ] **新员工 onboarding**：webhook（HRIS 入职事件）→ 行政助理拉文档清单 → 培训知识库引导 → 多日跟进 trigger
- [ ] **代码 PR 审查**：GitHub webhook → 代码审查员工跑 review → 评论回写 PR → 关键改动转 await_approval

### 场景市场（Scenario Marketplace）

- [ ] **场景包格式**：一个场景 = `workflow.json` + `triggers.json` + `agents/*.md` + `knowledge/*.md` + `README.md`，可分享 / 安装
- [ ] **场景市场 UI**：浏览 / 试运行 / 一键安装 / 评分评论
- [ ] **场景包版本管理**：升级提示 + diff 预览 + 回滚

### 让数字员工跨场景协作

- [ ] **员工目录画像**：每位员工自动生成"擅长 / 不擅长"标签（基于历史交互 + 技能 + 工具集）
- [ ] **场景智能推荐**：用户描述"我想要 X 流程" → 推荐最适合的场景模板 + 已有员工
- [ ] **跨场景记忆共享**：客户工单分流和合同审批流见到的都是同一个客户档案

### 把基础设施进一步藏起来

- [ ] **自然语言 → 完整场景包**：v1.3 已有"自然语言 → 工作流草稿"，v1.4 把它扩展到**整个场景**——一句话描述出 workflow + trigger + 推荐员工 + 推荐 KB 结构的完整草案
- [ ] **典型问题向导**：把"我的工作流卡在审批没人审"这种问题做成自助诊断
- [ ] **场景级仪表盘**：不是"今天 token 用了多少"，是"今天客户工单平均处理多久"

### 同步推进的基础能力

- [ ] **场景级 ACL**：场景包安装时一次性把所需的 channel / agent / KB / 工具的 allowlist 都配好
- [ ] **跨 workspace 场景共享**：场景模板能在多个工作空间间复用（克隆 + 覆盖配置）
- [ ] **场景运行成本预估**：安装前看见预期 token / API 调用 / 触发频率

---

## 我们故意不做的事

> "我对我们没做过的事情和我们做过的事情一样感到自豪。"

| 砍掉的功能 | 为什么 | 什么时候才该做 |
|-----------|--------|--------------|
| **完整 RBAC 权限模型** | MateClaw 是数字员工系统，不是企业管理平台。单团队不需要管理 100 种权限组合 | 当真正出现需要细粒度权限的多团队 SaaS 客户时 |
| **多租户** | 同上。过早的多租户是架构癌症 | 当有明确的 SaaS 商业化路径时 |
| **SSO / LDAP / SAML** | 企业集成是个无底洞 | 当付费企业客户明确要求时 |
| **30+ 节点的可视化工作流编辑器** | 用户大多用不上。**v1.3 的 7 种 step mode 已经覆盖 90% 实际场景**，剩下的复杂度推到 LLM 自然语言生成 | 真有用户场景需要 30+ 节点时（很少） |
| **移动端原生 App** | 8 个 IM 渠道 + 桌面端 + Web 已经覆盖。你在手机上用钉钉 / 飞书 / Telegram 就在用 MateClaw | 当 Web / IM 渠道有不可替代的移动专属能力时 |
| **替代 ReAct / Plan-Execute** | 工作流和这两条引擎**是协作关系**，不是替代——单 agent 多轮推理仍在那两条引擎里 | 永远不替代 |

---

## 版本里程碑

| 版本 | 一句话 | 用户体验目标 | 状态 |
|------|--------|-------------|------|
| **v1.0** | 它能思考和行动 | 一个能用工具解决问题的 AI 助手 | ✅ 已发布 |
| **v1.1** | 它无处不在 | 8 个渠道 + 4 层记忆 + 工作空间 + LLM Wiki | ✅ 已发布 |
| **v1.2** | 它是你的同事 | 数字员工 + 5 个职业模板 + 骨架式技能 + ACP 桥接 + Backstage 运行时 | ✅ 已发布 |
| **v1.3** | 它能编排业务流 | 工作流 + 触发器 + 图像编辑 + 文档生成 + per-agent 工具绑定 | ✅ 已发布 |
| **v1.4** | **它能落地场景** | **行业场景模板 + 场景市场 + 自然语言生成工作流 + 跨场景员工画像** | 📋 规划中 |

---

## One More Thing

我们做 MateClaw，不是为了追赶 ChatGPT、不是为了做下一个 Dify、不是为了融资 PPT 上多一个 buzzword。

我们做它，是因为我们相信一件事：

**AI 不应该是一个网页上的对话框。它应该是你的第二个大脑。**

它住在你的钉钉里、你的飞书里、你的 Telegram 里。它读过你所有的文档。它记得你三个月前说过的话。它会用你公司的内部工具。它在你睡觉的时候整理记忆。**它能替你跑一整条业务流程**。

总有一天，你会忘记它是一个程序。

**那一天，就是我们成功的那一天。**

---

*Stay hungry. Stay foolish.*
