import { describe, expect, it } from 'vitest'

import { maskSensitivePageContent } from '../src/content-mask'

describe('maskSensitivePageContent', () => {
  it('masks password values, OTPs and payment-like secrets', () => {
    const input = [
      '<input type="password" value="secret123">',
      'OTP: 654321',
      'CVV: 987',
      'Card 4111111111111111',
      'Authorization: Bearer abc.def.ghi'
    ].join('\n')

    const output = maskSensitivePageContent(input)

    expect(output).not.toContain('secret123')
    expect(output).not.toContain('654321')
    expect(output).not.toContain('987')
    expect(output).not.toContain('4111111111111111')
    expect(output).not.toContain('abc.def.ghi')
    expect(output).toContain('[REDACTED]')
  })

  it('preserves ordinary form labels and non-sensitive values', () => {
    const input = '<input aria-label="First name" value="Reeturaj">'
    expect(maskSensitivePageContent(input)).toBe(input)
  })
})
