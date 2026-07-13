export type PageAgentRequestType =
  | 'MODEL_INVOKE'
  | 'AUTHORIZE_ACTION'
  | 'ACTIVITY_EVENT'
  | 'TASK_RESULT'
  | 'ASK_USER'
  | 'USER_TAKEOVER'
  | 'AUDIT_EVENT'

export interface UnoOneSession {
  id: string
  nonce: string
  origin: string
  protocolVersion: number
}

interface NativeBridgeObject {
  postMessage(message: string): void
  onmessage: ((event: MessageEvent<string>) => void) | null
}

interface BridgeResponse {
  protocolVersion: number
  requestId: string
  success: boolean
  payload: string
  errorCode?: string | null
  errorMessage?: string | null
}

declare global {
  interface Window {
    UnoOnePageAgent?: NativeBridgeObject
    __UNOONE_PAGE_AGENT_SESSION__?: UnoOneSession
  }
}

const pending = new Map<
  string,
  {
    resolve: (value: string) => void
    reject: (error: Error) => void
    timeout: number
  }
>()

let listenerInstalled = false

function bridge(): NativeBridgeObject {
  const value = window.UnoOnePageAgent
  if (!value || typeof value.postMessage !== 'function') {
    throw new Error('UnoOne native bridge is unavailable')
  }
  return value
}

export function currentSession(): UnoOneSession {
  const session = window.__UNOONE_PAGE_AGENT_SESSION__
  if (!session) throw new Error('UnoOne browser session has not been initialized')
  if (session.origin !== window.location.origin) {
    throw new Error('UnoOne session origin does not match the current page')
  }
  return session
}

function installListener(): void {
  if (listenerInstalled) return
  const value = bridge()
  value.onmessage = (event: MessageEvent<string>) => {
    let response: BridgeResponse
    try {
      response = JSON.parse(String(event.data)) as BridgeResponse
    } catch {
      return
    }
    const item = pending.get(response.requestId)
    if (!item) return
    window.clearTimeout(item.timeout)
    pending.delete(response.requestId)
    if (response.success) item.resolve(response.payload)
    else item.reject(new Error(`${response.errorCode ?? 'NATIVE_ERROR'}: ${response.errorMessage ?? 'Native request failed'}`))
  }
  listenerInstalled = true
}

export async function sendNative(
  type: PageAgentRequestType,
  payload: unknown,
  timeoutMs = 30_000
): Promise<string> {
  installListener()
  const session = currentSession()
  const requestId = crypto.randomUUID()
  const request = {
    protocolVersion: session.protocolVersion,
    requestId,
    sessionId: session.id,
    sessionNonce: session.nonce,
    origin: session.origin,
    type,
    payload: typeof payload === 'string' ? payload : JSON.stringify(payload)
  }

  return await new Promise<string>((resolve, reject) => {
    const timeout = window.setTimeout(() => {
      pending.delete(requestId)
      reject(new Error(`UnoOne native request timed out after ${timeoutMs} ms`))
    }, timeoutMs)
    pending.set(requestId, { resolve, reject, timeout })
    bridge().postMessage(JSON.stringify(request))
  })
}
