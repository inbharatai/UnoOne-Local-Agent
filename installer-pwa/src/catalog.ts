export interface ArtifactRef {
  path: string
  sizeBytes: number
  sha256: string
  mimeType: string
}

export interface AppRelease {
  id: 'unoone-android'
  platform: 'android'
  versionName: string
  versionCode: number
  releaseDate: string
  minimumAndroidApi: number
  releaseNotes?: string
  artifact: ArtifactRef
}

export interface ModelRelease {
  id: string
  version: string
  runtime: 'litertlm' | 'onnx' | 'sherpa-onnx' | 'tflite'
  qualificationStatus:
    | 'integrity-verified'
    | 'load-tested'
    | 'tool-tested'
    | 'device-qualified'
    | 'production-approved'
  minimumRamMb: number
  recommendedRamMb?: number
  license?: string
  artifact: ArtifactRef
}

export interface LanguagePackRelease {
  id: string
  languageCode: string
  displayName: string
  nativeName: string
  version: string
  status: 'planned' | 'baseline' | 'beta' | 'stable' | 'deprecated'
  requiredModelIds: string[]
  downloadable?: boolean
  notes?: string
}

export interface CatalogPayload {
  catalogVersion: number
  channel: 'stable' | 'beta'
  generatedAt: string
  minimumInstallerVersion?: number
  apps: AppRelease[]
  models: ModelRelease[]
  languagePacks: LanguagePackRelease[]
}

export interface SignedCatalogEnvelope {
  schemaVersion: 1
  keyId: string
  signatureAlgorithm: 'Ed25519'
  payload: CatalogPayload
  signature: string
}

export interface LoadedCatalog {
  envelope: SignedCatalogEnvelope
  verified: boolean
  verificationMessage: string
}

export function canonicalJson(value: unknown): string {
  if (value === null || typeof value === 'boolean' || typeof value === 'string') {
    return JSON.stringify(value)
  }
  if (typeof value === 'number') {
    if (!Number.isSafeInteger(value)) throw new Error('Catalogue numbers must be safe integers')
    return String(value)
  }
  if (Array.isArray(value)) return `[${value.map(canonicalJson).join(',')}]`
  if (typeof value === 'object') {
    const entries = Object.entries(value as Record<string, unknown>)
      .filter(([, item]) => item !== undefined)
      .sort(([left], [right]) => left.localeCompare(right))
    return `{${entries
      .map(([key, item]) => `${JSON.stringify(key)}:${canonicalJson(item)}`)
      .join(',')}}`
  }
  throw new Error(`Unsupported catalogue value type: ${typeof value}`)
}

export function validateCatalogEnvelope(value: unknown): SignedCatalogEnvelope {
  if (!value || typeof value !== 'object') throw new Error('Catalogue is not an object')
  const envelope = value as Partial<SignedCatalogEnvelope>
  if (envelope.schemaVersion !== 1) throw new Error('Unsupported catalogue schema version')
  if (envelope.signatureAlgorithm !== 'Ed25519') throw new Error('Unsupported signature algorithm')
  if (!envelope.keyId || typeof envelope.keyId !== 'string') throw new Error('Catalogue keyId is missing')
  if (!envelope.signature || typeof envelope.signature !== 'string') throw new Error('Catalogue signature is missing')
  if (!envelope.payload || typeof envelope.payload !== 'object') throw new Error('Catalogue payload is missing')

  const payload = envelope.payload as CatalogPayload
  if (!Number.isSafeInteger(payload.catalogVersion) || payload.catalogVersion < 1) {
    throw new Error('Invalid catalogVersion')
  }
  if (payload.channel !== 'stable' && payload.channel !== 'beta') throw new Error('Invalid release channel')
  if (!Array.isArray(payload.apps) || !Array.isArray(payload.models) || !Array.isArray(payload.languagePacks)) {
    throw new Error('Catalogue collections are malformed')
  }

  for (const app of payload.apps) validateArtifact(app.artifact)
  for (const model of payload.models) validateArtifact(model.artifact)
  return envelope as SignedCatalogEnvelope
}

export async function loadSignedCatalog(options: {
  apiBase: string
  channel: 'stable' | 'beta'
  publicKeySpkiBase64: string
  allowUnsignedDevelopment: boolean
}): Promise<LoadedCatalog> {
  const apiBase = options.apiBase.replace(/\/$/, '')
  const response = await fetch(`${apiBase}/v1/catalog/${options.channel}.json`, {
    cache: 'no-store',
    headers: { accept: 'application/json' }
  })
  if (!response.ok) throw new Error(`Catalogue request failed with HTTP ${response.status}`)
  const envelope = validateCatalogEnvelope(await response.json())
  if (envelope.payload.channel !== options.channel) throw new Error('Catalogue channel mismatch')

  if (!options.publicKeySpkiBase64.trim()) {
    if (!options.allowUnsignedDevelopment) {
      throw new Error('Production catalogue public key is not configured; downloads are locked')
    }
    return {
      envelope,
      verified: false,
      verificationMessage: 'Unsigned development mode — do not use for public distribution'
    }
  }

  const verified = await verifyCatalogSignature(envelope, options.publicKeySpkiBase64)
  if (!verified) throw new Error('Catalogue signature verification failed')
  return {
    envelope,
    verified: true,
    verificationMessage: `Verified Ed25519 catalogue (${envelope.keyId})`
  }
}

export async function verifyCatalogSignature(
  envelope: SignedCatalogEnvelope,
  publicKeySpkiBase64: string
): Promise<boolean> {
  if (!globalThis.crypto?.subtle) throw new Error('Web Crypto is unavailable')
  const publicKey = await globalThis.crypto.subtle.importKey(
    'spki',
    decodeBase64(publicKeySpkiBase64),
    { name: 'Ed25519' },
    false,
    ['verify']
  )
  return globalThis.crypto.subtle.verify(
    { name: 'Ed25519' },
    publicKey,
    decodeBase64Url(envelope.signature),
    new TextEncoder().encode(canonicalJson(envelope.payload))
  )
}

export function artifactUrl(apiBase: string, artifactPath: string): string {
  const encodedPath = artifactPath
    .split('/')
    .map((segment) => encodeURIComponent(segment))
    .join('/')
  return `${apiBase.replace(/\/$/, '')}/v1/artifacts/${encodedPath}`
}

export async function sha256Hex(blob: Blob): Promise<string> {
  if (!globalThis.crypto?.subtle) throw new Error('Web Crypto is unavailable')
  const digest = await globalThis.crypto.subtle.digest('SHA-256', await blob.arrayBuffer())
  return [...new Uint8Array(digest)].map((byte) => byte.toString(16).padStart(2, '0')).join('')
}

export function formatBytes(value: number): string {
  if (!Number.isFinite(value) || value < 0) return 'Unknown size'
  const units = ['B', 'KB', 'MB', 'GB', 'TB']
  let size = value
  let unit = 0
  while (size >= 1024 && unit < units.length - 1) {
    size /= 1024
    unit += 1
  }
  return `${size >= 100 || unit === 0 ? size.toFixed(0) : size.toFixed(1)} ${units[unit]}`
}

function validateArtifact(artifact: ArtifactRef | undefined): void {
  if (!artifact) throw new Error('Release artifact is missing')
  if (!/^(apk|brain|speech|vision)\/[A-Za-z0-9._/-]+$/.test(artifact.path)) {
    throw new Error(`Invalid artifact path: ${artifact.path}`)
  }
  if (!Number.isSafeInteger(artifact.sizeBytes) || artifact.sizeBytes < 1) {
    throw new Error(`Invalid artifact size: ${artifact.path}`)
  }
  if (!/^[a-f0-9]{64}$/.test(artifact.sha256)) {
    throw new Error(`Invalid artifact SHA-256: ${artifact.path}`)
  }
}

function decodeBase64(value: string): ArrayBuffer {
  const normalized = value.replace(/\s+/g, '')
  const binary = atob(normalized)
  return Uint8Array.from(binary, (character) => character.charCodeAt(0)).buffer
}

function decodeBase64Url(value: string): ArrayBuffer {
  const normalized = value.replace(/-/g, '+').replace(/_/g, '/')
  const padding = '='.repeat((4 - (normalized.length % 4)) % 4)
  return decodeBase64(normalized + padding)
}
