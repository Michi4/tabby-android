# Tabby Android — Termius-like companion for Tabby

Native Android client for [Tabby](https://github.com/eugeny/tabby) (`Eugeny/tabby`).
No official Android app exists (upstream closed Android as `not_planned`), so this app
syncs your Tabby SSH profiles via your own Tabby sync server and connects with native SSH.

Tested target: **RedMagic 10 Pro / Android 16 (API 36)**, min Android 8.0 (API 26).

## Features (v1)

- **Multiple sync servers**: each with name + host (your own Tabby Web instance) +
  secret token + chosen remote config id. Like desktop Tabby, the host starts empty:
  config sync requires an instance of the Tabby Web service
  (`Eugeny/tabby-web`, linked in-app via Learn more).
- **Bidirectional sync**: Pull (`GET`) downloads profiles; Upload (`PATCH /api/1/configs/{id}`,
  same call desktop Tabby makes) pushes them back. **Upload is always an explicit tap
  behind a confirm dialog - there is no auto-upload**, so the phone can never silently
  clobber your desktop config. Merge preserves unmanaged server entries and unknown
  YAML keys; deletes propagate via tombstones. HTTPS enforced (no cleartext sync).
- **Hosts (Termius-like)**: search, groups, quick-connect `user@host:port`, manual add/edit/delete.
- **Terminal tabs**: browser-style tabs at top, VT100/ANSI colors, Esc/Tab/Ctrl/arrows toolbar,
  TOFU host-key accept dialog, 144Hz-friendly rendering.
- **Secrets best practice**: sync tokens + SSH passwords in `EncryptedSharedPreferences`
  (Android Keystore); profiles/accounts in DataStore; SSH passwords also ephemeral in-memory per tab.
- Empty remote config (`{}`) handled gracefully with guidance.

## Configure

1. Install debug APK (`app/build/outputs/apk/debug/`).
2. Open **Sync** → **+** → Name: `Home`, Host: your Tabby Web instance URL (https),
   paste your **Secret sync token** (desktop Tabby → Settings → Config sync) →
   **Test & list configs** → pick config → **Save** → **Pull**.
   To send phone-side changes back, use **Upload** on the account card (confirm dialog first).
3. Open **Hosts** → tap a host → enter password → **Connect**.

Never commit real tokens. The app never logs tokens/passwords.

## Build & test (headless)

```bash
./gradlew :app:assembleDebug
./gradlew :app:testDebugUnitTest
./gradlew :app:lintDebug
```

- `TabbyYamlParserTest`, `QuickConnectParserTest`, `TerminalBufferTest`, `SyncApiTest` run on JVM, no emulator needed.
- For on-device: `adb install -r app/build/outputs/apk/debug/app-debug.apk`, or plug in your RedMagic 10 Pro.

## Architecture

- Single-activity Compose + Navigation (Hosts / Terminal / Sync), Material3, edge-to-edge.
- `data/sync`: Retrofit API + `TabbyYamlParser` + `QuickConnectParser` + `SyncRepository`.
- `data/local`: DataStore `ProfileRepository`, Keystore `SecureTokenStorage`.
- `data/ssh`: `SshConnection` (mwiede JSch, IO dispatcher) + `TerminalBuffer` (own VT subset, tested).
- `ui/state`: `ConnectionsViewModel`, `TerminalTabsViewModel` (survives rotation), `SyncAccountsViewModel`.

## Roadmap

- Private-key import/generate (RSA/ECDSA/Ed25519) + agent forwarding stub.
- SFTP browser tab, port-forward editor, jump-host chaining.
- Room migration if profile count grows; biometric lock for secrets.

## Legal

Unofficial community client, not affiliated with or endorsed by the Tabby developers.
Tabby and Tabby Web are MIT-licensed open source projects by Eugeny Pankov:
`Eugeny/tabby` (the desktop terminal) and `Eugeny/tabby-web` (the sync service
this app talks to). This app is MIT-licensed too (see LICENSE) and speaks the
same sync API as desktop Tabby — no Tabby artwork is bundled; the icon and
theme are original.
