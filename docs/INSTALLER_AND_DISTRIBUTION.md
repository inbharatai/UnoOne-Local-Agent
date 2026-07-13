# UnoOne Installer and Distribution Runbook

## Current status

The repository contains a deployable distribution architecture, but no public production deployment is claimed yet.

- `distribution/api/` — read-only Cloudflare Worker that streams signed catalogues and immutable artifacts from R2
- `installer-pwa/` — installable web portal that verifies the Ed25519 catalogue before enabling APK downloads
- `distribution/catalog/` — JSON schemas and catalogue fixtures
- `scripts/catalog/` — key generation, deterministic signing and verification tools

The backend distributes files only. It does not run AI inference and does not receive prompts, audio, screenshots, browser forms, contacts or local memories.

## Recommended production domains

| Purpose | Domain |
|---|---|
| Installer PWA | `https://unoone.inbharat.ai` |
| Distribution API and artifacts | `https://models.unoone.inbharat.ai` |

Do not serve the installer from a domain that can inject third-party scripts. The installer Content Security Policy allows only its own scripts/styles and HTTPS catalogue requests.

## R2 object layout

```text
unoone-releases/
├── catalogue/
│   ├── stable.json
│   └── beta.json
├── apk/
│   └── stable/
│       └── unoone-<version>.apk
├── brain/
│   └── gemma-4-e2b/<version>/
│       └── gemma-4-E2B-it.litertlm
├── speech/
│   ├── shared/
│   └── languages/
└── vision/
    └── blind-aid/
```

The Worker refuses paths outside `apk/`, `brain/`, `speech/`, `vision/` and `catalogue/`. It rejects traversal, empty path segments, non-HTTPS browser origins and write methods.

## One-time Cloudflare setup

1. Create R2 buckets:
   - `unoone-releases`
   - `unoone-releases-preview`
2. Review `distribution/api/wrangler.toml`.
3. Set `ALLOWED_ORIGINS` to the exact installer and approved InBharat origins.
4. Authenticate Wrangler using a deployment token restricted to this Worker and bucket.
5. Deploy the Worker from `distribution/api/`:

```bash
npm install
npm run typecheck
npm test
npm run deploy
```

6. Attach `models.unoone.inbharat.ai` as the Worker custom domain.
7. Confirm that `POST`, `PUT`, `PATCH` and `DELETE` return `405`.
8. Confirm that an unapproved `Origin` receives `403`.

## Catalogue signing keys

Generate the production key pair on an offline or tightly controlled release machine:

```bash
node scripts/catalog/generate_ed25519_keypair.mjs /secure/offline/unoone-catalog-keys
```

Outputs:

- `catalog-private.pem` — signing key; never commit, upload or place in Cloudflare
- `catalog-public.pem` — engineering verification key
- `catalog-public.spki.b64` — browser-ready public key for the PWA build

The repository ignores everything inside `distribution/keys/` except its documentation and `.gitignore`.

### Key handling rules

- Keep the private key offline or in a hardware-backed signing system.
- Never store it in the Android APK, PWA, Worker environment or GitHub repository.
- Public keys may be committed or injected at build time.
- Rotate by publishing a new `keyId`, updating the installer public key, then signing new catalogues.
- Keep the old public key available during a controlled migration window if older installers must remain functional.

## Prepare a release payload

Create an unsigned payload matching `distribution/catalog/release-catalog.schema.json`.

Every artifact entry requires:

- exact R2 path;
- byte size;
- lowercase SHA-256;
- MIME type;
- version and qualification metadata.

Do not list a file before its exact bytes are uploaded and independently measured.

## Sign the catalogue

```bash
node scripts/catalog/sign_catalog.mjs \
  --input /release-work/stable.payload.json \
  --private-key /secure/offline/unoone-catalog-keys/catalog-private.pem \
  --key-id unoone-prod-2026-01 \
  --output /release-work/stable.json
```

Verify before upload:

```bash
node scripts/catalog/verify_catalog.mjs \
  --catalog /release-work/stable.json \
  --public-key /secure/offline/unoone-catalog-keys/catalog-public.pem \
  --key-id unoone-prod-2026-01
```

The signature covers deterministic canonical JSON of the `payload` object. Any change to a version, path, size, checksum, language or model status invalidates the signature.

## Publish artifacts

Upload immutable artifacts before the catalogue. Example commands:

```bash
cd distribution/api
npx wrangler r2 object put unoone-releases/apk/stable/unoone-0.4.0.apk \
  --file /release-work/unoone-0.4.0.apk \
  --content-type application/vnd.android.package-archive

npx wrangler r2 object put unoone-releases/catalogue/stable.json \
  --file /release-work/stable.json \
  --content-type application/json
```

Publishing the catalogue last prevents clients from seeing references to missing artifacts.

## Build the installer PWA

The production build requires:

```bash
export VITE_DISTRIBUTION_API_URL=https://models.unoone.inbharat.ai
export VITE_CATALOG_PUBLIC_KEY_SPKI_BASE64="$(cat /secure/offline/unoone-catalog-keys/catalog-public.spki.b64)"
cd installer-pwa
npm install
npm run typecheck
npm test
npm run build
```

Deploy `installer-pwa/dist/` to the approved static host attached to `unoone.inbharat.ai`.

### Fail-closed behavior

A production PWA build without `VITE_CATALOG_PUBLIC_KEY_SPKI_BASE64` displays release status but locks downloads. A malformed, unsigned, wrong-key or tampered catalogue also locks downloads.

`VITE_ALLOW_UNSIGNED_CATALOG=true` works only during Vite development mode and must never be used for public deployment.

## User download flow

1. User opens the official installer domain.
2. PWA fetches `stable.json` with `cache: no-store`.
3. Browser verifies the Ed25519 signature.
4. Only then does the APK download button become active.
5. User may select the downloaded APK for a local size and SHA-256 check.
6. Android installs UnoOne.
7. UnoOne downloads models and language packs through its own verified installer.

## Rollback

Artifacts are immutable. To roll back:

1. Create a new catalogue version referencing the previously approved APK/model paths.
2. Sign it with the current production key.
3. Verify it independently.
4. Replace `catalogue/stable.json`.
5. Do not overwrite historical artifact bytes under an existing path.

## Release checklist

- [ ] Android CI green
- [ ] Distribution CI green
- [ ] APK signed with the intended Android release key
- [ ] APK byte size and SHA-256 measured twice
- [ ] Gemma/model artifacts have correct licences and integrity records
- [ ] Catalogue payload schema reviewed
- [ ] Catalogue signed offline
- [ ] Signature verified with the public key
- [ ] Artifacts uploaded before catalogue
- [ ] Installer production public key configured
- [ ] Installer download unlocked only after signature verification
- [ ] Downloaded APK passes local verifier
- [ ] Rollback catalogue prepared

## Explicit non-goals

The distribution Worker does not:

- accept uploads from the public internet;
- run Gemma or speech inference;
- accept user prompts or telemetry;
- expose arbitrary R2 keys;
- sign releases;
- hold the private catalogue signing key;
- remotely control an Android device.
