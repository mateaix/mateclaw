# Doctor

Doctor 是应用内的健康抽屉。它从后端健康服务读取当前本机实例状态；当前实现不是一个独立的定时诊断系统。

可以从布局里的状态按钮 / 设置区域打开。抽屉每次打开或点击刷新时都会请求后端。

## 当前后端 API

```bash
curl http://localhost:18088/api/v1/system/health \
  -H "Authorization: Bearer <token>"
```

响应结构：

```json
{
  "code": 200,
  "msg": "success",
  "data": {
    "overall": "healthy",
    "checks": [
      {
        "name": "default-model",
        "status": "healthy",
        "message": "Default model: qwen-plus",
        "action": null
      }
    ]
  }
}
```

`overall` 取值为 `healthy`、`warning`、`error`。每个检查项包含：

| 字段 | 含义 |
|---|---|
| `name` | 稳定检查 key，例如 `default-model`、`database`、`browser`、`provider:<id>`、`mcp:<name>` |
| `status` | `healthy`、`warning` 或 `error` |
| `message` | 抽屉里展示的简短诊断信息 |
| `action` | 可选 `{ label, route }`，提示去哪里修 |

## 当前检查项

当前 `SystemHealthService` 检查：

| 检查 | 验证什么 | 常见修复入口 |
|---|---|---|
| 默认模型 | 是否配置并能加载默认模型 | `/settings/models` |
| Provider 配置 | 需要 API key 的 provider 是否已配置 | `/settings/models` |
| 已启用 MCP 服务 | 已启用 MCP 服务是否有成功连接结果 | `/settings/mcp-servers` |
| 数据库初始化 | 首次启动 bootstrap 是否完成 | `/setup` |
| 浏览器诊断 | 浏览器工具启动前置条件 | `/api/v1/system/browser-health` |

浏览器诊断也有独立接口：

```bash
curl http://localhost:18088/api/v1/system/browser-health \
  -H "Authorization: Bearer <token>"
```

## 当前源码没有实现的旧内容

旧文档曾提到 `/api/v1/doctor/run`、`/api/v1/doctor/checks`、`/api/v1/doctor/history`、Doctor 定时后台运行、`mate_doctor_check`、`mate_doctor_check_history`。这些端点和表在当前后端源码中不存在。当前健康检查请使用 `/api/v1/system/health`。

## 相关页面

- [API 参考](./api) —— 源码对齐的路由索引
- [模型配置](./models) —— 模型 / provider 设置
- [MCP 协议](./mcp) —— MCP 服务配置
- [安全与审批](./security) —— Tool Guard 和审批行为
