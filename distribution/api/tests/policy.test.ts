import { describe, expect, it } from 'vitest'

import { corsOrigin, normalizeArtifactKey, parseAllowedOrigins } from '../src/policy'

describe('distribution policy', () => {
  it('accepts only configured HTTPS origins', () => {
    const allowed = parseAllowedOrigins(
      'https://unoone.inbharat.ai, https://www.inbharat.ai, http://unsafe.example'
    )

    expect(allowed.has('https://unoone.inbharat.ai')).toBe(true)
    expect(allowed.has('https://www.inbharat.ai')).toBe(true)
    expect(allowed.has('http://unsafe.example')).toBe(false)
    expect(corsOrigin('https://unoone.inbharat.ai', allowed)).toBe('https://unoone.inbharat.ai')
    expect(corsOrigin('https://attacker.example', allowed)).toBeNull()
  })

  it('permits only explicit release prefixes', () => {
    expect(normalizeArtifactKey('apk/stable/unoone.apk')).toBe('apk/stable/unoone.apk')
    expect(normalizeArtifactKey('brain/gemma-4-e2b/model.litertlm')).toBe(
      'brain/gemma-4-e2b/model.litertlm'
    )
    expect(normalizeArtifactKey('speech/languages/as-IN/model.onnx')).toBe(
      'speech/languages/as-IN/model.onnx'
    )
  })

  it('blocks traversal, empty segments and unrelated bucket paths', () => {
    expect(normalizeArtifactKey('../secret')).toBeNull()
    expect(normalizeArtifactKey('apk//unoone.apk')).toBeNull()
    expect(normalizeArtifactKey('private/secrets.txt')).toBeNull()
    expect(normalizeArtifactKey('apk/%2e%2e/secret')).toBeNull()
    expect(normalizeArtifactKey('apk\\secret')).toBeNull()
  })
})
