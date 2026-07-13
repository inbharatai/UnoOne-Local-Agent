import { createPublicKey, verify } from 'node:crypto'
import { readFile } from 'node:fs/promises'
import path from 'node:path'

import { canonicalJson, validateReleasePayload } from './canonical-json.mjs'

const args = parseArgs(process.argv.slice(2))
const catalogPath = required(args, 'catalog')
const publicKeyPath = required(args, 'public-key')
const expectedKeyId = args.get('key-id')

const envelope = JSON.parse(await readFile(catalogPath, 'utf8'))
if (!envelope || typeof envelope !== 'object' || Array.isArray(envelope)) {
  throw new Error('Catalogue envelope must be an object')
}
const allowedEnvelopeKeys = new Set(['schemaVersion', 'keyId', 'signatureAlgorithm', 'payload', 'signature'])
for (const key of Object.keys(envelope)) {
  if (!allowedEnvelopeKeys.has(key)) throw new Error(`Catalogue envelope contains unsupported field: ${key}`)
}
if (envelope.schemaVersion !== 1) throw new Error('Unsupported catalogue schemaVersion')
if (envelope.signatureAlgorithm !== 'Ed25519') throw new Error('Unsupported catalogue signature algorithm')
if (typeof envelope.keyId !== 'string' || !/^[A-Za-z0-9._-]{3,80}$/.test(envelope.keyId)) {
  throw new Error('Catalogue keyId is missing or malformed')
}
if (typeof envelope.signature !== 'string' || !/^[A-Za-z0-9_-]{64,}$/.test(envelope.signature)) {
  throw new Error('Catalogue signature is missing or malformed')
}
if (expectedKeyId && envelope.keyId !== expectedKeyId) {
  throw new Error(`Catalogue keyId mismatch: expected ${expectedKeyId}, received ${envelope.keyId}`)
}

const payload = validateReleasePayload(envelope.payload)
const publicKey = createPublicKey(await readFile(publicKeyPath, 'utf8'))
if (publicKey.asymmetricKeyType !== 'ed25519') {
  throw new Error('Catalogue public key must be Ed25519')
}

const valid = verify(
  null,
  Buffer.from(canonicalJson(payload), 'utf8'),
  publicKey,
  Buffer.from(envelope.signature, 'base64url')
)

if (!valid) {
  console.error(JSON.stringify({ valid: false, catalog: path.resolve(catalogPath), keyId: envelope.keyId }))
  process.exit(1)
}

console.log(
  JSON.stringify({
    valid: true,
    catalog: path.resolve(catalogPath),
    keyId: envelope.keyId,
    channel: payload.channel,
    catalogVersion: payload.catalogVersion
  })
)

function parseArgs(values) {
  if (values.length % 2 !== 0) throw new Error('Arguments must be supplied as --name value pairs')
  const parsed = new Map()
  for (let index = 0; index < values.length; index += 2) {
    const key = values[index]
    const value = values[index + 1]
    if (!key?.startsWith('--') || value === undefined || value.startsWith('--')) {
      throw new Error('Arguments must be supplied as --name value pairs')
    }
    const normalized = key.slice(2)
    if (parsed.has(normalized)) throw new Error(`Duplicate argument --${normalized}`)
    parsed.set(normalized, value)
  }
  return parsed
}

function required(values, key) {
  const value = values.get(key)
  if (!value) throw new Error(`Missing required argument --${key}`)
  return value
}
