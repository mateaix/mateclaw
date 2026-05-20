---
name: node-inspect-debugger
description: Debug Node.js via --inspect + Chrome DevTools Protocol CLI.
version: 1.1.0
tags:
- debugging
- nodejs
- node-inspect
- cdp
- breakpoints
- electron
- vite
author: ported
---
# Node.js Inspect Debugger

## Overview

When `console.log` isn't enough, drive Node's built-in V8 inspector programmatically from the terminal. You get real breakpoints, step in/over/out, call-stack walking, local/closure scope dumps, and arbitrary expression evaluation in the paused frame.

Two tools, pick one:

- **`node inspect`** — built-in, zero install, CLI REPL. Best for quick poking.
- **`ndb` / CDP via `chrome-remote-interface`** — scriptable from Node/Python; best when you want to automate many breakpoints, collect state across runs, or debug non-interactively from an agent loop.

**Prefer `node inspect` first.** It's always available and the REPL is fast.

In this repo the Node.js surfaces are the front-end packages — `mateclaw-ui`, `mateclaw-webchat`, and the Electron desktop app `mateclaw-desktop`. The Spring Boot backend is a JVM process and is not a target for this skill.

## When to Use

- A Node-based build or packaging step (a Vite build, an `electron-builder` hook, a `scripts/` helper) fails and you need to see intermediate state
- The Electron desktop **main process** (`mateclaw-desktop`) crashes, hangs on startup, or mishandles the bundled Java backend child process
- A Vite dev server or a build plugin behaves wrong and `console.log` can't reach the value
- You need to inspect a value in a closure that `console.log` can't reach without patching
- Perf: attach to a running process to capture a CPU profile or heap snapshot

**Don't use for:** things `console.log` solves in under a minute. Breakpoint-driven debugging is heavier; use it when the payoff is real. The Electron **renderer** is a Chromium page, not a Node target — debug it with the window's built-in DevTools, not `node inspect`.

## Quick Reference: `node inspect` REPL

Launch paused on first line:

```bash
node inspect path/to/script.js
# or with tsx
node --inspect-brk $(which tsx) path/to/script.ts
```

The `debug>` prompt accepts:

| Command | Action |
|---|---|
| `c` or `cont` | continue |
| `n` or `next` | step over |
| `s` or `step` | step into |
| `o` or `out` | step out |
| `pause` | pause running code |
| `sb('file.js', 42)` | set breakpoint at file.js line 42 |
| `sb(42)` | set breakpoint at line 42 of current file |
| `sb('functionName')` | break when function is called |
| `cb('file.js', 42)` | clear breakpoint |
| `breakpoints` | list all breakpoints |
| `bt` | backtrace (call stack) |
| `list(5)` | show 5 lines of source around current position |
| `watch('expr')` | evaluate expr on every pause |
| `watchers` | show watched expressions |
| `repl` | drop into REPL in current scope (Ctrl+C to exit REPL) |
| `exec expr` | evaluate expression once |
| `restart` | restart script |
| `kill` | kill the script |
| `.exit` | quit debugger |

**In the `repl` sub-mode:** type any JS expression, including access to locals/closure variables. `Ctrl+C` exits back to `debug>`.

## Attaching to a Running Process

When the process is already running (e.g. a Vite dev server, or the Electron main process):

```bash
# 1. Send SIGUSR1 to enable the inspector on an existing process
kill -SIGUSR1 <pid>
# Node prints: Debugger listening on ws://127.0.0.1:9229/<uuid>

# 2. Attach the debugger CLI
node inspect -p <pid>
# or by URL
node inspect ws://127.0.0.1:9229/<uuid>
```

To start a process with the inspector from the beginning:

```bash
node --inspect script.js           # listen on 127.0.0.1:9229, keep running
node --inspect-brk script.js       # listen AND pause on first line
node --inspect=0.0.0.0:9230 script.js   # custom host:port
```

For TypeScript via tsx:

```bash
node --inspect-brk --import tsx script.ts
# or older tsx
node --inspect-brk -r tsx/cjs script.ts
```

## Programmatic CDP (scripting from terminal)

When you want to automate — set many breakpoints, capture scope state, script a repro — use `chrome-remote-interface`:

```bash
npm i -g chrome-remote-interface        # or project-local
# Start your target:
node --inspect-brk=9229 target.js &
```

Driver script (save as `/tmp/cdp-debug.js`):

```javascript
const CDP = require('chrome-remote-interface');

(async () => {
  const client = await CDP({ port: 9229 });
  const { Debugger, Runtime } = client;

  Debugger.paused(async ({ callFrames, reason }) => {
    const top = callFrames[0];
    console.log(`PAUSED: ${reason} @ ${top.url}:${top.location.lineNumber + 1}`);

    // Walk scopes for locals
    for (const scope of top.scopeChain) {
      if (scope.type === 'local' || scope.type === 'closure') {
        const { result } = await Runtime.getProperties({
          objectId: scope.object.objectId,
          ownProperties: true,
        });
        for (const p of result) {
          console.log(`  ${scope.type}.${p.name} =`, p.value?.value ?? p.value?.description);
        }
      }
    }

    // Evaluate an expression in the paused frame
    const { result } = await Debugger.evaluateOnCallFrame({
      callFrameId: top.callFrameId,
      expression: 'typeof state !== "undefined" ? JSON.stringify(state) : "n/a"',
    });
    console.log('state =', result.value ?? result.description);

    await Debugger.resume();
  });

  await Runtime.enable();
  await Debugger.enable();

  // Set a breakpoint by URL regex + line
  await Debugger.setBreakpointByUrl({
    urlRegex: '.*dist-electron/main/index\\.js$',
    lineNumber: 119,       // 0-indexed
    columnNumber: 0,
  });

  await Runtime.runIfWaitingForDebugger();
})();
```

Run it:

```bash
node /tmp/cdp-debug.js
```

`chrome-remote-interface` is not a dependency of any package in this repo. Install it to a throwaway location so you don't dirty a project's `package.json`:

```bash
mkdir -p /tmp/cdp-tools && cd /tmp/cdp-tools && npm i chrome-remote-interface
NODE_PATH=/tmp/cdp-tools/node_modules node /tmp/cdp-debug.js
```

## Debugging the Electron Desktop App

`mateclaw-desktop` is an Electron app. The **main process** is a Node process — `electron/main/index.ts`, compiled by Vite to `dist-electron/main/index.js` (the `main` field in `package.json`). It spawns the Java backend as a child process. The **renderer** is a Chromium `BrowserWindow` — debug that with the window's DevTools, not this skill.

### Launch the main process paused

Electron forwards `--inspect` / `--inspect-brk` to its main process. Build the Electron output first so there is a `dist-electron/` to run:

```bash
cd mateclaw-desktop
npm run build                          # produces dist/ and dist-electron/
npx electron --inspect-brk=9229 .      # Electron starts, paused on the main process first line
# In another terminal:
node inspect ws://127.0.0.1:9229/<uuid>
```

Then inside `debug>`:

```
sb('dist-electron/main/index.js', 220)   # e.g. the suspect line in window/backend setup
cont
```

When it pauses, `repl` → inspect `mainWindow`, `javaProcess`, `BACKEND_PORT`, the updater state, etc.

### Attach to an already-running desktop app

The Electron main process is the one launched without a `--type=` flag (renderer/GPU/utility processes carry `--type=`):

```bash
# Find the main process PID (the entry without --type=)
ps aux | grep -i 'mateclaw-desktop' | grep -v -- '--type='

# Enable the inspector on it
kill -SIGUSR1 <main-pid>

# Find the WS URL and attach
curl -s http://127.0.0.1:9229/json/list | jq -r '.[0].webSocketDebuggerUrl'
node inspect ws://127.0.0.1:9229/<uuid>
```

The Java backend that the main process spawns is a JVM, not a Node target — it will not appear in `/json/list`. To debug that, use the JVM's own remote-debug flags, not this skill.

## Debugging a Vite Dev Server

`mateclaw-ui`, `mateclaw-webchat`, and `mateclaw-desktop` all run `vite` for `dev`. To step through Vite config or a build plugin, run Vite's binary under the inspector instead of the `pnpm dev` wrapper:

```bash
cd mateclaw-ui
node --inspect-brk ./node_modules/vite/bin/vite.js
# In another terminal: node inspect -p <pid>, then sb('vite.config.ts', N), cont
```

This pauses inside the Node process that loads `vite.config.ts` and runs plugin hooks. The browser-side Vue code it serves is not reachable here — that runs in the browser and is debugged with browser DevTools.

## Heap Snapshots & CPU Profiles (Non-interactive)

From the CDP driver above, swap Debugger for `HeapProfiler` / `Profiler`:

```javascript
// CPU profile for 5 seconds
await client.Profiler.enable();
await client.Profiler.start();
await new Promise(r => setTimeout(r, 5000));
const { profile } = await client.Profiler.stop();
require('fs').writeFileSync('/tmp/cpu.cpuprofile', JSON.stringify(profile));
// Open /tmp/cpu.cpuprofile in Chrome DevTools → Performance tab
```

```javascript
// Heap snapshot
await client.HeapProfiler.enable();
const chunks = [];
client.HeapProfiler.addHeapSnapshotChunk(({ chunk }) => chunks.push(chunk));
await client.HeapProfiler.takeHeapSnapshot({ reportProgress: false });
require('fs').writeFileSync('/tmp/heap.heapsnapshot', chunks.join(''));
```

## Common Pitfalls

1. **Wrong line numbers in TS source.** Breakpoints hit the emitted JS, not the `.ts`. Either (a) break in the built file (`dist-electron/main/index.js`), or (b) enable sourcemaps (`node --enable-source-maps`) and use `sb('electron/main/index.ts', N)` — but only with CDP clients that follow sourcemaps. The `node inspect` CLI does not.

2. **`--inspect` vs `--inspect-brk`.** `--inspect` starts the inspector but doesn't pause; your script races past your first breakpoint if you attach too late. Use `--inspect-brk` when you need to set breakpoints before any code runs.

3. **Port collisions.** Default is `9229`. If multiple Node processes are inspecting, pass `--inspect=0` (random port) and read the actual URL from `/json/list`:
   ```bash
   curl -s http://127.0.0.1:9229/json/list   # lists all inspectable targets on the host
   ```

4. **Child processes.** `--inspect` on a parent does NOT inspect its children. Electron itself is multi-process, and the desktop main process additionally spawns the Java backend. Use `NODE_OPTIONS='--inspect-brk' node parent.js` to propagate to every Node child; be aware they all need unique ports (Node auto-increments when `NODE_OPTIONS='--inspect'` is inherited).

5. **Background kills.** If you `Ctrl+C` out of `node inspect` while the target is paused, the target stays paused. Either `cont` first, or `kill` the target explicitly.

6. **Running `node inspect` through the agent's shell tool.** The `execute_shell_command` tool is one-shot and non-interactive — it cannot drive the interactive `debug>` REPL. For interactive stepping, run `node inspect` in a real terminal yourself. For agent-driven debugging, prefer the scripted CDP driver above: it is fully non-interactive and runs fine as a single `execute_shell_command` call.

7. **Security.** `--inspect=0.0.0.0:9229` exposes arbitrary code execution. Always bind to `127.0.0.1` (the default) unless you have an isolated network.

## Verification Checklist

After setting up a debug session, verify:

- [ ] `curl -s http://127.0.0.1:9229/json/list` returns exactly the target you expect
- [ ] First breakpoint actually hits (if it doesn't, you likely missed `--inspect-brk` or attached after execution completed)
- [ ] Source listing at pause shows the right file (mismatch = sourcemap issue, see pitfall 1)
- [ ] `exec process.pid` in `repl` returns the PID you meant to attach to

## One-Shot Recipes

**"Why is this variable undefined at line X?"**
```bash
node --inspect-brk script.js &
node inspect -p $!
# debug>
sb('script.js', X)
cont
# paused. Now:
repl
> myVariable
> Object.keys(this)
```

**"What's the call path into this function?"**
```
debug> sb('suspectFn')
debug> cont
# paused on entry
debug> bt
```

**"This async chain hangs — where?"**
```
# Start with --inspect (no -brk), let it run to the hang, then:
debug> pause
debug> bt
# Now you see the stuck frame
```
