# A2A 协议

MateClaw 可以把已启用智能体暴露为 A2A JSON-RPC 端点，也可以通过工具调用其他 A2A 对端。

## 配置

```yaml
mateclaw:
  a2a:
    enabled: true
    base-url: https://your-public-host
    call-timeout-ms: 120000
    max-tasks: 1000
    task-ttl-seconds: 3600
```

生产环境必须配置 `base-url`。为空时，MateClaw 会按入站请求推导名片 URL，这依赖反向代理正确传递 Host 与协议头。

## 入站

- `GET /.well-known/agent-card.json` 和匿名 `GET /api/a2a/card` 返回最小公开名片，不包含 `skills`。
- 带 Bearer 访问 `GET /api/a2a/card` 返回 enabled 智能体列表到 `skills[]`；调用时把智能体 id 放到 `message.metadata.skillId`。
- `POST /api/a2a` 复用 MateClaw Bearer token 鉴权，支持 `message/send`、`message/stream`、`tasks/get`、`tasks/cancel`。

## 出站工具

智能体可调用 `call_a2a_agent`：

```json
{
  "url": "https://peer.example.com/api/a2a",
  "headers": {
    "Authorization": "Bearer token"
  },
  "stream": false
}
```

出站默认拒绝内网和保留地址，不跟随重定向，限制响应大小；阻塞发送返回在途任务时，会继续轮询 `tasks/get`。
