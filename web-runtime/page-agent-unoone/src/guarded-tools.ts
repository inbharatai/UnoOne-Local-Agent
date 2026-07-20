import { tool, type PageAgentTool } from '@page-agent/core'
import { z } from 'zod'

import { sendNative } from './native-bridge'

interface AuthorizationResponse {
  allowed: boolean
  requiresUserTakeover: boolean
  actionClass: string
  message: string
}

async function elementSummary(agent: any, index: number): Promise<string> {
  const state = await agent.pageController.getBrowserState()
  return indexedElementSummary(String(state.content ?? ''), index)
}

export function indexedElementSummary(content: string, index: number): string {
  const lines = content.split('\n')
  // Match the controller's leading index token exactly. `includes('[1]')` also matched `[10]`,
  // which could authorize one field and then act on another.
  const exactIndex = new RegExp(`^\\s*\\*?\\[${index}\\](?:<|\\s|$)`)
  const exact = lines.find((line) => exactIndex.test(line))
  return exact?.trim() || `interactive element index ${index}`
}

type TypedControl = 'select' | 'checkbox' | 'radio' | 'date'

interface IndexedTarget {
  index: number
  summary: string
}

function isTypedControl(summary: string, type: TypedControl): boolean {
  const line = summary.toLowerCase()
  if (type === 'select') return /<select\b/.test(line)
  return new RegExp(`<input\\b[^\\n]*\\btype=${type}(?:\\s|/|>|$)`).test(line)
}

/**
 * Resolve a typed form control against the controller's freshly rebuilt selector map.
 *
 * Page Agent reuses small numeric indexes after scrolling or DOM updates. A model response can
 * therefore carry a formerly valid index that now refers to a label or a different field. Never
 * execute that stale index: accept it only when the current DOM proves the type, otherwise remap
 * only when the current DOM contains exactly one unambiguous control of the requested type.
 */
export function resolveTypedTarget(
  content: string,
  requestedIndex: number,
  type: TypedControl
): IndexedTarget | null {
  const requestedSummary = indexedElementSummary(content, requestedIndex)
  if (isTypedControl(requestedSummary, type)) {
    return { index: requestedIndex, summary: requestedSummary }
  }

  const candidates = content
    .split('\n')
    .map((summary) => {
      const match = /^\s*\*?\[(\d+)](?:<|\s|$)/.exec(summary)
      return match && isTypedControl(summary, type)
        ? { index: Number(match[1]), summary: summary.trim() }
        : null
    })
    .filter((candidate): candidate is IndexedTarget => candidate !== null)

  return candidates.length === 1 ? candidates[0] ?? null : null
}

async function typedTarget(
  agent: any,
  requestedIndex: number,
  type: TypedControl
): Promise<IndexedTarget | null> {
  const state = await agent.pageController.getBrowserState()
  return resolveTypedTarget(String(state.content ?? ''), requestedIndex, type)
}

function unavailable(type: TypedControl): string {
  return `⚠️ The requested ${type} control is not uniquely available in the current DOM. Inspect or scroll the page before retrying.`
}

function valueCategory(text: string): string {
  if (/^[^\s@]+@[^\s@]+\.[^\s@]+$/.test(text)) return 'email'
  if (/^\+?[0-9 ()-]{7,}$/.test(text)) return 'phone-or-number'
  if (/^\d{4}-\d{2}-\d{2}$/.test(text)) return 'date'
  return 'ordinary-text'
}

async function authorize(
  actionName: string,
  summary: string,
  options: { elementIndex?: number; fieldLabel?: string; valueCategory?: string } = {}
): Promise<AuthorizationResponse> {
  const payload = await sendNative('AUTHORIZE_ACTION', {
    actionName,
    summary,
    elementIndex: options.elementIndex,
    fieldLabel: options.fieldLabel,
    valueCategory: options.valueCategory
  })
  return JSON.parse(payload) as AuthorizationResponse
}

function rejected(auth: AuthorizationResponse): string {
  return auth.requiresUserTakeover
    ? `⏸ User takeover required: ${auth.message}`
    : `⛔ Action blocked: ${auth.message}`
}

export function createGuardedTools(): Record<string, PageAgentTool | null> {
  return {
    execute_javascript: null,

    click_element_by_index: tool({
      description: 'Click an indexed element after UnoOne native safety authorization',
      inputSchema: z.object({ index: z.number().int().min(0) }),
      execute: async function (input) {
        const summary = await elementSummary(this, input.index)
        const auth = await authorize('click_element_by_index', summary, {
          elementIndex: input.index,
          fieldLabel: summary
        })
        if (!auth.allowed) return rejected(auth)
        return (await this.pageController.clickElement(input.index)).message
      }
    }),

    input_text: tool({
      description: 'Enter ordinary text into an indexed field after native safety authorization',
      inputSchema: z.object({ index: z.number().int().min(0), text: z.string() }),
      execute: async function (input) {
        const summary = await elementSummary(this, input.index)
        const auth = await authorize('input_text', summary, {
          elementIndex: input.index,
          fieldLabel: summary,
          valueCategory: valueCategory(input.text)
        })
        if (!auth.allowed) return rejected(auth)
        return (await this.pageController.inputText(input.index, input.text)).message
      }
    }),

    select_dropdown_option: tool({
      description: 'Select a dropdown option after native safety authorization',
      inputSchema: z.object({ index: z.number().int().min(0), text: z.string() }),
      execute: async function (input) {
        const target = await typedTarget(this, input.index, 'select')
        if (!target) return unavailable('select')
        const auth = await authorize('select_dropdown_option', target.summary, {
          elementIndex: target.index,
          fieldLabel: target.summary
        })
        if (!auth.allowed) return rejected(auth)
        return (await this.pageController.selectOption(target.index, input.text)).message
      }
    }),

    toggle_checkbox: tool({
      description: 'Toggle an indexed checkbox after native safety authorization',
      inputSchema: z.object({ index: z.number().int().min(0) }),
      execute: async function (input) {
        const target = await typedTarget(this, input.index, 'checkbox')
        if (!target) return unavailable('checkbox')
        const auth = await authorize('toggle_checkbox', target.summary, {
          elementIndex: target.index,
          fieldLabel: target.summary
        })
        if (!auth.allowed) return rejected(auth)
        return (await this.pageController.clickElement(target.index)).message
      }
    }),

    choose_radio: tool({
      description: 'Choose an indexed radio option after native safety authorization',
      inputSchema: z.object({ index: z.number().int().min(0) }),
      execute: async function (input) {
        const target = await typedTarget(this, input.index, 'radio')
        if (!target) return unavailable('radio')
        const auth = await authorize('choose_radio', target.summary, {
          elementIndex: target.index,
          fieldLabel: target.summary
        })
        if (!auth.allowed) return rejected(auth)
        return (await this.pageController.clickElement(target.index)).message
      }
    }),

    pick_date: tool({
      description: 'Enter an ISO date into an indexed date field after native safety authorization',
      inputSchema: z.object({ index: z.number().int().min(0), date: z.string() }),
      execute: async function (input) {
        const target = await typedTarget(this, input.index, 'date')
        if (!target) return unavailable('date')
        const auth = await authorize('pick_date', target.summary, {
          elementIndex: target.index,
          fieldLabel: target.summary,
          valueCategory: 'date'
        })
        if (!auth.allowed) return rejected(auth)
        return (await this.pageController.inputText(target.index, input.date)).message
      }
    }),

    submit_form: tool({
      description: 'Submit a form only after explicit UnoOne confirmation',
      inputSchema: z.object({ index: z.number().int().min(0), purpose: z.string() }),
      execute: async function (input) {
        const summary = `${await elementSummary(this, input.index)}; purpose: ${input.purpose}`
        const auth = await authorize('submit_form', summary, {
          elementIndex: input.index,
          fieldLabel: summary
        })
        if (!auth.allowed) return rejected(auth)
        return (await this.pageController.clickElement(input.index)).message
      }
    }),

    upload_file: tool({
      description: 'Open the Android file picker for the user to choose a local file; PageAgent never reads arbitrary device files',
      inputSchema: z.object({ index: z.number().int().min(0), purpose: z.string() }),
      execute: async function (input) {
        const summary = `${await elementSummary(this, input.index)}; upload purpose: ${input.purpose}`
        const auth = await authorize('upload_file', summary, {
          elementIndex: input.index,
          fieldLabel: summary
        })
        if (!auth.allowed) return rejected(auth)
        // Clicking the real <input type="file"> is what asks Android WebView to invoke
        // WebChromeClient.onShowFileChooser. The previous implementation only displayed a native
        // "takeover complete" dialog and never clicked the element, so no chooser could be opened
        // and the upload always stalled after approval.
        return (await this.pageController.clickElement(input.index)).message
      }
    }),

    scroll: tool({
      description: 'Scroll vertically after UnoOne native read-only authorization',
      inputSchema: z.object({
        down: z.boolean().default(true),
        num_pages: z.number().min(0).max(10).optional().default(0.1),
        pixels: z.number().int().min(0).optional(),
        index: z.number().int().min(0).optional()
      }),
      execute: async function (input) {
        const summary = input.index === undefined
          ? 'scroll the current page vertically'
          : `scroll container index ${input.index} vertically`
        const auth = await authorize('scroll', summary, { elementIndex: input.index })
        if (!auth.allowed) return rejected(auth)
        return (await this.pageController.scroll({ ...input, numPages: input.num_pages })).message
      }
    }),

    scroll_horizontally: tool({
      description: 'Scroll horizontally after UnoOne native read-only authorization',
      inputSchema: z.object({
        right: z.boolean().default(true),
        pixels: z.number().int().min(0),
        index: z.number().int().min(0).optional()
      }),
      execute: async function (input) {
        const summary = input.index === undefined
          ? 'scroll the current page horizontally'
          : `scroll container index ${input.index} horizontally`
        const auth = await authorize('scroll_horizontally', summary, { elementIndex: input.index })
        if (!auth.allowed) return rejected(auth)
        return (await this.pageController.scrollHorizontally(input)).message
      }
    })
  }
}
