import { createApp, defineComponent, nextTick } from 'vue'
import { afterEach, beforeEach, expect, it, vi } from 'vitest'

const mocks = vi.hoisted(() => ({
  list: vi.fn(),
  create: vi.fn(),
  update: vi.fn(),
  test: vi.fn(),
}))
vi.mock('@/api/index', () => ({ datasourceApi: mocks }))
vi.mock('vue-i18n', () => ({ useI18n: () => ({ t: (key: string) => key }) }))
vi.mock('@/composables/useMcToast', () => ({
  mcToast: { error: vi.fn(), success: vi.fn(), warning: vi.fn() },
}))
vi.mock('@/components/common/useConfirm', () => ({ mcConfirm: vi.fn() }))

import Datasources from '../Datasources.vue'

let app: ReturnType<typeof createApp> | undefined

async function flush() {
  for (let i = 0; i < 8; i++) await Promise.resolve()
  await nextTick()
}

async function setInput(host: HTMLElement, selector: string, value: string) {
  const input = host.querySelector<HTMLInputElement>(selector)!
  input.value = value
  input.dispatchEvent(new Event('input', { bubbles: true }))
  await nextTick()
}

beforeEach(() => {
  vi.clearAllMocks()
  mocks.list.mockResolvedValue({ data: [] })
  mocks.create.mockResolvedValue({
    data: {
      id: 7,
      name: 'Analytics',
      description: '',
      dbType: 'mysql',
      host: 'db.internal',
      port: 3306,
      databaseName: 'analytics',
      username: 'reader',
      password: 'encrypted-password-from-create',
      extraParams: '',
      schemaName: '',
      enabled: true,
    },
  })
  mocks.update.mockResolvedValue({ data: {} })
  mocks.test.mockResolvedValue({ data: { success: true } })
})

afterEach(() => {
  app?.unmount()
  app = undefined
  document.body.innerHTML = ''
})

it('does not repopulate the password from create when testing twice in one modal', async () => {
  const host = document.createElement('div')
  document.body.appendChild(host)
  app = createApp(Datasources)
  app.component('ElIcon', defineComponent({ template: '<span><slot /></span>' }))
  app.mount(host)
  await flush()

  const addButton = [...host.querySelectorAll<HTMLButtonElement>('button')]
    .find(button => button.textContent?.includes('datasources.addButton'))!
  addButton.click()
  await nextTick()
  await setInput(host, 'input[placeholder="datasources.placeholders.name"]', 'Analytics')
  await setInput(host, 'input[placeholder="datasources.placeholders.host"]', 'db.internal')
  await setInput(host, 'input[placeholder="datasources.placeholders.databaseName"]', 'analytics')
  await setInput(host, 'input[autocomplete="new-password"]', 'reader-password')

  const testButton = host.querySelector<HTMLButtonElement>('.btn-test')!
  testButton.click()
  await flush()
  expect(mocks.create).toHaveBeenCalledTimes(1)
  expect(mocks.test).toHaveBeenCalledWith(7)

  testButton.click()
  await flush()

  expect(mocks.create).toHaveBeenCalledTimes(1)
  expect(mocks.update).toHaveBeenCalledTimes(1)
  expect(mocks.update).toHaveBeenCalledWith(7, expect.objectContaining({ password: '******' }))
  expect(mocks.test).toHaveBeenCalledTimes(2)
  expect(mocks.test).toHaveBeenNthCalledWith(2, 7)
})
