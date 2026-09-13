# Production Readiness Audit — Tabby Android

- **Date:** 2026-09-13
- **Scope:** full checkout at `a853ee5` (plus audit fixes in working tree) — Android app, sync client, SSH, storage, build/release
- **Method:** read-only subagent audits (frontend, API-client, security-static, data-layer) with file:line evidence → independent verification by re-reading cited code, running `./gradlew :app:testDebugUnitTest :app:lintDebug :app:assembleDebug`, adb inspection of the installed debug build, git-history secret scan (filenames only), Maven Central metadata + NVD/Snyk for dependency CVEs
- **Limitations (explicit):**
  - The test device is PIN-locked; no live UI driving, screenshots-of-flows, or E2E were possible. UI findings are code-verified, not driven. Phase 6 journeys are static walkthroughs, labeled as such.
  - No staging backend exists (the app talks to each user's self-hosted Tabby Web instance). No production data was touched; nothing in this audit writes, migrates, or deletes real data.
  - No CI history exists (no `.github` before this audit), so the new workflow's first run is unverified.
  - SSH runtime behavior after the JSch bump is compile- + unit-test-verified only; handshake behavior needs an unlocked-device smoke test.

## Phase 0 — Inventory

### Stack
| Layer | Technology | Evidence |
|---|---|---|
| Language/build | Kotlin 2.0.20, AGP 8.5.2, Gradle 8.7, Java 17 | `build.gradle.kts:3-6`, `app/build.gradle.kts` |
| UI | JetBrains Compose (BOM 2024.09.00), Material3, navigation-compose 2.7.7 | `app/build.gradle.kts:70-83` |
| Persistence | DataStore Preferences 1.1.1 (`tabby_client`), EncryptedSharedPreferences (security-crypto, now 1.1.0 stable), `filesDir/known_hosts`, AndroidKeyStore | `ProfileRepository.kt:19`, `SecureTokenStorage.kt:26-32`, `SshConnection.kt:22`, `VaultGuard.kt:25` |
| Network | Retrofit 2.11.0 + OkHttp 4.12.0, SnakeYAML 2.3, kotlinx-serialization-json 1.7.3 | `app/build.gradle.kts:96-104` |
| SSH | mwiede JSch (now 2.28.7) | `app/build.gradle.kts:107` |
| Tests/lint | JUnit4 + MockWebServer + coroutines-test, Android lint | `app/build.gradle.kts:112-115`, `app/src/test` (17 files) |
| Package manager | Gradle, `FAIL_ON_PROJECT_REPOS`, google()+mavenCentral() only | `settings.gradle.kts` |
| SDK | minSdk 26, targetSdk 36, compileSdk 36 | `app/build.gradle.kts:12-17` |

### Screens / routes
`Routes.CONNECTIONS` (Hosts), `Routes.TERMINAL`, `Routes.SETTINGS` (`TabbyApp.kt:38-42`) + dialogs: Quick connect, Connect/auth, Add/Edit host, Host actions, Upload confirm, Delete confirm, Keys manager, Vault unlock, Host-key TOFU/changed-key, Font-size editor, Add/Edit sync server.

### Endpoints called (all verified in code)
| Method | Path | Use |
|---|---|---|
| GET | `api/1/configs` | config picker (`SyncRepository.kt:37`) |
| GET | `api/1/configs/{id}` | pull + pre-upload re-read (`SyncRepository.kt:48,91`) |
| PATCH | `api/1/configs/{id}` `{content, last_used_with_version}` | explicit upload only (`SyncRepository.kt:99`) |
| SSH | user hosts, `StrictHostKeyChecking=ask`, 15s/15s/10s timeouts, keepalive ≤300s, 3 dead-peer retries | `SshConnection.kt:101-150` |

### Storage keys
Full inventory in Phase 4: 16 DataStore keys (all non-secret; `vault_sealed_json` is Keystore-GCM ciphertext by design), 5 encrypted-pref patterns (tokens, passwords, PEMs, passphrases, vault pw), Keystore alias `tabby_vault_guard`, `known_hosts` (public keys), 3 in-memory secret maps (session-scoped, cleared on close/forget).

### Env vars / third-party integrations
None at runtime. Release signing via `~/.android/tabby-keys/tabby-release.jks` + `TABBY_RELEASE_STORE_PASSWORD` (gradle property or env) — both outside git (`app/build.gradle.kts:29-40`, `.gitignore`). No payment/email/auth-provider/analytics/CDN. `local.properties` contains only `sdk.dir`; `gradle.properties` only standard keys (verified, values not printed).

### Deployment
No CI, no Dockerfile, no backend to deploy: release = locally built APK attached to GitHub releases (APKs gitignored). Rollback = reinstall previous release APK. No health endpoints / monitoring (N/A for a client app — stated, not missing).

### README vs reality
- Test count corrected to 128 during this audit; architecture section lists the new `TerminalConnection`/`LocalShellConnection` files.
- `docs/screenshots/{hosts,terminal,settings}.png` all exist — no broken image refs.
- No OpenAPI spec (no owned backend — N/A).

---

## Phase 1 — Frontend findings

### [MEDIUM] [FIXED] Demo ERROR shown twice
**Where:** `ui/screens/TerminalScreen.kt:622` (generic `state == ERROR` text) + `:632` (demo `ERROR` row)
**Evidence:** ran the demo-error path by reading both blocks; both render `connStatus` when a demo shell fails.
**Impact:** duplicate error text for demo users.
**Fix:** generic block now `state == ERROR && !isDemo`. Verified by compile + 128 tests green.

### [MEDIUM] [FIXED] Unreachable empty branch in tab strip
**Where:** `ui/screens/TerminalScreen.kt:224` (`if (tabs.isEmpty())` inside `if (tabs.size > 1)`)
**Evidence:** read — condition can never be true; reachable empty state is the `current == null` card.
**Impact:** dead code, confusion.
**Fix:** removed. Compile + tests green.

### [MEDIUM] [FIXED] CONNECTING had no loading indicator (button "…" only)
**Where:** `ui/screens/TerminalScreen.kt` connect buttons (saved-creds + password rows)
**Evidence:** read — state surfaced only as "…" text and header subtitle.
**Impact:** weak feedback on slow networks.
**Fix:** `CircularProgressIndicator(18.dp)` while `CONNECTING`. Compile green.

### [HIGH] [FIXED] Host actions unreachable by keyboard (long-press only)
**Where:** `ui/screens/ConnectionsScreen.kt:500` (`combinedClickable(onClick, onLongClick)`); actions dialog `:551`
**Evidence:** read — pin/edit/delete only via `onLongPress`; no button alternative.
**Impact:** keyboard/D-pad users cannot pin, edit, or delete hosts.
**Fix:** `⋮` "Host actions" `IconButton` on every card opening the same sheet. Compile green.

### [MEDIUM] [FIXED] Static "Show" descriptions on password toggles
**Where:** `TerminalScreen.kt:702`, `ConnectionsScreen.kt:373,739`, `SyncAccountsScreen.kt:309`
**Evidence:** read — announced "Show"/"Show password" even when visible.
**Impact:** screen-reader misinformation on 4 toggles.
**Fix:** conditional Hide/Show descriptions. Compile green.

### [MEDIUM] [FIXED] Status dot is color-only
**Where:** `ui/screens/TerminalScreen.kt:246` (`Text("●", color = statusColor(st))`)
**Evidence:** read — TalkBack hears a bullet, not the state.
**Impact:** connection state invisible to AT users.
**Fix:** `clearAndSetSemantics { contentDescription = statusLabel(st) }` + new `statusLabel()` in `Theme.kt`. Compile green.

### [MEDIUM] [FIXED] KeysDialog: dead reveal toggle, silent busy, empty dismiss slot
**Where:** `ui/screens/KeysDialog.kt:54` (`pwVisible` never toggled), `:123,138` (`enabled = !busy` only), `:147` (`dismissButton = {}`)
**Evidence:** read all three.
**Impact:** passphrase can never be revealed to check typing; no progress feedback on slow key ops.
**Fix:** trailing visibility toggle, busy spinner, dropped empty slot. Compile green.

### [MEDIUM] [FIXED] Font-size editor: unlabeled field, silent invalid dismiss
**Where:** `ui/screens/SettingsScreen.kt:204` (no label), `:212` (`toIntOrNull()?.let` + unconditional dismiss)
**Evidence:** read.
**Impact:** confusing validation-less input.
**Fix:** label "Size in sp (1–256)", inline error, dialog stays open on invalid input. Compile green.

### [MEDIUM] [FIXED] Config picker communicated selection by "✓ " prefix only
**Where:** `ui/screens/SyncAccountsScreen.kt:212`
**Evidence:** read — plain TextButtons, no selection semantics.
**Impact:** AT users can't tell which config is picked.
**Fix:** `RadioButton` + `selectable(role = RadioButton)` rows. Compile green.

### [MEDIUM] [FIXED] Dead key tables + unused import
**Where:** `TerminalScreen.kt:948,955` (`SYMBOL_KEYS`, `NAV_KEYS` superseded by `TOP_ROW_KEYS`/`EDIT_SYMBOL_KEYS`/`NAV_ARROWS`), `:99` vs FQN use
**Evidence:** grep shows tests were the only consumers; `ModNavRow` used a local duplicate list.
**Impact:** two sources of truth for key bytes.
**Fix:** removed tables, `ModNavRow` uses `TOP_ROW_KEYS`, tests rewritten to the live tables. 128 tests green.

### [LOW] (accepted) Terminal hardcodes black background / white text
**Where:** `TerminalScreen.kt:519,533`, sender `604-610`
**Evidence:** read — intentional (terminal always black), documented in report.
**Impact:** none; light-mode users get a black terminal by design.

### [LOW] [FIXED] Empty dialog slots
**Where:** `ConnectionsScreen.kt:575` (`confirmButton = {}`), `KeysDialog.kt:147`
**Evidence:** read.
**Impact:** cosmetic code smell.
**Fix:** Cancel moved to `confirmButton`; empty `dismissButton` dropped. Compile green.

Checked OK (one line each): TabbyApp nav labels/dead-ends; all dialogs dismissable; Connections empty/loading (`CircularProgressIndicator` at `:300`)/error+snackbar; search/add/KeyDialog-empty/Sync-empty/loading/error states; Settings switches keyboard-operable via `toggleable(Role.Switch)`; Settings contrast all-theme.

## Phase 2 — Backend/API-client findings

### [MEDIUM] [FIXED] Vault `IllegalArgumentException` escaped with raw parser text
**Where:** `data/sync/VaultCrypto.kt:76-77` (`catch (e: IllegalArgumentException) { throw e }`) vs `data/sync/VaultSync.kt:37-41,78-82` (catches only `VaultBadPassphraseException`/`VaultFormatException`)
**Evidence:** read both; corrupt hex/base64/JSON from `hexToBytes:117`, `base64ToBytes:128`, `parseToJsonElement:70` bypassed the friendly `Failed(...)` path into `SyncRepository:59,108` → `friendlyError:74` raw passthrough.
**Impact:** wrong error ("Invalid hex" instead of passphrase/format failure) + server-influenced strings in UI.
**Fix:** envelope errors (pre-decrypt) → `VaultFormatException("Invalid vault data")`; post-decrypt JSON errors → `VaultBadPassphraseException`; plus IAE belt-and-braces catches in `VaultSync` → `Failed("Invalid vault")`. New tests: `corruptHexIsFormatErrorNotPassphraseError`, `corruptBase64IsFormatError`, `corruptVaultEnvelopeFailsCleanly` (pull+push). 128 tests green.

### [MEDIUM] [FIXED] No OkHttp `callTimeout`
**Where:** `data/sync/TabbySyncApi.kt:91-93` (connect/read/write only)
**Evidence:** read builder; grep confirms no `callTimeout`.
**Impact:** slow-trickle `GET configs/{id}` could hold sync up to 30s per read with no overall cap.
**Fix:** `.callTimeout(120, SECONDS)`. Compile + tests green.

### [MEDIUM] [FIXED] Weaker duplicate host helper
**Where:** `data/model/Models.kt:21` (`normalizedHost()` — trim only, no HTTPS) vs `data/sync/TabbySyncApi.kt:52` (`normalizeHost()` — enforces https)
**Evidence:** grep — single caller `SyncRepository.kt:30`, which re-normalized; latent bypass for future callers.
**Impact:** future direct use could build a Retrofit client for `http://`.
**Fix:** call site uses `normalizeHost()`; `normalizedHost()` deleted. Compile + tests green.

### [MEDIUM] [FIXED] Dead `GET api/1/user` + `ApiUser` DTO
**Where:** `data/sync/TabbySyncApi.kt:22`, `data/sync/ApiDtos.kt:18`
**Evidence:** grep — zero call sites in main or tests.
**Impact:** unused attack surface/maintenance.
**Fix:** removed both. Compile + tests green.

### [LOW] [FIXED] Quick dialog could open blank-host tabs
**Where:** `ui/screens/TerminalScreen.kt` quick `onClick` (only `isNotBlank` gate)
**Evidence:** read `QuickConnectParser` — `"@"`/odd inputs yield `host=""` → later DNS failure.
**Impact:** confusing late failure.
**Fix:** parse-validate via `connectionsVm.quickConnect(quick).host`, inline error, no tab on blank. Compile green.

### [HIGH] [OPEN] Upload is last-write-wins (no ETag/version guard)
**Where:** `data/sync/SyncRepository.kt:91-99` (GET → merge → PATCH, no `If-Match`)
**Evidence:** read — concurrent desktop edit between GET and PATCH is silently clobbered.
**Impact:** data loss across devices (requires concurrent edit in the same seconds + explicit user tap; mitigated by merge preserving unmanaged entries `TabbyYamlSerializer.kt:67` and tap-only uploads).
**Fix:** needs server support (Tabby Web has no conditional-update API) — documented, not implementable client-side. Human decision: accept or pursue upstream.

Checked OK: HTTPS enforcement + normalize (`TabbySyncApi.kt:39-57`); HTTP/TLS/DNS errors sanitized (codes only, no bodies); no secrets in errors/logs (zero `Log/println` hits in scope; `BASIC` logging DEBUG-only); SSH timeouts/keepalive/dead-peer (15s/15s/10s, ≤300s, 3x); `StrictHostKeyChecking=ask` strictest mode; single host-key-accept retry only (`SshConnection.kt:68-73`); blank passwords never sent; parsers null-safe (`as?`, `toIntOrNull`, `coerceIn`); YAML failures → empty, never crash.

## Phase 3 — Security findings

### [HIGH] [FIXED] SSH library 2 years stale (0.2.21)
**Where:** `app/build.gradle.kts:107`
**Evidence:** Maven Central metadata: latest `2.28.7`; NVD/Snyk `CVE-2026-86231` (cert-revocation bypass, fixed 2.28.6, CVSS 3.7 Low, high complexity); Terrapin `CVE-2023-48795` needs ≥0.2.15 (0.2.21 OK). 0.2.21 predates the vulnerable cert code but was 2 years behind on transport fixes.
**Impact:** known-vuln class in the SSH transport dependency.
**Fix:** bumped to `2.28.7` (includes the CVE fix). Verified: `./gradlew :app:dependencies` resolves 2.28.7; full compile + 128 tests green. Remaining: handshake smoke on a real host once the device is unlocked (explicit open item).

### [MEDIUM] [FIXED] SnakeYAML relied on implicit 2.x safe defaults
**Where:** `data/sync/TabbyYamlParser.kt:33,51,82` (`Yaml()` on untrusted remote config)
**Evidence:** read — no `Constructor` (good), but safety was implicit in the 2.3 default, so a downgrade silently re-enables `!!java/object` gadget instantiation (CVE-2022-1471 class, fixed in 2.0+, we use 2.3 — not currently vulnerable).
**Impact:** latent RCE on dependency downgrade.
**Fix:** explicit `Yaml(SafeConstructor(LoaderOptions()))` + comment; all YAML unit tests green.

### [MEDIUM] [FIXED] Pre-release crypto lib
**Where:** `app/build.gradle.kts:93` (`security-crypto:1.1.0-alpha06`)
**Evidence:** Google Maven metadata lists stable `1.1.0`.
**Impact:** alpha Keystore/Tink code guarding all secrets.
**Fix:** bumped to `1.1.0`; resolved + 128 tests green.

### [MEDIUM] [FIXED] Secret-bearing `toString()` landmines
**Where:** `SshKeyManager.kt:14` (`GeneratedKey.privatePem`), `VaultCrypto.kt:35,42` (`StoredVault.contentsB64`, `VaultContent` decrypted config+secrets), `ApiDtos.kt:26` (`UpdateConfigBody.content`)
**Evidence:** read — default `toString()` would embed secrets; safe today only because nothing logs (verified zero log calls).
**Impact:** one future `Log.d` leaks a private key or vault.
**Fix:** redacting `toString()` overrides + 3 unit tests asserting redaction. 128 green.

### [MEDIUM] [FIXED] Unused `ACCESS_NETWORK_STATE` permission
**Where:** `app/src/main/AndroidManifest.xml:5`
**Evidence:** `git grep ConnectivityManager|activeNetwork|NetworkCapabilities` → zero code hits (verified).
**Impact:** unnecessary permission (normal-level; not dangerous, but hygiene).
**Fix:** removed. (Manifest re-verified: INTERNET only, `allowBackup=false`, launcher-only exported activity, no deep links/providers/receivers/services, no cleartext opt-in, no `debuggable`.)

### [MEDIUM] (docs-aligned) VaultGuard 60s window vs "fresh auth every use" header
**Where:** `data/local/VaultGuard.kt:15-22` (claimed CryptoObject-required) vs `:91-100` (60s window) vs `ui/util/Biometrics.kt:14-17` (no CryptoObject on purpose)
**Evidence:** read all three — header contradicts implementation.
**Impact:** misleading security contract; real posture = prompt-gated + 60s Keystore window (code execution within 60s of an auth could reuse the key — high bar, MEDIUM).
**Fix:** header rewritten to describe the actual design + why; per-use CryptoObject (0s) recorded as future hardening needing on-device verification — not changed blindly. Human decision if tightening is wanted.

### Accepted / informational (verified, no action)
- AES-256-CBC without MAC + 8-byte salt: forced by desktop Tabby interop (`VaultCrypto.kt:25-30`); do not fork.
- PBKDF2-HMAC-SHA512 ×100k → 256-bit, CSPRNG salt/IV per encrypt, `PBEKeySpec.clearPassword()` (`VaultCrypto.kt:86-111`); passphrase `String`s linger till GC (accepted, standard).
- Guarded storage itself AES-256-GCM/128 (`VaultGuard.kt:44-61`).
- `FLAG_SECURE` default-block + opt-in re-applied on resume/focus (verified live earlier: window flags cleared, real screenshot captured, persists across restart).
- No hardcoded secrets anywhere in tree or history (git-history scan hit only a dummy `BEGIN/END OPENSSH PRIVATE KEY` **test-fixture string** in `SshKeyManagerTest.kt:33` — verified not a real key).
- No WebView, no intent extras, no custom TrustManager, no `Log`, clipboard marked sensitive (API 33+).
- Plaintext DataStore holds inventory metadata only (hosts/users/URLs) — visible on rooted devices; accepted, documented.

## Phase 4 — Data findings

### [HIGH] [FIXED] Deletes orphaned secrets and references
**Where:** `ui/state/ViewModels.kt:69-78` (`deleteProfile`), `:409` (`deleteAccount`) + `data/local/SecureTokenStorage.kt` (no `removeSshPassword`)
**Evidence:** read — no remover existed; passwords, vault pw, tokens, sealed blobs, lock modes, tombstones, pins, open tabs, session maps all survived deletes.
**Impact:** deleted hosts/accounts leave decryptable secrets on disk indefinitely.
**Fix:** `removeSshPassword`/`removeVaultPassphrase` added; `deleteProfile` purges password + session maps; `deleteAccount` purges token, vault pw/sealed/mode, tombstones, per-profile passwords, pins, open tabs, in-memory vault state. Compile + tests green.

### [HIGH] [FIXED] Delete+tombstone non-atomic (resurrection on crash)
**Where:** `ViewModels.kt:73-76` (two separate DataStore edits)
**Evidence:** read — crash between `saveCached` and `addTombstone` loses the delete; next pull resurrects the host.
**Impact:** deleted hosts come back.
**Fix:** `ProfileRepository.deleteCachedProfile()` — delete + tombstone + pin/open-tab purge in ONE `edit{}`; manual path uses `purgeProfileRefs()`. Compile + tests green.

### [MEDIUM] [FIXED] `forgetVaultPassphrase` kept stale lock mode
**Where:** `ViewModels.kt:196-203`
**Evidence:** read — mode stayed `forever`/`guarded` with no secret → every auto-read fails locked.
**Impact:** vault stuck in-tracking locked state after forget.
**Fix:** `clearVaultLockMode()` (new) resets to ask-every-time. Compile + tests green.

### [MEDIUM] [FIXED] `clearHostKeys` swallowed failure, reported success
**Where:** `data/local/SecureTokenStorage.kt:85-89` → `ViewModels.kt:500-505`
**Evidence:** read — `runCatching{...}` result discarded, success toast unconditional.
**Impact:** user believes pins are gone when they aren't.
**Fix:** returns `Boolean`; failure surfaces "Could not delete saved host keys — try again". Compile + tests green.

### [HIGH] [OPEN] Corrupt JSON blobs reset to empty (data-loss risk on next write)
**Where:** `ProfileRepository.kt:127-143,167-191,223-290,336` (`runCatching{decode}.getOrDefault(empty*)`)
**Evidence:** read — every list/map read fails open to empty; the raw corrupt blob stays until the next save overwrites it.
**Impact:** a corrupt `manual_profiles_json` + any later save wipes user hosts (passwords orphaned); corrupt tombstones resurrect deletes.
**Fix (proposed, not implemented):** quarantine raw to `<key>.corrupt-bak` before any overwriting save. Not implemented because DataStore paths have zero JVM-testable coverage and the device is locked — needs on-device verification. Human decision.

### Accepted
- Corrupt/empty `known_hosts` → TOFU re-prompt (fail-closed, `SshConnection.kt:91-93`).
- Encrypted-prefs tamper throws to caller as sync error (fail-closed, no silent wipe).
- Upload-then-clear ordering correct (PATCH before tombstone clear — safe retry).
- No N+1: list renders do no per-item secret IO; secret loads are per-tap/per-tab only.
- Forward-compat via `ignoreUnknownKeys`; rule established: new model fields must carry defaults.
- `allowBackup=false` closes the Auto-Backup exfil path.

## Phase 5 — Infrastructure findings

### [HIGH] [FIXED] No CI — nothing ran lint/tests/build automatically
**Where:** repo root (`.github` absent — verified `ls`)
**Evidence:** no workflows; regression protection was human-only.
**Impact:** regressions shippable silently.
**Fix:** added `.github/workflows/ci.yml` (checkout + JDK 17 + Android SDK + `testDebugUnitTest` + `lintDebug` + `assembleDebug` on push/PR). Runs on next push — execution unverified here (stated).

### Checked OK
- Secrets: keystore + passwords outside git (`.gitignore` covers `*.jks/*.keystore/*.pem/*.key/*.p12/google-services.json/*token*`); `gradle.properties`/`local.properties` contain only standard keys (verified, keys-only listing).
- Signing: local release keystore + env/property password, `isMinifyEnabled=false` (no obfuscation claims).
- No owned backend → no health endpoints, monitoring, env parity, or migration/rollback machinery applicable (rollback = reinstall prior release APK).

## Phase 6 — Critical journeys (static walkthroughs — NOT driven; device locked)

1. **Add host → connect → TOFU accept:** `ConnectionsScreen` add dialog (`:340`+) validates non-blank host, saves profile + encrypted password (`ViewModels.kt:61-67`); tap connects directly when creds saved (`:106-122`); unknown key raises `UnknownHostKeyException` (`SshConnection.kt:133-139`) → dialog with SHA256 fingerprint + out-of-band warning (`TerminalScreen.kt:750+`); accept saves pin (`savePendingKey`) and retries once (`SshConnection.kt:68-74`); changed key takes the hard-block warning path (`:114-131`). Rough edge fixed in audit: blank-host quick input now errors inline instead of failing at DNS.
2. **Sync pull → edit → upload:** Pull rebuilds cache per account with vault/locked/error branches (`ViewModels.kt:92-141`); manual edits stay local; Upload is explicit + confirm-dialog (`SyncAccountsScreen.kt:156-171`) and PATCHes merged content preserving unmanaged entries (`TabbyYamlSerializer.kt:67`) — with the open last-write-wins caveat (H6). Deletes propagate via tombstones (now atomic, H3).
3. **Vault unlock (all 3 modes):** session (memory only), forever (encrypted store), guarded (biometric prompt → Keystore-GCM blob) (`ViewModels.kt:151-203`, `Biometrics.kt:84-126`); wrong passphrase → "Incorrect vault passphrase" (now also for corrupt envelopes → "Invalid vault", H4). Biometric flow could not be driven (locked device + no enrolled-biometric control).

## Phase 7 — Testing

- **Run:** `./gradlew :app:testDebugUnitTest` → **128 tests, 0 failures, 0 errors, 2 skipped** (`SshConnectionLiveTest`, needs a live server — verified skip reason). `:app:lintDebug` → pass (was 1 pre-existing indentation error, fixed in 1.4.0). `:app:assembleDebug` → pass.
- **Added in audit:** 8 tests (2 vault-format, 2 vault-sync envelope, 1 `replacePin`, 3 `toString` redactions).
- **Gaps (open):** zero instrumented/E2E tests; `ViewModels`/`ProfileRepository`/`SecureTokenStorage` DataStore paths untested on JVM (need Robolectric or device); SSH handshake and biometric flows untested (need device/server). → H8.

## Fix log (batches, each re-verified with compile + full unit tests)
1. Deps: jsch 0.2.21→2.28.7, security-crypto alpha06→1.1.0 (resolved versions confirmed; 120 green).
2. Storage hygiene: removers, atomic delete+tombstone+purge, lock reset, hostkey bool (120 green).
3. Network/parse: vault IAE restructure + catches, callTimeout 120s, SafeConstructor, atomic known_hosts, normalizedHost removal (+5 tests, 125 green).
4. UI/a11y: 10 fixes above; key-table dedup + test rewrite (125 green).
5. Hardening/misc: toString redactions (+3 tests), dead endpoint/DTO removal, quick-host guard, VaultGuard docs, permission removal, CI workflow (128 green + lint green).

## Scorecard

| Phase | Verdict | Open HIGH |
|---|---|---|
| 0 Recon | Clean | — |
| 1 Frontend | Clean (10 fixed) | — |
| 2 API-client | Clean except H6 | H6 last-write-wins (needs server support) |
| 3 Security | Clean except runtime re-verify | jsch handshake smoke pending (device) |
| 4 Data | Clean except H7 | H7 corrupt-JSON quarantine (proposed) |
| 5 Infra | Clean except CI first-run | CI executes on next push (unverified) |
| 6 Journeys | Static only | driving blocked (locked device) |
| 7 Testing | 128/128 green | H8 no E2E (harness+device needed) |

## Go / No-Go: **CONDITIONAL GO**

Shippable as a GitHub-release APK **after** this checklist, in order:
1. Unlock-device smoke: real SSH connect (exercises jsch 2.28.7 handshake + TOFU), demo shell, screenshot toggle row, biometric vault unlock if enrolled.
2. First CI run on push must be green.
3. Decide H6 (accept last-write-wins + document in README, or pursue upstream conditional update) and H7 (implement quarantine vs accept).
4. H8: add at least one instrumented smoke (launch + open demo shell) when a harness exists.

No CRITICAL items. No secrets in tree or history (one dummy fixture string verified). No destructive action was taken; nothing was pushed or deployed.
