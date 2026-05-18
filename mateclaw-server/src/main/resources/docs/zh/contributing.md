# 贡献指南

**你在这里写的代码会跑在别人的机器上。**

这是唯一一件需要记住的事。MateClaw 是 Apache 2.0、自部署、一个 JAR 包发布的产品。你加的每一行都会被某个你永远不会见到的人下载、解压、执行。**写那种六个月之后你在别人家的日志里凌晨两点看到也会开心的代码。**

---

## 开始之前

### 1. Fork 并克隆

```bash
git clone https://github.com/YOUR_USERNAME/mateclaw.git
cd mateclaw
```

### 2. 启动后端

```bash
cd mateclaw-server
mvn spring-boot:run
```

后端在 18088 端口。H2 console 在 `/h2-console`，Swagger UI 在 `/swagger-ui.html`。

::: tip
模型配置**走 UI**——启动**不再需要**设 `DASHSCOPE_API_KEY` 环境变量。登录之后去 `设置 → 模型` 添加供应商。
:::

### 3. 启动前端

```bash
cd mateclaw-ui
pnpm install
pnpm dev
```

前端在 5173 端口，把 `/api` proxy 到后端。

### 4. 验证

打开 [http://localhost:5173](http://localhost:5173)。用 `admin` / `admin123` 登录。在 `设置 → 模型` 里加一个供应商。发一条测试消息。如果 token 流式回来，你就准备好了。

---

## 开发流程

```bash
# 1. 从 main 开一个 feature branch
git checkout -b feat/your-feature-name

# 2. 小而有意义的 commit
git add <具体的文件>
git commit -m "feat(scope): 你改了什么"

# 3. 和上游保持同步
git fetch upstream
git rebase upstream/main

# 4. 推送、开 PR
git push origin feat/your-feature-name
```

---

## 分支命名

| 前缀 | 用途 | 示例 |
|------|------|------|
| `feat/` | 新功能 | `feat/voice-input` |
| `fix/` | Bug 修复 | `fix/sse-reconnect` |
| `docs/` | 文档 | `docs/api-reference` |
| `refactor/` | 重构 | `refactor/agent-state` |
| `chore/` | 构建、依赖、工具 | `chore/upgrade-spring-boot` |
| `test/` | 只改测试 | `test/add-approval-coverage` |

---

## Commit 消息

[Conventional Commits](https://www.conventionalcommits.org/) 格式：

```
type(scope): brief description

可选的更长描述，解释做了什么、为什么。
```

示例：

```
feat(agent): add max iteration limit to ReAct loop
fix(channel): handle DingTalk message encoding correctly
docs(tools): add examples for WebSearchTool
chore(deps): upgrade Spring Boot to 3.5.1
```

**Commit 消息写的是改动，不是代码。** "给 WebSearchTool 加重试逻辑" 有用。"更新 WebSearchTool.java" 没用。

---

## 后端约定

### 包结构

新代码放进对应的 `vip.mate.*` 包：

| 你在加… | 包 |
|---------|-----|
| 一个新工具 | `vip.mate.tool` |
| 一个新渠道适配器 | `vip.mate.channel` |
| 一个新的 Agent 图节点 | `vip.mate.agent.graph.node` |
| 一个记忆 provider | `vip.mate.memory.spi` |
| 一个新的 Wiki 功能 | `vip.mate.wiki` |
| 工具代码 | `vip.mate.common` |

### 代码风格

- **鼓励 Java 17+ 特性**——record、sealed 类、text block、模式匹配、类型明显时用 `var`
- Spring bean 用**构造器注入**
- **命名**：`XxxService`、`XxxController`、`XxxMapper`、`XxxEntity`
- **数据库**：MyBatis Plus，不用 JPA。`mate_` 前缀。camelCase Java 字段 → snake_case 列。
- **逻辑删除**走 `deleted` 列
- **每张表**都要有 `create_time`、`update_time`、`deleted`

### Agent 图是一张 StateGraph

**不要去找 `BaseAgent` 类层次**——Agent 运行时是一张**节点和边组成的 StateGraph**。加 Agent 行为时，想的是：

- **一个节点**（reasoning、action、observation、plan generation）——在 `vip.mate.agent.graph.node` 或 `vip.mate.agent.graph.plan.node`
- **一条边**或 **dispatcher**——在 `vip.mate.agent.graph.edge` 或 `vip.mate.agent.graph.plan.edge`
- **一个 state key**——在 `vip.mate.agent.graph.state.MateClawStateKeys`

把这些拼起来的 builder 是 `AgentGraphBuilder`。节点的流式事件通过 `GraphEventPublisher` 和 `NodeStreamingChatHelper` 发出去。

### 加一个新工具

```java
@Component
public class MyNewTool {

    @Tool(description = "清晰的描述让 LLM 决定什么时候用这个工具")
    public String myMethod(
            @ToolParam(description = "这个参数控制什么") String input) {
        return "result";
    }
}
```

- Spring `@Component`
- 每个 `@Tool` 方法变成一个可调用的工具
- **每个参数都要加 `@ToolParam`**——这是 LLM 读的描述
- **如果工具是危险的，为它加一条 Tool Guard 规则**

### 加一个新渠道

1. 在 `vip.mate.channel` 创建一个实现 `ChannelAdapter`（或流式的 `StreamingChannelAdapter`）的类
2. 在 `ChannelWebhookController` 注册 webhook 端点
3. 加配置属性
4. 更新 `docs/en/channels.md` 和 `docs/zh/channels.md`

### 加一个记忆 provider

1. 在 `vip.mate.memory.spi` 创建一个实现 `MemoryProvider` 的类
2. 注册成 Spring bean
3. 在 `mate.memory.providers.{name}` 下加配置
4. 加测试

### SQL schema 变更

Schema 由 **Flyway** 管理。新 DDL 写在 `db/migration/h2/` 和 `db/migration/mysql/` 下各一份 `V{next}__description.sql` 文件。每份文件必须兼容对应方言（MySQL 不支持 `ADD COLUMN IF NOT EXISTS`，用 `INFORMATION_SCHEMA` 守卫；H2 原生支持）。

种子数据由 `DatabaseBootstrapRunner` 从 `db/data-*.sql` 加载——幂等（`INSERT ... ON DUPLICATE KEY UPDATE` / `MERGE INTO`）。

---

## 前端约定

### 代码风格

- 所有新组件用 **`<script setup>` 的 Composition API**
- **必须用 TypeScript**——绝对必要之外不要用 `any`
- 共享状态用 **Pinia store**，组件状态用本地 `ref` / `reactive`
- **Element Plus** 优先于自定义实现
- **TailwindCSS** utility class；避免 inline style
- **路径别名** `@` → `src/`
- **设计 token** 在 `src/assets/main.css`（`--mc-*` CSS 变量）——**别硬编码颜色**

### 状态所有权

每个 Pinia store **独占**自己领域的状态。外部代码调 action，**不直接改 state**。

```typescript
// 对
agentStore.fetchAgents()
themeStore.setMode('dark')

// 错
agentStore.agents = []         // 别这样
```

### 加一个新页面

1. 在 `src/views/` 创建 view
2. 在 `src/router/index.ts` 注册路由
3. 在 `src/i18n/zh-CN.ts` 和 `src/i18n/en-US.ts` **两边**都加翻译
4. 需要共享状态就在 `src/stores/` 创建一个 Pinia store
5. 在 `src/views/layout/MainLayout.vue` 里把页面加进侧边栏

### 组件结构

```vue
<script setup lang="ts">
import { ref, onMounted } from 'vue'
import { useAgentStore } from '@/stores/useAgentStore'

const agentStore = useAgentStore()
const loading = ref(false)

onMounted(async () => {
  loading.value = true
  await agentStore.fetchAgents()
  loading.value = false
})
</script>

<template>
  <div class="p-4">
    <el-table :data="agentStore.agents" v-loading="loading">
      <!-- columns -->
    </el-table>
  </div>
</template>
```

---

## 测试

### 后端测试

```bash
cd mateclaw-server
mvn test                                  # 全部测试
mvn test -Dtest=StateGraphReActAgentTest  # 单个类
mvn test -Dtest=StateGraphReActAgentTest#testChat  # 单个方法
```

### 前端类型检查和 lint

```bash
cd mateclaw-ui
pnpm build          # vue-tsc 类型检查 + vite build
pnpm lint           # ESLint 自动修复
```

### 手动测试清单

- [ ] 后端启动无错
- [ ] 前端编译无类型错误（`pnpm build`）
- [ ] 用默认凭证能登录
- [ ] 模型在 UI 里配好了
- [ ] 对话能流式返回
- [ ] 新功能按 PR 描述工作
- [ ] 浏览器控制台没有错误
- [ ] 如果改了用户面行为，**文档也更新了**

---

## 文档变更

PR 改了用户面行为——新功能、重命名的端点、改过的配置 key——**在同一个 PR 里更新文档**。

文档在 `docs/`。挑相关页面更新 `docs/en/` 和 `docs/zh/`。中英版本**独立写作**，不是翻译——和已有页面的语气和风格保持一致。

```bash
cd docs
pnpm build
```

**PR 开出来之前 build 必须零错误通过。**

---

## Pull request 流程

1. **标题**——conventional commit 格式
2. **描述**——做了什么、为什么、怎么做的；链接 issue
3. **截图**——UI 改动带 before/after
4. **测试**——描述你怎么测的
5. **破坏性变更**——在最上面清楚标注

### PR 模板

```markdown
## What

改动的简短描述。

## Why

为什么需要这个改动（链接 issue）。

## How

技术方案。

## Testing

怎么测的。

## Screenshots (if UI changes)

Before / After。
```

---

## 报告 bug

- MateClaw 版本（或 commit hash）
- Java 版本和操作系统
- **精确的**复现步骤
- 预期 vs 实际行为
- 相关日志输出

**好的 bug 报告得到好的修复。**

---

## 下一步

- [快速开始](./quickstart)——搭建走一遍
- [项目介绍](./intro)——架构概览
- [架构说明](./architecture)——给开发者的 StateGraph 深入
- [路线图](./roadmap)——我们接下来在做什么
