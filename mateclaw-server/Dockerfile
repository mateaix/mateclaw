# Multi-stage build
#
# Stage 1 — Frontend (Node / pnpm)
#   Builds the Vue 3 admin SPA and emits static files to /static inside the
#   build container. These files are later copied into the JAR's classpath so
#   Spring Boot serves the SPA at the root URL.
FROM node:22-alpine AS frontend-builder
# Pin pnpm to a major version so the Docker build doesn't break when the npm
# `latest` tag jumps majors. pnpm v10+ blocks dependency lifecycle scripts by
# default; the allowed packages live under `pnpm.onlyBuiltDependencies` in
# mateclaw-ui/package.json.
RUN npm install -g pnpm@10 --silent
WORKDIR /frontend
# Install dependencies first (layer cache)
COPY mateclaw-ui/package.json mateclaw-ui/pnpm-lock.yaml mateclaw-ui/.npmrc ./
RUN pnpm install --frozen-lockfile --ignore-scripts && pnpm rebuild

# Build with increased Node heap to avoid OOM during vite build
ENV NODE_OPTIONS="--max-old-space-size=4096"
# Copy source and build
COPY mateclaw-ui/ ./
# Override outDir: vite.config.ts writes to ../mateclaw-server/…/static which
# is outside this container; call vite directly to control --outDir.
# NODE_OPTIONS=--max-old-space-size=6144 keeps Rollup's `rendering chunks`
# phase from getting SIGKILL'd by the host kernel's OOM-killer on memory-
# constrained servers. The earlier removal of this flag relied on lazy-
# loading + manualChunks dropping the per-chunk peak, but Rollup still
# minifies several vendor chunks (monaco / mermaid / echarts) in parallel
# so the cumulative working set blows past Node's default ~1.5 GB heap
# and trips the OOM-killer mid-build. The fix is not the heap flag
# itself; it is keeping the build reproducible on smaller hosts.
# Skipping vue-tsc here is intentional — type errors are caught in CI, not in
# the production Docker image build.
RUN NODE_OPTIONS=--max-old-space-size=6144 pnpm exec vite build --outDir /static --emptyOutDir

# Stage 2 — Backend (Maven)
FROM maven:3.9-eclipse-temurin-21 AS builder

# Optional Maven extra flags passed at build time.
# Set MAVEN_FLAGS=-Paliyun-first in .env (or via --build-arg) to put Aliyun
# repos first. This speeds up builds inside mainland China.
ARG MAVEN_FLAGS=""

# Inject mirror settings to avoid Maven Central timeouts in restricted networks
COPY mateclaw-server/settings.xml /root/.m2/settings.xml

# Copy the root parent plus module POMs first for Docker layer caching.
WORKDIR /build
COPY pom.xml ./pom.xml
COPY mateclaw-plugin-api/pom.xml mateclaw-plugin-api/pom.xml
COPY mateclaw-server/pom.xml mateclaw-server/pom.xml
COPY mateclaw-plugin-sample/pom.xml mateclaw-plugin-sample/pom.xml
COPY mateclaw-plugin-search-sample/pom.xml mateclaw-plugin-search-sample/pom.xml

# Pre-fetch backend dependencies through the reactor so the parent POM,
# dependencyManagement, and internal module versions all resolve consistently.
RUN mvn -pl mateclaw-server -am dependency:go-offline -q ${MAVEN_FLAGS}

# Copy backend source and inject pre-built frontend into the right classpath location
COPY mateclaw-plugin-api/src mateclaw-plugin-api/src
COPY mateclaw-server/src mateclaw-server/src
COPY --from=frontend-builder /static mateclaw-server/src/main/resources/static

RUN mvn -pl mateclaw-server -am package -Dmaven.test.skip=true -q ${MAVEN_FLAGS}

# Stage 3 — Runtime
#
# Uses Microsoft's official Playwright image (Ubuntu Noble, glibc) with all three
# browsers (Chromium / Firefox / WebKit) and every system library Chromium needs
# pre-installed. This avoids the `playwright install` step and the Alpine/musl
# incompatibility that blocks browser_use on minimal images.
#
# We pin to the exact Playwright version declared in the root pom.xml. If you
# bump the Java dependency, bump this tag in lockstep — Microsoft rebuilds each
# tag with the matching driver, so mismatched versions cause the java driver to
# re-download browsers at runtime (defeating the whole point of this image).
FROM mcr.microsoft.com/playwright:v1.59.0-noble
WORKDIR /app

# JDK 21 is NOT part of the base image (it ships Node for the JS driver).
# Install openjdk-21 explicitly and add CJK fonts so Chinese pages render
# correctly in screenshots and snapshots.
#
# PDF extraction toolchain — DocumentExtractTool tries pdftotext first, then
# Python pdfplumber/pypdf, then falls through to a naive Java parser that
# reads bytes as ISO_8859_1 (mojibake for CJK). Without poppler-utils the
# Docker image always hits the naive path and feeds garbled text to the
# Wiki pipeline.
#
# We install poppler-utils (backend 1) and tesseract (backend 4), which
# together cover the vast majority of PDFs including scanned docs. The
# Python backend is intentionally skipped — pip install against aliyun
# mirrors in CN networks hits transient hash-mismatch failures on cffi /
# cryptography transitive deps, and RFC-051 PR-1c will replace the Python
# hop with JVM-native Tika extraction anyway. Leaving it out keeps the
# image ~200 MB smaller and the build reproducible.
RUN apt-get update \
    && apt-get install -y --no-install-recommends \
        openjdk-21-jre-headless \
        fonts-noto-cjk \
        fonts-noto-color-emoji \
        poppler-utils \
        python3-pip \
        tesseract-ocr \
        tesseract-ocr-chi-sim \
        tzdata \
        python3-pip \
        python-is-python3 \
        python3-dev \
        build-essential \
    && rm -rf /var/lib/apt/lists/*

# Remove PEP 668's EXTERNALLY-MANAGED marker so pip can install packages
# system-wide without --break-system-packages. This is a container — there is
# no host Python environment to protect. Skill scripts and LLM-generated code
# need to `pip install` on the fly; PEP 668 would block every install with
# "error: externally-managed-environment".
RUN rm -f /usr/lib/python3*/EXTERNALLY-MANAGED

# Tell Playwright Java where Microsoft's image stored the browsers.
# BrowserLauncher's BUNDLED strategy will then succeed without extra config.
ENV PLAYWRIGHT_BROWSERS_PATH=/ms-playwright \
    TZ=Asia/Shanghai \
    LANG=C.UTF-8 \
    LC_ALL=C.UTF-8 \
    JAVA_TOOL_OPTIONS="-Duser.timezone=Asia/Shanghai -Dsun.jnu.encoding=UTF-8" \
    PIP_INDEX_URL=https://pypi.org/simple \
    PIP_TRUSTED_HOST=pypi.org

# Default DB profile, overridable by the SPRING_PROFILES_ACTIVE env var
# (compose sets it explicitly: mysql / postgres / kingbase). It must be an ENV,
# not a -D system property on the ENTRYPOINT: a hardcoded
# -Dspring.profiles.active outranks the SPRING_PROFILES_ACTIVE env var and would
# silently pin the profile regardless of what compose passes.
ENV SPRING_PROFILES_ACTIVE=mysql

COPY --from=builder /build/mateclaw-server/target/*.jar app.jar

EXPOSE 18088
EXPOSE 1455
ENTRYPOINT ["java", "-jar", "app.jar"]
