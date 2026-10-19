import assert from 'node:assert/strict'
import fs from 'node:fs'
import os from 'node:os'
import path from 'node:path'
import test from 'node:test'
import { getLocalJwtSecret } from '../../electron/main/jwtSecret.mjs'

function makeUserDataPath(t) {
  const userDataPath = fs.mkdtempSync(path.join(os.tmpdir(), 'mateclaw-jwt-secret-'))
  t.after(() => fs.rmSync(userDataPath, { recursive: true, force: true }))
  return userDataPath
}

test('creates a random per-installation secret with private file permissions', (t) => {
  const userDataPath = makeUserDataPath(t)
  const secretPath = path.join(userDataPath, 'jwt-secret')
  const secret = getLocalJwtSecret(userDataPath)

  assert.match(secret, /^[A-Za-z0-9_-]{64}$/)
  assert.equal(Buffer.from(secret, 'base64url').length, 48)
  assert.equal(fs.readFileSync(secretPath, 'utf8'), secret)

  if (process.platform !== 'win32') {
    assert.equal(fs.statSync(secretPath).mode & 0o777, 0o600)
  }
})

test('reuses the persisted secret after restart', (t) => {
  const userDataPath = makeUserDataPath(t)
  const first = getLocalJwtSecret(userDataPath)
  const afterRestart = getLocalJwtSecret(userDataPath)

  assert.equal(afterRestart, first)
})

test('preserves explicit overrides, including invalid values for server-side rejection', (t) => {
  const userDataPath = makeUserDataPath(t)

  assert.equal(getLocalJwtSecret(userDataPath, 'explicit-secret-with-more-than-32-bytes'), 'explicit-secret-with-more-than-32-bytes')
  assert.equal(getLocalJwtSecret(userDataPath, ''), '')
  assert.equal(getLocalJwtSecret(userDataPath, 'short'), 'short')
  assert.equal(fs.existsSync(path.join(userDataPath, 'jwt-secret')), false)
})
