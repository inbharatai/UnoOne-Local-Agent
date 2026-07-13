export function canonicalJson(value) {
  if (value === null || typeof value === 'boolean' || typeof value === 'string') {
    return JSON.stringify(value)
  }
  if (typeof value === 'number') {
    if (!Number.isSafeInteger(value)) throw new Error('Catalogue numbers must be safe integers')
    return String(value)
  }
  if (Array.isArray(value)) return `[${value.map(canonicalJson).join(',')}]`
  if (typeof value === 'object') {
    const entries = Object.entries(value)
      .filter(([, item]) => item !== undefined)
      .sort(([left], [right]) => left.localeCompare(right))
    return `{${entries.map(([key, item]) => `${JSON.stringify(key)}:${canonicalJson(item)}`).join(',')}}`
  }
  throw new Error(`Unsupported catalogue value type: ${typeof value}`)
}

export function validateReleasePayload(payload) {
  assertObject(payload, 'Catalogue payload')
  assertExactKeys(
    payload,
    ['catalogVersion', 'channel', 'generatedAt', 'minimumInstallerVersion', 'apps', 'models', 'languagePacks'],
    'Catalogue payload'
  )
  assertPositiveInteger(payload.catalogVersion, 'catalogVersion')
  if (!['stable', 'beta'].includes(payload.channel)) throw new Error('Invalid release channel')
  if (typeof payload.generatedAt !== 'string' || Number.isNaN(Date.parse(payload.generatedAt)) || !payload.generatedAt.includes('T')) {
    throw new Error('Invalid generatedAt timestamp')
  }
  assertPositiveInteger(payload.minimumInstallerVersion, 'minimumInstallerVersion')
  if (!Array.isArray(payload.apps) || !Array.isArray(payload.models) || !Array.isArray(payload.languagePacks)) {
    throw new Error('Catalogue collections must be arrays')
  }

  const artifactPaths = new Set()
  const appVersionCodes = new Set()
  for (const app of payload.apps) {
    assertObject(app, 'App release')
    assertExactKeys(
      app,
      ['id', 'platform', 'versionName', 'versionCode', 'releaseDate', 'minimumAndroidApi', 'releaseNotes', 'artifact'],
      'App release',
      ['releaseNotes']
    )
    if (app.id !== 'unoone-android' || app.platform !== 'android') throw new Error('Invalid Android release identity')
    if (typeof app.versionName !== 'string' || !app.versionName.trim()) throw new Error('Invalid Android versionName')
    assertPositiveInteger(app.versionCode, 'Android versionCode')
    if (appVersionCodes.has(app.versionCode)) throw new Error(`Duplicate Android versionCode: ${app.versionCode}`)
    appVersionCodes.add(app.versionCode)
    if (typeof app.releaseDate !== 'string' || !/^\d{4}-\d{2}-\d{2}$/.test(app.releaseDate)) {
      throw new Error('Invalid Android releaseDate')
    }
    if (!Number.isSafeInteger(app.minimumAndroidApi) || app.minimumAndroidApi < 28) {
      throw new Error('Invalid minimum Android API')
    }
    validateArtifact(app.artifact, artifactPaths)
    if (!app.artifact.path.startsWith(`apk/${payload.channel}/`)) {
      throw new Error(`Android artifact path does not match ${payload.channel} channel`)
    }
    if (app.artifact.mimeType !== 'application/vnd.android.package-archive') {
      throw new Error('Invalid Android APK MIME type')
    }
  }

  const modelIds = new Set()
  for (const model of payload.models) {
    assertObject(model, 'Model release')
    assertExactKeys(
      model,
      ['id', 'version', 'runtime', 'qualificationStatus', 'minimumRamMb', 'recommendedRamMb', 'license', 'artifact'],
      'Model release',
      ['recommendedRamMb', 'license']
    )
    if (typeof model.id !== 'string' || !/^[a-z0-9][a-z0-9._-]+$/.test(model.id)) {
      throw new Error(`Invalid model id: ${String(model.id)}`)
    }
    if (modelIds.has(model.id)) throw new Error(`Duplicate model id: ${model.id}`)
    modelIds.add(model.id)
    if (typeof model.version !== 'string' || !model.version.trim()) throw new Error(`Invalid model version: ${model.id}`)
    if (!['litertlm', 'onnx', 'sherpa-onnx', 'tflite'].includes(model.runtime)) {
      throw new Error(`Invalid model runtime: ${model.id}`)
    }
    if (!['integrity-verified', 'load-tested', 'tool-tested', 'device-qualified', 'production-approved'].includes(model.qualificationStatus)) {
      throw new Error(`Invalid model qualification status: ${model.id}`)
    }
    if (!Number.isSafeInteger(model.minimumRamMb) || model.minimumRamMb < 512) {
      throw new Error(`Invalid minimum RAM: ${model.id}`)
    }
    if (model.recommendedRamMb !== undefined && (!Number.isSafeInteger(model.recommendedRamMb) || model.recommendedRamMb < model.minimumRamMb)) {
      throw new Error(`Invalid recommended RAM: ${model.id}`)
    }
    if (model.license !== undefined && (typeof model.license !== 'string' || !model.license.trim())) {
      throw new Error(`Invalid model licence: ${model.id}`)
    }
    validateArtifact(model.artifact, artifactPaths)
  }

  const packIds = new Set()
  const languageCodes = new Set()
  for (const pack of payload.languagePacks) {
    assertObject(pack, 'Language pack')
    assertExactKeys(
      pack,
      ['id', 'languageCode', 'displayName', 'nativeName', 'version', 'status', 'requiredModelIds', 'downloadable', 'notes'],
      'Language pack',
      ['downloadable', 'notes']
    )
    if (typeof pack.id !== 'string' || !/^[A-Za-z0-9._-]+$/.test(pack.id)) throw new Error('Invalid language pack id')
    if (packIds.has(pack.id)) throw new Error(`Duplicate language pack id: ${pack.id}`)
    packIds.add(pack.id)
    if (typeof pack.languageCode !== 'string' || !/^[a-z]{2,3}-[A-Z]{2}$/.test(pack.languageCode)) {
      throw new Error(`Invalid language code: ${String(pack.languageCode)}`)
    }
    if (languageCodes.has(pack.languageCode)) throw new Error(`Duplicate language code: ${pack.languageCode}`)
    languageCodes.add(pack.languageCode)
    for (const field of ['displayName', 'nativeName', 'version']) {
      if (typeof pack[field] !== 'string' || !pack[field].trim()) throw new Error(`Incomplete language pack metadata: ${pack.id}`)
    }
    if (!['planned', 'baseline', 'beta', 'stable', 'deprecated'].includes(pack.status)) {
      throw new Error(`Invalid language pack status: ${pack.id}`)
    }
    if (!Array.isArray(pack.requiredModelIds) || new Set(pack.requiredModelIds).size !== pack.requiredModelIds.length) {
      throw new Error(`Invalid language dependencies: ${pack.id}`)
    }
    for (const modelId of pack.requiredModelIds) {
      if (!modelIds.has(modelId)) throw new Error(`Language pack ${pack.id} references unknown model ${modelId}`)
    }
    if (pack.downloadable === true && !['baseline', 'beta', 'stable'].includes(pack.status)) {
      throw new Error(`Language pack ${pack.id} cannot be downloadable while ${pack.status}`)
    }
    if (pack.downloadable === true && pack.requiredModelIds.length === 0) {
      throw new Error(`Downloadable language pack ${pack.id} has no model dependencies`)
    }
  }

  return payload
}

function validateArtifact(artifact, paths) {
  assertObject(artifact, 'Artifact')
  assertExactKeys(artifact, ['path', 'sizeBytes', 'sha256', 'mimeType'], 'Artifact')
  if (typeof artifact.path !== 'string' || !/^(apk|brain|speech|vision)\/[A-Za-z0-9._/-]+$/.test(artifact.path)) {
    throw new Error(`Invalid artifact path: ${String(artifact.path)}`)
  }
  const segments = artifact.path.split('/')
  if (segments.some((segment) => !segment || segment === '.' || segment === '..')) {
    throw new Error(`Invalid artifact path segments: ${artifact.path}`)
  }
  if (paths.has(artifact.path)) throw new Error(`Duplicate artifact path: ${artifact.path}`)
  paths.add(artifact.path)
  assertPositiveInteger(artifact.sizeBytes, `Artifact size: ${artifact.path}`)
  if (typeof artifact.sha256 !== 'string' || !/^[a-f0-9]{64}$/.test(artifact.sha256)) {
    throw new Error(`Invalid artifact SHA-256: ${artifact.path}`)
  }
  if (typeof artifact.mimeType !== 'string' || !artifact.mimeType.trim() || /[\u0000-\u001f\u007f]/.test(artifact.mimeType)) {
    throw new Error(`Invalid artifact MIME type: ${artifact.path}`)
  }
}

function assertObject(value, label) {
  if (!value || typeof value !== 'object' || Array.isArray(value)) throw new Error(`${label} must be an object`)
}

function assertPositiveInteger(value, label) {
  if (!Number.isSafeInteger(value) || value < 1) throw new Error(`${label} must be a positive safe integer`)
}

function assertExactKeys(value, allowed, label, optional = []) {
  const allowedSet = new Set(allowed)
  for (const key of Object.keys(value)) {
    if (!allowedSet.has(key)) throw new Error(`${label} contains unsupported field: ${key}`)
  }
  const optionalSet = new Set(optional)
  for (const key of allowed) {
    if (!optionalSet.has(key) && !(key in value)) throw new Error(`${label} is missing required field: ${key}`)
  }
}
