export const ALLOWED_ARTIFACT_PREFIXES = [
  'apk/',
  'brain/',
  'speech/',
  'vision/',
  'catalogue/'
] as const

export function parseAllowedOrigins(raw: string | undefined): Set<string> {
  return new Set(
    (raw ?? '')
      .split(',')
      .map((value) => value.trim())
      .filter((value) => /^https:\/\/[a-z0-9.-]+(?::\d+)?$/i.test(value))
      .map((value) => value.replace(/\/$/, '').toLowerCase())
  )
}

export function normalizeArtifactKey(rawPath: string): string | null {
  let decoded: string
  try {
    decoded = decodeURIComponent(rawPath)
  } catch {
    return null
  }

  const key = decoded.replace(/^\/+/, '')
  if (!key || key.includes('\\') || key.includes('\u0000')) return null
  if (key.split('/').some((segment) => segment === '' || segment === '.' || segment === '..')) return null
  if (!ALLOWED_ARTIFACT_PREFIXES.some((prefix) => key.startsWith(prefix))) return null
  return key
}

export function corsOrigin(requestOrigin: string | null, allowed: Set<string>): string | null {
  if (!requestOrigin) return null
  const normalized = requestOrigin.replace(/\/$/, '').toLowerCase()
  return allowed.has(normalized) ? requestOrigin : null
}
