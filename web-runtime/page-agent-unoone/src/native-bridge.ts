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

/** Retained types for legacy tests only. No page-visible native capability exists. */
export function currentSession(): UnoOneSession {
  throw new Error('Page-visible native sessions have been removed')
}
export async function sendNative(_type: PageAgentRequestType, _payload: unknown, _timeoutMs = 30_000): Promise<string> {
  throw new Error('Page-visible native bridge has been removed; tasks are native-owned')
}
