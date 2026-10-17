# Release signing

Every APK on the [releases page](https://github.com/ssfuisu/cortex/releases) is signed. Android only installs an update if it is signed by the same key as the installed app (or a key it was rotated to), so the signing key decides who can update your Cortex.

## Certificates

| | SHA-256 fingerprint |
|---|---|
| **Current key** (used by Android 13+) | `F5:F5:56:18:EB:AA:CE:26:88:33:95:28:D4:62:01:BE:BB:2C:75:BA:8F:74:4C:6E:F6:5C:27:04:B8:B0:B3:54` |
| **Original key** (releases up to 1.26.29, still the signer on Android 12 and older) | `83:2C:4F:3E:29:17:AD:74:2E:4E:A4:89:C4:C7:7B:A9:ED:2C:ED:FA:3F:AF:99:81:83:9E:89:F9:5A:95:A3:BA` |

Check a downloaded APK with `apksigner verify --verbose --print-certs Cortex-arm64-v8a-signed.apk` (Android SDK build-tools). It should list one of the two fingerprints above.

## Why there are two keys

The original key's private part and password were committed to this repository, so anyone could produce an APK that Android would accept as a Cortex update. It was replaced:

- The new private key exists only in the repository's encrypted secrets, never in the repo.
- `signing/cortex.lineage` is the *proof of rotation* (APK Signature Scheme v3): it states, signed by the original key, that the new key succeeds it. Releases carry it, so installed copies update to the new key without being reinstalled. It contains only public certificates.
- Android 13 and newer use the new key (v3.1 signing block). Android 12 and older do not support targeting a rotation by version, so they keep seeing the original key and continue to update as before. On those versions the original key's weakness remains; a later release can move them once Android 13+ is the norm.
- Copies already updated to the new key refuse APKs signed only with the original key (the rotation lineage disallows rolling back).

## For maintainers

The release workflow needs these repository secrets and fails without them (there is no fallback key): `RELEASE_KEYSTORE_BASE64`, `RELEASE_STORE_PASSWORD`, `RELEASE_KEY_ALIAS`, `RELEASE_KEY_PASSWORD` for the new key and `RELEASE_OLD_KEYSTORE_BASE64`, `RELEASE_OLD_STORE_PASSWORD`, `RELEASE_OLD_KEY_ALIAS`, `RELEASE_OLD_KEY_PASSWORD` for the original one. Losing the new key means no further updates can be published, so keep an offline backup. `workflow_dispatch` with `dry_run` builds, signs and verifies without publishing anything.
