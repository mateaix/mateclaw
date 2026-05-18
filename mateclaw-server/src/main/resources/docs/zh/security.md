# 安全与审批

**手能伸得远，但边界清清楚楚。**

MateClaw 给 Agent 真实的能力——shell 访问、文件写入、浏览器自动化、委托给其他 Agent、通过 MCP 调用远程工具。这是"手能伸得远"那一半。这一页讲的是另一半：**不让强有力的手干蠢事的边界**。

- **JWT 认证**——你是谁
- **Tool Guard（基于规则）**——每个 Agent 被允许做什么
- **审批工作流**——执行前什么时候需要人来决定
- **File Guard**——Agent 眼里的文件系统长什么样
- **工作空间隔离**——每个团队能看到什么
- **审计日志**——所有人做过的所有事，按时间顺序，永远保留

生产环境跑 MateClaw 的话，从头到尾读完这页。

::: tip Agentic, but not autonomous
每个公司的 IT 部门和 CISO 在买 AI 之前问的同一个问题：

> **"它会不会跑飞了，删了我不该删的东西？"**

任何说"AI 不会跑飞"的人都在骗你。MateClaw 的答案不一样——**敏感操作问你一句再执行。**

Agent 想删文件、发邮件、跑写入型 SQL、调付费 API——任何一条 Tool Guard 规则匹配上的调用，会**在回合中途暂停**，审批通知推到你的 IM（飞书 / 钉钉 / Slack / 邮件），你点批准，Agent 从暂停的地方接着跑。每一个动作进 `mate_tool_guard_audit_log`——按时间序、永远保留、可导出 CSV。

**会动手（agentic），但不擅自动手（not autonomous）。**

这是「让 AI 替你干活」和「让 AI 替你做主」之间那条线。MateClaw 站在线的左边——也是你的 CISO 第一次不会一上来就否决的那一边。
:::

---

## JWT 认证

### 怎么工作的

1. 用户往 `/api/v1/auth/login` 提交凭证
2. 服务器校验之后返回一个 JWT
3. 之后所有请求在 `Authorization` header 里带 token
4. 服务器在每个请求上校验 token

### 登录

```bash
curl -X POST http://localhost:18088/api/v1/auth/login \
  -H "Content-Type: application/json" \
  -d '{"username": "admin", "password": "admin123"}'
```

响应：

```json
{
  "code": 200,
  "data": {
    "token": "eyJhbGciOiJIUzI1NiJ9...",
    "tokenType": "Bearer",
    "expiresIn": 86400
  }
}
```

### 修改密码

用户可以在个人设置里修改自己的密码。管理员可以在成员管理里重置任何成员的密码。

---

### 滑动窗口续签

MateClaw 实现了滑动窗口 token 续签。当 token 剩余有效期低于 `renewal-threshold`（默认 2 小时 / 7200000ms）时，服务器在响应头 `X-New-Token` 里发一个新 token。前端自动拿到新 token 替换旧的，**用户感知不到**。活跃用户不会被踢下线；空闲会话该过期还是过期。

### 配置

```yaml
mateclaw:
  auth:
    jwt:
      secret: your-secret-key-must-be-at-least-32-characters-long
      expiration: 86400000    # 24 小时，毫秒
      sliding-window: true
```

::: warning
**生产环境必须改默认 JWT secret。** 至少 32 字符。用环境变量（`JWT_SECRET=...`）设置，**不要 commit**。
:::

### 错误码

| 状态码 | 含义 | 响应 |
|--------|------|------|
| 401 | Token 缺失、过期或无效 | `{"code": 401, "message": "Unauthorized"}` |
| 403 | Token 有效但权限不足 | `{"code": 403, "message": "Forbidden"}` |

前端统一处理——跳登录页、清空存储的 token。

### 默认凭证

MateClaw 出厂带 `admin` / `admin123`。**除了你自己笔记本之外的任何部署都必须立刻改。**

### Spring Security 配置

- **无状态会话**——服务端不存 session；所有状态都在 JWT 里
- **公共端点**——`/api/v1/auth/login`、`/h2-console/**`、`/swagger-ui/**`
- **受保护端点**——`/api/v1/**` 下的其他所有路径
- **CSRF 关闭**——无状态 JWT 不需要

---

## Tool Guard —— 基于规则的权限引擎

Tool Guard 是 MateClaw 决定一次工具调用被允许做什么的机制。**它不是一个扁平的"危险工具清单"。** 它是一个规则引擎。每条规则说：*对这个工具，可选匹配这些参数，在这个工作空间里，做 X*——X 是 `allow`、`deny`、或 `require_approval`。

### 三张表

| 表 | 用途 |
|----|------|
| **`mate_tool_guard_config`** | 全局配置——开关、默认策略、审批超时、通知渠道 |
| **`mate_tool_guard_rule`** | 单条规则——工具模式、可选参数正则、工作空间范围、动作、优先级 |
| **`mate_tool_guard_audit_log`** | 每一次受守护的调用一条记录——工具、参数、匹配的规则、决定、用户、时间戳 |

### 一条规则是怎么被评估的

```
收到工具调用
      │
      ▼
加载这个工作空间 + 全局的所有规则，按优先级排序
      │
      ▼
按优先级遍历每条规则：
  ┌─ 工具名匹配模式吗？
  │  └─ 不 → 下一条
  ├─ 参数模式匹配吗（如果有）？
  │  └─ 不 → 下一条
  └─ 都匹配 → 执行这条规则的动作，停
      │
      ▼
没有规则匹配 → 执行默认策略
      │
      ▼
动作：allow / deny / require_approval
      │
      ▼
写一条审计日志
      │
      ▼
执行 / 拒绝 / 挂起等审批
```

优先级更高的规则先执行。**第一个匹配的规则赢**。一条规则可以限定到特定的工作空间，也可以是全局的。

### 示例规则

```
规则 1（优先级 100）：ShellExecuteTool，参数匹配 "^(ls|cat|grep|find)\\s" → allow
规则 2（优先级 50）： ShellExecuteTool                                    → require_approval
规则 3（优先级 50）： WriteFileTool，arg.path 以 "/tmp" 开头             → allow
规则 4（优先级 40）： WriteFileTool                                       → require_approval
规则 5（优先级 30）： *                                                    → allow（默认）
```

只读的 shell 命令立刻执行。其他的需要审批。`/tmp` 下的文件写入自由；其他地方需要审批。其他所有工具放行。

### 管理规则

`设置 → 安全与审批 → Tool Guard 规则` 提供完整 UI。或者走配置文件：

```yaml
mateclaw:
  tool:
    guard:
      enabled: true
      default-policy: require_approval
      rules:
        - tool: ShellExecuteTool
          arg-pattern: "^(ls|cat|grep|find)\\s"
          action: allow
          priority: 100
        - tool: ShellExecuteTool
          action: require_approval
          priority: 50
        - tool: WriteFileTool
          arg-pattern: "^/tmp/"
          action: allow
          priority: 50
        - tool: WriteFileTool
          action: require_approval
          priority: 40
```

或者走 API：

```bash
curl -X POST http://localhost:18088/api/v1/security/guard/rules \
  -H "Authorization: Bearer <token>" \
  -H "Content-Type: application/json" \
  -d '{
    "tool": "ShellExecuteTool",
    "argPattern": "^(ls|cat|grep|find)\\s",
    "action": "allow",
    "priority": 100
  }'
```

### 危险模式检测

除了用户定义的规则之外，MateClaw 的 shell 工具内置了一套危险模式检测——不管你的规则怎么写，有些模式本身就是危险的。`find -delete`、`rm -rf /`、用管道把 `bash` 接到下载上之类的模式，**即使有规则本来会 allow，也会强制触发更高级别的审批**。

---

## 审批工作流 —— 人在回路

当一条规则评估为 `require_approval` 时，MateClaw 不会简单地让调用失败。它会**在回合中途挂起 Agent**，创建一条 pending approval，呈现给用户，等用户决定之后**从暂停的地方恢复执行**。

::: tip 1.3.0 起：工作流也走同一套审批
v1.3.0 的 [工作流](./workflow) `await_approval` step 通过同一套 `mate_tool_approval` 表挂起整条 workflow run，跨服务重启不丢；审批结果通过 channel 通知（飞书 / 钉钉 / Slack / 企微）推回审批人，resolve 后 workflow runtime 自动 resume 下一 step。也就是说——同一份审计、同一份通知、同一种"暂停—恢复"语义，同时覆盖 Agent 工具调用和 workflow step。
:::

### 工作流

```
Agent 调用工具
     │
     ▼
Tool Guard：require_approval
     │
     ▼
创建 mate_tool_approval 行（status=pending）
     │
     ▼
图状态里 AWAITING_APPROVAL=true
     │
     ▼
发出 approval_required SSE 事件
     │
     ▼
图干净地终止
     │
     ▼
前端显示审批卡片
     │
     ▼
用户点 Approve 或 Reject
     │
     ▼
POST /api/v1/approvals/{id}/resolve
     │
     ├─ Approved → 重新加载 Agent，replay 工具调用，继续推理
     └─ Rejected → 把拒绝作为 observation 返回，继续推理
```

"replay" 机制很重要。Agent 恢复时**不会从头重新推理**——它直接跳到已经批准的工具调用、执行、从观察继续。**没有重复的 LLM 调用，没有浪费的 token。**

### `mate_tool_approval` 表

| 列 | 用途 |
|----|------|
| `id` | 主键 |
| `agent_id` | 哪个 Agent 在等 |
| `conversation_id` | 哪个会话被挂起 |
| `tool_name` | 要调的工具 |
| `tool_args` | 实际参数的 JSON |
| `rule_id` | 触发审批的规则 |
| `status` | `pending` / `approved` / `rejected` / `expired` |
| `requested_at` | 审批被创建的时间 |
| `resolved_at` | 用户决定的时间 |
| `resolved_by` | 谁决定的 |
| `notes` | 决定时可选的备注 |

### 占位符替换

有时候 Agent 的工具参数里带占位符——一个被计算出来的文件路径、一条带模板的命令。审批工作流**在弹出对话框之前就替换完占位符**，用户看到的是**真正的值**。审批同样返回替换后的值，Agent 执行的**就是**用户看到的。

### 超时

Pending approval 在一个可配置的超时后过期（默认 10 分钟）。过期的审批变成 `rejected`，Agent 把这个过期当作用户的拒绝一样对待。

### 通知

MateClaw 可以通过 `channel/notification/` 适配器通知——邮件、应用内提醒、钉钉/飞书推送。在 `设置 → 安全与审批 → 通知` 里配置。

### API 方式处理审批

```bash
# 列出 pending 审批
curl http://localhost:18088/api/v1/approvals?status=pending \
  -H "Authorization: Bearer <token>"

# 批准
curl -X POST http://localhost:18088/api/v1/approvals/123/resolve \
  -H "Authorization: Bearer <token>" \
  -H "Content-Type: application/json" \
  -d '{"decision": "approved"}'

# 拒绝并带原因
curl -X POST http://localhost:18088/api/v1/approvals/123/resolve \
  -H "Authorization: Bearer <token>" \
  -H "Content-Type: application/json" \
  -d '{"decision": "rejected", "notes": "这个工作空间不适合"}'
```

---

## File Guard

File Guard 是文件系统级的访问控制。它坐在读写文件的任何工具或技能下面，决定哪些路径在边界内。

### 评估管道

```
文件访问请求
     │
     ▼
路径规范化（解析 ..、符号链接、相对路径）
     │
     ▼
白名单检查：路径在允许的目录里吗？
     │
     ▼
黑名单检查：路径在被拒的目录里吗？
     │
     ▼
符号链接检查：顺着链接走会跳出沙盒吗？
     │
     ▼
允许 / 拒绝
```

### 内置规则

| 规则 | 说明 |
|------|------|
| 工作空间隔离 | 默认访问限定在工作空间目录内 |
| 系统路径拒绝 | `/etc`、`/usr`、`/bin`、`/boot` 等默认拒绝 |
| 敏感文件保护 | `.ssh`、`.config`、`.env` 拒绝 |
| 路径穿越防护 | `../` 攻击被检测和阻止 |
| 符号链接检查 | 符号链接的目标被解析并重新校验 |

### 配置

```yaml
mateclaw:
  security:
    file-guard:
      enabled: true
      allowed-paths:
        - "${user.dir}/workspace"
        - "${java.io.tmpdir}/mateclaw"
      denied-paths:
        - "/etc"
        - "/usr"
        - "${user.home}/.ssh"
        - "${user.home}/.config"
        - "${user.home}/.env"
```

可视化编辑器在 `设置 → 安全与审批 → File Guard`。

---

## 工作空间隔离

工作空间是 MateClaw 把多个团队的数据隔开的方式。每个 Agent、技能、Wiki、会话、记忆文件都**属于且只属于一个工作空间**。

### 沿工作空间边界生效的安全基元

- **File Guard**——路径白名单默认是 `workspace/{workspaceId}/...`
- **Tool Guard 规则**——可以限定到特定的工作空间
- **Wiki 知识库**——归属于工作空间，只有成员能读
- **记忆文件**——每个 Agent 的记忆在它工作空间的目录下面
- **渠道**——每个渠道归属于一个工作空间

### 角色

| 角色 | 能做什么 |
|------|----------|
| **Owner** | 所有事，包括删除工作空间 |
| **Admin** | 除了删除工作空间或变更 owner 之外的所有事 |
| **Member** | 用 Agent、读写 wiki、创建会话 |
| **Viewer** | 只读——看得到 Agent 和 KB，不能创建或修改 |

完整细节在 [工作空间](./workspaces)。

### 工作空间隔离**不**覆盖的

- **共享的全局配置**——JWT secret、模型 provider key、MCP 服务定义都是全局的
- **审计日志的跨工作空间访问**——带权限的安全管理员可以跨所有工作空间查询审计事件

---

## 审计日志

每一个安全相关的动作都被记在 `mate_audit_event`。**仅追加**——你不能改一条已有的记录，按配置的窗口（默认 90 天）保留。

### 记什么

| 事件类型 | 捕获的数据 |
|----------|------------|
| **工具调用** | 工具名、参数、结果摘要、耗时、Agent、工作空间 |
| **Tool Guard 决定** | 匹配的规则、执行的动作、规则 ID |
| **审批** | 谁批准/拒绝、什么时候、备注 |
| **File Guard 决定** | 路径、允许/拒绝、原因 |
| **技能执行** | 技能名、参数、Agent |
| **登录事件** | 用户、IP、成功/失败 |
| **配置变更** | 安全相关设置的旧值和新值 |

### 记录结构

```
timestamp       什么时候发生
user_id         谁做的（自动事件是 system）
action          做了什么
resource        对什么做的
details         具体细节的 JSON
result          success / failure / denied
ip_address      源 IP（可用时）
workspace_id    属于哪个工作空间
```

### 查询

`设置 → 安全与审批 → 审计日志`：按时间范围、事件类型、用户、工作空间、结果过滤。导出 CSV。

API：

```bash
curl "http://localhost:18088/api/v1/audit/events?from=2026-04-01&to=2026-04-11&action=tool_call" \
  -H "Authorization: Bearer <token>"
```

---

## 技能安全扫描

自定义技能在变活之前会被扫描危险模式：

| 检查 | 找什么 |
|------|--------|
| **Prompt 注入** | 覆盖 system prompt 的企图、隐藏指令 |
| **危险工具引用** | 不在允许列表里的工具，或请求了需要审批却没声明的工具 |
| **外部 URL 引用** | 技能正文里指向不可信外部资源的链接 |
| **脚本注入** | 嵌入的脚本或代码执行企图 |

### 严重级别

| 级别 | 动作 |
|------|------|
| `CRITICAL` | 安装被阻止；必须修好 |
| `HIGH` | 警告 + 管理员必须确认 |
| `MEDIUM` | 显示警告；允许安装 |
| `LOW` | 仅记录 |
| `INFO` | 仅记录 |

扫描报告在 `设置 → 安全与审批 → 技能扫描` 里。

---

## API Key 保护

- API keys 在数据库里**加密存储**
- Keys 在所有 API 响应里**脱敏显示**（`sk-****abcd`）——创建之后永远不会完整返回给前端
- MCP 服务的 `env_json` 和 `headers_json` 值按同样方式脱敏
- MCP 配置里的环境变量引用（`${VAR}`）在运行时从进程环境解析

---

## 网络安全

### 生产建议

| 建议 | 细节 |
|------|------|
| **HTTPS** | 用反向代理 + TLS（Nginx 或 Caddy） |
| **关掉 H2 console** | 生产环境 `spring.h2.console.enabled=false` |
| **防火墙** | 只开放对外端口 |
| **限流** | 在反向代理层配置 |
| **MySQL，不是 H2** | 生产用独立的 MySQL 8 实例 |

### Nginx 反向代理示例

```nginx
server {
    listen 443 ssl;
    server_name mateclaw.example.com;

    ssl_certificate /etc/ssl/certs/mateclaw.pem;
    ssl_certificate_key /etc/ssl/private/mateclaw.key;

    location / {
        proxy_pass http://localhost:18080;
        proxy_set_header Host $host;
        proxy_set_header X-Real-IP $remote_addr;
        proxy_set_header X-Forwarded-For $proxy_add_x_forwarded_for;
        proxy_set_header X-Forwarded-Proto $scheme;

        # SSE 支持
        proxy_buffering off;
        proxy_read_timeout 86400s;
    }
}
```

---

## 安全最佳实践

1. **改默认密码。** 现在就改。每一个部署都改。
2. **设一个真正的 JWT secret。** 至少 32 字符，通过环境变量，永远不要 commit。
3. **最小权限。** 只启用 Agent 真的需要的工具。
4. **默认 `require_approval`。** 把 Tool Guard 的 `default-policy` 翻成 `require_approval`，然后为安全场景加 `allow` 规则。**新加的工具默认安全**。
5. **配好 File Guard。** 在任何 Agent 真的碰文件系统之前把 allowed/denied 路径锁死。
6. **定期看审计日志。** 设个定时提醒。找异常。
7. **盯住技能扫描。** CRITICAL 发现不该被轻易绕过。
8. **网络隔离。** Ollama、H2 console、内部 MCP 服务——这些都不该对公网可达。
9. **生产环境别跳过审批。** 自动批准规则应该**窄而具体**。`allow *` 是定时炸弹。

---

## 安全配置参考

```yaml
mateclaw:
  auth:
    jwt:
      secret: ${JWT_SECRET:your-secret-key-at-least-32-chars}
      expiration: 86400
      sliding-window-ratio: 0.5

  tool:
    guard:
      enabled: true
      default-policy: require_approval
      approval-timeout-seconds: 600
      notifications:
        email-enabled: false
        dingtalk-enabled: false

  security:
    file-guard:
      enabled: true
      allowed-paths:
        - "${user.dir}/workspace"
      denied-paths:
        - "/etc"
        - "${user.home}/.ssh"

    audit-log:
      enabled: true
      retention-days: 90

    skill:
      security-scan:
        enabled: true
        block-critical: true
```

---

## 下一步

- [工具系统](./tools)——工具细节和 Tool Guard 规则模式
- [技能系统](./skills)——技能安全扫描细节
- [工作空间](./workspaces)——工作空间隔离基元
- [Agent 引擎](./agents)——审批如何挂起并恢复 Agent 回合
- [配置说明](./config)——完整配置参考
