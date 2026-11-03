import { describe, expect, it } from 'vitest'

describe('browser storage in the DOM test environment', () => {
  it.each(['localStorage', 'sessionStorage'] as const)('%s provides the browser Storage API', name => {
    const storage = globalThis[name]
    expect(typeof storage.getItem, `${process.version}: ${name}.getItem`).toBe('function')
    expect(typeof storage.clear, `${process.version}: ${name}.clear`).toBe('function')
    storage.clear()
    storage.setItem('token', 'test-token')
    expect(storage.getItem('token')).toBe('test-token')
    expect(storage.length).toBe(1)
    expect(storage.key(0)).toBe('token')
    storage.removeItem('token')
    expect(storage.getItem('token')).toBeNull()
    expect(storage.length).toBe(0)
  })
})
