import { copyFile, mkdir, stat } from 'node:fs/promises'
import { fileURLToPath } from 'node:url'
import path from 'node:path'

const here = path.dirname(fileURLToPath(import.meta.url))
const runtimeRoot = path.resolve(here, '..')
const repositoryRoot = path.resolve(runtimeRoot, '..', '..')
const source = path.join(runtimeRoot, 'dist', 'unoone-page-agent.js')
const destinationDir = path.join(
  repositoryRoot,
  'android-app',
  'UnoOneAgent',
  'securebrowser',
  'src',
  'main',
  'assets',
  'page-agent'
)
const destination = path.join(destinationDir, 'unoone-page-agent.js')

const info = await stat(source).catch(() => null)
if (!info?.isFile() || info.size === 0) {
  throw new Error(`PageAgent bundle not found or empty: ${source}. Run npm run build first.`)
}

await mkdir(destinationDir, { recursive: true })
await copyFile(source, destination)
console.log(`Copied ${source} -> ${destination} (${info.size} bytes)`)
