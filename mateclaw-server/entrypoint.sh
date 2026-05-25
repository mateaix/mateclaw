#!/bin/bash
set -e

# Start GitHub webhook sync server in background (port 18089)
if [ -f /app/scripts/utils/sync_github.py ]; then
    python3 /app/scripts/utils/sync_github.py >> /app/scripts/utils/sync_github.log 2>&1 &
    echo "[entrypoint] sync_github.py started (pid $!)"
else
    echo "[entrypoint] WARNING: sync_github.py not found, skipping"
fi

# Start main Spring Boot app (port 18088)
exec java -jar -Dspring.profiles.active=mysql /app/app.jar
