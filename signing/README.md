# Signing Configuration & Keystores

This directory contains signing material strictly for **development and debug builds**.

### Contents
- `debug.keystore`: Standard Android Debug Keystore generated with public default credentials:
  - **Alias**: `androiddebugkey`
  - **Password**: `android`
  - **Validity**: Development / Debug builds only.

---

### Security Policy
1. **Never Commit Production Release Keys**:
   - Production signing keys (`*.jks`, `*.keystore`, `keystore.properties`) must **never** be added to version control.
   - All `.jks`, `.keystore` (except `debug.keystore`), and `keystore.properties` files are ignored in `.gitignore`.

2. **Release Signing in CI/CD**:
   - Production release APKs are signed in GitHub Actions using encrypted repository secrets (`RELEASE_KEYSTORE_BASE64`, `RELEASE_KEY_ALIAS`, `RELEASE_STORE_PASSWORD`, `RELEASE_KEY_PASSWORD`).
   - The Gradle `release` build type dynamically inspects environment variables (`RELEASE_KEYSTORE_PATH`, etc.) and remains unsigned if credentials are not provided.
