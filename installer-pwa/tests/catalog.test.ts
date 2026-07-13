import { generateKeyPairSync, sign as nodeSign } from 'node:crypto'
import { describe, expect, it } from 'vitest'

import {
  type CatalogPayload,
  type SignedCatalogEnvelope,
  artifactUrl,
  assertCatalogAcceptable,
  canonicalJson,
  formatBytes,
  validateCatalogEnvelope,
  validateCatalogPayload,
  verifyCatalogSignature
} from '../src/catalog'

const NOW_MS = Date.parse('2026-07-12T12:00:00Z')

function payload(overrides: Partial<CatalogPayload> = {}): CatalogPayload {
  return {
    catalogVersion: 1,
    channel: 'stable',
    generatedAt: '2026-07-12T00:00:00Z',
    minimumInstallerVersion: 1,
    apps: [
      {
        id: 'unoone-android',
        platform: 'android',
        versionName: '0.4.0-alpha-v2',
        versionCode: 4,
        releaseDate: '2026-07-12',
        minimumAndroidApi: 28,
        artifact: {
          path: 'apk/stable/unoone-v0.4.0-alpha-v2.apk',
          sizeBytes: 123456,
          sha256: 'a'.repeat(64),
          mimeType: 'application/vnd.android.package-archive'
        }
      }
    ],
    models: [
      {
        id: 'gemma-4-e2b',
        version: '6e5c4f1',
        runtime: 'litertlm',
        qualificationStatus: 'integrity-verified',
        minimumRamMb: 6144,
        recommendedRamMb: 8192,
        license: 'Apache-2.0',
        artifact: {
          path: 'brain/gemma-4-e2b/6e5c4f1/gemma-4-E2B-it.litertlm',
          sizeBytes: 2588147712,
          sha256: 'b'.repeat(64),
          mimeType: 'application/octet-stream'
        }
      }
    ],
    languagePacks: [
      {
        id: 'en-IN-base',
        languageCode: 'en-IN',
        displayName: 'English',
        nativeName: 'English',
        version: '1.0.0-baseline',
        status: 'baseline',
        requiredModelIds: ['gemma-4-e2b'],
        downloadable: true
      }
    ],
    ...overrides
  }
}

function envelope(catalogue = payload()): SignedCatalogEnvelope {
  return {
    schemaVersion: 1,
    keyId: 'test-key',
    signatureAlgorithm: 'Ed25519',
    payload: catalogue,
    signature: 'x'.repeat(86)
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
    const signedEnvelope: SignedCatalogEnvelope = {
      schemaVersion: 1,
      keyId: 'test-key',
      signatureAlgorithm: 'Ed25519',
      payload: signedPayload,
      signature
    }
    const publicSpki = publicKey.export({ type: 'spki', format: 'der' }).toString('base64')

    expect(await verifyCatalogSignature(signedEnvelope, publicSpki)).toBe(true)
    signedEnvelope.payload.catalogVersion = 2
    expect(await verifyCatalogSignature(signedEnvelope, publicSpki)).toBe(false)
  })

  it('rejects malformed artifact integrity metadata', () => {
    const malformed = envelope()
    malformed.payload.apps[0]!.artifact.sha256 = 'not-a-checksum'
    expect(() => validateCatalogEnvelope(malformed)).toThrow(/SHA-256/)
  })

  it('rejects unknown language dependencies and planned downloadable packs', () => {
    const unknownDependency = payload()
    unknownDependency.languagePacks[0]!.requiredModelIds = ['missing-model']
    expect(() => validateCatalogPayload(unknownDependency)).toThrow(/unknown model/)

    const plannedDownload = payload()
    plannedDownload.languagePacks[0]!.status = 'planned'
    expect(() => validateCatalogPayload(plannedDownload)).toThrow(/cannot be downloadable/)
  })

  it('rejects channel mismatches and installer version incompatibility', () => {
    expect(() =>
      assertCatalogAcceptable(payload(), {
        channel: 'beta',
        installerVersion: 1,
        nowMs: NOW_MS
      })
    ).toThrow(/channel mismatch/)

    expect(() =>
      assertCatalogAcceptable(payload({ minimumInstallerVersion: 2 }), {
        channel: 'stable',
        installerVersion: 1,
        nowMs: NOW_MS
      })
    ).toThrow(/Installer update required/)
  })

  it('blocks stale, future, rollback and equivocated catalogues', () => {
    expect(() =>
      assertCatalogAcceptable(payload({ generatedAt: '2025-01-01T00:00:00Z' }), {
        channel: 'stable',
        installerVersion: 1,
        nowMs: NOW_MS
      })
    ).toThrow(/stale/)

    expect(() =>
      assertCatalogAcceptable(payload({ generatedAt: '2026-07-13T00:00:00Z' }), {
        channel: 'stable',
        installerVersion: 1,
        nowMs: NOW_MS
      })
    ).toThrow(/future/)

    expect(() =>
      assertCatalogAcceptable(payload({ catalogVersion: 1 }), {
        channel: 'stable',
        installerVersion: 1,
        nowMs: NOW_MS,
        previousState: {
          version: 2,
          generatedAt: '2026-07-11T00:00:00Z',
          keyId: 'test-key'
        }
      })
    ).toThrow(/rollback/)

    expect(() =>
      assertCatalogAcceptable(payload({ generatedAt: '2026-07-12T01:00:00Z' }), {
        channel: 'stable',
        installerVersion: 1,
        nowMs: NOW_MS,
        previousState: {
          version: 1,
          generatedAt: '2026-07-12T00:00:00Z',
          keyId: 'test-key'
        }
      })
    ).toThrow(/equivocation/)
  })

  it('builds encoded artifact URLs and readable sizes', () => {
    expect(artifactUrl('https://models.example/', 'apk/stable/unoone-v0.4.0.apk')).toBe(
      'https://models.example/v1/artifacts/apk/stable/unoone-v0.4.0.apk'
    )
    expect(formatBytes(2588147712)).toBe('2.4 GB')
  })

  it('rejects unsafe artifact path traversal', () => {
    expect(() => artifactUrl('https://models.example/', 'apk/stable/../secret.apk')).toThrow(
      /Invalid artifact path/
    )
  })
})
