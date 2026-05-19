import { acceptHMRUpdate, defineStore } from 'pinia'
import { ref } from 'vue'
import { agentApi } from '@/api/index'
import type { Agent } from '@/types/index'

export const useAgentStore = defineStore('agent', () => {
  const agents = ref<Agent[]>([])
  const loading = ref(false)

  async function fetchAgents() {
    loading.value = true
    try {
      const res: any = await agentApi.list()
      agents.value = res.data || res || []
    } catch (e) {
      console.error('Failed to fetch agents', e)
    } finally {
      loading.value = false
    }
  }

  async function createAgent(data: Partial<Agent>) {
    const res: any = await agentApi.create(data)
    const agent = res.data || res
    agents.value.unshift(agent)
    return agent
  }

  async function updateAgent(id: number, data: Partial<Agent>) {
    const res: any = await agentApi.update(id, data)
    const updated = res.data || res
    const idx = agents.value.findIndex((a) => a.id === id)
    if (idx !== -1) agents.value[idx] = updated
    return updated
  }

  async function deleteAgent(id: number) {
    await agentApi.delete(id)
    agents.value = agents.value.filter((a) => a.id !== id)
  }

  return { agents, loading, fetchAgents, createAgent, updateAgent, deleteAgent }
})

// Enable HMR for this store: editing it during `pnpm dev` patches the live
// store instead of requiring a full page reload. Stripped from prod builds.
if (import.meta.hot) {
  import.meta.hot.accept(acceptHMRUpdate(useAgentStore, import.meta.hot))
}
