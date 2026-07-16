import { describe, expect, it } from 'vitest'

import { indexedElementSummary } from '../src/guarded-tools'

describe('indexedElementSummary', () => {
  it('does not confuse index 1 with index 10', () => {
    const content = '[10]<input name="bankAccount">\n[1]<input name="firstName">'
    expect(indexedElementSummary(content, 1)).toContain('firstName')
    expect(indexedElementSummary(content, 1)).not.toContain('bankAccount')
  })

  it('fails closed to a generic summary when the index is missing', () => {
    expect(indexedElementSummary('[10]<button>Pay</button>', 1)).toBe('interactive element index 1')
  })
})
