# A2A Protocol

MateClaw can expose enabled agents through an A2A JSON-RPC endpoint and can call other A2A peers from agent tools.

## Configuration

```yaml
mateclaw:
  a2a:
    enabled: true
    base-url: https://your-public-host
    call-timeout-ms: 120000
    max-tasks: 1000
    task-ttl-seconds: 3600
```

`base-url` is required for production deployments. If it is empty, MateClaw derives the Agent Card URL from the incoming request, which depends on proxy headers being correct.

## Inbound

- `GET /.well-known/agent-card.json` and anonymous `GET /api/a2a/card` return the public minimal card without `skills`.
- Authenticated `GET /api/a2a/card` returns enabled agents in `skills[]`; use the agent id as `message.metadata.skillId`.
- `POST /api/a2a` requires an existing MateClaw Bearer token and supports `message/send`, `message/stream`, `tasks/get`, and `tasks/cancel`.

## Outbound Tool

Agents can call `call_a2a_agent` with:

```json
{
  "url": "https://peer.example.com/api/a2a",
  "headers": {
    "Authorization": "Bearer token"
  },
  "stream": false
}
```

Outbound calls reject private or reserved network targets by default, do not follow redirects, cap response size, and poll `tasks/get` when a blocking send returns an in-progress task.
