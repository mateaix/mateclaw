# Custom SearXNG image for MateClaw.
#
# Bakes our settings.yml into /etc/searxng/settings.yml so the sidecar works
# out of the box with no host bind-mount. The upstream image ships JSON output
# disabled and the Limiter plugin enabled — both silently break mateclaw's
# SearXNGSearchProvider, so this override is required.
FROM searxng/searxng:latest

COPY docker/searxng/settings.yml /etc/searxng/settings.yml
COPY docker/searxng/engines/* /usr/local/searxng/searx/engines
