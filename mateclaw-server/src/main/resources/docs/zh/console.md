# 控制台 UI

控制台是每个 MateClaw 部署都自带的 Vue 3 SPA。它跑在你的浏览器里（或者 Electron 桌面窗口里），通过 REST + SSE 跟 Spring Boot 后端对话，把 MateClaw 的每一项能力——聊天、Agent、知识、工具、技能、渠道、安全、定时任务、用量分析——都放在**同一套登录**后面。

这一页是地图。它按侧边栏分组、按页面一个一个走下来，同时指出每个页面用的 API 端点。

---

## 技术栈

- **框架**——Vue 3 + Composition API + TypeScript
- **状态**——Pinia（领域驱动）
- **UI**——Element Plus + TailwindCSS 4 + `--mc-*` CSS 变量
- **构建**——Vite 6
- **路由**——Vue Router，history 模式
- **i18n**——vue-i18n（`zh-CN` / `en-US`）
- **HTTP**——Axios 管 REST，原生 `fetch` 管 SSE
- **认证**——JWT，自动注入 + 滑动窗口续签

---

## 布局

左侧栏 + 右内容区（`MainLayout.vue`）。侧栏可以折叠，状态持久化到 `localStorage`。侧栏底部是主题切换（浅/深/跟系统）和用户信息。

### 侧栏分组

按基于意图的信息架构分成六段：

| 分组 | 页面 |
|------|------|
| **Chat** | 聊天控制台 |
| **Use** | Agent、Wiki、记忆浏览器、多模态工作室、会话 |
| **Extend** | 工具、技能、MCP 服务 |
| **Operate** | 渠道、定时任务、Token 用量、仪表盘、数据源 |
| **Workspace** | 当前工作空间概览、成员、活动 |
| **System** | 设置、安全与审批、Doctor、引导 |

**你没权限看的页面会被隐藏**。

### 侧栏通知徽标（1.4.0 新增）

侧栏会在两处冒出实时徽标，提示需要你处理的事：

- **待审批**——红色数字角标，点击跳到 [安全与审批](./security)
- **卡死员工**——橙色小圆点，点击跳到员工页的**实时**运行时视图（见 [Backstage](./backstage)）

### 认证守卫

除了 `/login` 之外每条路由都被路由守卫保护。开发时设置 `VITE_SKIP_AUTH=true` 绕过。

---

## 页面

### 1. 登录

**路由：** `/login`

用户名密码表单。

- 成功后把 token + 用户名 + 角色 + 活跃工作空间存进 `localStorage`
- 重定向到聊天控制台（或者首次登录时重定向到引导向导）

**API：** `POST /api/v1/auth/login`

**默认凭证：** `admin` / `admin123`——**立刻改掉**。

---

### 2. 引导向导

**路由：** `/onboarding`

首次登录自动显示。四步向导：

1. **欢迎**——简短的产品概览
2. **配一个模型**——选一个供应商粘贴 API Key（或 OAuth 进 ChatGPT Plus、或自动探测 Ollama）；1.4.0 起这一步直接做**供应商启用**——勾上要用的供应商即开即用
3. **挑一个 Agent 模板**——根据选择种一个默认 Agent
4. **发第一条消息**——一个测试 prompt，让你能看到流式工作

跳过后直接落到聊天控制台。

---

### 3. 聊天控制台

**路由：** `/chat`

主交互面。会话在左，当前对话在右。

特性：

- **Agent 选择器**——每个 Agent 有自己的会话历史
- **会话列表**——按日期分组（今天 / 昨天 / 最近 7 天 / 更早）
- **模型切换器**——为这次对话选任何一个已配置的模型
- **分组的模型下拉**——按供应商和标签分组，带搜索
- **消息气泡**——Markdown + 代码高亮
- **分段消息**——thinking / tool_call / tool_result / content 渐进加载、实时持久化
- **思考面板**——展开/折叠看推理链
- **工具调用可视化**——工具名、参数、结果内嵌
- **阶段状态指示器**——流式过程中当前阶段
- **持久任务清单**——Plan-and-Execute 的计划和步骤状态在一个**扛得住刷新**的侧边面板里
- **工具审批卡片**——内嵌的批准/拒绝按钮
- **文件上传**——点击 / 粘贴 / 拖拽
- **停止生成**——中途打断
- **建议**——会话为空时显示的 prompt 芯片

**API：**

- `POST /api/v1/chat/stream`——SSE 流式（原生 fetch）
- `POST /api/v1/chat/upload`
- `POST /api/v1/chat/{conversationId}/stop`
- `POST /api/v1/approvals/{id}/resolve`
- `GET /api/v1/chat/{conversationId}/pending-approvals`
- `GET /api/v1/conversations`——列表
- `GET /api/v1/conversations/{id}/messages`
- `DELETE /api/v1/conversations/{id}`
- `GET /api/v1/agents`
- `GET /api/v1/models/enabled`
- `GET/PUT /api/v1/models/active`

---

### 4. Agents

**路由：** `/agents`

Agent 的 CRUD，表格呈现。

- 按类型搜索和过滤（全部 / ReAct / Plan-Execute）
- 从模板选择器创建
- 编辑 system prompt、工具、知识库绑定、最大迭代、图标、标签
- 开关、软删除
- **Agent 上下文页面**（`/agents/{id}/context`）——深入查看注入 prompt、绑定工具、绑定 KB、记忆文件、近期活动

**API：** `/api/v1/agents`

---

### 5. LLM Wiki

**路由：** `/wiki`

管理知识库和 Wiki 页面。见 [LLM Wiki](./wiki)。

- **KB 列表**——卡片网格
- **KB 详情**——原始材料、Wiki 页面、搜索的 tab
- **原始材料管理**——上传、扫描目录、粘贴文本、重新消化、删除
- **页面浏览**——全文搜索、反向链接、锁定/解锁、原地编辑
- **页面编辑器**——带实时预览的 markdown、来源面板
- **Agent 绑定**——哪些 Agent 能读这个 KB

---

### 6. 多模态工作室

**路由：** `/multimodal`

不走 Agent 直接生成媒体。

- 图像生成、视频生成、音乐生成
- TTS 试玩、STT 试玩
- 结果画廊——下载、分享、扔进对话

---

### 7. 会话

**路由：** `/sessions`

::: tip 1.4.0：真正的会话管理页
v1.4.0 起 `/sessions` 是一个独立的会话管理页，从**聊天页头部的溢出菜单**进入。它带**服务端分页** + 按**标题 / ID** 搜索，采用层次化的**卡片布局**，每行还有一个**可原地编辑的模型芯片**——直接在列表里给某个会话改默认模型。
:::

浏览所有 Agent 和渠道下的对话。

- 按关键字搜索（标题 / ID，服务端分页）
- 会话标题、ID、Agent、消息数、状态、上次活跃时间
- 每行**可原地编辑的模型芯片**
- 渠道来源图标
- 跳进聊天控制台
- 删除历史会话

---

### 8. 工具

**路由：** `/tools`

所有已注册工具的表格。

- 名字、描述、类型、危险标志、启用状态
- 注册自定义工具、编辑、开关、删除
- provider 支撑的工具有测试按钮

---

### 9. 技能

**路由：** `/skills`

技能包的卡片网格。

- 分类 tab（全部 / 内置 / 自定义 / MCP）
- 每张卡片显示名字、图标、类型徽章、版本、描述、运行时状态、安全扫描摘要
- 创建 / 编辑 / 开关 / 删除
- **从 ClawHub 安装**——浏览社区技能、预览、一键安装
- 刷新运行时状态

---

### 10. MCP 服务

**路由：** `/mcp-servers`

- 表格：服务名、描述、传输类型、连接状态、工具数、启用状态
- 添加（stdio / streamable_http / sse）
- 测试连接（延迟 + 发现的工具）
- 刷新所有、编辑、删除、开关

---

### 11. 渠道

**路由：** `/channels`

八个 IM 渠道 + Web 的卡片网格。

- 每张卡片：图标、名字、类型、描述、启用状态、实时连接指示器
- 添加渠道（钉钉 / 飞书 / 企业微信 / 微信 / Telegram / Discord / QQ / Slack）
- 编辑、开关、删除
- 健康视图——渠道健康监控结果

---

### 12. 定时任务（调度中心）

**路由：** `/settings/scheduler`（旧 `/cron-jobs` 自动重定向）

::: tip 1.4.0：合并为调度中心
v1.4.0 起，**定时任务**和**触发器**合并为单个**调度中心**页面（`设置 → 调度中心`），分**计划任务 / 事件触发器 / 运行历史**三个 tab，每个 tab 旁带条目计数，右上角动作按钮随当前 tab 变化。计划任务支持 `wiki_process` 类型（错峰处理知识库）和**可视化 cron 编辑器**。详见 [触发器](./triggers)。
:::

按 cron 调度触发 Agent 对话的计划任务。

- 名字、Agent、任务类型、cron 表达式、下次运行、上次运行、启用状态
- 创建、编辑、删除
- **立即运行**
- 每个任务的执行历史

---

### 13. 数据源

**路由：** `/datasources`

外部数据库连接，Agent 通过 SQL 查询技能访问。

- 名字、类型（MySQL / PostgreSQL / SQLite 等）、主机/端口、启用状态
- 创建、编辑、测试连接、删除
- 按数据源的权限限制

---

### 14. Token 用量

**路由：** `/token-usage`

- 日期范围选择器
- 汇总卡片——总 prompt/completion token、消息数、估算成本
- 按模型细分

---

### 15. 仪表盘

**路由：** `/dashboard`

- 汇总卡片——活跃 Agent、今天对话数、今天工具调用数、待审批数
- **模型配置卡片**（1.4.0 新增）——列出已启用的 LLM 供应商，每个带**连通状态**和**当前活跃模型**，并附一个跳到模型设置的链接
- **趋势图**——7 / 30 / 90 天的消息 / 工具调用 / token 用量
- **Top Agent / Top 工具**——按用量排名
- 近期审批活动

---

### 16. Doctor

**路由：** `/doctor`

系统健康检查。后端可达性、数据库、模型供应商、渠道、MCP、wiki、记忆 cron、磁盘使用。每项检查报告 `ok` / `warning` / `error`，带简短诊断和可修复时的"修复"链接。见 [Doctor](./doctor)。

---

### 17. 设置

子路由布局，四个子页面。设置子导航底部钉了一个浮动按钮，可**折叠/展开**子导航（1.4.0 新增）。

#### 17.1 模型

**路由：** `/settings/models`（默认设置页）

管理模型供应商和模型配置。卡片网格。

- 供应商卡片——名字、图标、ID、内置/自定义徽章、活跃状态、base URL、脱敏的 API key、模型数
- 添加自定义供应商（OpenAI 兼容）
- 管理供应商下的模型
- 测试连接
- **发现模型**——自动拉取
- 单模型测试按钮
- 删除自定义供应商

见 [模型配置](./models)。

#### 17.2 系统

**路由：** `/settings/system`

全局系统参数。

- 语言、流式响应、调试模式
- **搜索服务**——供应商链、fallback、API keys（脱敏）
- **默认 Agent**

**API：** `/api/v1/settings`

#### 17.3 工作空间、成员、活动

**路由：** `/settings/workspaces`、`/settings/members`、`/settings/activity`

- 创建 / 重命名 / 删除工作空间
- 邀请 / 移除成员，分配角色（owner / admin / member / viewer）
- 活动流——近期工作空间事件

见 [工作空间](./workspaces)。

#### 17.4 Feature Flags

**路由：** `/settings/feature-flags`

运行时可切换的特性开关，每一行就是一个 flag——拨一下开关，后端不重启立即生效。

每行展示：

- **Key**（等宽字体）—— 例如 `wiki.ocr.enabled`、`wiki.hot_cache.enabled`、`wiki.compile.4stage.enabled`
- **描述**——一句人类可读说明
- **开关**——启用 / 禁用
- **范围**——可选的 `whitelist_kb_ids`（逗号分隔）、`whitelist_user_ids`（逗号分隔）、`rollout_percent`（0–100）。任一项被设了，flag 就变成有范围的：只有列出的 KB / 用户 / 按确定性哈希命中的百分比才能看到打开效果。
- **接通徽标**——后端消费方还没接进来的 flag 会变灰显示，并打上"尚未实现"的徽标，避免你拨了一个其实没人监听的开关。

**API：** `/api/v1/feature-flags`

| Method | Path | 说明 |
|---|---|---|
| `GET` | `/` | 列出全部 flag |
| `PUT` | `/{flagKey}` | 局部更新 `enabled` / `description` / 白名单 / `rollout_percent` |

运行时求值顺序：`enabled=false` → 关；命中白名单 → 开；`rollout_percent` → 确定性 `floorMod(id, 100) < rolloutPercent`；以上都不命中 → 开。开关读取走 30 秒内存缓存，admin 端写入后立即失效缓存。

#### 17.5 关于

**路由：** `/settings/about`

版本信息、技术栈、致谢。

---

### 18. 安全与审批

子路由布局，四个子页面。

#### 18.1 Tool Guard

**路由：** `/security/tool-guard`

基于规则的 Tool Guard 配置。

- **全局配置**——开关、默认策略、审批超时、通知
- **规则表格**——规则名、严重级别、分类、决定、内置标志、启用状态、优先级
- 创建 / 编辑 / 删除 / 调整顺序 / 开关

见 [安全与审批](./security)。

#### 18.2 File Guard

**路由：** `/security/file-guard`

- 全局启用/禁用
- 允许路径、拒绝路径、工作空间级覆盖

#### 18.3 审批

**路由：** `/security/approvals`

待处理和历史审批。

- 按状态过滤（pending / approved / rejected / expired）
- 工具名、Agent、工作空间、请求时间、请求用户
- 内嵌处理带备注
- **占位符替换后的参数预览**——看看 Agent 到底会执行什么

#### 18.4 审计日志

**路由：** `/security/audit-logs`

- 统计卡片——总数、blocked、需审批、allowed
- 过滤器——工具、决定、日期、用户、工作空间
- 行展开——匹配的规则、原始参数、会话片段
- 导出 CSV

---

### 19. Backstage —— Admin 运行时控制台

**路由：** `/backstage`  ·  **要求：** `ROLE_ADMIN`

实时看每一个正在干活的数字员工——状态环头像、watchdog 卡死 / 孤儿判定、软停 / 强停 / 一键全清、按子 Agent 中断。有人说"Agent 卡住了"时打开的就是这一页。

完整指南：[Backstage](./backstage)。

---

## Pinia Store

MateClaw 用领域驱动的 Pinia store。每个 store **独占**自己的状态。

| Store | 文件 | 管 |
|-------|------|-----|
| `useAgentStore` | `stores/useAgentStore.ts` | Agent 列表 + CRUD |
| `useWorkspaceStore` | `stores/useWorkspaceStore.ts` | 当前工作空间 + 成员 |
| `useWikiStore` | `stores/useWikiStore.ts` | KB、页面、原始材料 |
| `useCronJobStore` | `stores/useCronJobStore.ts` | 定时任务 |
| `useThemeStore` | `stores/useThemeStore.ts` | 主题模式、持久化 |

聊天状态**不在**全局 store 里——由 `useChat` composable（`composables/chat/useChat.ts`）管理，scope 到聊天组件生命周期。

### 状态所有权

```typescript
// 对
agentStore.fetchAgents()
themeStore.setMode('dark')

// 错
agentStore.agents = []         // 别这样
```

---

## 深色模式

三种模式：**浅色**、**深色**、**跟系统**。侧栏底部切换。

- `useThemeStore` 持久化到 `localStorage`
- 切换时在 `<html>` 上加/去 `dark` class
- TailwindCSS 4 用 `dark:` 前缀
- Element Plus 主题通过 `--mc-*` CSS 变量切换
- 跟系统模式用 `matchMedia('(prefers-color-scheme: dark)')`

复杂场景用 `--mc-*` 设计 token，切换自动处理。

---

## 国际化

- `zh-CN`——简体中文（默认）
- `en-US`——英文

文件在 `src/i18n/`。切换：`设置 → 系统 → 语言`。**立刻生效**。

---

## API 层

### Axios 实例

```typescript
const http = axios.create({
  baseURL: '/api/v1',
  timeout: 30000,
})
```

**请求拦截器**——从 `localStorage` 读 JWT 注入 `Authorization` header。

**响应拦截器**——解包 `R<T>: { code, msg, data }`，取 `X-New-Token` header 做滑动窗口续签，处理 401/403 → 清 token、跳登录。

### SSE 流式

聊天流式用原生 `fetch`，**不用** Axios：

```typescript
fetch('/api/v1/chat/stream', {
  method: 'POST',
  headers: {
    Accept: 'text/event-stream',
    Authorization: `Bearer ${token}`,
    'Content-Type': 'application/json',
  },
  body: JSON.stringify(data),
})
```

响应通过 `ReadableStream` 渐进读取。

---

## 路由表

```
/login                     —— 登录
/onboarding                —— 首次登录向导
/                          —— 重定向到 /chat（或 /onboarding）

/chat                      —— 聊天控制台
/agents                    —— Agent
/agents/:id/context        —— Agent 上下文深入视图
/wiki                      —— LLM Wiki
/multimodal                —— 多模态工作室
/sessions                  —— 会话

/tools                     —— 工具
/skills                    —— 技能
/mcp-servers               —— MCP 服务

/channels                  —— 渠道
/settings/scheduler        —— 调度中心（计划任务 / 事件触发器 / 运行历史；旧 /cron-jobs 重定向到此）
/datasources               —— 数据源
/token-usage               —— Token 用量
/dashboard                 —— 仪表盘
/doctor                    —— Doctor

/settings                  —— 重定向到 /settings/models
/settings/models           —— 模型设置
/settings/system           —— 系统设置
/settings/workspaces       —— 工作空间
/settings/members          —— 成员
/settings/activity         —— 活动
/settings/about            —— 关于

/security                  —— 重定向到 /security/tool-guard
/security/tool-guard       —— Tool Guard
/security/file-guard       —— File Guard
/security/approvals        —— 审批
/security/audit-logs       —— 审计日志
```

未匹配的路径重定向到 `/chat`。

---

## 构建和开发

```bash
cd mateclaw-ui
pnpm install
pnpm dev          # 5173 端口，把 /api proxy 到 18088
pnpm build        # vue-tsc + vite build，产物进 ../mateclaw-server/.../static
pnpm lint         # ESLint
```

构建产物嵌入 Spring Boot JAR。

技巧：

- **热重载**——dev 模式 HMR
- **路径别名**——`@` → `src/`
- **自动导入**——Element Plus 组件自动导入
- **样式优先级**——TailwindCSS utility class 优先
- **跳过认证**——`VITE_SKIP_AUTH=true` 绕过登录

---

## 下一步

- [快速开始](./quickstart)——让后端 + 前端跑起来
- [贡献指南](./contributing)——前端约定
- [配置说明](./config)——运行时设置
