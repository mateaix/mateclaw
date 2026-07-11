const assert = require('node:assert/strict')
const fs = require('node:fs')
const os = require('node:os')
const path = require('node:path')
const test = require('node:test')

const {
  createZipBuffer,
  readZipEntries,
  resolveDriverDirectory,
  trimDriverBundleInAppJar,
  ZIP_STORED,
  default: afterPack,
} = require('../trim-playwright-driver.cjs')

test('resolveDriverDirectory maps electron platform and arch to Playwright driver folder', () => {
  assert.equal(resolveDriverDirectory('darwin', 'x64'), 'driver/mac')
  assert.equal(resolveDriverDirectory('darwin', 'arm64'), 'driver/mac-arm64')
  assert.equal(resolveDriverDirectory('linux', 'x64'), 'driver/linux')
  assert.equal(resolveDriverDirectory('linux', 'arm64'), 'driver/linux-arm64')
  assert.equal(resolveDriverDirectory('win32', 'x64'), 'driver/win32_x64')
  assert.equal(resolveDriverDirectory('win32', 'arm64'), 'driver/win32_x64')
  assert.deepEqual(resolveDriverDirectory('darwin', 'universal'), ['driver/mac', 'driver/mac-arm64'])
})

test('trimDriverBundleInAppJar keeps only the target driver and preserves nested jar as STORED', async () => {
  const tmp = fs.mkdtempSync(path.join(os.tmpdir(), 'mateclaw-driver-trim-'))
  const appJarPath = path.join(tmp, 'app.jar')

  const driverBundle = createZipBuffer([
    entry('META-INF/MANIFEST.MF', 'Manifest-Version: 1.0\n'),
    entry('driver/mac/node', 'mac'),
    entry('driver/mac-arm64/node', 'mac-arm64'),
    entry('driver/linux/node', 'linux'),
    entry('driver/linux-arm64/node', 'linux-arm64'),
    entry('driver/win32_x64/node.exe', 'win32'),
    entry('com/microsoft/playwright/Driver.class', 'class'),
  ])

  const appJar = createZipBuffer([
    entry('BOOT-INF/classpath.idx', '- "BOOT-INF/lib/driver-bundle-1.52.0.jar"\n'),
    entry('BOOT-INF/lib/driver-bundle-1.52.0.jar', driverBundle, ZIP_STORED),
    entry('BOOT-INF/lib/other.jar', 'other', ZIP_STORED),
  ])

  fs.writeFileSync(appJarPath, appJar)

  const result = await trimDriverBundleInAppJar(appJarPath, 'driver/mac-arm64')
  assert.equal(result.removedDriverEntries, 4)

  const outerEntries = readZipEntries(fs.readFileSync(appJarPath))
  const nestedEntry = outerEntries.find((item) => item.name === 'BOOT-INF/lib/driver-bundle-1.52.0.jar')
  assert.equal(nestedEntry.method, ZIP_STORED)

  const innerEntries = readZipEntries(nestedEntry.data)
  const names = innerEntries.map((item) => item.name).sort()

  assert.deepEqual(names, [
    'META-INF/MANIFEST.MF',
    'com/microsoft/playwright/Driver.class',
    'driver/mac-arm64/node',
  ])
})

test('trimDriverBundleInAppJar throws when driver-bundle is missing', async () => {
  const tmp = fs.mkdtempSync(path.join(os.tmpdir(), 'mateclaw-driver-trim-'))
  const appJarPath = path.join(tmp, 'app.jar')

  fs.writeFileSync(appJarPath, createZipBuffer([
    entry('BOOT-INF/lib/other.jar', 'other', ZIP_STORED),
  ]))

  await assert.rejects(
    () => trimDriverBundleInAppJar(appJarPath, 'driver/mac-arm64'),
    /Playwright driver-bundle jar not found/
  )
})

test('trimDriverBundleInAppJar throws when no target driver entries are kept', async () => {
  const tmp = fs.mkdtempSync(path.join(os.tmpdir(), 'mateclaw-driver-trim-'))
  const appJarPath = path.join(tmp, 'app.jar')
  fs.writeFileSync(appJarPath, createAppJarWithDriverBundle())

  await assert.rejects(
    () => trimDriverBundleInAppJar(appJarPath, 'driver/mac-arm64-v2'),
    /No entries kept under driver\/mac-arm64-v2/
  )
})

test('trimDriverBundleInAppJar can keep both mac drivers for universal builds', async () => {
  const tmp = fs.mkdtempSync(path.join(os.tmpdir(), 'mateclaw-driver-trim-'))
  const appJarPath = path.join(tmp, 'app.jar')
  fs.writeFileSync(appJarPath, createAppJarWithDriverBundle())

  const result = await trimDriverBundleInAppJar(appJarPath, ['driver/mac', 'driver/mac-arm64'])
  assert.equal(result.keptDriverEntries, 2)

  const outerEntries = readZipEntries(fs.readFileSync(appJarPath))
  const nestedEntry = outerEntries.find((item) => item.name === 'BOOT-INF/lib/driver-bundle-1.52.0.jar')
  const driverNames = readZipEntries(nestedEntry.data)
    .filter((item) => item.name.startsWith('driver/'))
    .map((item) => item.name)
    .sort()

  assert.deepEqual(driverNames, [
    'driver/mac-arm64/node',
    'driver/mac/node',
  ])
})

test('afterPack throws when app.jar is missing', async () => {
  const tmp = fs.mkdtempSync(path.join(os.tmpdir(), 'mateclaw-driver-trim-'))

  await assert.rejects(
    () => afterPack({
      appOutDir: tmp,
      arch: 'arm64',
      electronPlatformName: 'darwin',
      packager: {
        appInfo: { productFilename: 'MateClaw' },
      },
    }),
    /app\.jar not found/
  )
})

function entry(name, data, method) {
  return {
    name,
    data: Buffer.isBuffer(data) ? data : Buffer.from(data),
    method,
  }
}

function createAppJarWithDriverBundle() {
  const driverBundle = createZipBuffer([
    entry('META-INF/MANIFEST.MF', 'Manifest-Version: 1.0\n'),
    entry('driver/mac/node', 'mac'),
    entry('driver/mac-arm64/node', 'mac-arm64'),
    entry('driver/linux/node', 'linux'),
    entry('driver/linux-arm64/node', 'linux-arm64'),
    entry('driver/win32_x64/node.exe', 'win32'),
    entry('com/microsoft/playwright/Driver.class', 'class'),
  ])

  return createZipBuffer([
    entry('BOOT-INF/classpath.idx', '- "BOOT-INF/lib/driver-bundle-1.52.0.jar"\n'),
    entry('BOOT-INF/lib/driver-bundle-1.52.0.jar', driverBundle, ZIP_STORED),
    entry('BOOT-INF/lib/other.jar', 'other', ZIP_STORED),
  ])
}
