import { PageAgentCore } from '@page-agent/core'
import { PageController } from '@page-agent/page-controller'

import { maskSensitivePageContent } from './content-mask'
import { createGuardedTools } from './guarded-tools'
import { createLocalGemmaFetch } from './local-gemma-fetch'
import { currentSession, sendNative } from './native-bridge'

interface RuntimeResult {
  success: boolean
  data: string
}

interface UnoOneRuntimeApi {
  execute(task: string): Promise<RuntimeResult>
  stop(): Promise<void>
  status(): string
  dispose(): Promise<void>
}

declare global {
  interface Window {
    UnoOnePageAgentRuntime?: UnoOneRuntimeApi
  }
}

const pageController = new PageController({
  enableMask: true,
  viewportExpansion: 0,
  keepSemanticTags: true,
  includeAttributes: ['name', 'type', 'placeholder', 'aria-label', 'role', 'autocomplete']
})

const agent = new PageAgentCore({
  pageController,
  baseURL: 'https://unoone.local/v1',
  model: 'gemma-4-e2b-local',
  customFetch: createLocalGemmaFetch(),
  customTools: createGuardedTools(),
  experimentalScriptExecutionTool: false,
  experimentalLlmsTxt: false,
  transformPageContent: maskSensitivePageContent,
  maxSteps: 40,
  stepDelay: 0.4,
  instructions: {
    system: [
      'Operate only on the current approved page origin.',
      'Use one DOM action per step and verify the result before continuing.',
      'Request native authorization for every DOM action and obey its decision exactly.',
      'When native authorization allows an action, execute it; when it denies or requests takeover, do not bypass or retry it.',
      'Use ask_user when required data is missing or user takeover is necessary.',
      'Do not use or request JavaScript execution.'
    ].join(' ')
  }
})

agent.onAskUser = async (question, options) => {
  if (options?.signal.aborted) throw new DOMException('Task aborted', 'AbortError')
  return await sendNative('ASK_USER', { question })
}

agent.addEventListener('activity', (event: Event) => {
  const detail = (event as CustomEvent<unknown>).detail
  void sendNative('ACTIVITY_EVENT', { detail }).catch(() => undefined)
})

agent.addEventListener('statuschange', () => {
  void sendNative('ACTIVITY_EVENT', { status: agent.status }).catch(() => undefined)
})

const runtime: UnoOneRuntimeApi = {
  async execute(task: string): Promise<RuntimeResult> {
    const cleanTask = task.trim()
    if (!cleanTask) throw new Error('Browser task is required')
    const session = currentSession()
    if (session.origin !== window.location.origin) throw new Error('Browser origin changed')

    const result = await agent.execute(cleanTask)
    const compact = { success: result.success, data: String(result.data ?? '') }
    await sendNative('TASK_RESULT', compact).catch(() => undefined)
    return compact
  },

  async stop(): Promise<void> {
    await agent.stop()
  },

  status(): string {
    return agent.status
  },

  async dispose(): Promise<void> {
    await agent.stop()
    agent.dispose()
  }
}

Object.defineProperty(window, 'UnoOnePageAgentRuntime', {
  value: runtime,
  writable: false,
  configurable: false
})

window.dispatchEvent(
  new CustomEvent('unoone-page-agent-runtime-loaded', {
    detail: { origin: window.location.origin }
  })
)

export { runtime }
