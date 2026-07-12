import { createPublicKey, verify } from 'node:crypto'
import { readFile } from 'node:fs/promises'
import path from 'node:path'

import { canonicalJson } from './canonical-json.mjs'

const args = parseArgs(process.argv.slice(2))
const catalogPath = required(args, 'catalog')
const publicKeyPath = required(args, 'public-key')
const expectedKeyId = args.get('key-id')

const envelope = JSON.parse(await readFile(catalogPath, 'utf8'))
if (envelope.schemaVersion !== 1) throw new Error('Unsupported catalogue schemaVersion')
if (envelope.signatureAlgorithm !== 'Ed25519') throw new Error('Unsupported catalogue signature algorithm')
if (!envelope.payload || typeof envelope.payload !== 'object') throw new Error('Catalogue payload is missing')
if (typeof envelope.signature !== 'string' || envelope.signature.length < 64) {
  throw new Error('Catalogue signature is missing or malformed')
}
if (expectedKeyId && envelope.keyId !== expectedKeyId) {
  throw new Error(`Catalogue keyId mismatch: expected ${expectedKeyId}, received ${envelope.keyId}`)
}

const publicKey = createPublicKey(await readFile(publicKeyPath, 'utf8'))
if (publicKey.asymmetricKeyType !== 'ed25519') {
  throw new Error('Catalogue public key must be Ed25519')
}

const valid = verify(
  null,
  Buffer.from(canonicalJson(envelope.payload), 'utf8'),
  publicKey,
  Buffer.from(envelope.signature, 'base64url')
)

if (!valid) {
  console.error(JSON.stringify({ valid: false, catalog: path.resolve(catalogPath), keyId: envelope.keyId }))
  process.exit(1)
}

console.log(JSON.stringify({ valid: true, catalog: path.resolve(catalogPath), keyId: envelope.keyId }))

function parseArgs(values) {
  const parsed = new Map()
  for (let index = 0; index < values.length; index += 2) {
    const key = values[index]
    const value = values[index + 1]
    if (!key?.startsWith('--') || value === undefined) {
      throw new Error('Arguments must be supplied as --name value pairs')
    }
    parsed.set(key.slice(2), value)
  }
  return parsed
}

function required(values, key) {
  const value = values.get(key)
  if (!value) throw new Error(`Missing required argument --${key}`)
  return value
}
