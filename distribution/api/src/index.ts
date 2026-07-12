import { corsOrigin, normalizeArtifactKey, parseAllowedOrigins } from './policy'

export interface Env {
  RELEASES: R2Bucket
  ALLOWED_ORIGINS?: string
}

const JSON_HEADERS = {
  'content-type': 'application/json; charset=utf-8',
  'x-content-type-options': 'nosniff',
  'referrer-policy': 'no-referrer'
}

export default {
  async fetch(request: Request, env: Env): Promise<Response> {
    const url = new URL(request.url)
    const allowedOrigins = parseAllowedOrigins(env.ALLOWED_ORIGINS)
    const requestOrigin = request.headers.get('origin')
    const approvedOrigin = corsOrigin(requestOrigin, allowedOrigins)

    if (requestOrigin && !approvedOrigin) {
      return json({ error: 'origin_not_allowed' }, 403)
    }

    if (request.method === 'OPTIONS') {
      return new Response(null, {
        status: 204,
        headers: corsHeaders(approvedOrigin)
      })
    }

    if (!['GET', 'HEAD'].includes(request.method)) {
      return json({ error: 'method_not_allowed' }, 405, approvedOrigin, { allow: 'GET, HEAD, OPTIONS' })
    }

    if (url.pathname === '/healthz') {
      return json(
        {
          service: 'unoone-distribution-api',
          status: 'ok',
          inference: false,
          writes: false
        },
        200,
        approvedOrigin,
        { 'cache-control': 'no-store' }
      )
    }

    const catalogKey = catalogObjectKey(url.pathname)
    if (catalogKey) {
      return serveObject(request, env.RELEASES, catalogKey, approvedOrigin, 'public, max-age=300')
    }

    if (url.pathname.startsWith('/v1/artifacts/')) {
      const key = normalizeArtifactKey(url.pathname.slice('/v1/artifacts/'.length))
      if (!key) return json({ error: 'invalid_artifact_path' }, 400, approvedOrigin)
      return serveObject(request, env.RELEASES, key, approvedOrigin, 'public, max-age=31536000, immutable')
    }

    return json({ error: 'not_found' }, 404, approvedOrigin)
  }
}

function catalogObjectKey(pathname: string): string | null {
  if (pathname === '/v1/catalog/stable.json' || pathname === '/v1/catalog/stable') {
    return 'catalogue/stable.json'
  }
  if (pathname === '/v1/catalog/beta.json' || pathname === '/v1/catalog/beta') {
    return 'catalogue/beta.json'
  }
  return null
}

async function serveObject(
  request: Request,
  bucket: R2Bucket,
  key: string,
  approvedOrigin: string | null,
  cacheControl: string
): Promise<Response> {
  if (request.method === 'HEAD') {
    const object = await bucket.head(key)
    if (!object) return json({ error: 'artifact_not_found' }, 404, approvedOrigin)

    const headers = objectHeaders(object, approvedOrigin, cacheControl)
    headers.set('content-length', String(object.size))
    return new Response(null, { status: 200, headers })
  }

  const object = await bucket.get(key, { range: request.headers })
  if (!object) return json({ error: 'artifact_not_found' }, 404, approvedOrigin)

  if (request.headers.get('if-none-match') === object.httpEtag) {
    return new Response(null, {
      status: 304,
      headers: objectHeaders(object, approvedOrigin, cacheControl)
    })
  }

  const headers = objectHeaders(object, approvedOrigin, cacheControl)
  const range = object.range
  const partial = Boolean(request.headers.get('range') && range)

  if (range && typeof range.offset === 'number' && typeof range.length === 'number') {
    headers.set('content-range', `bytes ${range.offset}-${range.offset + range.length - 1}/${object.size}`)
    headers.set('content-length', String(range.length))
  } else {
    headers.set('content-length', String(object.size))
  }

  return new Response(object.body, {
    status: partial ? 206 : 200,
    headers
  })
}

function objectHeaders(
  object: R2Object,
  approvedOrigin: string | null,
  cacheControl: string
): Headers {
  const headers = new Headers(corsHeaders(approvedOrigin))
  object.writeHttpMetadata(headers)
  headers.set('etag', object.httpEtag)
  headers.set('accept-ranges', 'bytes')
  headers.set('cache-control', cacheControl)
  headers.set('x-content-type-options', 'nosniff')
  headers.set('referrer-policy', 'no-referrer')
  headers.set('cross-origin-resource-policy', 'cross-origin')
  return headers
}

function corsHeaders(approvedOrigin: string | null): Headers {
  const headers = new Headers({
    'access-control-allow-methods': 'GET, HEAD, OPTIONS',
    'access-control-allow-headers': 'range, if-none-match',
    'access-control-expose-headers': 'accept-ranges, content-length, content-range, etag',
    'access-control-max-age': '86400',
    vary: 'Origin'
  })
  if (approvedOrigin) headers.set('access-control-allow-origin', approvedOrigin)
  return headers
}

function json(
  body: unknown,
  status: number,
  approvedOrigin: string | null = null,
  extraHeaders: Record<string, string> = {}
): Response {
  const headers = new Headers(JSON_HEADERS)
  for (const [key, value] of Object.entries(extraHeaders)) headers.set(key, value)
  for (const [key, value] of corsHeaders(approvedOrigin)) headers.set(key, value)
  return new Response(JSON.stringify(body), { status, headers })
}
