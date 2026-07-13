/// <reference types="vite/client" />

interface ImportMetaEnv {
  readonly VITE_DISTRIBUTION_API_URL?: string
  readonly VITE_CATALOG_PUBLIC_KEY_SPKI_BASE64?: string
  readonly VITE_ALLOW_UNSIGNED_CATALOG?: string
}

interface ImportMeta {
  readonly env: ImportMetaEnv
}
