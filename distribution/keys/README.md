# Catalogue Signing Keys

This directory must never contain committed key material.

Generate keys on a controlled release machine:

```bash
node scripts/catalog/generate_ed25519_keypair.mjs /secure/path/unoone-catalog-keys
```

- `catalog-private.pem` signs release catalogues and must remain offline or hardware-protected.
- `catalog-public.pem` verifies catalogues during release engineering.
- `catalog-public.spki.b64` is safe to inject into the installer PWA production build.

The `.gitignore` in this directory rejects all unlisted files. Do not weaken it to commit a test or production private key.
