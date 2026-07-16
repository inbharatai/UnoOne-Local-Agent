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
  targetPattern?: string
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
        const tagPattern = decision.actionName === 'select_dropdown_option'
          ? 'select'
          : decision.actionName === 'input_text' || decision.actionName === 'pick_date'
            ? '(?:input|textarea)'
            : '(?:button|input|a)'
        const tailPattern = decision.targetPattern ? `[^\\n]*${decision.targetPattern}[^\\n]*` : '[^\\n>]*'
        const indexedElement = new RegExp(`\\[(\\d+)\\]<${tagPattern}\\b${tailPattern}>`, 'i').exec(
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

test('fills a complete form with select, checkbox, radio, date, and explicit submit', async ({ page }) => {
  await installMockNativeBridge(
    page,
    [
      {
        evaluationPreviousGoal: 'No previous action', memory: 'Country is empty', nextGoal: 'Select India',
        actionName: 'select_dropdown_option', actionArgumentsJson: JSON.stringify({ index: AUTO_INDEX, text: 'India' })
      },
      {
        evaluationPreviousGoal: 'Country selected', memory: 'Terms are unchecked', nextGoal: 'Accept terms',
        actionName: 'toggle_checkbox', targetPattern: 'checkbox', actionArgumentsJson: JSON.stringify({ index: AUTO_INDEX })
      },
      {
        evaluationPreviousGoal: 'Terms accepted', memory: 'Plan is unselected', nextGoal: 'Choose Pro',
        actionName: 'choose_radio', targetPattern: 'radio', actionArgumentsJson: JSON.stringify({ index: AUTO_INDEX })
      },
      {
        evaluationPreviousGoal: 'Plan chosen', memory: 'Date is empty', nextGoal: 'Set the date',
        actionName: 'pick_date', targetPattern: 'date', actionArgumentsJson: JSON.stringify({ index: AUTO_INDEX, date: '2026-08-01' })
      },
      {
        evaluationPreviousGoal: 'All fields are ready', memory: 'Form is not submitted', nextGoal: 'Submit after confirmation',
        actionName: 'submit_form', targetPattern: 'submit', actionArgumentsJson: JSON.stringify({ index: AUTO_INDEX, purpose: 'Submit profile' })
      },
      {
        evaluationPreviousGoal: 'Form submitted', memory: 'Task complete', nextGoal: 'Finish',
        actionName: 'done', actionArgumentsJson: JSON.stringify({ text: 'Profile form completed', success: true })
      }
    ],
    () => ({ allowed: true, actionClass: 'FORM_ACTION', message: 'Allowed for test' })
  )

  await loadFixture(page, `<!doctype html><html><body><form id="profile" onsubmit="event.preventDefault();window.submitted=true">
    <label>Country<select name="country"><option value="">Choose</option><option>India</option></select></label>
    <label><input name="terms" type="checkbox">Accept terms</label>
    <label><input name="plan" type="radio" value="pro">Pro</label>
    <label>Date<input name="start" type="date"></label>
    <button type="submit">Submit profile</button>
  </form><script>window.submitted=false</script></body></html>`)

  const result = await page.evaluate(() =>
    window.UnoOnePageAgentRuntime!.execute('Complete the profile form and submit it')
  )

  expect(result.success).toBe(true)
  await expect(page.locator('select[name=country]')).toHaveValue('India')
  await expect(page.locator('input[name=terms]')).toBeChecked()
  await expect(page.locator('input[name=plan]')).toBeChecked()
  await expect(page.locator('input[name=start]')).toHaveValue('2026-08-01')
  expect(await page.evaluate(() => (window as any).submitted)).toBe(true)
})

test('fills email, numeric, and multiline textarea controls', async ({ page }) => {
  await installMockNativeBridge(
    page,
    [
      {
        evaluationPreviousGoal: 'No previous action', memory: 'Email is empty', nextGoal: 'Enter email',
        actionName: 'input_text', targetPattern: 'email',
        actionArgumentsJson: JSON.stringify({ index: AUTO_INDEX, text: 'reeturaj@example.com' })
      },
      {
        evaluationPreviousGoal: 'Email entered', memory: 'Experience is empty', nextGoal: 'Enter experience',
        actionName: 'input_text', targetPattern: 'experience',
        actionArgumentsJson: JSON.stringify({ index: AUTO_INDEX, text: '7' })
      },
      {
        evaluationPreviousGoal: 'Experience entered', memory: 'Message is empty', nextGoal: 'Enter message',
        actionName: 'input_text', targetPattern: 'message',
        actionArgumentsJson: JSON.stringify({ index: AUTO_INDEX, text: 'Please review my application.' })
      },
      {
        evaluationPreviousGoal: 'All requested fields are filled', memory: 'Task complete', nextGoal: 'Finish',
        actionName: 'done', actionArgumentsJson: JSON.stringify({ text: 'Contact details completed', success: true })
      }
    ],
    () => ({ allowed: true, actionClass: 'ORDINARY_INPUT', message: 'Allowed' })
  )

  await loadFixture(page, `<!doctype html><html><body><form>
    <label>Email<input name="email" type="email"></label>
    <label>Years of experience<input name="experience" type="number" min="0" max="60"></label>
    <label>Message<textarea name="message"></textarea></label>
  </form></body></html>`)

  const result = await page.evaluate(() =>
    window.UnoOnePageAgentRuntime!.execute('Fill my email, experience, and message')
  )

  expect(result.success, result.data).toBe(true)
  await expect(page.locator('input[name=email]')).toHaveValue('reeturaj@example.com')
  await expect(page.locator('input[name=experience]')).toHaveValue('7')
  await expect(page.locator('textarea[name=message]')).toHaveValue('Please review my application.')
})
