---
name: digital_employee
nameZh: 数字员工组建
nameEn: Digital Employee Builder
version: "1.0.0"
icon: "🧑‍💼"
description: "根据一句业务需求，自动规划并创建一组分工明确的数字员工（Agent），再把他们编排成一条工作流，无需逐个手工创建。"
tags: digital_employee,agent,workflow,team
dependencies:
  tools:
    - listAvailableAgents
    - list_capability_catalog
    - create_employee
    - workflow_draft_generate
---

# 数字员工组建

把一句业务需求，变成「一支分工明确的数字员工团队 + 一条把他们串起来的工作流」。用户不必逐个设计、逐个手工创建 Agent。

## 何时使用

- 用户描述了一个**需要多个角色协作**的目标（"帮我搭一个做竞品分析的团队"、"我要一套从线索到成交的销售流程"）。
- 用户说"建几个 Agent / 数字员工帮我做 X"、"把 X 这件事自动化成一支团队"。
- 现有 Agent 不足以覆盖需求，需要新建专才角色。

## 不应使用

- 一个 Agent 就能完成 → 直接用 `chat_with_agent` 或现有 Agent。
- 只是想编排**已存在**的 Agent 协作 → 用 `multi_agent_collaboration`。
- 只是想把流程做成工作流，且员工都已存在 → 直接用 `workflow_draft_generate`。

## 工作流程

### 第一步：理解需求，盘点现状

1. 读懂用户的业务目标、产出物、是否有触发条件（定时 / 来消息时）、要不要审批、结果发到哪个渠道。
2. 调用 `listAvailableAgents()` 看现有员工——**能复用就复用**，不要重复造同名角色。
3. 调用 `list_capability_catalog()` 拿到可分配的**技能名**和**工具名**清单。后续 `create_employee` 只能用清单里的真实名字，不要臆造。

如果需求关键信息缺失（产出物、角色边界），先向用户澄清一句再继续，不要凭空假设。

### 第二步：设计团队（2–6 个角色）

把目标拆成**互补的角色**，每个角色给出：

- `name`：工作区内唯一，用英文 kebab-case（如 `market-research-analyst`）。
- `description`：一句话职责，工作流编排器会据此分配任务。
- `systemPrompt`：定义这个员工的专长、视角、工作方式——要具体，别写空话。
- `skillNames` / `toolNames`：从 `list_capability_catalog()` 里挑这个角色真正需要的；**留空则继承全局默认能力**（通才）。专才角色建议显式收窄。
- `agentType`：默认 `react`；只有当角色需要"先规划再分步执行"时才用 `plan_execute`。

设计原则：

- 角色数量 2–6 个，宁少勿滥；每个角色职责单一、边界清晰。
- 避免两个角色职责重叠。
- 一般不指定 `modelName`，留空用工作区默认模型；用户明确要求某模型时才填。

### 第三步：逐个创建员工

对每个设计好的角色调用一次 `create_employee(...)`：

```
create_employee(
  name="market-research-analyst",
  description="负责竞品功能、定价、市场动态的检索与结构化整理",
  systemPrompt="你是资深市场研究分析师……（写清专长与产出格式）",
  skillNames=["news", "web_search"],   // 来自 list_capability_catalog，可留空
  toolNames=["web_search"]              // 来自 list_capability_catalog，可留空
)
```

- 工具会返回 `agentId` 和实际绑定的技能/工具；**记下每个员工的 name**，下一步要用。
- 若返回 `[error]`（如重名），换个名字重试，不要中断整个流程。
- 创建即启用——员工立刻可被工作流引用。

### 第四步：编排工作流

所有员工创建完成后，调用一次 `workflow_draft_generate(description=...)`。在 `description` 里：

- **点名第三步创建的真实员工**（用它们的 name），说明执行顺序、依赖关系、并行还是串行。
- 写清触发条件、是否需要审批、产出发往哪个渠道。

由于员工已经落库，`workflow_draft_generate` 会读到它们作为可用数字员工，直接引用真实 agent，而不是填 `TODO_*_AGENT` 占位。

> 工作流只会保存为**草稿**，不会自动发布、不会自动启用触发器。这是安全约定——让用户在工作流编辑器里 review 后再 publish。

### 第五步：汇报

给用户一份清晰小结：

- 创建了哪些员工（name + 职责 + 绑定的关键能力）。
- 生成的工作流草稿名称、id、各步骤如何串联。
- 草稿编译预校验是否通过、有无缺失字段需要补。
- 明确告知：工作流是草稿，请到工作流编辑器确认后再发布。

## 关键规则

- 员工 name 用英文 kebab-case 且工作区内唯一；工作流 step.name / outputVar 的命名约束由 `workflow_draft_generate` 负责，本技能不必操心。
- 技能名 / 工具名必须来自 `list_capability_catalog()`，臆造的名字会被静默跳过。
- 先创建员工，**再**生成工作流——顺序不能反，否则工作流只能拿到占位符。
- 能复用现有 Agent 就复用，避免制造一堆同质化角色。
- 不自动发布工作流，不自动启用触发器。
