import { describe, expect, it } from 'vitest'

import { indexedElementSummary, resolveTypedTarget } from '../src/guarded-tools'

describe('indexedElementSummary', () => {
  it('does not confuse index 1 with index 10', () => {
    const content = '[10]<input name="bankAccount">\n[1]<input name="firstName">'
    expect(indexedElementSummary(content, 1)).toContain('firstName')
    expect(indexedElementSummary(content, 1)).not.toContain('bankAccount')
  })

  it('fails closed to a generic summary when the index is missing', () => {
    expect(indexedElementSummary('[10]<button>Pay</button>', 1)).toBe('interactive element index 1')
  })

  it('recognizes Page Agent new-element indexes', () => {
    expect(indexedElementSummary('*[9]<input id=updates type=checkbox />', 9))
      .toContain('id=updates')
  })
})

describe('resolveTypedTarget', () => {
  const form = [
    '[6]<label for=country>Country />',
    '[7]<select id=country>India />',
    '[8]<label>Receive product updates />',
    '*[9]<input id=updates type=checkbox checked=false />'
  ].join('\n')

  it('remaps a stale label index to the one current checkbox', () => {
    expect(resolveTypedTarget(form, 6, 'checkbox')).toEqual({
      index: 9,
      summary: '*[9]<input id=updates type=checkbox checked=false />'
    })
  })

  it('never guesses between multiple controls of the same type', () => {
    const ambiguous = `${form}\n[10]<input id=terms type=checkbox checked=false />`
    expect(resolveTypedTarget(ambiguous, 6, 'checkbox')).toBeNull()
  })

  it('keeps a current typed index unchanged', () => {
    expect(resolveTypedTarget(form, 7, 'select')?.index).toBe(7)
  })
})
