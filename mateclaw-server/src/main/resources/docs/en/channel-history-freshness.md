# Channel history freshness

Native and DSH runtimes use the same channel-history policy. By default, the last 30 minutes retain full turns, 30 minutes to 24 hours retain user questions while replacing old answers and tool results with expiry notices, and older turns are omitted from automatic model context. Persisted messages remain unchanged.

For current figures, query the authoritative source in the current turn and report its timestamp. The 30-minute window is not a business-data cache TTL; model guidance does not guarantee a tool query.

```yaml
mate:
  agent:
    channel-history:
      enabled: true
      hot-minutes: 30
      warm-minutes: 1440
```

See the [detailed Chinese implementation and verification notes](../zh/channel-history-freshness) and [channel setup](./channels). Runtime behavior depends on the deployed server version; updating this documentation does not upgrade the server.
