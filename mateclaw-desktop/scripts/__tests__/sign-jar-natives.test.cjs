const assert = require('node:assert/strict')
const test = require('node:test')
const { isMachO, rewriteArchive } = require('../sign-jar-natives.cjs')
const { createZipBuffer, readZipEntries, ZIP_STORED, ZIP_DEFLATED } = require('../trim-playwright-driver.cjs')

const native = Buffer.from('cffaedfe00000001', 'hex')
const entry = (name, data, method = ZIP_DEFLATED) => ({ name, data: Buffer.from(data), method })

test('detects thin and universal Mach-O without mistaking Java classes for binaries', () => {
  for (const magic of ['feedface', 'cefaedfe', 'feedfacf', 'cffaedfe']) {
    assert.equal(isMachO(Buffer.from(`${magic}00000001`, 'hex')), true)
  }
  assert.equal(isMachO(Buffer.from('cafebabe00000002', 'hex')), true)
  assert.equal(isMachO(Buffer.from('bebafeca02000000', 'hex')), true)
  assert.equal(isMachO(Buffer.from('cafebabe00000041', 'hex')), false)
  assert.equal(isMachO(Buffer.from('MZ')), false)
})

test('signs all architectures and extensionless executables, preserves Spring Boot storage and removes stale JAR signatures', async () => {
  const dependency = createZipBuffer([
    entry('META-INF/MANIFEST.MF', 'Manifest-Version: 1.0\n'),
    entry('META-INF/VENDOR.SF', 'old digest'),
    entry('META-INF/VENDOR.RSA', 'old signature'),
    entry('mac/arm64/lib.dylib', native),
    entry('mac/x64/lib.jnilib', native),
    entry('driver/mac/node', native),
    entry('Example.class', Buffer.from('cafebabe00000041', 'hex')),
    entry('linux/lib.so', 'ELF'),
  ])
  const untouched = createZipBuffer([entry('hello.txt', 'unchanged')])
  const app = createZipBuffer([
    entry('BOOT-INF/lib/native.jar', dependency, ZIP_STORED),
    entry('BOOT-INF/lib/other.jar', untouched, ZIP_STORED),
  ])
  const signed = []
  const result = await rewriteArchive(app, async (data, name) => {
    signed.push(name)
    return Buffer.concat([data, Buffer.from('signed')])
  })
  assert.equal(result.count, 3)
  assert.equal(signed.length, 3)
  const outer = readZipEntries(result.buffer)
  assert.equal(outer[0].method, ZIP_STORED)
  assert.deepEqual(outer[1].data, untouched)
  const inner = readZipEntries(outer[0].data)
  assert.equal(inner.some(e => /\.(SF|RSA)$/.test(e.name)), false)
  assert.equal(inner.find(e => e.name === 'linux/lib.so').data.toString(), 'ELF')
  assert.equal(inner.find(e => e.name === 'mac/x64/lib.jnilib').data.subarray(-6).toString(), 'signed')
})

test('signing errors propagate without producing an archive', async () => {
  await assert.rejects(rewriteArchive(createZipBuffer([entry('lib.dylib', native)]), async () => {
    throw new Error('timestamp unavailable')
  }), /timestamp unavailable/)
})

test('remote builds skip local JAR processing', async () => {
  const previous = process.env.BUILD_MODE
  process.env.BUILD_MODE = 'remote'
  try {
    await require('../after-pack.cjs')({})
  } finally {
    if (previous === undefined) delete process.env.BUILD_MODE
    else process.env.BUILD_MODE = previous
  }
})
