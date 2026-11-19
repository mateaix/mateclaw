const fs = require('node:fs')
const os = require('node:os')
const path = require('node:path')
const { execFileSync } = require('node:child_process')
const { readZipEntries, createZipBuffer, ZIP_STORED } = require('./trim-playwright-driver.cjs')

function isMachO(data) {
  if (data.length < 8) return false
  const magic = data.readUInt32BE(0)
  if ([0xfeedface, 0xcefaedfe, 0xfeedfacf, 0xcffaedfe].includes(magic)) return true
  // Java class files share CAFEBABE with fat Mach-O, but contain a class version here.
  if ([0xcafebabe, 0xcafebabf].includes(magic)) return data.readUInt32BE(4) > 0 && data.readUInt32BE(4) < 32
  if ([0xbebafeca, 0xbfbafeca].includes(magic)) return data.readUInt32LE(4) > 0 && data.readUInt32LE(4) < 32
  return false
}

async function rewriteArchive(buffer, signBinary, label = 'app.jar') {
  let count = 0
  const entries = readZipEntries(buffer)
  for (const entry of entries) {
    if (isMachO(entry.data)) {
      entry.data = await signBinary(entry.data, `${label}/${entry.name}`)
      count++
    } else if (entry.name.endsWith('.jar')) {
      const nested = await rewriteArchive(entry.data, signBinary, `${label}/${entry.name}`)
      if (nested.count) {
        entry.data = nested.buffer
        count += nested.count
        // Spring Boot requires nested dependencies to be stored without compression.
        if (entry.name.startsWith('BOOT-INF/lib/')) entry.method = ZIP_STORED
      }
    }
  }
  if (!count) return { buffer, count }
  // Upstream JAR signatures become invalid when their native resources change.
  const unsigned = entries.filter(entry => !/^META-INF\/[^/]+\.(SF|RSA|DSA|EC)$/i.test(entry.name))
  return { buffer: createZipBuffer(unsigned), count }
}

async function signAppJar(appJar, options) {
  const temp = fs.mkdtempSync(path.join(os.tmpdir(), 'mateclaw-jar-sign-'))
  const output = `${appJar}.signed-tmp`
  try {
    const result = await rewriteArchive(fs.readFileSync(appJar), async (data, label) => {
      // Never extract archive-controlled paths onto the filesystem.
      const binary = path.join(temp, 'native')
      fs.writeFileSync(binary, data, { mode: 0o755 })
      const args = ['--force', '--sign', options.identity, '--timestamp', '--options', 'runtime']
      if (options.keychain) args.push('--keychain', options.keychain)
      if (options.entitlements) args.push('--entitlements', options.entitlements)
      args.push(binary)
      console.log(`[sign-jar-natives] ${label}`)
      execFileSync('/usr/bin/codesign', args, { stdio: 'pipe' })
      execFileSync('/usr/bin/codesign', ['--verify', '--strict', binary], { stdio: 'pipe' })
      return fs.readFileSync(binary)
    })
    if (result.count) {
      fs.writeFileSync(output, result.buffer)
      fs.renameSync(output, appJar)
    }
    return result.count
  } finally {
    fs.rmSync(temp, { recursive: true, force: true })
    fs.rmSync(output, { force: true })
  }
}

module.exports = { isMachO, rewriteArchive, signAppJar }
