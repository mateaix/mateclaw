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

IMAGE_TAG := mateclaw-1.2.0
REGISTRY  := connor-mateclaw-registry.zeabur.app/mateclaw/mateclaw-server
SEARXNG_IMAGE := connor-mateclaw-registry.zeabur.app/searxng/searxng:$(IMAGE_TAG)

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
<<<<<<< Updated upstream
	  -t $(SEARXNG_IMAGE) \
	  --push .
=======
	  -f docker/searxng/Dockerfile \
	  -t $(SEARXNG_SG_IMAGE) \
	  -t $(SEARXNG_GZ_IMAGE) \
	  --push \
	  --progress=plain .
>>>>>>> Stashed changes
