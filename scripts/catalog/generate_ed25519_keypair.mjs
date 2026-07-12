import { generateKeyPairSync } from 'node:crypto'
import { chmod, mkdir, writeFile } from 'node:fs/promises'
import path from 'node:path'

const outputDir = path.resolve(process.argv[2] ?? 'distribution/keys')
await mkdir(outputDir, { recursive: true })

const { privateKey, publicKey } = generateKeyPairSync('ed25519')
const privatePem = privateKey.export({ type: 'pkcs8', format: 'pem' })
const publicPem = publicKey.export({ type: 'spki', format: 'pem' })

const privatePath = path.join(outputDir, 'catalog-private.pem')
const publicPath = path.join(outputDir, 'catalog-public.pem')

await writeFile(privatePath, privatePem, { flag: 'wx', mode: 0o600 })
await chmod(privatePath, 0o600)
await writeFile(publicPath, publicPem, { flag: 'wx', mode: 0o644 })

console.log(JSON.stringify({ privateKey: privatePath, publicKey: publicPath }))
