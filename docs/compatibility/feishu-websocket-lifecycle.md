# Feishu WebSocket lifecycle (oapi-sdk 2.7.1)

The adapter owns reconnect scheduling. SDK `autoReconnect` stays false. SDK
`start()` initiates a handshake and returns; only `awaitReady` success makes the
adapter CONNECTED. Startup failures and actual socket failures enter the existing
single exponential-backoff loop. A 15-second probe observes the SDK socket;
OkHttp protocol ping/pong (20 seconds) detects half-open transports without
business messages. A quiet channel does not imply a broken connection.

`FeishuWebSocketClient` is a deliberately narrow, version-bound compatibility
bridge. SDK 2.7.1 has a private constructor and no public resource-disposal or
transport-status APIs. Its `close()` does not shut down the executor. The bridge
validates `executor`, `httpClient`, and `conn` field types. Reads of the non-volatile
`conn` field acquire the SDK monitor used by `disconnect()`. It configures the dedicated
HTTP client before use, and releases SDK/HTTP executors and idle connections on
permanent close. It cancels the underlying socket after SDK close (which only
enqueues a graceful close), outside the SDK monitor to allow failure callbacks.
It cancels HTTP calls and interrupts the starter before taking
the lifecycle lock, so a pending handshake cannot prevent shutdown. Do not remove
these contract tests or upgrade the SDK without rechecking its lifecycle.

Feishu opts out of message-age staleness with Duration.ZERO. Other adapters retain
their existing thresholds.
ERROR recovery remains active; long-poll adapters retain their existing positive
thresholds. The legacy Feishu `silent_disconnect_threshold_seconds` setting no
longer triggers reconnects. A transport reconnect reuses the HTTP token client;
token refresh already has its own schedule. Permanent stop shuts down that client.

Regression tests use a loopback-only HTTP/WebSocket peer and synthetic credentials,
with no model calls or live Feishu messages. They cover delayed/rejected handshakes,
TCP resets, peer close, missing pongs, stop during startup, close-before-start,
close between handshake and success, and 25 real SDK start/stop cycles. Each old
SDK and HTTP executor must terminate; quiet channels, ERROR cooldown, and Weixin
five-minute stale recovery are verified separately.

## Validation

Run the lifecycle and existing channel regressions on Java 21:

```sh
JAVA_TOOL_OPTIONS=-Djdk.httpclient.allowRestrictedHeaders=connection \
  mvn -pl mateclaw-server -am \
  -Dtest='Feishu*Test,ChannelHealthMonitor*Test,ChannelAdapterStalenessTest' \
  -Dsurefire.failIfNoSpecifiedTests=false test
```

The JVM option matches the application startup HTTP setting; the broader existing
Feishu HTTP tests also run outside the application main method. The lifecycle
tests do not need credentials or Internet access. The native SDK
resource bridge is scoped to the pinned oapi-sdk 2.7.1; review the reflected field
contracts before changing that dependency. Local socket tests do not establish
real-user delivery or multi-day reliability.
