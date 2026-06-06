#!/bin/sh
set -e

envsubst < /etc/mihomo/config.yaml > /tmp/mihomo-config.yaml
exec "$@" -f /tmp/mihomo-config.yaml
