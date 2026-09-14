# Changelog

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
