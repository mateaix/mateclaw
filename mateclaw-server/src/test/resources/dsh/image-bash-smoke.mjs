import assert from 'node:assert/strict';
import { createRequire } from 'node:module';
import { pathToFileURL } from 'node:url';
import { mkdirSync, existsSync, readFileSync } from 'node:fs';
const require = createRequire(`${process.env.DSH_PREFIX}/package.json`);
const load = async name => import(pathToFileURL(require.resolve(`@deepseek-ai/${name}`)).href);
const { Context } = await load('cordis');
const ctx = new Context();
const version = require('@deepseek-ai/dsh/package.json').version;
const batchExecutor = ['0.2.2-rc.5', '0.2.3-alpha.2'].includes(version);
mkdirSync('/tmp/dsh-smoke-workspace', { recursive: true });
for (const [name, config] of [
  ['dsh-system-prompt', {}], ['dsh-session-projection', {}],
  ['dsh-fs-local', {}], ['dsh-working-directory', { defaultDirectory: '/tmp/dsh-smoke-workspace' }],
  ['dsh-subprocess-local', {}], ['dsh-sandbox-local', {}],
  ['dsh-sandbox-policy', { mode: 'workspace-write', workspaceRoot: '/tmp/dsh-smoke-workspace' }],
  ['dsh-bash-sandbox', {}], ['dsh-shell-env', {}], ['dsh-tools', {}],
  ['dsh-tool-bash', { enableRunInBackground: false }],
]) {
  // This service was introduced after the server's default 0.2.0-rc.1 release.
  if (name === 'dsh-working-directory' && version === '0.2.0-rc.1') continue;
  const plugin = await load(name);
  ctx.plugin(plugin.default ?? plugin, config);
}
if (batchExecutor) {
  const executor = await load('dsh-tool-executor-native');
  ctx.plugin(executor.default ?? executor, {});
}
for (let i=0; i<100 && !ctx.tools?.get('bash'); i++) await new Promise(resolve => setTimeout(resolve, 20));
const execute = async input => {
  if (!batchExecutor) return ctx.tools.execute(input);
  const batch = await ctx.tools.execute({
    calls: [{ type: 'tool-call', id: input.callId, name: input.name, arguments: JSON.stringify(input.arguments) }],
    signal: input.signal,
  });
  assert.equal(batch.results.length, 1);
  return batch.results[0].result;
};
try {
  assert.ok(ctx.tools?.get('bash'), 'Bash tool must register');
  const confined = await ctx.sandbox.confine(['bash', '-c', 'true'], {
    mode: 'workspace-write', workspaceRoot: '/tmp/dsh-smoke-workspace',
  });
  console.log(`DSH_SANDBOX_BACKEND ${confined.argv[0]}`);
  if (process.env.DSH_EXPECT_BWRAP === 'true') {
    assert.equal(confined.argv[0], 'bwrap', 'privileged smoke must use bwrap');
    console.log('DSH_BWRAP_BACKEND_OK');
  }
  const result = await execute({callId:'smoke', name:'bash', arguments:{
    description:'Write and read sandbox smoke marker',
    command:"printf DSH_BASH_OK > marker; cat marker", workdir:'/tmp/dsh-smoke-workspace',
  }, signal:AbortSignal.timeout(30000)});
  console.log(JSON.stringify(result));
  assert.ok(!result.isError, 'Bash tool must succeed');
  assert.equal(result.value.exitCode, 0);
  assert.equal(result.value.sandbox.enforcement, 'full');
  assert.equal(result.value.sandbox.mode, 'workspace-write');
  assert.equal(readFileSync('/tmp/dsh-smoke-workspace/marker','utf8'), 'DSH_BASH_OK');
  const denied = await execute({callId:'denied',name:'bash',arguments:{
    description:'Check sandbox blocks writes outside workspace',
    command:'printf forbidden > /etc/dsh-smoke-forbidden',workdir:'/tmp/dsh-smoke-workspace',
  },signal:AbortSignal.timeout(30000)});
  console.log(JSON.stringify(denied));
  assert.equal(denied.value.sandbox.denied, true);
  assert.notEqual(denied.value.exitCode, 0);
  assert.equal(existsSync('/etc/dsh-smoke-forbidden'),false, 'sandbox must block outside write');
  console.log('DSH_BASH_SANDBOX_OK');
} finally { await ctx.fiber.dispose(); }
