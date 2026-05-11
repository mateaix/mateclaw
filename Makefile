# Docker buildx builder setup
.PHONY: builder-create builder-rm builder-inspect

BUILDER_NAME := multi-builder

builder-create:
	docker buildx create \
	  --driver docker-container \
	  --driver-opt network=host \
	  --use \
	  --config ~/.docker/buildkitd.toml \
	  --name $(BUILDER_NAME)

builder-rm:
	docker buildx rm $(BUILDER_NAME)

builder-inspect:
	docker buildx inspect --bootstrap

# Docker build targets

METACLAW_SERVER_IMAGE_TAG := 1.2.3-SNAPSHOT
MATECLAW_SERVER_SG_IMAGE := sgccr.ccs.tencentyun.com/connor-ai-lab/mateclaw-server:$(METACLAW_SERVER_IMAGE_TAG)
MATECLAW_SERVER_GZ_IMAGE := ccr.ccs.tencentyun.com/connor-ai-lab/mateclaw-server:$(METACLAW_SERVER_IMAGE_TAG)
# MATECLAW_SERVER_IMAGE := connor-mateclaw-registry.zeabur.app/mateclaw/mateclaw-server:$(IMAGE_TAG)
# MATECLAW_SERVER_TENCENT_IMAGE := ccr.ccs.tencentyun.com/connor-ai-lab/mateclaw/mateclaw-server:$(IMAGE_TAG)

SEARXNG_IMAGE_TAG := 1.0.1-SNAPSHOT
SEARXNG_SG_IMAGE := sgccr.ccs.tencentyun.com/connor-ai-lab/mateclaw-searxng:$(SEARXNG_IMAGE_TAG)
SEARXNG_GZ_IMAGE := ccr.ccs.tencentyun.com/connor-ai-lab/mateclaw-searxng:$(SEARXNG_IMAGE_TAG)
# SEARXNG_IMAGE := connor-mateclaw-registry.zeabur.app/mateclaw/searxng:$(IMAGE_TAG)
# SEARXNG_TENCENT_IMAGE := ccr.ccs.tencentyun.com/connor-ai-lab/mateclaw/searxng:$(IMAGE_TAG)

build:
	docker buildx build \
	  --platform linux/amd64 \
	  --no-cache \
	  -f mateclaw-server/Dockerfile \
	  --build-arg MAVEN_FLAGS="-Paliyun-first" \
	  -t $(MATECLAW_SERVER_SG_IMAGE) \
	  -t $(MATECLAW_SERVER_GZ_IMAGE) \
	  --push \
	  --progress=plain .

pull-searxng:
	docker pull --platform linux/amd64 searxng/searxng:latest

build-searxng:
	docker buildx build \
	  --platform linux/amd64 \
	  -t $(SEARXNG_SG_IMAGE) \
	  -t $(SEARXNG_GZ_IMAGE) \
	  --push .
