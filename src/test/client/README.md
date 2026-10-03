# Native client controls

Build the standalone Fabric test mod with `bash src/test/client/build.sh` and run its focused validation with `python3 src/test/client/test.py`. The output is `build/client-automator/InstanceAutomator.jar`. Stop the owned client completely before replacing that file in its profile.

The bridge accepts authenticated loopback requests at `POST /command`, with `X-Automator-Token` and a JSON `op`. Requests dispatch on the native game thread. Inspect `GET /state` for the active screen, visible widgets, cursor and pending input before the next interaction.

- `keys` supports `playerList` alongside movement and inventory keys, with a bounded tick lease.
- `pause-screen` opens the real pause menu of a connected player through native `pauseGame(false)`.
- `chat-screen` opens the real chat screen with optional `text`; `clear-chat` clears its displayed history while preserving command recall.
- `cursor` moves the visible virtual cursor on native screens. `gui` supports widget listing, native click, key, text and close operations. GUI clicks translate protocol left/right buttons to native SDL buttons; container slot clicks retain protocol buttons.
- `anvil-name` types into the focused native anvil text field. `sign-text` selects line 0–3 before typing. Both accept `text`, `replace` and `ticksPerChar`; input is bounded to 512 printable characters and 1–20 ticks per character. Wait until `textPending` is false before submitting or changing screens.
- `server-list` opens the actual multiplayer screen from a disconnected client with an explicit `127.0.0.1:<port>` address and optional display name.

These controls operate real client UI. They do not insert synthetic chat messages, alter renderer output or substitute screenshots for recorded playback.
