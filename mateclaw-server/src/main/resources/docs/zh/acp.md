---
title: ACP 接入 —— 把外部编码 Agent 接进 MateClaw
description: MateClaw 作为 ACP 宿主，通过 stdio 把 prompt 转交给 Claude Code、Codex、OpenCode、Qwen Code 等任意 Agent Client Protocol 端点。内置端点、可视化环境变量编辑、自动桥接技能卡、信任模型、错误翻译。
head:
  - - meta
    - name: keywords
      content: ACP,Agent Client Protocol,Claude Code,Codex,OpenCode,Qwen Code,外部 Agent,stdio JSON-RPC,编码 Agent 接入
---

# ACP —— Agent Client Protocol

**ACP 是 MateClaw 把 prompt 交给别人写的 Agent 的方式。**

Agent Client Protocol 是一个开放规范，定义 Agent 客户端通过 JSON-RPC 调用 Agent 服务端的协议。MateClaw 扮演 **宿主**：拉起一个外部 CLI（Claude Code、Codex、OpenCode、Qwen Code …），通过 stdio 完成 `initialize` → `session/new` → `session/prompt` 三步握手，把流式响应回填到对话里，然后关闭进程。

如果说 MCP 是 "插一个工具"，ACP 就是 **"插一整个 Agent"**。在 MateClaw 的一次轮次里，调用 Claude Code 和调用任何内置工具没有任何区别——你的 Agent 直接请求 `acp_claude-code_prompt` 然后读取结果即可。

---

## ACP vs MCP 一眼区分

| | **MCP** | **ACP** |
|---|---|---|
| 接什么 | 工具服务器 | Agent |
| 粒度 | 按工具（`tools/list`） | 按 prompt（一次性） |
| MateClaw 的传输 | stdio / streamable_http / sse | stdio |
| 会话模型 | 长连接、多次调用 | 无状态：拉起 → prompt → 关闭 |
| 典型用法 | 文件系统、搜索、自定义数据 API | 把编码任务转给 Claude Code / Codex |
| 在 MateClaw 的呈现 | 工具目录 | 技能目录（自动桥接）+ 工具包装 |

同一个数字员工可以同时用两套。

---

## 内置端点

随 MateClaw 出厂的 Flyway 迁移会预置四个端点，**默认全部禁用**——你装好对应 CLI 之后再打开。

| 标识 | 显示名 | Command | 备注 |
|---|---|---|---|
| `claude-code` | Claude Code | `npx -y @zed-industries/claude-agent-acp` | Anthropic 的 Claude Code，读 `ANTHROPIC_API_KEY` |
| `codex` | OpenAI Codex CLI | `npx -y @zed-industries/codex-acp` | OpenAI 的编码 Agent，读 `OPENAI_API_KEY` |
| `opencode` | OpenCode | `opencode acp` | 多模型 Agent，二进制需在 `PATH` 中 |
| `qwen-code` | Qwen Code | `qwen --acp` | 阿里的编码 Agent，读 `DASHSCOPE_API_KEY` |

内置行写保护——可以改 `args_json` / `env_json` / `description` / `trusted` / `enabled`，但不能改 slug、不能换 command、不能删除。要跑别的 Agent，**新建一个自定义端点**就行。

---

## 在控制台配置

`设置 → ACP 端点` 是完整的 CRUD 入口。

### 新建 / 编辑端点

- **Slug** —— 小写标识符（如 `claude-code`），创建后不可修改。技能通过 slug 引用端点。
- **显示名** —— 技能页展示用的人类标签。
- **描述** —— 运维备注。
- **Command** —— 可执行文件（`npx`、`opencode` …），内置行锁定不可改。
- **Args（JSON 数组）** —— CLI 参数，例如 `["-y","@zed-industries/claude-agent-acp"]`。
- **Env（JSON 对象）** —— 注入子进程的额外环境变量。可视化编辑器会把 key 命中 `*API_KEY*` / `*TOKEN*` / `*SECRET*` / `*PASS*` 的值自动打码。
- **Tool parse mode** —— `call_title` / `call_detail` / `update_detail`，决定上游工具调用事件渲染到流式抄本的方式。
- **Trusted** —— 打开时，MateClaw 会自动同意上游 Agent 发来的 `session/request_permission`；关闭时，所有权限请求一律拒绝（适合非交互场景）。
- **Enabled** —— 启停开关。禁用的端点不会进入技能目录。

### 测试连接

点击 **Test** 会拉起进程、跑一遍 `initialize` + `session/new`，再关掉。结果面板显示协议版本、Agent 能力、耗时，失败时附带翻译过的错误提示（见 [信任与错误翻译](#trust-error-translation)）。状态会写回行上：`last_status` / `last_tested_at` / `last_error`。

### 启用 / 停用 / 删除

- **Toggle** —— 把端点从目录里摘掉但不删除。
- **Delete** —— 仅自定义端点可删，内置端点拒绝删除。

任何变更都会发出 `AcpEndpointChangedEvent`，技能目录立刻重新同步——不需要重启服务。

---

## REST API

基础路径：`/api/v1/acp/endpoints`，需要 JWT。

| Method | Path | 作用 |
|---|---|---|
| `GET`    | `/`              | 列出全部端点 |
| `GET`    | `/{id}`          | 取单条 |
| `POST`   | `/`              | 新建自定义端点 |
| `PUT`    | `/{id}`          | 局部更新（内置 `command` 锁定） |
| `DELETE` | `/{id}`          | 删除自定义端点（内置拒绝） |
| `PUT`    | `/{id}/toggle?enabled=true\|false` | 启用 / 停用 |
| `POST`   | `/{id}/test`     | 跑连接测试 |

### 新建自定义端点

```bash
curl -X POST http://localhost:18088/api/v1/acp/endpoints \
  -H "Content-Type: application/json" \
  -H "Authorization: Bearer <token>" \
  -d '{
    "name": "my-coder",
    "displayName": "我的自研 Agent",
    "description": "内部编码 Agent",
    "command": "npx",
    "argsJson": "[\"-y\",\"@my-org/my-acp-agent\"]",
    "envJson": "{\"MY_API_KEY\":\"sk-...\"}",
    "toolParseMode": "call_detail",
    "trusted": true,
    "enabled": true
  }'
```

### 测试端点

```bash
curl -X POST http://localhost:18088/api/v1/acp/endpoints/9100002/test \
  -H "Authorization: Bearer <token>"
```

返回示例：

```json
{
  "name": "claude-code",
  "command": "npx",
  "args": ["-y", "@zed-industries/claude-agent-acp"],
  "agentCapabilities": { "loadSession": false, "promptCapabilities": { "image": true } },
  "status": "OK",
  "elapsedMs": 1842
}
```

失败时 `status` 为 `ERROR`，`error` 字段是翻译后的提示。

---

## 端点是怎么被 Agent 用上的

两条路径：

### 1. 自动桥接的虚拟技能（零配置）

每个启用的端点都会被注册一张虚拟技能卡，并在工具注册表里多出一个名为 `acp_<slug>_prompt` 的包装工具。它接收一个 `prompt` 字符串参数，返回上游 Agent 累积的文本回答。任何数字员工都可以像调内置工具一样调用它，不用写技能清单。

```
设置 → ACP 端点（打开开关）
   ↓
AcpEndpointChangedEvent
   ↓
技能目录新增 "Claude Code" 卡片
工具注册表新增 "acp_claude-code_prompt"
   ↓
Agent 调用工具 → AcpDelegationService.prompt()
   ↓
拉起 → initialize → session/new → session/prompt
   ↓
累积 agent-message-chunk 通知
   ↓
把文本回填到 Agent 的轮次
```

### 2. 手写技能（完全可控）

技能清单可以声明 `type: acp` 并绑定到某个端点。技能会得到自己的包装工具（`acp_<endpoint>_<skill>_prompt`），可以在每次 prompt 前注入 `systemPrefix`，也可以按会话覆盖 `cwd`。

```yaml
# SKILL.md frontmatter
type: acp
acp:
  endpoint: claude-code
  systemPrefix: |
    你正在 MateClaw 仓库里工作。报完成前一定要先跑 `mvn test`。
  cwd: /workspaces/mateclaw
```

`claude-code-helper` 和 `codex-helper` 这两个出厂技能模板就是这么做的。

---

## 信任与错误翻译 {#trust-error-translation}

### 信任开关

ACP 服务端可以在做敏感动作前（写文件、跑 shell 命令等）发 `session/request_permission` 请宿主放行。MateClaw **不会** 在流式响应中途打断用户去问，而是按端点的 `trusted` 标志决定：

- `trusted: true` —— 自动选择 Agent 给的第一个选项放行。适合你自己装好的可信 CLI。
- `trusted: false` —— 所有权限请求一律拒绝。适合沙盒或不可信端点；上游 Agent 会优雅退避。

### 错误翻译

编码 Agent 的报错出了名地难懂。`AcpRuntimeSupport.translateAuthError()` 识别常见 401 / 403 / "Request not allowed" 模式，把它们改写成可执行建议：

- 缺密钥 → 提示 "请设置 `ANTHROPIC_API_KEY`" / `OPENAI_API_KEY` / `DASHSCOPE_API_KEY` / `GOOGLE_API_KEY`，按端点对应。
- Claude Code OAuth 钥匙串劫持 → 建议跑 `claude logout`，把 `~/.claude/` 里盖住你环境变量的旧 OAuth token 清掉。

提示会在测试面板里弹出，也会带进 Agent 收到的流式错误信息里。

### 超时与限额

- `initialize` 握手：15 秒
- `session/new`：10 秒
- 整个 `session/prompt` 往返：5 分钟
- stdio 缓冲上限：单次 50 MiB（行级 `stdio_buffer_limit_bytes` 可改）

---

## 数据库 —— `mate_acp_endpoint`

| 列 | 类型 | 默认 | 用途 |
|---|---|---|---|
| `id` | BIGINT | — | 主键，内置占用 `9100001`–`9100004` |
| `name` | VARCHAR(64) | — | 唯一 slug，技能引用此字段 |
| `display_name` | VARCHAR(128) | NULL | 显示名 |
| `description` | TEXT | NULL | 运维备注 |
| `command` | VARCHAR(256) | — | 进程命令 |
| `args_json` | TEXT | NULL | CLI 参数（JSON 数组） |
| `env_json` | TEXT | NULL | 环境变量覆盖（JSON 对象） |
| `tool_parse_mode` | VARCHAR(32) | `call_title` | `call_title` / `call_detail` / `update_detail` |
| `builtin` | BOOLEAN | FALSE | 内置行写保护 |
| `trusted` | BOOLEAN | TRUE | 自动放行权限请求 |
| `enabled` | BOOLEAN | FALSE | 默认关闭，按需打开 |
| `stdio_buffer_limit_bytes` | BIGINT | 52428800 | 单次 stdio 累积 50 MiB 上限 |
| `last_status` | VARCHAR(32) | NULL | `OK` / `ERROR` |
| `last_tested_at` | DATETIME | NULL | 上次测试时间 |
| `last_error` | TEXT | NULL | 上次测试错误 |
| `workspace_id` | BIGINT | 1 | 绑定的工作空间 |
| `create_time` / `update_time` | DATETIME | — | 时间戳 |
| `deleted` | INT | 0 | 逻辑删除 |

DDL 在 `db/migration/{h2,mysql}/V68__add_acp_endpoints.sql`。

---

## 排查

### "Command not found"

`command` 必须在跑 MateClaw 的用户的 `PATH` 里。`which npx`（或 `which opencode` / `which qwen`）确认一下。Docker 里要把 CLI 装进镜像。实在不行就把 `command` 写成绝对路径。

### Claude Code 报 "Request not allowed" / 403

多半是你 `~/.claude/` 里有个 OAuth token 缓存，盖住了你在环境变量编辑器里设的 `ANTHROPIC_API_KEY`。跑一次 `claude logout`，再点 **Test** 试试。测试面板检测到这种情况会主动提示。

### `session/new` 卡住

通常是上游 CLI 在首次启动时下载依赖（`npx -y` 会这样）。要么先在 MateClaw 之外手动跑一次 CLI 把依赖预热好，要么直接重试——后续调用都很快。

### "Subprocess output exceeded buffer"

Agent 在一次调用里输出超过了 50 MiB 的 stdio。把端点行的 `stdioBufferLimitBytes` 调大，或者把 prompt 拆成多轮。

### 工具没出现在技能页

- 确认 `enabled: true`。
- 确认测试通过（`last_status: OK`）。
- 看一眼 Agent 的工具绑定——自动桥接的工具默认对所有数字员工可用，除非被明确排除。

---

## 下一步

- [技能系统](./skills) —— 包括手写的 `type: acp` 技能
- [工具系统](./tools) —— 包装工具是怎么进注册表的
- [MCP 协议](./mcp) —— 服务工具用的姊妹协议
- [安全与审批](./security) —— 信任开关怎么和工具守卫配合
