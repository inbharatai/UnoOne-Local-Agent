import { sendNative } from './native-bridge'

interface ChatMessage {
  role: string
  content?: string | null
}

interface OpenAiRequestBody {
  messages?: ChatMessage[]
  tools?: unknown
  max_tokens?: number
  max_completion_tokens?: number
}

interface PageAgentModelDecision {
  evaluationPreviousGoal: string
  memory: string
  nextGoal: string
  actionName: string
  actionArgumentsJson: string
}

function messageContent(messages: ChatMessage[] | undefined, role: string): string {
  return messages?.filter((message) => message.role === role).map((message) => message.content ?? '').join('\n') ?? ''
}

/**
 * `customFetch` adapter for PageAgent's OpenAI-compatible LLM client.
 * No HTTP request is made: prompts and the macro-tool schema are passed to the native Gemma bridge.
 */
export function createLocalGemmaFetch(): typeof fetch {
  return async (_input: RequestInfo | URL, init?: RequestInit): Promise<Response> => {
    if (typeof init?.body !== 'string') {
      return jsonResponse({ error: { message: 'Missing PageAgent request body' } }, 400)
    }

    let body: OpenAiRequestBody
    try {
      body = JSON.parse(init.body) as OpenAiRequestBody
    } catch {
      return jsonResponse({ error: { message: 'Invalid PageAgent request JSON' } }, 400)
    }

    const invocation = {
      systemPrompt: messageContent(body.messages, 'system'),
      userPrompt: messageContent(body.messages, 'user'),
      macroToolSchemaJson: JSON.stringify(body.tools ?? []),
      maxOutputTokens: body.max_completion_tokens ?? body.max_tokens ?? 512
    }

    const payload = await sendNative('MODEL_INVOKE', invocation, 45_000)
    const decision = JSON.parse(payload) as PageAgentModelDecision
    if (!decision.actionName) throw new Error('Local Gemma returned no PageAgent action')

    let actionArguments: unknown = {}
    if (decision.actionArgumentsJson.trim()) {
      actionArguments = JSON.parse(decision.actionArgumentsJson)
    }

    const macroArguments = {
      evaluation_previous_goal: decision.evaluationPreviousGoal,
      memory: decision.memory,
      next_goal: decision.nextGoal,
      action: {
        [decision.actionName]: actionArguments
      }
    }

    return jsonResponse({
      id: `unoone-${crypto.randomUUID()}`,
      object: 'chat.completion',
      created: Math.floor(Date.now() / 1000),
      model: 'gemma-4-e2b-local',
      choices: [
        {
          index: 0,
          finish_reason: 'tool_calls',
          message: {
            role: 'assistant',
            content: null,
            tool_calls: [
              {
                id: `call-${crypto.randomUUID()}`,
                type: 'function',
                function: {
                  name: 'AgentOutput',
                  arguments: JSON.stringify(macroArguments)
                }
              }
            ]
          }
        }
      ],
      usage: {
        prompt_tokens: 0,
        completion_tokens: 0,
        total_tokens: 0
      }
    })
  }
}

function jsonResponse(body: unknown, status = 200): Response {
  return new Response(JSON.stringify(body), {
    status,
    headers: { 'content-type': 'application/json' }
  })
}
