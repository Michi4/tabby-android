# Changelog

## 1.4.9 (versionCode 14)

> Note: 1.4.6–1.4.8 were pulled before shipping (their tags/releases were
> deleted); this build is the first to ship all of the below. Phones on
> ≤1.4.5 upgrade directly to this. Terminal + updater + toggles all
> verified live on-device in this build.

### Terminal scrollback actually works now (the "not staying" bug, found live)
- **You can finally scroll up into history.** The main view only ever
  rendered the tail rows, so swiping up rubber-banded and the Follow toggle
  did nothing observable. The last 100 scrollback lines now render above the
  live screen in the same scroll view (find highlights work in history too).
- **Finger scrolling fixed**: the pinch handler swallowed every drag, so
  swipes never scrolled. New touch router: one finger drags (with fling),
  two fingers pinch-zoom, taps/long-press/selection pass through untouched.
- **Follow means follow**: fresh/restored tabs open on the live prompt
  (previously stranded at the top of old history looking frozen while you
  typed); follow tracks live across bursts, keyboard open/close and
  rotation; dragging up or find-jumping parks the view (never yanked);
  reaching the bottom, the ↓ button, or re-tapping Follow resumes.
  Follow OFF truly never moves on its own.
- Perf-safe: per-frame render stays at screen rows + a cheap plain-text
  history window; the buffer still keeps up to your full scrollback setting.

### Terminal input stays honest (live rig + device tests)
- **No more "ececho" from suggestions/macros**: tapping a suggestion now edits
  the current command like a completion, sending only the difference from the
  typed prefix instead of appending the whole command.
- **Disconnected tabs refuse input**: typing, Return, fill and macro-run no
  longer let the local field grow while sends are dropped, preventing phantom
  history entries and reconnect desync.
- **Paste uses bracketed paste when available**: DEC private mode 2004 is now
  tracked, and paste is wrapped in the standard start/end markers when the
  server accepts it, so multi-line pastes need not execute line-by-line.
- **Unicode and fast-typing races fixed**: surrogate-pair characters are
  added/erased as one code point, wide characters erase visually correctly,
  and demo-shell keystrokes preserve FIFO order even when back-to-back sends
  fan out across IO workers.

### Sync survives partial failures and corrupt remote configs
- **One locked/offline server no longer wipes another server's hosts**: `Pull`
  now updates each account's cached slice independently instead of rebuilding
  the whole list from successful accounts only.
- **Device-only assignments survive sync**: local SSH-key assignments and port
  forwards attached to synced hosts are preserved when the desktop config comes
  back unchanged (they are never uploaded, but they must not be erased either).
- **Invalid remote YAML fails closed**: a corrupt/non-mapping server config is
  now reported as unreadable instead of being mistaken for an empty `{}` config,
  so Pull cannot drop hosts and Upload cannot overwrite a corrupt remote with a
  stripped config.

### Updater stops interrupting you
- **The installer never opens on its own anymore.** 1.4.7 auto-launched the
  system installer the moment a download finished — a fullscreen popup over
  whatever you were doing (including a live terminal), possibly repeatedly.
  Now a finished download waits as `Ready to install` with a big Install
  button; the installer opens only from your explicit tap. Auto-download
  stays (toggleable), auto-install is gone.
- **Single daily check**: the launch check lived in two places (app + view
  model) and could double-fire; now only the app triggers it. Downloads also
  refuse to stack/restart while one is active.

### Toggles stop "bugging away"
- **Switches flip instantly** (optimistic UI): every Settings switch now
  shows your tap immediately and reconciles when storage lands, instead of
  waiting on the persistence round-trip that made taps look ignored.
- **Per-tab Follow + font size survive rotation**: they were plain `remember`
  state, so rotating (or backgrounding) silently reset them to defaults —
  the toggle looked like it "bugged away". Now `rememberSaveable` per tab.
- **Key-row toggle matches your layout**: it cycled a hardcoded 4→3→2→1
  that didn't match custom layouts; now it cycles your layout's own row
  count down to hidden and back (new `nextKeyRows`, unit-tested).

### Settings can never crash the app
- **Self-healing preferences**: DataStore now has a corruption handler —
  a corrupt prefs file falls back to defaults instead of crashing every
  settings collector.
- **Guarded writes**: all settings writes are `runCatching` fire-and-forget,
  so a toggle can never take the process down even on disk I/O failure.

## 1.4.7 (versionCode 12)

### In-app updater perfected
- **Direct in-app download** now streams the APK inside the app (OkHttp with resume-aware progress) instead of relying on DownloadManager — you always know where it is and see live `MB / MB` + percent.
- **Auto-install, no file hunting** — as soon as the download finishes the system installer opens automatically (PackageInstaller session when possible, otherwise FileProvider + explicit package-installer targeting so a changed default APK handler can never hijack it). Unknown-sources permission is requested inline.
- **Fully automatic if you want it** — new `Auto-download updates` switch (on by default) downloads the moment an update is found and immediately prompts to install; otherwise you tap `Update now`.
- **Robust to edge cases** — handles redirects, verifies HTTP success + minimum size, syncs to disk, cleans old APKs, survives rotation, and shows `Download failed` with retry instead of silent stall. Already-downloaded APKs are reused instantly.
- **Never by default app** — installer intent is explicitly targeted to the system package installer and granted to all resolvers, so changing your default APK opener can never break updates.

## 1.4.6 (versionCode 11)

### Audit hardening (from 1.4.5 review)
- Settings key-layout editor: "Add key" now opens the picker (was inert).
- Updater: allowlist for APK host + https + tag sanitized for filename; evil asset URL → `Failed("bad asset URL")` (new test).
- Scrollback: `open_tabs_scrollback_json` now drops secret-bearing lines at write (`isSensitiveCommand` filter) and purges orphans on profile delete; cap stays 10×200.
- Data: `clearVaultLockMode`/`clearRemoteHash`/`saveUpdateCheck` + ESP history/macros now quarantine `.corrupt-bak` before overwrite.
- Infra: `data_extraction_rules.xml` + `android:dataExtractionRules` for the `allowBackup` deprecation on API 31+.

## 1.4.5 (versionCode 10)

### TUI rendering perfection + data races (the since-1.3.0 TUI bug, finally pinned)
- **Cut-off logos, wrapped tables**: Compose `Text` was soft-wrapping the
  80-cell buffer lines at its own width (worse at large fonts). Fixed with
  `softWrap = false` + horizontal scroll — buffer lines map 1:1 to visual
  rows (Termius never wraps). Proven with a replayed captured opencode frame.
- **Robustness**: APC/SOS/PM strings (kitty graphics) swallowed; tmux-wrapped
  DCS unwrapped; `List.removeLast()` crash on real phones fixed.
- **CJK/emoji 2-column**: wide glyphs advance 2 cells, filler skipped, wraps
  whole (like xterm) — no more misaligned btop bars.

### Adaptive viewport
- Real character cells are measured; buffer + remote pty resize together
  (SIGWINCH) on font, key-row, keyboard, fullscreen and rotation changes.

### Macros, history & suggestions (the big UX win)
- Fish-style suggestion chips learned from commands you ran (secret-looking
  lines never recorded), frequency-ranked, toggleable in Settings, filled
  for review then submitted normally.
- Macros: name + command (both encrypted), tap the row to fill, ▶ to run;
  fast-typing now dispatches newline per character — deleting on an empty
  line sends DEL correctly on every keyboard.
- Fixes: suggestions now not suppressed for short prefixes like \`l\` (fixed
  to 2), demo-shell send race fixed (serializes disciplined sends; lost
  markers repro → hammering test), and fill now transmits (nothing vanishes
  on submit).

## 1.4.4 (versionCode 9)

### Port-forward manager
- Per-host local (`phone :port → server service`, e.g. dashboards) and
  remote tunnels with per-forward enable switches, managed in the host editor.
- Forwards start on connect (failures reported, never fatal), pause/resume
  live from the toolbar, stop on disconnect. Device-only — never uploaded.

### In-app updates
- Daily GitHub release check (silent unless something is new) plus manual
  check in Settings → Updates, with a reminder card, release notes preview,
  one-tap DownloadManager download and system installer handoff (unknown-sources
  flow included). Verified against a mocked release API in tests.

## 1.4.3 (versionCode 8)

### Gestures
- Pinch-to-zoom font on the terminal (two fingers; tap, scroll and
  long-press selection pass through untouched), toggleable in Settings.
- Swipe tab-switching deliberately omitted: horizontal swipes collide with
  text-selection drags on the output — chevrons + tab strip stay the
  unambiguous switchers.

### Command suggestions + macros
- Fish-style suggestion chips above the keys, learned from commands you ran
  (frequency-ranked, encrypted at rest, secret-looking lines never recorded),
  toggleable in Settings with one-tap history clear. Tap fills the line for
  review — nothing sends until you press Enter.
- Macros: save name + command (encrypted), tap to fill, ▶ to run at once.

### App lock + biometric robustness
- Optional full app lock (Settings → Privacy): system biometrics/device PIN
  on every launch and return; rotation doesn't re-prompt, backing out sends
  the app to background instead of stranding it.
- Why you never saw a biometric prompt before: biometrics only gate the
  opt-in per-account *guarded vault* mode — default modes never prompt.

### Find, scrollback, links
- Find bar in the terminal (toolbar 🔍): searches the whole buffer incl.
  scrollback, match count, prev/next with auto-scroll, current-hit highlight.
- Scrollback size setting (1k–50k lines, default 5k); open tabs reopen with
  their last 200 lines after process death (live tabs always keep everything).
- URLs in output are tappable links (long-press still selects); select-all
  keeps working through the native Android selection menu.

## 1.4.2 (versionCode 7)

### Adaptive viewport (redraws on every geometry change)
- The terminal measures its real character cells and resizes buffer + server
  pty together (SIGWINCH), so `btop`/`opencode` redraw on font size, key-row,
  keyboard, fullscreen and rotation changes instead of staying stuck at 80x24.
  New channels open at the current size; content survives resizes.
- Fixed a device-crashing `List.removeLast()` (Java 21 API absent on Android)
  in the shrink path — caught by the new tests before it ever shipped.

### Customizable key rows (Settings → Terminal)
- Any order, any position, 1–4 rows: move keys with ‹ ›, remove with ×, add
  unused keys from the picker (no duplicates), add/remove rows, reset.
- `↑`/`↓` render as arrow icons (content-described); everything else keeps
  its text label; unknown ids are skipped, never crash.
- Key spacing slider (0–16dp) with a live preview using the real row
  renderer — what you see is what the terminal shows.

## 1.4.1 (versionCode 6)

### Terminal rendering (the opencode/btop wrapping bug)
- Root cause of cut-off logos and wrapped lines: Compose `Text` soft-wrapped
  the 80-cell buffer lines at the view width (worse at large font sizes), so
  every long line looked sliced — Termius never wraps. The terminal screen now
  uses `softWrap = false` with horizontal scroll: buffer lines map 1:1 to
  visual rows at any font size.
- Proof: a real captured opencode frame replayed through the buffer renders
  byte-perfect (new `OpencodeFrameTest` regression test ships the captured
  stream); the corruption lived purely in the Compose layer.
- Robustness alongside: APC/SOS/PM strings (kitty graphics etc.) are swallowed
  instead of leaking payload text on screen; tmux-wrapped DCS is unwrapped
  and parsed instead of skipped.

## 1.4.0 (versionCode 5)

### Keyboard & function keys
- Top key row is now `Esc Tab Ctrl ← ↑ ↓ → Alt AltGr`; remaining keys moved to
  row 2 (`Enter` + `Home/End/PgUp/PgDn/Ins/Del` + symbols) and row 3 (`F1–F12`).
- Removed the old `CTRL`/`ALT` toolbar chips — modifiers live in the top row.
- `Ctrl`/`Alt`/`AltGr` are one-shot: tap arms the next keypress only
  (e.g. tap `Ctrl`, then `b`), double-tap locks sticky, third tap releases.
  Locked state shows `▪`, one-shot shows `•`.
- Modifiers apply to every send path (typed text, extended keys, `Enter`) with
  correct xterm sequences (`Ctrl+arrows` → `ESC[1;5A…`, `Alt+special` → extra
  `ESC` prefix, `Ctrl+F1` → `ESC[1;5P`, …); `AltGr` sends `Alt`-style `ESC` prefix.

### Connections, auth & keys
- Tapping a host card or ▶ connects instantly when auth is already saved
  (stored password or key); the auth dialog only appears when setup is missing.
- Long-press a host to pin/unpin, **edit** (name/host/port/user/key for manual
  AND synced hosts) or delete.
- Open tabs persist across backgrounding and process death and restore as
  one-tap reconnect tabs.
- Terminal auth reloads creds from encrypted storage (plus ephemeral handoff),
  so reconnect works without re-typing after returning to the app.
- Password field hidden when not needed: saved password/key shows a compact
  `Connect`/`Reconnect` row with a `Change` expander instead of a password box;
  key connects allow blank (passphraseless) without friction.
- Changed-host-key error now points to `Forget saved host keys (Settings → Privacy)`.
- Upload guards against overwriting desktop edits: if the server config changed
  since your last pull, upload stops and offers "Pull first" or "Upload anyway".
- Corrupt local data is quarantined to `<key>.corrupt-bak` before any
  overwriting save instead of being silently wiped.

### Terminal emulation (btop / tmux / vim / opencode)
- Full alternate-screen support (`1047/1048/1049` with cursor save/restore/clear).
- Scroll margins (`DECSTBM`), insert/delete lines (`L/M`), insert/delete/erase
  chars (`@/P/X`), erase display/line variants (`J/K` 0/1/2/3 incl. scrollback
  clear), scroll regions (`S/T`), vertical moves (`E/F/d`), cursor save/restore
  (`s/u`, `ESC 7/8`), index/reverse-index (`ESC D/M`), next-line (`ESC E`).
- `DECSET/DECRST` handling: cursor visibility (`?25`), wrap (`?7`), bracketed
  paste (`?2004`) and mouse modes (`?1000…`) accepted as no-ops; `DSR ?5n/?6n`
  replies are queued and written back so full-screen apps never stall.
- SGR additions: dim (`2`), underline (`4/24`), reverse (`7/27`) with true
  reverse-video rendering, plus existing 256/truecolor approximation.
- Split-safe input: incomplete trailing UTF-8 and split escape sequences are
  carried into the next `feed()` instead of corrupting output.
- Double-width glyphs: CJK/Hangul/fullwidth advance 2 columns (overwrites stay
  aligned, wraps whole like xterm); emoji pairs advance 2; box drawing,
  braille and blocks stay 1 column. Verified live against `tmux`, `btop`,
  `vim`, `less` and the `opencode` TUI.
- Connection drains terminal replies back to the server; added `setPtySize()`
  for future dynamic resizing.

### Fullscreen
- New fullscreen mode (toolbar button + `Settings → Terminal` default): hides
  headers, tab strip, toolbar and bottom nav — only the terminal screen remains.
- In fullscreen, key rows appear while the input is focused / keyboard is open
  (respecting the key-rows setting); translucent exit button overlays the screen.

### Demo shell (no server needed)
- New on-device demo shell (`/system/bin/sh` via pipes with a small cooked-mode
  line discipline: echo, backspace, `Ctrl+C` clears, `Ctrl+D` exits).
- Entry points: `Terminal → + → Try the demo shell`, the empty-terminal card,
  and the empty-hosts card. Basic commands (`ls`, `echo`, …) stream live;
  full-screen TUIs still need real SSH (no pty on-device).
- Demo tabs auto-start (no auth UI), restart with one tap, survive the tab
  restore path, and are never synced or uploaded.

### Motion
- Terminal tabs swipe-slide in tab order with fade (`AnimatedContent`).
- `Hosts ↔ Terminal ↔ Settings` cross-slide in tab order with fade.
- Toolbar/fullscreen chrome and key rows fade/expand in and out; host cards
  animate placement on pin/reorder.

### Settings & privacy
- Settings redesigned: concise one-line rows, full-row-tappable switches
  (tapping anywhere on the row toggles — the old switch-only target is what
  made the screenshot toggle feel broken), exact font-size entry by tapping
  the size, trimmed prose throughout.
- Privacy switch additionally hardened: immediate effect, re-applied on
  resume AND on window focus, so the toggle reliably enables screenshots.
- Font size effectively unlimited (`1–256sp`, was `10–20sp`) in Settings and in
  per-tab +/- controls; the minimum of 1 only guards against invalid
  zero/negative sizes that crash text layout.
- Terminal settings now document fullscreen, key-row layout and one-shot
  behavior alongside follow-output and key rows.

### General
- Version bump `1.3.0 (4)` → `1.4.0 (5)`.
- Test suite `65` → `134` unit tests (alt-screen, margins, erase/insert/delete,
  DSR, cursor visibility, split UTF-8, modifier mappings, demo-shell line
  discipline, unlimited font prefs, vault format errors, known_hosts replace,
  secret `toString` redaction, content hashing, wide-char columns).
- First on-device instrumented smoke test (`connectedDebugAndroidTest`: launch →
  demo shell). It caught a real bug: focus/keyboard requests after connect now
  pin to `Main.immediate` instead of assuming the resuming dispatcher.
- Fixed pre-existing `changedKeyIsHardBlock` expectation to match the shipped
  error text.
