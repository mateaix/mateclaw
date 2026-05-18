# 工作空间

**一个工作空间就是一个团队所有东西外面的一个盒子。**

MateClaw 在单次部署里支持多个团队的方式，是把每一种资源——Agent、技能、Wiki 知识库、会话、记忆文件、Tool Guard 规则、渠道——组织进**工作空间**。你登录时看到的是你所属的工作空间、其他什么都看不到。切换工作空间时，整个 UI 重新 scope：不同的 Agent、不同的技能、不同的知识、不同的渠道。

重点是**一个 MateClaw 部署可以同时服务一个产品组、一个工程组、一个研究组**，他们的数据、Agent、对话不会互相渗透。

---

## 什么属于工作空间

几乎所有东西。被 scope 的资源：

| 资源 | 怎么 scope 的 |
|------|---------------|
| **Agent** | 每一行 Agent 都有 `workspace_id` 外键 |
| **技能** | 自定义和 MCP 技能按工作空间 scope；内置技能是全局的 |
| **Wiki 知识库** | 每个 KB 属于且只属于一个工作空间 |
| **会话和消息** | scope 到 Agent 所在的工作空间 |
| **工作空间记忆文件** | `workspace/{workspaceId}/{agentId}/...` |
| **渠道** | 每个渠道绑一个 Agent，所以传递地绑一个工作空间 |
| **Tool Guard 规则** | 规则可以是全局或 scope 到特定工作空间 |
| **File Guard 路径** | 允许/拒绝路径可以按工作空间 |
| **Cron 任务** | scope 到它触发的 Agent 所在的工作空间 |
| **数据源** | 外部 DB 连接，按工作空间 scope |
| **审计事件** | 每个审计事件记录它的 `workspace_id` |

**不**被 scope 的（即全局的）：

- JWT secret 和认证配置
- 模型供应商和 API Key（全局，但用量按工作空间追踪）
- MCP 服务定义（全局连接；工作空间访问由权限控制）
- `mate_system_setting` 里的系统级设置
- 内置技能

---

## 工作空间角色

每个用户在工作空间里被分配四种角色之一：

| 角色 | 能做什么 |
|------|----------|
| **Owner** | 所有事，包括删除工作空间和管理成员 |
| **Admin** | 除了删除工作空间或变更 owner 之外的所有事 |
| **Member** | 用 Agent、读写 wiki、创建会话、调用工具（受 Tool Guard 约束） |
| **Viewer** | 只读——看得到 Agent 和 KB、读会话、**不能创建或修改** |

一个用户可以属于多个工作空间、**在不同工作空间有不同角色**。切换工作空间时，有效权限跟着切换。

### 角色的 scope

角色控制 **UI 可见性**和 **API 访问**。控制台隐藏用户没权限用的菜单项——一个对某工作空间只有 viewer 角色的用户，**完全看不到**安全菜单或工作空间管理页面。后端在每个 API 端点上执行同样的规则，所以 viewer 打一个受保护的端点返回 `403 Forbidden`。

---

## 创建一个工作空间

`设置 → 工作空间 → 新建工作空间`。

1. 按"**团队在做什么**"起名，不是"团队叫什么"（"产品调研"比"Alpha 组"好）
2. 可选描述
3. 保存

你成为这个工作空间的 owner。现在可以邀请成员了。

### 走 API

```bash
curl -X POST http://localhost:18088/api/v1/workspaces \
  -H "Authorization: Bearer <token>" \
  -H "Content-Type: application/json" \
  -d '{
    "name": "产品调研",
    "description": "竞品调研和产品规格"
  }'
```

---

## 邀请成员

`设置 → 成员 → 添加成员`。输入一个已存在的 MateClaw 用户名，选角色，保存。

成员**下次页面加载时**立刻在工作空间切换器里看到这个工作空间。没有邀请邮件，没有接受流程——成员的账号已经在 MateClaw 里了。

### 走 API

```bash
curl -X POST http://localhost:18088/api/v1/workspaces/1/members \
  -H "Authorization: Bearer <token>" \
  -H "Content-Type: application/json" \
  -d '{
    "userId": 42,
    "role": "member"
  }'
```

---

## 切换工作空间

管理控制台左上角。点工作空间名字打开切换器，选另一个切。整个 UI 重新 scope：

- 侧栏菜单按新工作空间的角色重新渲染
- Agent 列表刷新显示这个工作空间里的 Agent
- Wiki 列表、技能列表、渠道列表等全部改变
- 活跃的对话**保持打开**（它们属于自己的工作空间）

工作空间选择**按用户持久化**——你再次登录时落到上次用的工作空间。

---

## 沿工作空间边界生效的安全基元

这是工作空间隔离真正起作用的地方。

### File Guard

File Guard 的默认 allowed-path 列表是 `workspace/{workspaceId}/...`。工作空间 A 里一个 Agent 发出的工具调用**读不到也写不到**属于工作空间 B 的文件，不管它怎么用路径穿越的 trick——符号链接检查和路径规范化会逮住它。

### Tool Guard 规则

规则可以 scope 到特定工作空间。你可以有：

- 一条**全局**规则说 `ShellExecuteTool` 需要审批
- 一条**工作空间特定**的规则说命令匹配一个狭窄的只读模式时 `ShellExecuteTool` 被允许

**只有第二条规则在那个工作空间里生效。** 其他工作空间只看到全局规则。

### Wiki 知识库

Wiki KB 的数据**永远不会离开它的工作空间**。工作空间 B 里的 Agent 读不到属于工作空间 A 的 KB，**即使它尝试**。Wiki 检索和读取工具从绑定 Agent 的工作空间解析知识库 ID；跨工作空间读在 API 层被拒绝。

### 记忆文件

工作空间记忆文件（PROFILE.md、MEMORY.md、每日笔记）住在 `workspace/{workspaceId}/{agentId}/` 下面。File Guard 执行工作空间边界；记忆工具的 list/read/write 操作被限定到调用者的工作空间内。

### 渠道

每个渠道绑一个 Agent，传递地绑一个工作空间。工作空间 A 里配置的一个钉钉机器人和工作空间 B 里配置的一个钉钉机器人**完全独立**，即使它们被配置成连接同一个钉钉应用（你大概率不想这样，但技术上允许）。

---

## 工作空间隔离**不**覆盖的

- **共享的全局配置**——JWT secret、模型供应商 API Key、MCP 服务定义是全局的。工作空间管理员改不了。
- **审计日志的跨工作空间访问**——带正确权限的安全管理员可以跨所有工作空间查询审计事件。这是**刻意的**——你想看到可疑活动，不管它发生在哪个工作空间。
- **Token 用量报告**——全局聚合，在仪表盘里按工作空间、按 Agent、按模型细分。
- **模型供应商成本**——全局层面每个 provider 一个计费关系；按工作空间的配额在[路线图](./roadmap)上。

---

## 在工作空间之间移动资源

**不直接支持。** 你有两个选项：

1. **导出导入**——一些资源有 JSON 导出（Agent 走 API、Wiki KB 走 API）。在目标工作空间重新创建。
2. **改所有权**——admin 或 owner 可以直接在数据库里更新简单资源的 `workspace_id` 列。这不是官方支持的；**自担风险而且一定要带备份**。

我们希望在未来版本里支持一等公民的移动。需要这个就在 [GitHub issue](https://github.com/matevip/mateclaw/issues) 上留言。

---

## 删除一个工作空间

**只有 owner 能删工作空间。** `设置 → 工作空间 → [工作空间] → 删除`。

删工作空间会：

- 软删除它下面的每一个资源——Agent、技能、KB、会话、记忆文件、渠道
- 移除所有成员关联
- 记录一个审计事件

**软删除**意味着数据不被物理移除——它被标记为 `deleted = 1`、从查询里隐藏。误删的话数据库管理员可以通过翻转标记恢复。配置的保留期过后，删除的数据可能被清理任务永久清除。

---

## 工作空间管理 API

```bash
# 列出你所属的工作空间
curl http://localhost:18088/api/v1/workspaces \
  -H "Authorization: Bearer <token>"

# 获取单个工作空间详情
curl http://localhost:18088/api/v1/workspaces/1 \
  -H "Authorization: Bearer <token>"

# 创建
curl -X POST http://localhost:18088/api/v1/workspaces \
  -H "Authorization: Bearer <token>" \
  -H "Content-Type: application/json" \
  -d '{"name": "产品调研"}'

# 更新
curl -X PUT http://localhost:18088/api/v1/workspaces/1 \
  -H "Authorization: Bearer <token>" \
  -H "Content-Type: application/json" \
  -d '{"description": "更新后的描述"}'

# 删除（仅 owner）
curl -X DELETE http://localhost:18088/api/v1/workspaces/1 \
  -H "Authorization: Bearer <token>"

# 成员管理
curl http://localhost:18088/api/v1/workspaces/1/members \
  -H "Authorization: Bearer <token>"

curl -X POST http://localhost:18088/api/v1/workspaces/1/members \
  -H "Authorization: Bearer <token>" \
  -H "Content-Type: application/json" \
  -d '{"userId": 42, "role": "member"}'

curl -X DELETE http://localhost:18088/api/v1/workspaces/1/members/42 \
  -H "Authorization: Bearer <token>"

curl -X PUT http://localhost:18088/api/v1/workspaces/1/members/42/role \
  -H "Authorization: Bearer <token>" \
  -H "Content-Type: application/json" \
  -d '{"role": "admin"}'
```

---

## 数据模型

**`mate_workspace`**

| 列 | 用途 |
|----|------|
| `id` | 主键 |
| `name` | 工作空间名 |
| `description` | 简短描述 |
| `owner_id` | Owner 的用户 ID |
| `create_time` / `update_time` | 时间戳 |
| `deleted` | 逻辑删除标志 |

**`mate_workspace_member`**

| 列 | 用途 |
|----|------|
| `id` | 主键 |
| `workspace_id` | 外键到 `mate_workspace` |
| `user_id` | 外键到 `mate_user` |
| `role` | `owner` / `admin` / `member` / `viewer` |
| `joined_at` | 用户加入这个工作空间的时间 |
| `create_time` / `update_time` | 时间戳 |

---

## 下一步

- [控制台](./console)——工作空间切换器和 UI
- [安全与审批](./security)——工作空间隔离和 Tool Guard、File Guard 的交互
- [LLM Wiki](./wiki)——工作空间 scope 的知识库
- [记忆系统](./memory)——工作空间记忆文件
