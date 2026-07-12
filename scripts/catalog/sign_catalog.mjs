import { createPrivateKey, sign } from 'node:crypto'
import { mkdir, readFile, rename, writeFile } from 'node:fs/promises'
import path from 'node:path'

import { canonicalJson } from './canonical-json.mjs'

const args = parseArgs(process.argv.slice(2))
const inputPath = required(args, 'input')
const privateKeyPath = required(args, 'private-key')
const keyId = required(args, 'key-id')
const outputPath = required(args, 'output')

const payload = JSON.parse(await readFile(inputPath, 'utf8'))
const privateKey = createPrivateKey(await readFile(privateKeyPath, 'utf8'))
if (privateKey.asymmetricKeyType !== 'ed25519') {
  throw new Error('Catalogue private key must be Ed25519')
}

const canonicalPayload = Buffer.from(canonicalJson(payload), 'utf8')
const signature = sign(null, canonicalPayload, privateKey).toString('base64url')
const envelope = {
  schemaVersion: 1,
  keyId,
  signatureAlgorithm: 'Ed25519',
  payload,
  signature
}

await mkdir(path.dirname(path.resolve(outputPath)), { recursive: true })
const temporary = `${outputPath}.tmp`
await writeFile(temporary, `${JSON.stringify(envelope, null, 2)}\n`, { mode: 0o644 })
await rename(temporary, outputPath)
console.log(JSON.stringify({ output: path.resolve(outputPath), keyId, signature }))

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
