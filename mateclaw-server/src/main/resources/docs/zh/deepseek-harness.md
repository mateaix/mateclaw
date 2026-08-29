---
title: DeepSeek Harness 接入
description: 在 MateClaw 中安装 DeepSeek Harness，并把它配置为数字员工运行时。
head:
  - - meta
    - name: keywords
      content: DeepSeek Harness,DSH,数字员工,Agent runtime,JSON-RPC,Cordis
---

# DeepSeek Harness 接入

本文说明如何把官方 DeepSeek Harness（简称 DSH）接入 MateClaw，并在员工页面创建一个由 DSH 驱动的数字员工。

DSH 在 MateClaw 中是**员工运行时**，不是 MCP 工具，也不是普通插件。MCP 负责为员工提供工具；DSH 负责启动外部 Agent 进程、运行 ReAct 循环并把思考、工具和文本事件流回 MateClaw。

## 架构

```text
MateClaw Chat / SSE
        |
        v
DSH Runtime Provider
        |
        v  JSON-RPC over stdin/stdout
dsh-jsonrpc-agent
        |
        v
DeepSeek API + Cordis composition
```

MateClaw 仍然负责员工、会话、权限、工作空间、消息持久化和 UI 投影。DSH 只负责运行时回合。API Key 由 MateClaw 的 DeepSeek 提供商配置注入到 DSH 子进程，不要把密钥写进 `runtimeConfig`、员工提示词或仓库文件。

## 前置条件

- macOS、Linux 或 Windows（本文命令以 macOS / Linux 为例）
- JDK 21
- 已启动的 MateClaw 后端和前端
- DeepSeek API Key
- 已构建的 DSH JSON-RPC Agent
- DSH 仓库中的 Cordis 配置文件

确认 DSH 可执行文件：

```bash
"$DSH_JSONRPC_AGENT" --help
test -x "$DSH_JSONRPC_AGENT"
test -f "$DSH_CORDIS_CONFIG"
```

## 安装 DSH

请以 [DeepSeek Harness 官方仓库](https://github.com/deepseek-ai/deepseek-harness) 的安装说明为准构建运行时。构建完成后，需要得到两个路径：

```text
<dsh-root>/dist-exe/dsh-jsonrpc-agent-pkg-<platform>
<dsh-root>/python/sdk-runtime/src/deepseek_harness_runtime/runtime/cordis.yml
```

不要把 DSH 二进制复制到 MateClaw 的源码仓库。推荐放在独立目录，并通过环境变量告诉 MateClaw 位置。

## 在 IDEA 中配置后端

打开 IDEA 的 **Run | Edit Configurations...**，选择 MateClaw 的 Spring Boot 配置，在 **Environment variables** 中增加：

```text
DSH_JSONRPC_AGENT=/absolute/path/to/dsh-jsonrpc-agent-pkg-macos-arm64
DSH_CORDIS_CONFIG=/absolute/path/to/cordis.yml
DSH_CWD=/absolute/path/to/mateclaw-workspace
```

示例：

```text
DSH_JSONRPC_AGENT=/opt/deepseek-harness/dist-exe/dsh-jsonrpc-agent-pkg-macos-arm64
DSH_CORDIS_CONFIG=/opt/deepseek-harness/python/sdk-runtime/src/deepseek_harness_runtime/runtime/cordis.yml
DSH_CWD=/var/lib/mateclaw/workspace
```

`DSH_CWD` 必须是后端进程可读写的目录。IDEA 启动配置中的路径必须是绝对路径；修改后需要重启后端，Spring Boot 不会热加载环境变量。

## 配置 DeepSeek 提供商

1. 登录 MateClaw。
2. 打开 **设置 → 模型**。
3. 配置并启用 **DeepSeek** 提供商。
4. 填入 DeepSeek API Key 和 Base URL。
5. 确认至少有一个启用的 DeepSeek chat 模型。

DSH 默认模型是 `deepseek-v4-flash`。如果员工没有绑定具体模型，MateClaw 会使用全局默认模型名，并从 `deepseek` 提供商注入凭证。自定义模型时，模型必须能由 DeepSeek Harness 的 DeepSeek provider route 使用。

## 创建 DSH 数字员工

在 **数字员工 → 新建** 中：

1. 填写员工名称、角色和目标。
2. 将运行时选择为 **DSH / DeepSeek Harness**。
3. 配置工作空间；留空时使用 `DSH_CWD`。
4. `runtimeConfig` 使用 JSON 对象，例如：

```json
{
  "mode": "qa",
  "workspace": "default",
  "policy": "read-only"
}
```

5. 保存员工并进入聊天。

运行时配置只描述员工级策略。不要在其中写 `DEEPSEEK_API_KEY`、Cookie、Bearer Token 或本机敏感路径。

## 验证清单

在 DSH 员工会话中发送：

```text
请只回复：DSH_RUNTIME_OK
```

成功标准：

- 员工标题显示 `DSH Harness`。
- 输入框发送后能看到思考状态和文本流。
- 日志出现 `provider=deepseek` 和 `apiKeyConfigured=true`。
- 日志出现 `turn/end` 且 `kind=completed`。
- 页面不会显示“本次没有输出”。
- 同一个会话不会被并发启动两个 DSH live session。

不要复用已经完成过的测试 `conversationId` 创建新的 DSH live session。DSH 会检测到磁盘上的 session 日志与新的 live session 不一致，并返回 `id collision`。请使用“新对话”创建新的会话。

## 日志与诊断

后端日志通常位于：

```text
logs/mateclaw.log
```

重点搜索：

```bash
rg "\[DSH\]|MISSING_CREDENTIAL|EMPTY_RESPONSE|id collision" logs/mateclaw.log
```

安全诊断接口：

```http
GET /api/v1/admin/agent-runtime/dsh/diagnostics
```

它只返回命令、可执行文件、Cordis 文件和能力状态，不返回 API Key。

## 常见问题

### `MISSING_CREDENTIAL`

检查：

1. 设置 → 模型中的 DeepSeek 提供商是否已启用。
2. API Key 是否保存成功。
3. 员工是否使用 DSH，而不是把 DSH 二进制配置成 MCP command。
4. 后端是否使用了修改后的 IDEA 配置并完成重启。

日志中的 `apiKeyConfigured=false` 表示凭证没有进入 DSH 子进程。

### `EMPTY_RESPONSE`

检查模型是否可用、Base URL 是否正确，以及该模型是否支持当前请求。先使用固定短消息验证，再逐步增加工具或技能。

### `dsh.command_unavailable`

`DSH_JSONRPC_AGENT` 必须指向真实可执行文件。检查文件权限：

```bash
chmod +x /absolute/path/to/dsh-jsonrpc-agent-pkg-macos-arm64
```

### `dsh.cordis_missing`

`DSH_CORDIS_CONFIG` 必须指向实际存在的 `cordis.yml`，不是 DSH 包目录。若传入包目录，MateClaw 会尝试解析其下的 `runtime/cordis.yml`。

### 页面显示重复回答

确保使用最新后端版本。DSH 会同时发送增量文本事件和最终消息快照，MateClaw 只应投影增量文本，不能把快照再次追加到回答中。

## MCP、插件和 DSH 的边界

| 机制 | 适合做什么 | 是否替代 DSH |
|------|------------|-------------|
| MCP | 提供文件、GitHub、数据库等工具 | 否 |
| 插件 | 扩展 MateClaw 的工具、模型、渠道或记忆能力 | 否 |
| DSH 员工运行时 | 承载 DeepSeek Harness 的 Agent 循环和外部进程 | 是员工运行时，不是工具 |

推荐组合是：**DSH 作为员工运行时，MCP 作为工具层，MateClaw 作为治理和可视化层**。

## 安全建议

- API Key 只放在 MateClaw 模型提供商配置或受控环境变量中。
- DSH 工作空间使用专用目录，不要直接指向整个用户主目录。
- 初次接入使用只读策略和最小工具集。
- 不要把 `.sessions/`、日志或配置文件提交到 Git。
- 生产环境中限制 DSH 子进程的文件、网络和凭证访问范围。
