import { expect, test, type Page } from '@playwright/test'
import path from 'node:path'
import { fileURLToPath } from 'node:url'

const here = path.dirname(fileURLToPath(import.meta.url))
const bundlePath = path.resolve(here, '..', 'dist', 'unoone-page-agent.js')
const origin = 'https://unoone.test'

interface Decision {
  evaluationPreviousGoal: string
  memory: string
  nextGoal: string
  actionName: string
  actionArgumentsJson: string
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
  await page.addInitScript(
    ({ testOrigin, plannedDecisions }) => {
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

      let decisionIndex = 0
      const bridge = {
        onmessage: null as ((event: MessageEvent<string>) => void) | null,
        postMessage(raw: string) {
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
              const decision = plannedDecisions[Math.min(decisionIndex, plannedDecisions.length - 1)]
              decisionIndex += 1
              payload = JSON.stringify(decision)
            } else if (request.type === 'AUTHORIZE_ACTION') {
              const actionRequest = JSON.parse(request.payload) as { actionName: string; summary: string }
              const handler = (window as any).__authorizeForTest as (
                value: { actionName: string; summary: string }
              ) => unknown
              payload = JSON.stringify(handler(actionRequest))
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
    { testOrigin: origin, plannedDecisions: decisions }
  )

  await page.exposeFunction('__unooneAuthorizeNode', authorize)
  await page.addInitScript(() => {
    ;(window as any).__authorizeForTest = (request: { actionName: string; summary: string }) =>
      (window as any).__unooneAuthorizeNode(request)
  })
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
        actionArgumentsJson: JSON.stringify({ index: 0, text: 'Reeturaj' })
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
        actionArgumentsJson: JSON.stringify({ index: 0 })
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
