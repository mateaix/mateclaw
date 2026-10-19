import { randomBytes } from 'node:crypto'
import { chmodSync, mkdirSync, readFileSync, writeFileSync } from 'node:fs'
import { join } from 'node:path'

const SECRET_FILE = 'jwt-secret'

function readSecret(path) {
  const secret = readFileSync(path, 'utf8')
  chmodSync(path, 0o600)
  return secret
}

export function getLocalJwtSecret(userDataPath, explicitSecret) {
  if (explicitSecret !== undefined) return explicitSecret

  mkdirSync(userDataPath, { recursive: true, mode: 0o700 })
  const secretPath = join(userDataPath, SECRET_FILE)

  try {
    return readSecret(secretPath)
  } catch (error) {
    if (error.code !== 'ENOENT') throw error
  }

  const secret = randomBytes(48).toString('base64url')

  try {
    writeFileSync(secretPath, secret, { encoding: 'utf8', flag: 'wx', mode: 0o600 })
    chmodSync(secretPath, 0o600)
    return secret
  } catch (error) {
    if (error.code !== 'EEXIST') throw error
    return readSecret(secretPath)
  }
}
