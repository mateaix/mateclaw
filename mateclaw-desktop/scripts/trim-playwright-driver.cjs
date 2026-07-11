const fs = require('node:fs')
const path = require('node:path')
const zlib = require('node:zlib')

const ZIP_STORED = 0
const ZIP_DEFLATED = 8
const DRIVER_BUNDLE_PATTERN = /^BOOT-INF\/lib\/driver-bundle-[^/]+\.jar$/

const ARCH_X64 = 1
const ARCH_ARM64 = 3

async function afterPack(context) {
  const platform = context.electronPlatformName || context.packager?.platform?.name
  const arch = normalizeArch(context.arch)
  const keepDriverDirectory = resolveDriverDirectory(platform, arch)
  const appJarPath = findAppJar(context)

  if (!appJarPath) {
    throw new Error(`[trim-playwright-driver] app.jar not found in ${context.appOutDir}`)
  }

  const result = await trimDriverBundleInAppJar(appJarPath, keepDriverDirectory)
  const keptLabel = asArray(keepDriverDirectory).join(', ')
  console.log(
    `[trim-playwright-driver] ${path.relative(process.cwd(), appJarPath)}: ` +
      `kept ${keptLabel}, removed ${result.removedDriverEntries} driver entries, ` +
      `${formatBytes(result.beforeBytes)} -> ${formatBytes(result.afterBytes)}`
  )
}

function findAppJar(context) {
  const appOutDir = context.appOutDir
  const productFilename = context.packager?.appInfo?.productFilename || context.packager?.appInfo?.productName || 'MateClaw'
  const platform = context.electronPlatformName || context.packager?.platform?.name

  const candidates = []
  if (platform === 'darwin') {
    candidates.push(path.join(appOutDir, `${productFilename}.app`, 'Contents', 'Resources', 'app.jar'))
  }
  candidates.push(path.join(appOutDir, 'resources', 'app.jar'))

  for (const candidate of candidates) {
    if (fs.existsSync(candidate)) return candidate
  }

  const found = findFirstFile(appOutDir, 'app.jar', 4)
  return found
}

function findFirstFile(root, fileName, maxDepth, depth = 0) {
  if (!root || depth > maxDepth || !fs.existsSync(root)) return null
  for (const dirent of fs.readdirSync(root, { withFileTypes: true })) {
    const fullPath = path.join(root, dirent.name)
    if (dirent.isFile() && dirent.name === fileName) return fullPath
    if (dirent.isDirectory()) {
      const found = findFirstFile(fullPath, fileName, maxDepth, depth + 1)
      if (found) return found
    }
  }
  return null
}

async function trimDriverBundleInAppJar(appJarPath, keepDriverDirectory) {
  const appJarBuffer = fs.readFileSync(appJarPath)
  const outerEntries = readZipEntries(appJarBuffer)
  const driverBundleEntry = outerEntries.find((entry) => DRIVER_BUNDLE_PATTERN.test(entry.name))
  const keepDriverDirectories = asArray(keepDriverDirectory)

  if (!driverBundleEntry) {
    throw new Error(`Playwright driver-bundle jar not found in ${appJarPath}`)
  }

  const innerEntries = readZipEntries(driverBundleEntry.data)
  let removedDriverEntries = 0
  let keptDriverEntries = 0

  const trimmedInnerEntries = innerEntries.filter((entry) => {
    if (!entry.name.startsWith('driver/')) return true
    if (keepDriverDirectories.some((directory) => entry.name.startsWith(`${directory}/`))) {
      keptDriverEntries += 1
      return true
    }
    removedDriverEntries += 1
    return false
  })

  if (keptDriverEntries === 0) {
    throw new Error(
      `No entries kept under ${keepDriverDirectories.join(', ')}; Playwright driver layout may have changed`
    )
  }

  const trimmedDriverBundle = createZipBuffer(trimmedInnerEntries.map(cloneEntryForWrite))
  const rewrittenOuterEntries = outerEntries.map((entry) => {
    if (entry.name !== driverBundleEntry.name) return cloneEntryForWrite(entry)
    return {
      ...cloneEntryForWrite(entry),
      data: trimmedDriverBundle,
      method: ZIP_STORED,
    }
  })

  const rewrittenAppJar = createZipBuffer(rewrittenOuterEntries)
  fs.writeFileSync(appJarPath, rewrittenAppJar)

  return {
    beforeBytes: appJarBuffer.length,
    afterBytes: rewrittenAppJar.length,
    driverBundleName: driverBundleEntry.name,
    keptDriverEntries,
    removedDriverEntries,
  }
}

function cloneEntryForWrite(entry) {
  return {
    name: entry.name,
    data: Buffer.from(entry.data),
    method: entry.method,
    date: entry.date,
    comment: entry.comment,
    externalAttributes: entry.externalAttributes,
  }
}

function resolveDriverDirectory(platform, arch) {
  const normalizedArch = normalizeArch(arch)

  if (platform === 'darwin') {
    if (normalizedArch === 'arm64') return 'driver/mac-arm64'
    if (normalizedArch === 'x64') return 'driver/mac'
    if (normalizedArch === 'universal') return ['driver/mac', 'driver/mac-arm64']
  }

  if (platform === 'linux') {
    if (normalizedArch === 'arm64') return 'driver/linux-arm64'
    if (normalizedArch === 'x64') return 'driver/linux'
  }

  if (platform === 'win32') {
    return 'driver/win32_x64'
  }

  throw new Error(`Unsupported platform/arch for Playwright driver trim: ${platform}/${arch}`)
}

function asArray(value) {
  return Array.isArray(value) ? value : [value]
}

function normalizeArch(arch) {
  if (arch === ARCH_X64 || arch === 'x64') return 'x64'
  if (arch === ARCH_ARM64 || arch === 'arm64') return 'arm64'
  if (arch === 'universal' || arch === 4) return 'universal'
  return String(arch)
}

function readZipEntries(buffer) {
  const eocdOffset = findEndOfCentralDirectory(buffer)
  const centralDirectorySize = buffer.readUInt32LE(eocdOffset + 12)
  const centralDirectoryOffset = buffer.readUInt32LE(eocdOffset + 16)
  const entries = []

  let offset = centralDirectoryOffset
  const centralDirectoryEnd = centralDirectoryOffset + centralDirectorySize

  while (offset < centralDirectoryEnd) {
    const signature = buffer.readUInt32LE(offset)
    if (signature !== 0x02014b50) {
      throw new Error(`Invalid central directory signature at offset ${offset}`)
    }

    const flags = buffer.readUInt16LE(offset + 8)
    const method = buffer.readUInt16LE(offset + 10)
    const dosTime = buffer.readUInt16LE(offset + 12)
    const dosDate = buffer.readUInt16LE(offset + 14)
    const crc = buffer.readUInt32LE(offset + 16)
    const compressedSize = buffer.readUInt32LE(offset + 20)
    const uncompressedSize = buffer.readUInt32LE(offset + 24)
    const fileNameLength = buffer.readUInt16LE(offset + 28)
    const extraLength = buffer.readUInt16LE(offset + 30)
    const commentLength = buffer.readUInt16LE(offset + 32)
    const externalAttributes = buffer.readUInt32LE(offset + 38)
    const localHeaderOffset = buffer.readUInt32LE(offset + 42)
    const name = buffer.toString('utf8', offset + 46, offset + 46 + fileNameLength)
    const comment = buffer.subarray(offset + 46 + fileNameLength + extraLength, offset + 46 + fileNameLength + extraLength + commentLength)

    const localSignature = buffer.readUInt32LE(localHeaderOffset)
    if (localSignature !== 0x04034b50) {
      throw new Error(`Invalid local file header signature for ${name}`)
    }

    const localNameLength = buffer.readUInt16LE(localHeaderOffset + 26)
    const localExtraLength = buffer.readUInt16LE(localHeaderOffset + 28)
    const dataOffset = localHeaderOffset + 30 + localNameLength + localExtraLength
    const compressedData = buffer.subarray(dataOffset, dataOffset + compressedSize)

    let data
    if (method === ZIP_STORED) {
      data = Buffer.from(compressedData)
    } else if (method === ZIP_DEFLATED) {
      data = zlib.inflateRawSync(compressedData)
    } else {
      throw new Error(`Unsupported ZIP method ${method} for ${name}`)
    }

    if (data.length !== uncompressedSize) {
      throw new Error(`Unexpected uncompressed size for ${name}: ${data.length} !== ${uncompressedSize}`)
    }

    entries.push({
      name,
      data,
      method,
      flags,
      crc,
      date: dosToDate(dosDate, dosTime),
      comment: Buffer.from(comment),
      externalAttributes,
    })

    offset += 46 + fileNameLength + extraLength + commentLength
  }

  return entries
}

function createZipBuffer(entries) {
  const localParts = []
  const centralParts = []
  let offset = 0

  for (const entry of entries) {
    const nameBuffer = Buffer.from(entry.name)
    const data = Buffer.isBuffer(entry.data) ? entry.data : Buffer.from(entry.data || '')
    const method = entry.method ?? ZIP_DEFLATED
    const compressedData = method === ZIP_STORED ? data : zlib.deflateRawSync(data)
    const crc = crc32(data)
    const { dosDate, dosTime } = dateToDos(entry.date)

    const localHeader = Buffer.alloc(30 + nameBuffer.length)
    localHeader.writeUInt32LE(0x04034b50, 0)
    localHeader.writeUInt16LE(20, 4)
    localHeader.writeUInt16LE(0x0800, 6)
    localHeader.writeUInt16LE(method, 8)
    localHeader.writeUInt16LE(dosTime, 10)
    localHeader.writeUInt16LE(dosDate, 12)
    localHeader.writeUInt32LE(crc, 14)
    localHeader.writeUInt32LE(compressedData.length, 18)
    localHeader.writeUInt32LE(data.length, 22)
    localHeader.writeUInt16LE(nameBuffer.length, 26)
    localHeader.writeUInt16LE(0, 28)
    nameBuffer.copy(localHeader, 30)

    localParts.push(localHeader, compressedData)

    const comment = Buffer.isBuffer(entry.comment) ? entry.comment : Buffer.alloc(0)
    const centralHeader = Buffer.alloc(46 + nameBuffer.length + comment.length)
    centralHeader.writeUInt32LE(0x02014b50, 0)
    centralHeader.writeUInt16LE(20, 4)
    centralHeader.writeUInt16LE(20, 6)
    centralHeader.writeUInt16LE(0x0800, 8)
    centralHeader.writeUInt16LE(method, 10)
    centralHeader.writeUInt16LE(dosTime, 12)
    centralHeader.writeUInt16LE(dosDate, 14)
    centralHeader.writeUInt32LE(crc, 16)
    centralHeader.writeUInt32LE(compressedData.length, 20)
    centralHeader.writeUInt32LE(data.length, 24)
    centralHeader.writeUInt16LE(nameBuffer.length, 28)
    centralHeader.writeUInt16LE(0, 30)
    centralHeader.writeUInt16LE(comment.length, 32)
    centralHeader.writeUInt16LE(0, 34)
    centralHeader.writeUInt16LE(0, 36)
    centralHeader.writeUInt32LE(entry.externalAttributes || 0, 38)
    centralHeader.writeUInt32LE(offset, 42)
    nameBuffer.copy(centralHeader, 46)
    comment.copy(centralHeader, 46 + nameBuffer.length)
    centralParts.push(centralHeader)

    offset += localHeader.length + compressedData.length
  }

  const centralDirectoryOffset = offset
  const centralDirectory = Buffer.concat(centralParts)
  const centralDirectorySize = centralDirectory.length

  const eocd = Buffer.alloc(22)
  eocd.writeUInt32LE(0x06054b50, 0)
  eocd.writeUInt16LE(0, 4)
  eocd.writeUInt16LE(0, 6)
  eocd.writeUInt16LE(entries.length, 8)
  eocd.writeUInt16LE(entries.length, 10)
  eocd.writeUInt32LE(centralDirectorySize, 12)
  eocd.writeUInt32LE(centralDirectoryOffset, 16)
  eocd.writeUInt16LE(0, 20)

  return Buffer.concat([...localParts, centralDirectory, eocd])
}

function findEndOfCentralDirectory(buffer) {
  const minOffset = Math.max(0, buffer.length - 0xffff - 22)
  for (let offset = buffer.length - 22; offset >= minOffset; offset -= 1) {
    if (buffer.readUInt32LE(offset) === 0x06054b50) return offset
  }
  throw new Error('End of central directory not found')
}

function dateToDos(date) {
  const value = date instanceof Date ? date : new Date(1980, 0, 1, 0, 0, 0)
  const year = Math.max(1980, value.getFullYear())
  return {
    dosDate: ((year - 1980) << 9) | ((value.getMonth() + 1) << 5) | value.getDate(),
    dosTime: (value.getHours() << 11) | (value.getMinutes() << 5) | Math.floor(value.getSeconds() / 2),
  }
}

function dosToDate(dosDate, dosTime) {
  const day = dosDate & 0x1f
  const month = (dosDate >> 5) & 0x0f
  const year = ((dosDate >> 9) & 0x7f) + 1980
  const second = (dosTime & 0x1f) * 2
  const minute = (dosTime >> 5) & 0x3f
  const hour = (dosTime >> 11) & 0x1f
  return new Date(year, Math.max(0, month - 1), day || 1, hour, minute, second)
}

function crc32(buffer) {
  let crc = 0xffffffff
  for (let i = 0; i < buffer.length; i += 1) {
    crc = CRC_TABLE[(crc ^ buffer[i]) & 0xff] ^ (crc >>> 8)
  }
  return (crc ^ 0xffffffff) >>> 0
}

function makeCrcTable() {
  const table = new Uint32Array(256)
  for (let i = 0; i < 256; i += 1) {
    let value = i
    for (let bit = 0; bit < 8; bit += 1) {
      value = value & 1 ? 0xedb88320 ^ (value >>> 1) : value >>> 1
    }
    table[i] = value >>> 0
  }
  return table
}

function formatBytes(bytes) {
  return `${(bytes / 1024 / 1024).toFixed(1)} MB`
}

const CRC_TABLE = makeCrcTable()

module.exports = afterPack
module.exports.default = afterPack
module.exports.createZipBuffer = createZipBuffer
module.exports.readZipEntries = readZipEntries
module.exports.resolveDriverDirectory = resolveDriverDirectory
module.exports.trimDriverBundleInAppJar = trimDriverBundleInAppJar
module.exports.ZIP_STORED = ZIP_STORED
module.exports.ZIP_DEFLATED = ZIP_DEFLATED
