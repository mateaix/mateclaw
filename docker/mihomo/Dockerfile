FROM alpine:latest AS builder

RUN apk add --no-cache wget

RUN wget -O /tmp/mihomo.gz https://github.com/MetaCubeX/mihomo/releases/download/v1.19.24/mihomo-linux-amd64-compatible-v1.19.24.gz \
    && gunzip -c /tmp/mihomo.gz > /tmp/mihomo-bin \
    && chmod +x /tmp/mihomo-bin

RUN mkdir -p /tmp/geo \
    && wget -O /tmp/geo/geoip.dat \
        https://github.com/Loyalsoldier/v2ray-rules-dat/releases/latest/download/geoip.dat \
    && wget -O /tmp/geo/geosite.dat \
        https://github.com/Loyalsoldier/v2ray-rules-dat/releases/latest/download/geosite.dat \
    && wget -O /tmp/geo/country.mmdb \
        https://github.com/MetaCubeX/meta-rules-dat/releases/latest/download/country.mmdb

FROM alpine:latest

RUN mkdir -p /etc/mihomo

COPY --from=builder /tmp/mihomo-bin /usr/local/bin/mihomo
COPY --from=builder /tmp/geo/geoip.dat /etc/mihomo/geoip.dat
COPY --from=builder /tmp/geo/geosite.dat /etc/mihomo/geosite.dat
COPY --from=builder /tmp/geo/country.mmdb /etc/mihomo/country.mmdb
COPY docker/mihomo/config.yaml /etc/mihomo/config.yaml

RUN chmod +x /usr/local/bin/mihomo

CMD ["mihomo", "-d", "/etc/mihomo"]