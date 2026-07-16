import { expect, test, type Page } from '@playwright/test'
import path from 'node:path'
import { fileURLToPath } from 'node:url'

const here = path.dirname(fileURLToPath(import.meta.url))
const bundlePath = path.resolve(here, '..', 'dist', 'unoone-page-agent.js')
const origin = 'https://unoone.test'
const AUTO_INDEX = -1

interface Decision {
  evaluationPreviousGoal: string
  memory: string
  nextGoal: string
  actionName: string
  actionArgumentsJson: string
}

interface ModelInvocation {
  userPrompt: string
}

async function installMockNativeBridge(
  page: Page,
  decisions: Decision[],
  authorize: (request: { actionName: string; summary: string }) => {
    allowed: boolean
    requiresUserTakeover?: boolean
    actionClass: string
    message: string
  }
): Promise<void> {
  await page.exposeFunction('__unooneAuthorizeNode', authorize)
  await page.addInitScript(
    ({ testOrigin, plannedDecisions, autoIndex }) => {
      const session = Object.freeze({
        id: 'playwright-session',
        nonce: 'playwright-nonce',
        origin: testOrigin,
        protocolVersion: 1
      })
      Object.defineProperty(window, '__UNOONE_PAGE_AGENT_SESSION__', {
        value: session,
        writable: false,
        configurable: false
      })

      const resolveDecision = (template: Decision, requestPayload: string): Decision => {
        const decision = structuredClone(template)
        const argumentsValue = JSON.parse(decision.actionArgumentsJson) as Record<string, unknown>
        if (argumentsValue.index !== autoIndex) return decision

        const invocation = JSON.parse(requestPayload) as ModelInvocation
        const tagPattern = decision.actionName === 'input_text' ? '(?:input|textarea)' : '(?:button|input|a)'
        const indexedElement = new RegExp(`\\[(\\d+)\\]<${tagPattern}\\b[^\\n>]*>`, 'i').exec(
          invocation.userPrompt
        )
        if (!indexedElement) {
          throw new Error(`No indexed ${tagPattern} element was present in the PageAgent browser state`)
        }

        argumentsValue.index = Number(indexedElement[1])
        decision.actionArgumentsJson = JSON.stringify(argumentsValue)
        return decision
      }

      let decisionIndex = 0
      const bridge = {
        onmessage: null as ((event: MessageEvent<string>) => void) | null,
        async postMessage(raw: string): Promise<void> {
          const request = JSON.parse(raw) as {
            requestId: string
            type: string
            payload: string
          }
          let success = true
          let payload = '{}'
          let errorCode: string | null = null
          let errorMessage: string | null = null

          try {
            if (request.type === 'MODEL_INVOKE') {
              const template = plannedDecisions[Math.min(decisionIndex, plannedDecisions.length - 1)]
              decisionIndex += 1
              payload = JSON.stringify(resolveDecision(template, request.payload))
            } else if (request.type === 'AUTHORIZE_ACTION') {
              const actionRequest = JSON.parse(request.payload) as { actionName: string; summary: string }
              const handler = (window as any).__unooneAuthorizeNode as (
                value: { actionName: string; summary: string }
              ) => Promise<unknown>
              payload = JSON.stringify(await handler(actionRequest))
            } else if (request.type === 'ASK_USER') {
              payload = 'test answer'
            } else if (request.type === 'USER_TAKEOVER') {
              payload = 'completed'
            }
          } catch (error) {
            success = false
            errorCode = 'MOCK_BRIDGE_ERROR'
            errorMessage = String(error)
          }

          queueMicrotask(() => {
            bridge.onmessage?.(
              new MessageEvent('message', {
                data: JSON.stringify({
                  protocolVersion: 1,
                  requestId: request.requestId,
                  success,
                  payload,
                  errorCode,
                  errorMessage
                })
              })
            )
          })
        }
      }
      Object.defineProperty(window, 'UnoOnePageAgent', {
        value: bridge,
        writable: false,
        configurable: false
      })
    },
    { testOrigin: origin, plannedDecisions: decisions, autoIndex: AUTO_INDEX }
  )
}

async function loadFixture(page: Page, html: string): Promise<void> {
  await page.route(`${origin}/**`, async (route) => {
    await route.fulfill({
      status: 200,
      contentType: 'text/html; charset=utf-8',
      body: html
    })
  })
  await page.goto(`${origin}/form`)
  await page.addScriptTag({ path: bundlePath })
  await expect.poll(() => page.evaluate(() => Boolean(window.UnoOnePageAgentRuntime))).toBe(true)
}

test('fills an ordinary form field through PageAgent and local Gemma bridge', async ({ page }) => {
  await installMockNativeBridge(
    page,
    [
      {
        evaluationPreviousGoal: 'No previous action',
        memory: 'The first-name field is empty',
        nextGoal: 'Fill the first-name field',
        actionName: 'input_text',
        actionArgumentsJson: JSON.stringify({ index: AUTO_INDEX, text: 'Reeturaj' })
      },
      {
        evaluationPreviousGoal: 'The first-name field was filled',
        memory: 'The requested field is complete',
        nextGoal: 'Finish the task',
        actionName: 'done',
        actionArgumentsJson: JSON.stringify({ text: 'Form field completed', success: true })
      }
    ],
    () => ({ allowed: true, actionClass: 'ORDINARY_INPUT', message: 'Allowed' })
  )

  await loadFixture(
    page,
    `<!doctype html><html><body><main><label for="first-name">First name</label><input id="first-name" name="firstName" /></main></body></html>`
  )

  const result = await page.evaluate(() =>
    window.UnoOnePageAgentRuntime!.execute('Fill the first-name field with Reeturaj and finish')
  )

  expect(result.success).toBe(true)
  await expect(page.locator('#first-name')).toHaveValue('Reeturaj')
})

test('does not click a payment button when native authorization blocks it', async ({ page }) => {
  await installMockNativeBridge(
    page,
    [
      {
        evaluationPreviousGoal: 'No previous action',
        memory: 'There is a Pay now button',
        nextGoal: 'Click the Pay now button',
        actionName: 'click_element_by_index',
        actionArgumentsJson: JSON.stringify({ index: AUTO_INDEX })
      },
      {
        evaluationPreviousGoal: 'The action was blocked by UnoOne safety',
        memory: 'Payments cannot be automated',
        nextGoal: 'Stop safely',
        actionName: 'done',
        actionArgumentsJson: JSON.stringify({ text: 'Payment was not performed', success: false })
      }
    ],
    () => ({ allowed: false, actionClass: 'PAYMENT', message: 'Payments are not automated by UnoOne' })
  )

  await loadFixture(
    page,
    `<!doctype html><html><body><button id="pay" onclick="window.paymentClicked=true">Pay now</button><script>window.paymentClicked=false</script></body></html>`
  )

  const result = await page.evaluate(() =>
    window.UnoOnePageAgentRuntime!.execute('Pay using the button')
  )

  expect(result.success).toBe(false)
  expect(await page.evaluate(() => (window as any).paymentClicked)).toBe(false)
})

test('authorized upload clicks the real file input and completes selection', async ({ page }) => {
  await installMockNativeBridge(
    page,
    [
      {
        evaluationPreviousGoal: 'No previous action',
        memory: 'A resume file input is available',
        nextGoal: 'Open the resume file input',
        actionName: 'upload_file',
        actionArgumentsJson: JSON.stringify({ index: AUTO_INDEX, purpose: 'Attach the resume' })
      },
      {
        evaluationPreviousGoal: 'The file picker was opened',
        memory: 'The requested file is attached',
        nextGoal: 'Finish the task',
        actionName: 'done',
        actionArgumentsJson: JSON.stringify({ text: 'Resume attached', success: true })
      }
    ],
    ({ actionName }) => ({
      allowed: actionName === 'upload_file',
      actionClass: 'FILE_TRANSFER',
      message: 'User confirmed'
    })
  )

  await loadFixture(
    page,
    `<!doctype html><html><body><main><label for="resume">Resume</label><input id="resume" type="file" name="resume" /></main></body></html>`
  )

  const chooserPromise = page.waitForEvent('filechooser')
  const resultPromise = page.evaluate(() =>
    window.UnoOnePageAgentRuntime!.execute('Attach my resume and finish')
  )
  const chooser = await chooserPromise
  await chooser.setFiles({ name: 'resume.txt', mimeType: 'text/plain', buffer: Buffer.from('UnoOne test resume') })
  const result = await resultPromise

  expect(result.success).toBe(true)
  expect(await page.locator('#resume').evaluate((input: HTMLInputElement) => input.files?.[0]?.name)).toBe('resume.txt')
})
