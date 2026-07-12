export const INSTALLER_VERSION = 1
export const MAX_CLOCK_SKEW_MS = 10 * 60 * 1000
export const MAX_STABLE_CATALOG_AGE_MS = 180 * 24 * 60 * 60 * 1000
export const MAX_BETA_CATALOG_AGE_MS = 60 * 24 * 60 * 60 * 1000

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

export interface CatalogReplayState {
  version: number
  generatedAt: string
  keyId: string
}

export interface LoadedCatalog {
  envelope: SignedCatalogEnvelope
  verified: boolean
  verificationMessage: string
  replayState: CatalogReplayState
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
  if (!value || typeof value !== 'object' || Array.isArray(value)) {
    throw new Error('Catalogue is not an object')
  }
  const envelope = value as Partial<SignedCatalogEnvelope>
  if (envelope.schemaVersion !== 1) throw new Error('Unsupported catalogue schema version')
  if (envelope.signatureAlgorithm !== 'Ed25519') throw new Error('Unsupported signature algorithm')
  if (!envelope.keyId || !/^[a-zA-Z0-9._-]{3,80}$/.test(envelope.keyId)) {
    throw new Error('Catalogue keyId is invalid')
  }
  if (!envelope.signature || !/^[A-Za-z0-9_-]{64,}$/.test(envelope.signature)) {
    throw new Error('Catalogue signature is invalid')
  }
  if (!envelope.payload || typeof envelope.payload !== 'object' || Array.isArray(envelope.payload)) {
    throw new Error('Catalogue payload is missing')
  }

  validateCatalogPayload(envelope.payload as CatalogPayload)
  return envelope as SignedCatalogEnvelope
}

export function validateCatalogPayload(payload: CatalogPayload): void {
  if (!Number.isSafeInteger(payload.catalogVersion) || payload.catalogVersion < 1) {
    throw new Error('Invalid catalogVersion')
  }
  if (payload.channel !== 'stable' && payload.channel !== 'beta') {
    throw new Error('Invalid release channel')
  }
  if (!isIsoDateTime(payload.generatedAt)) throw new Error('Invalid generatedAt timestamp')
  if (
    payload.minimumInstallerVersion !== undefined &&
    (!Number.isSafeInteger(payload.minimumInstallerVersion) || payload.minimumInstallerVersion < 1)
  ) {
    throw new Error('Invalid minimumInstallerVersion')
  }
  if (!Array.isArray(payload.apps) || !Array.isArray(payload.models) || !Array.isArray(payload.languagePacks)) {
    throw new Error('Catalogue collections are malformed')
  }

  const artifactPaths = new Set<string>()
  const appVersionCodes = new Set<number>()
  for (const app of payload.apps) {
    validateAppRelease(app, payload.channel)
    if (appVersionCodes.has(app.versionCode)) throw new Error(`Duplicate app versionCode: ${app.versionCode}`)
    appVersionCodes.add(app.versionCode)
    registerArtifactPath(artifactPaths, app.artifact.path)
  }

  const modelIds = new Set<string>()
  for (const model of payload.models) {
    validateModelRelease(model)
    if (modelIds.has(model.id)) throw new Error(`Duplicate model id: ${model.id}`)
    modelIds.add(model.id)
    registerArtifactPath(artifactPaths, model.artifact.path)
  }

  const languageIds = new Set<string>()
  const languageCodes = new Set<string>()
  for (const pack of payload.languagePacks) {
    validateLanguagePack(pack, modelIds)
    if (languageIds.has(pack.id)) throw new Error(`Duplicate language pack id: ${pack.id}`)
    if (languageCodes.has(pack.languageCode)) {
      throw new Error(`Duplicate language code: ${pack.languageCode}`)
    }
    languageIds.add(pack.id)
    languageCodes.add(pack.languageCode)
  }
}

export function assertCatalogAcceptable(
  payload: CatalogPayload,
  options: {
    channel: 'stable' | 'beta'
    installerVersion: number
    nowMs?: number
    previousState?: CatalogReplayState | null
  }
): void {
  if (payload.channel !== options.channel) throw new Error('Catalogue channel mismatch')
  const minimumInstallerVersion = payload.minimumInstallerVersion ?? 1
  if (minimumInstallerVersion > options.installerVersion) {
    throw new Error(
      `Installer update required: catalogue needs version ${minimumInstallerVersion}, this portal is ${options.installerVersion}`
    )
  }

  const generatedAtMs = Date.parse(payload.generatedAt)
  const nowMs = options.nowMs ?? Date.now()
  if (generatedAtMs > nowMs + MAX_CLOCK_SKEW_MS) {
    throw new Error('Catalogue timestamp is too far in the future')
  }
  const maximumAge = payload.channel === 'stable' ? MAX_STABLE_CATALOG_AGE_MS : MAX_BETA_CATALOG_AGE_MS
  if (nowMs - generatedAtMs > maximumAge) {
    throw new Error('Catalogue is stale; downloads remain locked until a fresh signed catalogue is published')
  }

  const previous = options.previousState
  if (!previous) return
  if (payload.catalogVersion < previous.version) {
    throw new Error(`Catalogue rollback blocked: received v${payload.catalogVersion}, previously verified v${previous.version}`)
  }
  if (payload.catalogVersion === previous.version && payload.generatedAt !== previous.generatedAt) {
    throw new Error('Catalogue equivocation blocked: the same version has a different timestamp')
  }
  if (payload.catalogVersion > previous.version && generatedAtMs < Date.parse(previous.generatedAt)) {
    throw new Error('Catalogue rollback blocked: newer version has an older generation timestamp')
  }
}

export async function loadSignedCatalog(options: {
  apiBase: string
  channel: 'stable' | 'beta'
  publicKeySpkiBase64: string
  allowUnsignedDevelopment: boolean
  installerVersion?: number
  previousState?: CatalogReplayState | null
  nowMs?: number
}): Promise<LoadedCatalog> {
  const apiBase = normalizeApiBase(options.apiBase)
  const response = await fetch(`${apiBase}/v1/catalog/${options.channel}.json`, {
    cache: 'no-store',
    headers: { accept: 'application/json' }
  })
  if (!response.ok) throw new Error(`Catalogue request failed with HTTP ${response.status}`)
  if (!response.headers.get('content-type')?.toLowerCase().includes('application/json')) {
    throw new Error('Catalogue response has an invalid content type')
  }

  const envelope = validateCatalogEnvelope(await response.json())
  const installerVersion = options.installerVersion ?? INSTALLER_VERSION

  let verified = false
  let verificationMessage = 'Unsigned development mode — do not use for public distribution'
  if (!options.publicKeySpkiBase64.trim()) {
    if (!options.allowUnsignedDevelopment) {
      throw new Error('Production catalogue public key is not configured; downloads are locked')
    }
  } else {
    verified = await verifyCatalogSignature(envelope, options.publicKeySpkiBase64)
    if (!verified) throw new Error('Catalogue signature verification failed')
    verificationMessage = `Verified Ed25519 catalogue (${envelope.keyId})`
  }

  assertCatalogAcceptable(envelope.payload, {
    channel: options.channel,
    installerVersion,
    nowMs: options.nowMs,
    previousState: verified ? options.previousState : null
  })

  return {
    envelope,
    verified,
    verificationMessage,
    replayState: {
      version: envelope.payload.catalogVersion,
      generatedAt: envelope.payload.generatedAt,
      keyId: envelope.keyId
    }
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
  validateArtifactPath(artifactPath)
  const encodedPath = artifactPath
    .split('/')
    .map((segment) => encodeURIComponent(segment))
    .join('/')
  return `${normalizeApiBase(apiBase)}/v1/artifacts/${encodedPath}`
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

function validateAppRelease(app: AppRelease, channel: 'stable' | 'beta'): void {
  if (app.id !== 'unoone-android' || app.platform !== 'android') throw new Error('Invalid Android release identity')
  if (!app.versionName || typeof app.versionName !== 'string') throw new Error('Invalid Android versionName')
  if (!Number.isSafeInteger(app.versionCode) || app.versionCode < 1) throw new Error('Invalid Android versionCode')
  if (!isIsoDate(app.releaseDate)) throw new Error('Invalid Android releaseDate')
  if (!Number.isSafeInteger(app.minimumAndroidApi) || app.minimumAndroidApi < 28) {
    throw new Error('Invalid minimum Android API')
  }
  validateArtifact(app.artifact)
  if (!app.artifact.path.startsWith(`apk/${channel}/`)) {
    throw new Error(`Android artifact path does not match ${channel} channel`)
  }
  if (app.artifact.mimeType !== 'application/vnd.android.package-archive') {
    throw new Error('Android artifact MIME type is invalid')
  }
}

function validateModelRelease(model: ModelRelease): void {
  if (!/^[a-z0-9][a-z0-9._-]+$/.test(model.id)) throw new Error(`Invalid model id: ${model.id}`)
  if (!model.version || typeof model.version !== 'string') throw new Error(`Invalid model version: ${model.id}`)
  if (!['litertlm', 'onnx', 'sherpa-onnx', 'tflite'].includes(model.runtime)) {
    throw new Error(`Invalid model runtime: ${model.id}`)
  }
  if (
    !['integrity-verified', 'load-tested', 'tool-tested', 'device-qualified', 'production-approved'].includes(
      model.qualificationStatus
    )
  ) {
    throw new Error(`Invalid model qualification status: ${model.id}`)
  }
  if (!Number.isSafeInteger(model.minimumRamMb) || model.minimumRamMb < 512) {
    throw new Error(`Invalid minimum RAM: ${model.id}`)
  }
  if (
    model.recommendedRamMb !== undefined &&
    (!Number.isSafeInteger(model.recommendedRamMb) || model.recommendedRamMb < model.minimumRamMb)
  ) {
    throw new Error(`Invalid recommended RAM: ${model.id}`)
  }
  validateArtifact(model.artifact)
}

function validateLanguagePack(pack: LanguagePackRelease, modelIds: Set<string>): void {
  if (!/^[a-zA-Z0-9._-]+$/.test(pack.id)) throw new Error(`Invalid language pack id: ${pack.id}`)
  if (!/^[a-z]{2,3}-[A-Z]{2}$/.test(pack.languageCode)) {
    throw new Error(`Invalid language code: ${pack.languageCode}`)
  }
  if (!pack.displayName?.trim() || !pack.nativeName?.trim() || !pack.version?.trim()) {
    throw new Error(`Incomplete language pack metadata: ${pack.id}`)
  }
  if (!['planned', 'baseline', 'beta', 'stable', 'deprecated'].includes(pack.status)) {
    throw new Error(`Invalid language pack status: ${pack.id}`)
  }
  if (!Array.isArray(pack.requiredModelIds) || new Set(pack.requiredModelIds).size !== pack.requiredModelIds.length) {
    throw new Error(`Invalid language model dependencies: ${pack.id}`)
  }
  for (const modelId of pack.requiredModelIds) {
    if (!modelIds.has(modelId)) throw new Error(`Language pack ${pack.id} references unknown model ${modelId}`)
  }
  if (pack.downloadable && ['planned', 'deprecated'].includes(pack.status)) {
    throw new Error(`Language pack ${pack.id} cannot be downloadable while ${pack.status}`)
  }
  if (pack.downloadable && pack.requiredModelIds.length === 0) {
    throw new Error(`Downloadable language pack ${pack.id} has no model dependencies`)
  }
}

function validateArtifact(artifact: ArtifactRef | undefined): void {
  if (!artifact) throw new Error('Release artifact is missing')
  validateArtifactPath(artifact.path)
  if (!Number.isSafeInteger(artifact.sizeBytes) || artifact.sizeBytes < 1) {
    throw new Error(`Invalid artifact size: ${artifact.path}`)
  }
  if (!/^[a-f0-9]{64}$/.test(artifact.sha256)) {
    throw new Error(`Invalid artifact SHA-256: ${artifact.path}`)
  }
  if (!artifact.mimeType || /[\u0000-\u001f\u007f]/.test(artifact.mimeType)) {
    throw new Error(`Invalid artifact MIME type: ${artifact.path}`)
  }
}

function validateArtifactPath(path: string): void {
  if (!/^(apk|brain|speech|vision)\/[A-Za-z0-9._/-]+$/.test(path)) {
    throw new Error(`Invalid artifact path: ${path}`)
  }
  const segments = path.split('/')
  if (segments.some((segment) => !segment || segment === '.' || segment === '..')) {
    throw new Error(`Invalid artifact path segments: ${path}`)
  }
}

function registerArtifactPath(paths: Set<string>, path: string): void {
  if (paths.has(path)) throw new Error(`Duplicate artifact path: ${path}`)
  paths.add(path)
}

function normalizeApiBase(value: string): string {
  const url = new URL(value)
  const localDevelopment = ['localhost', '127.0.0.1'].includes(url.hostname)
  if (url.protocol !== 'https:' && !(localDevelopment && url.protocol === 'http:')) {
    throw new Error('Distribution API must use HTTPS')
  }
  if (url.username || url.password || url.search || url.hash) throw new Error('Distribution API URL is invalid')
  return url.toString().replace(/\/$/, '')
}

function isIsoDate(value: string): boolean {
  return /^\d{4}-\d{2}-\d{2}$/.test(value) && !Number.isNaN(Date.parse(`${value}T00:00:00Z`))
}

function isIsoDateTime(value: string): boolean {
  return typeof value === 'string' && !Number.isNaN(Date.parse(value)) && /T/.test(value)
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
