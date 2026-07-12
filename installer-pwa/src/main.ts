import './styles.css'
import './channel.css'

import {
  INSTALLER_VERSION,
  type AppRelease,
  type CatalogPayload,
  type CatalogReplayState,
  artifactUrl,
  formatBytes,
  loadSignedCatalog,
  sha256Hex
} from './catalog'

const apiBase = (import.meta.env.VITE_DISTRIBUTION_API_URL ?? 'https://models.unoone.inbharat.ai').replace(
  /\/$/,
  ''
)
const publicKey = import.meta.env.VITE_CATALOG_PUBLIC_KEY_SPKI_BASE64 ?? ''
const allowUnsignedDevelopment =
  import.meta.env.DEV && import.meta.env.VITE_ALLOW_UNSIGNED_CATALOG === 'true'

const catalogStatus = requiredElement<HTMLDivElement>('catalog-status')
const appRelease = requiredElement<HTMLDivElement>('app-release')
const modelGrid = requiredElement<HTMLDivElement>('model-grid')
const languageGrid = requiredElement<HTMLDivElement>('language-grid')
const catalogVersion = requiredElement<HTMLSpanElement>('catalog-version')
const fileInput = requiredElement<HTMLInputElement>('apk-file')
const fileVerification = requiredElement<HTMLDivElement>('file-verification')
const reloadButton = requiredElement<HTMLButtonElement>('reload-button')
const installPwaButton = requiredElement<HTMLButtonElement>('install-pwa-button')
const channelSelect = requiredElement<HTMLSelectElement>('release-channel')

let currentApp: AppRelease | null = null
let currentChannel: 'stable' | 'beta' = selectedChannel()
let downloadsUnlocked = false
let deferredInstall: BeforeInstallPromptEvent | null = null

reloadButton.addEventListener('click', () => void refreshCatalogue())
channelSelect.addEventListener('change', () => {
  currentChannel = selectedChannel()
  void refreshCatalogue()
})
fileInput.addEventListener('change', () => void verifySelectedApk())
installPwaButton.addEventListener('click', async () => {
  if (!deferredInstall) return
  await deferredInstall.prompt()
  await deferredInstall.userChoice
  deferredInstall = null
  installPwaButton.hidden = true
})

window.addEventListener('beforeinstallprompt', (event) => {
  event.preventDefault()
  deferredInstall = event as BeforeInstallPromptEvent
  installPwaButton.hidden = false
})

if ('serviceWorker' in navigator) {
  window.addEventListener('load', () => {
    void navigator.serviceWorker.register('/sw.js').catch(() => undefined)
  })
}

void refreshCatalogue()

async function refreshCatalogue(): Promise<void> {
  const channel = currentChannel
  setStatus(`Loading signed ${channel} release catalogue…`, 'loading')
  reloadButton.disabled = true
  channelSelect.disabled = true
  appRelease.replaceChildren(textBlock('Waiting for catalogue…'))
  modelGrid.replaceChildren()
  languageGrid.replaceChildren()
  fileInput.value = ''
  fileVerification.textContent = 'No file selected.'
  fileVerification.className = 'verification-result'
  currentApp = null
  downloadsUnlocked = false

  try {
    const loaded = await loadSignedCatalog({
      apiBase,
      channel,
      publicKeySpkiBase64: publicKey,
      allowUnsignedDevelopment,
      installerVersion: INSTALLER_VERSION,
      previousState: loadReplayState(channel)
    })
    if (channel !== currentChannel) return

    downloadsUnlocked = loaded.verified || allowUnsignedDevelopment
    if (loaded.verified) saveReplayState(channel, loaded.replayState)
    renderCatalogue(loaded.envelope.payload)
    setStatus(
      `${loaded.verificationMessage} · ${humanize(channel)} channel`,
      loaded.verified ? 'verified' : 'warning'
    )
    catalogVersion.textContent = [
      `Installer v${INSTALLER_VERSION}`,
      `Catalogue v${loaded.envelope.payload.catalogVersion}`,
      loaded.envelope.keyId,
      formatTimestamp(loaded.envelope.payload.generatedAt)
    ].join(' · ')
  } catch (error) {
    if (channel !== currentChannel) return
    const message = error instanceof Error ? error.message : String(error)
    setStatus(`Downloads locked: ${message}`, 'error')
    appRelease.replaceChildren(
      textBlock('No release is available until the signed catalogue passes every verification gate.')
    )
    catalogVersion.textContent = 'Catalogue verification failed'
  } finally {
    if (channel === currentChannel) {
      reloadButton.disabled = false
      channelSelect.disabled = false
    }
  }
}

function renderCatalogue(payload: CatalogPayload): void {
  const apps = [...payload.apps].sort((left, right) => right.versionCode - left.versionCode)
  currentApp = apps[0] ?? null
  renderApp(currentApp, payload.channel)
  renderModels(payload)
  renderLanguages(payload)
}

function renderApp(app: AppRelease | null, channel: 'stable' | 'beta'): void {
  appRelease.className = 'release-card'
  appRelease.replaceChildren()
  if (!app) {
    appRelease.append(textBlock(`The verified ${channel} catalogue contains no Android release.`))
    return
  }

  const heading = node('div', 'release-title')
  const titleGroup = node('div')
  titleGroup.append(
    node('p', 'eyebrow', `UnoOne for Android · ${humanize(channel)}`),
    node('h3', '', `Version ${app.versionName}`),
    node(
      'p',
      'muted',
      `Build ${app.versionCode} · Android API ${app.minimumAndroidApi}+ · ${formatDate(app.releaseDate)}`
    )
  )
  const badge = node('span', downloadsUnlocked ? 'badge badge-ok' : 'badge badge-locked')
  badge.textContent = downloadsUnlocked
    ? channel === 'stable'
      ? 'Verified stable release'
      : 'Verified beta release'
    : 'Download locked'
  heading.append(titleGroup, badge)

  const facts = node('dl', 'facts')
  fact(facts, 'APK size', formatBytes(app.artifact.sizeBytes))
  fact(facts, 'SHA-256', app.artifact.sha256)
  fact(facts, 'Package', app.id)

  const notes = node(
    'p',
    'release-notes',
    app.releaseNotes?.trim() || 'Release notes were not included in this catalogue.'
  )

  const actions = node('div', 'actions')
  if (downloadsUnlocked) {
    const download = document.createElement('a')
    download.className = 'button button-primary'
    download.href = artifactUrl(apiBase, app.artifact.path)
    download.textContent = channel === 'stable' ? 'Download verified APK' : 'Download verified beta APK'
    download.rel = 'noopener noreferrer'
    download.referrerPolicy = 'no-referrer'
    actions.append(download)
  } else {
    const locked = node('button', 'button button-primary', 'Download locked') as HTMLButtonElement
    locked.disabled = true
    actions.append(locked)
  }

  const copy = node('button', 'button button-secondary', 'Copy SHA-256') as HTMLButtonElement
  copy.type = 'button'
  copy.addEventListener('click', async () => {
    try {
      await copyText(app.artifact.sha256)
      copy.textContent = 'Checksum copied'
    } catch {
      copy.textContent = 'Copy failed'
    }
    window.setTimeout(() => {
      copy.textContent = 'Copy SHA-256'
    }, 1800)
  })
  actions.append(copy)

  if (channel === 'beta') {
    const warning = node(
      'p',
      'channel-warning',
      'Beta releases may be incomplete or unstable. Keep backups and do not use them for safety-critical workflows.'
    )
    appRelease.append(heading, facts, notes, warning, actions)
  } else {
    appRelease.append(heading, facts, notes, actions)
  }
}

function renderModels(payload: CatalogPayload): void {
  modelGrid.replaceChildren()
  if (payload.models.length === 0) {
    modelGrid.append(textBlock('No model releases are listed in this catalogue.'))
    return
  }

  for (const model of [...payload.models].sort((left, right) => left.id.localeCompare(right.id))) {
    const card = node('article', 'catalog-card')
    card.append(
      node('p', 'eyebrow', model.runtime),
      node('h3', '', model.id),
      node('p', 'muted', `Version ${model.version}`),
      node('span', qualificationClass(model.qualificationStatus), humanize(model.qualificationStatus)),
      node(
        'p',
        'catalog-detail',
        `${formatBytes(model.artifact.sizeBytes)} · Minimum RAM ${formatBytes(model.minimumRamMb * 1024 * 1024)}`
      ),
      node(
        'p',
        'catalog-detail',
        model.license ? `Licence: ${model.license}` : 'Licence metadata pending'
      )
    )
    if (model.qualificationStatus !== 'production-approved') {
      card.append(
        node('p', 'catalog-detail', 'Not offered as a public production download until device qualification is complete.')
      )
    }
    modelGrid.append(card)
  }
}

function renderLanguages(payload: CatalogPayload): void {
  languageGrid.replaceChildren()
  if (payload.languagePacks.length === 0) {
    languageGrid.append(textBlock('No language packs are listed in this catalogue.'))
    return
  }

  for (const pack of [...payload.languagePacks].sort((left, right) =>
    left.displayName.localeCompare(right.displayName)
  )) {
    const card = node('article', 'catalog-card')
    const title = node('div', 'language-title')
    const names = node('div')
    names.append(node('h3', '', pack.displayName), node('p', 'native-name', pack.nativeName))
    title.append(names, node('span', languageStatusClass(pack.status), humanize(pack.status)))
    card.append(
      title,
      node('p', 'muted', `${pack.languageCode} · Version ${pack.version}`),
      node(
        'p',
        'catalog-detail',
        pack.downloadable ? 'Downloadable and health-checked inside the Android app' : 'Qualification pending'
      )
    )
    if (pack.notes) card.append(node('p', 'catalog-detail', pack.notes))
    languageGrid.append(card)
  }
}

async function verifySelectedApk(): Promise<void> {
  const file = fileInput.files?.[0]
  if (!file) {
    fileVerification.textContent = 'No file selected.'
    fileVerification.className = 'verification-result'
    return
  }
  if (!currentApp || !downloadsUnlocked) {
    fileVerification.textContent = 'Load a verified catalogue before checking an APK.'
    fileVerification.className = 'verification-result verification-error'
    return
  }
  if (file.size > 768 * 1024 * 1024) {
    fileVerification.textContent = 'This browser verifier is limited to APK files below 768 MB.'
    fileVerification.className = 'verification-result verification-error'
    return
  }

  fileVerification.textContent = `Calculating SHA-256 for ${file.name}…`
  fileVerification.className = 'verification-result verification-working'

  try {
    const digest = await sha256Hex(file)
    const sizeMatches = file.size === currentApp.artifact.sizeBytes
    const hashMatches = digest === currentApp.artifact.sha256
    if (sizeMatches && hashMatches) {
      fileVerification.textContent = `Verified locally: ${file.name} matches catalogue version ${currentApp.versionName}.`
      fileVerification.className = 'verification-result verification-ok'
    } else {
      fileVerification.textContent = [
        'Verification failed. Do not install this APK.',
        `Expected size: ${currentApp.artifact.sizeBytes}; received: ${file.size}.`,
        `Expected SHA-256: ${currentApp.artifact.sha256}.`,
        `Received SHA-256: ${digest}.`
      ].join(' ')
      fileVerification.className = 'verification-result verification-error'
    }
  } catch (error) {
    fileVerification.textContent = `Verification error: ${
      error instanceof Error ? error.message : String(error)
    }`
    fileVerification.className = 'verification-result verification-error'
  }
}

function selectedChannel(): 'stable' | 'beta' {
  return channelSelect.value === 'beta' ? 'beta' : 'stable'
}

function replayStorageKey(channel: 'stable' | 'beta'): string {
  return `unoone.catalog.${encodeURIComponent(apiBase)}.${channel}`
}

function loadReplayState(channel: 'stable' | 'beta'): CatalogReplayState | null {
  try {
    const raw = localStorage.getItem(replayStorageKey(channel))
    if (!raw) return null
    const value = JSON.parse(raw) as Partial<CatalogReplayState>
    if (
      !Number.isSafeInteger(value.version) ||
      (value.version ?? 0) < 1 ||
      typeof value.generatedAt !== 'string' ||
      Number.isNaN(Date.parse(value.generatedAt)) ||
      typeof value.keyId !== 'string'
    ) {
      localStorage.removeItem(replayStorageKey(channel))
      return null
    }
    return value as CatalogReplayState
  } catch {
    return null
  }
}

function saveReplayState(channel: 'stable' | 'beta', state: CatalogReplayState): void {
  try {
    localStorage.setItem(replayStorageKey(channel), JSON.stringify(state))
  } catch {
    // Catalogue verification remains valid even when private browsing blocks localStorage.
  }
}

async function copyText(value: string): Promise<void> {
  if (navigator.clipboard?.writeText) {
    await navigator.clipboard.writeText(value)
    return
  }
  const textarea = document.createElement('textarea')
  textarea.value = value
  textarea.setAttribute('readonly', '')
  textarea.style.position = 'fixed'
  textarea.style.opacity = '0'
  document.body.append(textarea)
  textarea.select()
  const copied = document.execCommand('copy')
  textarea.remove()
  if (!copied) throw new Error('Clipboard copy failed')
}

function setStatus(message: string, kind: 'loading' | 'verified' | 'warning' | 'error'): void {
  catalogStatus.textContent = message
  catalogStatus.className = `status-card status-${kind}`
}

function qualificationClass(status: string): string {
  return status === 'production-approved' || status === 'device-qualified'
    ? 'badge badge-ok'
    : 'badge badge-warning'
}

function languageStatusClass(status: string): string {
  return status === 'stable' || status === 'beta' || status === 'baseline'
    ? 'badge badge-ok'
    : 'badge badge-warning'
}

function humanize(value: string): string {
  return value.replace(/[-_]/g, ' ').replace(/\b\w/g, (character) => character.toUpperCase())
}

function formatDate(value: string): string {
  const date = new Date(`${value}T00:00:00Z`)
  return Number.isNaN(date.getTime())
    ? value
    : new Intl.DateTimeFormat(undefined, { year: 'numeric', month: 'short', day: 'numeric' }).format(date)
}

function formatTimestamp(value: string): string {
  const date = new Date(value)
  return Number.isNaN(date.getTime())
    ? value
    : new Intl.DateTimeFormat(undefined, {
        year: 'numeric',
        month: 'short',
        day: 'numeric',
        hour: '2-digit',
        minute: '2-digit',
        timeZoneName: 'short'
      }).format(date)
}

function fact(list: HTMLDListElement, label: string, value: string): void {
  const wrapper = node('div')
  wrapper.append(node('dt', '', label), node('dd', label === 'SHA-256' ? 'checksum' : '', value))
  list.append(wrapper)
}

function textBlock(text: string): HTMLParagraphElement {
  return node('p', 'muted', text)
}

function node<K extends keyof HTMLElementTagNameMap>(
  tag: K,
  className = '',
  text = ''
): HTMLElementTagNameMap[K] {
  const element = document.createElement(tag)
  if (className) element.className = className
  if (text) element.textContent = text
  return element
}

function requiredElement<T extends HTMLElement>(id: string): T {
  const element = document.getElementById(id)
  if (!element) throw new Error(`Missing required installer element #${id}`)
  return element as T
}

interface BeforeInstallPromptEvent extends Event {
  prompt(): Promise<void>
  userChoice: Promise<{ outcome: 'accepted' | 'dismissed'; platform: string }>
}
