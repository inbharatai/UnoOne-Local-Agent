import { createPrivateKey, sign } from 'node:crypto'
import { mkdir, readFile, rename, writeFile } from 'node:fs/promises'
import path from 'node:path'

import { canonicalJson, validateReleasePayload } from './canonical-json.mjs'

const args = parseArgs(process.argv.slice(2))
const inputPath = required(args, 'input')
const privateKeyPath = required(args, 'private-key')
const keyId = required(args, 'key-id')
const outputPath = required(args, 'output')

if (!/^[A-Za-z0-9._-]{3,80}$/.test(keyId)) {
  throw new Error('Catalogue key id must match ^[A-Za-z0-9._-]{3,80}$')
}

const payload = validateReleasePayload(JSON.parse(await readFile(inputPath, 'utf8')))
const privateKey = createPrivateKey(await readFile(privateKeyPath, 'utf8'))
if (privateKey.asymmetricKeyType !== 'ed25519') {
  throw new Error('Catalogue private key must be Ed25519')
}

const canonicalPayload = Buffer.from(canonicalJson(payload), 'utf8')
const signature = sign(null, canonicalPayload, privateKey).toString('base64url')
if (!/^[A-Za-z0-9_-]{64,}$/.test(signature)) throw new Error('Generated catalogue signature is malformed')

const envelope = {
  schemaVersion: 1,
  keyId,
  signatureAlgorithm: 'Ed25519',
  payload,
  signature
}

const resolvedOutput = path.resolve(outputPath)
const resolvedInput = path.resolve(inputPath)
const resolvedPrivateKey = path.resolve(privateKeyPath)
if (resolvedOutput === resolvedInput || resolvedOutput === resolvedPrivateKey) {
  throw new Error('Output path must not overwrite the payload or private key')
}

await mkdir(path.dirname(resolvedOutput), { recursive: true })
const temporary = `${resolvedOutput}.tmp-${process.pid}`
await writeFile(temporary, `${JSON.stringify(envelope, null, 2)}\n`, { mode: 0o644, flag: 'wx' })
await rename(temporary, resolvedOutput)
console.log(JSON.stringify({ output: resolvedOutput, keyId, catalogVersion: payload.catalogVersion }))

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
