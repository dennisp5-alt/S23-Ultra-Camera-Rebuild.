# S23 Ultra Camera Rebuild

Clean Samsung Galaxy S23 Ultra-focused CameraX rebuild.

Features: live preview framed to the exact 3:4 photo, photo capture, flash Off/Auto/On, timer (tap shutter again to cancel), rule-of-thirds grid, front/rear switch, pinch / slider / lens-button zoom, EV compensation, Photo/Portrait/Night/Macro modes (OEM extensions when the phone offers them), volume-key shutter, gallery save, tap the thumbnail to open the last photo, and a compact Samsung-style interface.

## Release signing (one-time setup)

GitHub Actions builds a signed release APK when these four repository secrets exist (Settings → Secrets and variables → Actions):

| Secret | Value |
|---|---|
| `SIGNING_KEYSTORE_BASE64` | the `.jks` keystore file, base64-encoded |
| `SIGNING_STORE_PASSWORD` | keystore password |
| `SIGNING_KEY_ALIAS` | key alias |
| `SIGNING_KEY_PASSWORD` | key password (same as the store password for PKCS12 keystores) |

Create a keystore once and keep it backed up privately (never commit it):

```
keytool -genkeypair -v -keystore s23camera.jks -alias s23camera -keyalg RSA -keysize 4096 -validity 10000
base64 -w0 s23camera.jks        # paste the output into SIGNING_KEYSTORE_BASE64
```

Without the secrets the workflow falls back to a debug APK, whose signing key changes every run, so it cannot be installed over the previous build.
