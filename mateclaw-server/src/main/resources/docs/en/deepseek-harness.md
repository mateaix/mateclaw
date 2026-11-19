---
title: DeepSeek Harness Integration
description: Configure, verify, upgrade, and recover the managed DeepSeek Harness SDK runtime.
---

# DeepSeek Harness Integration

DSH is an employee runtime. MateClaw launches the official `dsh --profile sdk` process and translates its JSON-RPC session events into conversation output. Configure the DeepSeek provider in **Settings → Models**, then select **DSH / DeepSeek Harness** when creating a digital employee with a workspace.

## Requirements and installation

The managed installer requires npm and registry access. **0.2.2-rc.1 requires Node.js 24.9+ (24.x)**; retained older releases accept Node.js 22.x or 24.x. It supports macOS arm64 and Linux x64 candidates. Actual package installation and Java adapter/model checks have passed on macOS arm64 with Node 22.20.0 and npm 11.6.2. Linux x64 remains pending actual verification; Windows/WSL has not been verified.

Use the DSH management console to install the pinned **`0.2.0-rc.1`** target. The reviewed alternative is **`0.1.7-rc.2`**. Installation uses checked-in dependency locks and `npm ci` in a separate candidate directory; it does not resolve a moving `latest` tag or modify global npm packages. All catalog entries are prereleases.

Run **Verify**, **Test connection**, and **Test task**. These mean different things: configuration checks, an SDK `initialize` handshake, and a real model task requiring the answer `OK`. A successful handshake alone does not establish model access. The wire server version `0.0.1` is not the npm package version; managed provenance records the pinned package separately. An external executable remains unverified unless independently checked.

The Docker runtime image pins Node.js 24.22.2 and includes bubblewrap for Linux sandbox execution. Rebuild or pull the updated image before installing managed DSH packages; updating the application JAR alone does not update container dependencies. Host installations must supply their own runtime dependencies.

## SDK configuration

Use the managed executable returned by installation (`bin/dsh` for 0.2.2-rc.1), or an independently verified absolute SDK executable path, profile `sdk`, an existing working directory, and a dedicated home root. Optional patch paths are an ordered JSON array of absolute files. MateClaw assigns a separate `DSH_HOME` for each workspace/employee pair and launches arguments directly, without a shell.

| Managed key | Purpose |
| --- | --- |
| `dsh.executable_path` | Absolute SDK CLI executable |
| `dsh.profile` | Must be `sdk` |
| `dsh.working_directory` | Default workspace directory |
| `dsh.home_root` | Root for isolated runtime homes |
| `dsh.patch_paths` | Ordered JSON array of patch files, for example `[]` |

Legacy `dsh-jsonrpc-agent`/Cordis entrypoint configuration requires migration. Clear `dsh.cordis_config_path` and the old `DSH_CORDIS_CONFIG` environment setting; replace the executable with the SDK CLI. Do not put a shell command or extra arguments in the executable field. `DSH_JSONRPC_AGENT` remains a compatibility environment name for an executable path, not an instruction to use the old binary.

Keep credentials in the provider configuration or dedicated protected runtime settings. MateClaw passes them to the child environment. For the official DeepSeek host, the adapter maps its ordinary API root to the Messages API `/anthropic` route required by these packages. Arbitrary custom endpoints still need a compatible Messages API.

MateClaw sends an explicit model output cap through `initialize.maxTokens`: the configured positive cap, defaulting to 4096, limited to half the known context window. This does not replace DSH's context management.

## Upgrade and rollback

Managed upgrades prepare a separate package generation, block new DSH turns, and wait up to 60 seconds for current turns to drain. They copy runtime homes and configured patch files, check the candidate handshake and a new model task in each affected home, then atomically select the executable/configuration/home generation together. Original homes and the previous generation remain available. Failure before activation keeps the previous selection. Native turns are not part of the DSH admission gate.

The home copier rejects symlinks and special files instead of following them. Customized homes containing such files require explicit migration. Generation records and package locations are retained; active home contents remain mutable during normal use.

Rollback is available for a completed operation while its candidate generation is still active. It copies and checks the retained previous home/configuration before switching. It does **not** merge sessions created after the upgrade or reverse database changes. Keep retained generations and backups until acceptance is complete.

Candidate checks start new sessions. They do not prove that every historic DSH session can be reopened or migrated. Actual persisted **V3 → V4 history migration has not been verified**.

DSH uses its profile-managed tools and does not provide MateClaw host approval integration. Member file isolation blocks this runtime. Rollback does not undo tool writes to workspace files or delete MateClaw chat records.

## Conversations and diagnostics

Each MateClaw turn uses a fresh SDK session ID. MateClaw replays bounded completed user/assistant text history; this is not restoration of DSH tool state. Do not reuse an existing persisted SDK ID as a fresh session. Completion is based on the SDK terminal event, not merely on an enqueue receipt or idle status.

The admin API is under `/api/v1/admin/dsh`: `GET /status`, `POST /verify`, `/test-connection`, `/test-task`, `/upgrades`, and `GET /upgrades/{id}`. Upgrade requests carry an exact `targetVersion`, the current `expectedRevision`, and an `idempotencyKey`; rollback is `POST /upgrades/{id}/rollback` with revision and idempotency key. Poll the operation record for results. Logs and status must not expose credentials.

## Recovering an orphaned runtime

`dsh.orphaned_runtime_requires_recovery` means a lease belongs to a JVM that is no longer alive. Admission deliberately remains blocked: its DSH process or tool descendants may still be writing the runtime home.

1. Stop DSH traffic and identify the lease under the managed install root's `leases/` directory (default root: `~/.mateclaw/runtimes/deepseek-harness`).
2. Verify the recorded JVM owner is gone, identify and stop its orphaned DSH processes **and tool descendants**, and confirm no process is writing the affected home.
3. Back up the affected state, then remove only the confirmed stale lease. Do not remove live leases or blindly delete the leases directory.
4. Inspect the durable upgrade operation and active generation, then retry verification before admitting work.

A backend restart alone is not orphan-process cleanup. Do not delete a stale lease merely to bypass the error.

## 0.2.2-rc.1 candidate (2026-11-05)

The catalog includes exact `0.2.2-rc.1`, alongside all retained older releases. The default remains `0.2.0-rc.1`; adding this candidate does not activate it. Start the backend with Node24 first on its PATH before installing the new candidate. The old releases remain installable on Node22 or Node24 and their generations remain available for rollback.

The managed `bin/dsh` launcher supplies a reviewed patch before administrator patches. It explicitly restores `fs-observation-policy` and sets the selected model's context/output metadata from MateClaw. The native SDK file tools retain read-before-write and stale-file checks; Bash remains subject to its own sandbox, not these file-tool observation checks. Explicit administrator patches can override these defaults. Use the returned launcher path to keep these settings; invoking `node_modules/.bin/dsh` directly bypasses them. Docker images apply the same patch. External installations must configure equivalent policies themselves.

Final answer text is delivered after the correlated `turn/end`, before waiting for idle and SDK shutdown, without appending it again after an EOF. This is settled-message delivery, not token streaming: this SDK emits no live text-delta notification. Tool receipts preserve success/error codes and distinguish PTC `ABORTED` from ordinary failures. Committed usage includes separate cache read/write counters. Host question and approval bridging remain unavailable; no new SDK capabilities are advertised.

Each turn still creates a fresh process/session. Upstream prefix-cache improvements do not prove cross-turn cache savings for this adapter; explicit context capacity prevents silently inheriting a larger upstream default. See the repository record `docs/verification/dsh-022-2026-11-05.md` for exact evidence and remaining platform gates.

## 0.2.1-rc.2 evolutionary candidate (2026-10-20)

The catalog now offers exact `0.2.1-rc.2` as an opt-in candidate alongside retained `0.2.0-rc.1` and `0.1.7-rc.2`. The default install target stays `0.2.0-rc.1`; adding the candidate does not change the active generation. All three versions are prereleases. The management page reads the server catalog and retains an installed catalog version rather than silently selecting a newer release.

Candidate checks on macOS arm64 / Node 22.20.0 / npm 11.6.2 cover clean `npm ci`, actual SDK initialize/shutdown, a DeepSeek V4 Flash reply (`SDK_OK`) and a real Bash tool task (`printf DSH_TOOL_OK`). Sanitized reply/tool recordings run through the Java turn adapter regression suite. Subsequent macOS checks also passed actual cancel/crash recovery, interrupted file-checkpoint verification and 20 fresh-process rounds in 501 seconds. They do not establish multi-hour endurance, persisted-history migration, Linux/WSL compatibility or production rollback for this candidate. Run the existing managed upgrade checks before activation; retain previous generations.

The adapter recognizes the candidate's job registration/settlement and working-directory metadata events without treating them as completion. `DSH_AGENTS_HOME` is explicitly scoped to `<employee-generation-home>/agents`, preventing implicit reads from the host user's `~/.agents`; a symlink at that instruction root is rejected. Administrator patch files can override SDK configuration and remain trusted inputs. This is not an OS sandbox, and DSH still has no MateClaw host tool-approval bridge.

Upstream model/search, minimal-profile image/compaction, interactive questions and working-directory APIs are separate integration work. Updating the package does not automatically expose them in MateClaw, merge DSH context management into native prompt budgets, or restore historic tool state. The existing text-history replay and output cap remain.

## Cancellation and workspace checkpoints (2026-10-20)

Real SDK/model checks exercised a running Bash sleep: explicit cancellation produced one CANCELLED event, and forcibly killing the SDK root failed the stream. In both tested cases the recorded SDK/tool PIDs exited and a new application connection completed. A separate interrupted-write case verified an already committed checkpoint without rewriting it. Twenty fresh-process rounds each read, append, wait and verify a ledger; all 20 exact ledger snapshots passed over 501 seconds. This is a bounded multi-minute exercise, not a multi-hour guarantee.

The transport now retains observed descendant ProcessHandles while the root is alive so those handles remain available after a crash. Cleanup is best effort: children born and reparented entirely between observations, or detached beforehand, can escape capture. An isolated runtime home does not provide OS containment, and stronger process supervision remains an open gate. Candidate status and strict-member-isolation restrictions remain.

Workspace-checkpoint recovery requires inspecting the existing artifact before deciding whether to repeat a write. It does not restore SDK tool state, automatically replay uncertain side effects, or prove exactly-once behavior for external systems. Goal acceptance, call budgets, approvals and application permissions are unchanged; regression covers them separately from these direct runtime probes.

## Optional Docker process supervision (experimental)

`native` remains the default. Native descendant observation cannot guarantee cleanup of a process that forks, creates a new session and reparents between observations. Optional `docker` mode creates a separate Linux PID namespace **before** SDK startup. Cancellation, SDK shutdown and loss of the attached Docker client remove only the recorded container ID. Completed/CANCELLED events require confirmed cleanup; a removal failure is an error and retains the conversation's generation lease until an explicit cancellation retry confirms removal. It never falls back to native execution.

Build a dedicated image from the bundled trusted lock (from the repository root):

```bash
docker build -f mateclaw-server/src/main/resources/dsh/container/Dockerfile \
  --build-arg DSH_VERSION=0.2.2-rc.1 \
  --build-arg DSH_LOCK_SHA256=ee63227735abbbbceb9c2f5442bdbdc286b8fdd9d82b15396f3e6f749ffbffe5 \
  -t mateclaw-dsh-local:0.2.2-rc.1 mateclaw-server/src/main/resources/dsh
docker image inspect --format '{{.Id}}' mateclaw-dsh-local:0.2.2-rc.1
```

The Dockerfile pins Node 24.22.2 by digest. Review the base, lock, native platform packages and resulting image before use. Configure the returned full `sha256:…` **image ID**, not a tag, then restart the backend:

```bash
export MATECLAW_DSH_CONTAINMENT_MODE=docker
export MATECLAW_DSH_DOCKER_EXECUTABLE=docker
export MATECLAW_DSH_DOCKER_IMAGES_JSON='{"0.2.2-rc.1":"sha256:<64 hexadecimal characters from image inspect>"}'
```

Provide a separate reviewed image for each SDK catalog version that must run, including candidate checks and rollback. The selected host managed executable identifies the trusted SDK version; the container runs its own matching SDK. Image version/lock labels must match. Runtime never pulls an image or upgrades the active generation. `/status` exposes the configured containment mode; it is not a compatibility certificate.

Requirements: a trusted local Docker daemon reachable through a Unix socket, a matching Linux image architecture, and a host UID/GID supported by Java's Unix identity provider. Remote Docker endpoints and Windows hosts are rejected. The selected daemon endpoint stays fixed through cleanup. The container drops all capabilities, uses `no-new-privileges`, a read-only image filesystem and a bounded PID count. Its independent `/tmp` is executable because the SDK native loader copies dynamic libraries there. Only the explicit workspace, scoped employee home and read-only administrator patch files are mounted. Docker access is privileged host access; credentials passed as environment values are visible to Docker administrators. There is no Docker socket, host PID namespace or privileged mode inside the container.

**Platform limitation:** on the tested macOS arm64 Docker Desktop setup, SDK model calls work, but its native Landlock runner denies reads from the host shared workspace (`EACCES`). Do not use this mode for workspace tasks on that setup. No sandbox disablement or automatic escalation is applied. Linux native bind mounts and other Desktop file-sharing backends require their own read/write/tool acceptance checks; they are not certified by a successful handshake. Process cleanup probes use container `/tmp`, separately from host-workspace compatibility.

JVM termination and daemon unavailability remain explicit recovery boundaries. Containers carry `vip.mateclaw.dsh.owner=<random UUID>` and `vip.mateclaw.dsh.jvm=<creator PID>` labels. After confirming the recorded JVM has died, inspect and remove only its identified containers on the original daemon; then perform the stale-lease recovery above. A daemon outage cannot confirm cleanup. Do not remove leases or replay uncertain tool effects merely to admit work. Existing member-isolation rejection, application permissions, Goal rules, bounded text replay and output budgets remain unchanged. Docker process supervision does not add a host approval bridge or certify a complete security sandbox.

### Native shared-workspace investigation (2026-10-21)

The reproduced Desktop bind mount is `fakeowner` (filesystem magic `0x6a656a63`), rather than a native Linux filesystem. The exact SDK Landlock launcher rejects reads even with explicit directory/file grants; unrestricted reads succeed. A disposable Docker ext4 volume passes the same sandbox read/write probe and rejects both direct outside writes and symlink escapes. This identifies the filesystem boundary; it does not certify a Desktop configuration fix.

The selected next step is Docker Desktop's [Synchronized file shares](https://docs.docker.com/desktop/features/synchronized-file-sharing/), which uses a native ext4 cache and bidirectional synchronization. It requires signing in with Pro, Team or Business entitlement. In Settings → Resources → File sharing, create a share for a disposable test directory first and wait for its Watching status. Do not set bind consistency to `consistent`, which bypasses synchronized shares. Validate actual sandbox behavior and host-visible contents before using a production workspace; do not infer Landlock compatibility from filesystem type or handshake alone. Current local validation is waiting for Docker Desktop login/entitlement; synchronized sharing has not yet been certified.

The opt-in `DshDockerSharedWorkspaceAcceptanceTest` uses `MATECLAW_DSH_ACCEPTANCE_DOCKER_IMAGE` plus `MATECLAW_DSH_ACCEPTANCE_SHARED_WORKSPACE` (an existing test-share directory). It creates its own temporary subdirectory, checks host→sandbox→host data across two writes, and requires `EACCES` for outside and symlink writes. The independent ext4 volume control runs with only the image variable. Disposable owned containers, volumes and directories are cleaned. No workspace copyback implementation, sandbox bypass, automatic escalation or runtime dependency upgrade has been introduced.
