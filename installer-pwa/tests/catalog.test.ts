import { generateKeyPairSync, sign as nodeSign } from 'node:crypto'
import { describe, expect, it } from 'vitest'

import {
  type CatalogPayload,
  type SignedCatalogEnvelope,
  artifactUrl,
  canonicalJson,
  formatBytes,
  validateCatalogEnvelope,
  verifyCatalogSignature
} from '../src/catalog'

function payload(): CatalogPayload {
  return {
    catalogVersion: 1,
    channel: 'stable',
    generatedAt: '2026-07-12T00:00:00Z',
    apps: [
      {
        id: 'unoone-android',
        platform: 'android',
        versionName: '0.4.0-alpha-v2',
        versionCode: 4,
        releaseDate: '2026-07-12',
        minimumAndroidApi: 28,
        artifact: {
          path: 'apk/stable/unoone-v0.4.0.apk',
          sizeBytes: 123456,
          sha256: 'a'.repeat(64),
          mimeType: 'application/vnd.android.package-archive'
        }
      }
    ],
    models: [],
    languagePacks: []
  }
}

describe('signed catalogue', () => {
  it('uses deterministic recursive key ordering', () => {
    expect(canonicalJson({ z: 2, a: { y: 1, x: [3, 2, 1] } })).toBe(
      '{"a":{"x":[3,2,1],"y":1},"z":2}'
    )
  })

  it('verifies an Ed25519 signature and rejects a tampered payload', async () => {
    const { privateKey, publicKey } = generateKeyPairSync('ed25519')
    const signedPayload = payload()
    const signature = nodeSign(
      null,
      Buffer.from(canonicalJson(signedPayload), 'utf8'),
      privateKey
    ).toString('base64url')
    const envelope: SignedCatalogEnvelope = {
      schemaVersion: 1,
      keyId: 'test-key',
      signatureAlgorithm: 'Ed25519',
      payload: signedPayload,
      signature
    }
    const publicSpki = publicKey.export({ type: 'spki', format: 'der' }).toString('base64')

    expect(await verifyCatalogSignature(envelope, publicSpki)).toBe(true)
    envelope.payload.catalogVersion = 2
    expect(await verifyCatalogSignature(envelope, publicSpki)).toBe(false)
  })

  it('rejects malformed artifact integrity metadata', () => {
    const malformed = {
      schemaVersion: 1,
      keyId: 'test-key',
      signatureAlgorithm: 'Ed25519',
      signature: 'x'.repeat(86),
      payload: payload()
    }
    malformed.payload.apps[0]!.artifact.sha256 = 'not-a-checksum'
    expect(() => validateCatalogEnvelope(malformed)).toThrow(/SHA-256/)
  })

  it('builds encoded artifact URLs and readable sizes', () => {
    expect(artifactUrl('https://models.example/', 'apk/stable/UnoOne 4.apk')).toBe(
      'https://models.example/v1/artifacts/apk/stable/UnoOne%204.apk'
    )
    expect(formatBytes(2588147712)).toBe('2.4 GB')
  })
})
