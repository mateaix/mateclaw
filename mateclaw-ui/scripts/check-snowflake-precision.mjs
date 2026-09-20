#!/usr/bin/env node

import { lstatSync, readdirSync, readFileSync } from 'node:fs'
import { dirname, join, relative } from 'node:path'
import { fileURLToPath } from 'node:url'

const uiRoot = dirname(dirname(fileURLToPath(import.meta.url)))
const sourceRoot = join(uiRoot, 'src')
const allowlist = 'snowflake-precision-ok'

const checks = [
  ['v-model.number bound to an *Id field', /v-model\.number\s*=\s*['"][^'"]*[Ii]d['"]/],
  ['Number()/parseInt() on an *Id value', /(Number|parseInt)\(\s*\w*[Ii]d\b/],
  ['Number()/parseInt() on an *Id member-access / lookup', /(Number|parseInt)\([^)]*(?:[a-z]Id\b|[Ii]d['"]|\.[Ii]d\b)/],
  ['input[type="number"] bound to an *Id field', /<input\b(?=[^>]*\btype\s*=\s*['"]number['"])(?=[^>]*\bv-model(?:\.[^=\s]+)?\s*=\s*['"][^'"]*[Ii]d['"])/],
  ["typeof <id> === 'number' silently drops string IDs", /typeof\s+\S*[Ii]d\b\s*===\s*['"]number['"]/],
]

function filesUnder(directory) {
  const files = []
  for (const entry of readdirSync(directory).sort()) {
    const path = join(directory, entry)
    const stat = lstatSync(path)
    if (stat.isDirectory()) files.push(...filesUnder(path))
    else if (stat.isFile()) files.push(path)
  }
  return files
}

let failed = false
for (const [label, pattern] of checks) {
  const hits = []
  for (const path of filesUnder(sourceRoot)) {
    const content = readFileSync(path)
    if (content.includes(0)) continue
    const lines = content.toString('utf8').split(/\r?\n/)
    lines.forEach((line, index) => {
      if (!line.includes(allowlist) && pattern.test(line)) {
        hits.push(`${relative(uiRoot, path)}:${index + 1}:${line}`)
      }
    })
  }
  if (hits.length > 0) {
    failed = true
    console.error(`\x1b[31m✘ ${label}\x1b[0m`)
    console.error(hits.join('\n'))
    console.error()
  }
}

if (failed) {
  console.error('\x1b[31mSnowflake ID precision violations found.\x1b[0m')
  console.error('\x1b[33mKeep backend-issued Snowflake IDs as strings throughout the UI.\x1b[0m')
  console.error('\x1b[33mFor a bounded non-ID value, append `// snowflake-precision-ok: <reason>` on the same line.\x1b[0m')
  process.exit(1)
}

console.log('\x1b[32m✓ Snowflake ID precision check: clean\x1b[0m')
