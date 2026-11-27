# Pass a reviewed, immutable Node 24.22.3+ (24.x) base image (name@sha256:...).
ARG NODE_IMAGE=node@sha256:49f7adaf9f1574bc45c20ea317139b39a59d7e758d0e93745e112fd85cb42623
FROM ${NODE_IMAGE}
ARG DSH_VERSION
ARG DSH_LOCK_SHA256
LABEL vip.mateclaw.dsh.version="${DSH_VERSION}" \
      vip.mateclaw.dsh.lock-sha256="${DSH_LOCK_SHA256}"
RUN apt-get update \
    && apt-get install -y --no-install-recommends bubblewrap \
    && rm -rf /var/lib/apt/lists/*
WORKDIR /opt/mateclaw-dsh
COPY locks/${DSH_VERSION}.json package-lock.json
COPY mateclaw-sdk.patch.yml mateclaw-sdk.patch.yml
COPY mateclaw-sdk-launcher.sh bin/dsh
RUN node -e 'const fs=require("fs");const crypto=require("crypto");if(crypto.createHash("sha256").update(fs.readFileSync("package-lock.json")).digest("hex")!==process.env.DSH_LOCK_SHA256)throw Error("lock digest mismatch");const l=JSON.parse(fs.readFileSync("package-lock.json"));fs.writeFileSync("package.json",JSON.stringify({name:"mateclaw-dsh-runtime",version:"1.0.0",private:true,dependencies:l.packages[""].dependencies,allowScripts:Object.fromEntries(Object.entries(l.packages).filter(([k,v])=>v.hasInstallScript).map(([k])=>[k.split("node_modules/").pop(),true]))}))' \
    && npm ci --registry=https://registry.npmjs.org --no-audit --no-fund \
    && npm cache clean --force \
    && case "$DSH_VERSION" in 0.2.2-rc.1|0.2.2-rc.5|0.2.3-alpha.2) chmod 755 bin/dsh ;; *) rm bin/dsh && ln -s ../node_modules/.bin/dsh bin/dsh ;; esac
ENTRYPOINT ["/opt/mateclaw-dsh/bin/dsh"]
