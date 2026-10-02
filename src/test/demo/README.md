# Wormholes demonstration studio

Records Wormholes construction, menus, projection, travel and dimensional doors in real Minecraft 26.3 clients. Each selected demonstration requires four wiki clips: first person and observer, each with and without the Wormholes Fabric mod. Available scenarios and their assertions live in `shots.json`; a scenario entry does not establish that footage has been recorded or accepted.

## Preparation

Run commands from the Wormholes project root. Java 25, Prism Launcher, Multiplexor, ffmpeg/ffprobe and cached Minecraft 26.3/Fabric 0.19.5/LWJGL 3.4.3 libraries are required. The studio uses four persistent Prism profiles named `Instance Automator - 1` through `Instance Automator - 4`, with two running at a time. Preserve these profiles. Clients render through the GPU with hidden SDL windows, without taking focus or grabbing or moving the desktop cursor. The studio disables Dynamic FPS in these profiles so hidden clients retain the recording frame rate.

`studio.py` selects the base Prism profile and actor/observer names; the actor name must match `fixture/resources/config.yml`. The base profile must contain compatible 26.3 mods and the desired resource settings. The studio disables shaders before connecting and capturing. The scene fixture builds its own flat-world garden and courtyard, independent of Adapt's scenery.

Prepare `build/demo/skin-profile.json` with a Mojang session-profile response containing the chosen skin's signed `textures` property. This local file must contain a nonempty `properties` array with a `textures` entry carrying `value` and `signature`. It is required before server creation; client readiness also waits for the actor's non-default skin. Keep this profile and all runtime credentials in ignored output directories.

Set both `WORMHOLES_DEMO_OUTPUT` and `WORMHOLES_DEMO_PROFILE_PREFIX` to isolate another studio. The output directory holds its lock, server/client state, skin profile and raw media; the profile prefix selects `<prefix> - 1` through `<prefix> - 4`. Use a separate absolute output path under ignored `build/`, supply its `skin-profile.json`, and keep the same environment for recording, reuse and export. Separate studios must use different output directories and profile prefixes. Final wiki asset paths are shared, so concurrent runs must select different demonstration IDs. Build shared jars serially before starting either studio.

```sh
export WORMHOLES_DEMO_OUTPUT="$PWD/build/demo-doors"
export WORMHOLES_DEMO_PROFILE_PREFIX="Wormholes Doors"
```

Build the server plugin, Fabric client mod, automator and scene fixture serially:

```sh
./gradlew build packedJar
./gradlew -p adapters/fabric build
bash src/test/client/build.sh
bash src/test/demo/fixture/build.sh
```

The current studio selects the runtime jars named in `studio.py`. Update those paths when the package version changes. Run the source checks before recording:

```sh
python3 src/test/client/test.py
python3 -m unittest discover -s src/test/demo/tests -p 'test_*.py' -v
```

## Recording and export

```sh
python3 src/test/demo/demo.py --only door-crafting --only pair-doors
python3 src/test/demo/docs_sync.py --only door-crafting --only pair-doors
```

Without `--only`, the director selects the entire shot sheet. By default it records both client variants, exports the selected 1920×1080/30 fps VP9 WebM clips, and stops/deletes its disposable Multiplexor instance. The clients remain installed in Prism. The output-directory lock prevents two runs from using that same studio output.

Use `--variant standard` or `--variant clientview` for one pair of clients. Repeat `--only <id>` to select multiple demonstrations; the director uses shot-sheet order. The synchronizer also accepts repeated `--only`, and requires all four nonempty clips for every selected ID before changing any page. A single-variant recording is not sufficient for synchronization. `--record-only` preserves raw MP4 captures without exporting. `--keep-open` leaves the owned server and clients running for inspection; subsequent `--reuse-server --reuse-clients` runs must select the same single variant. Stop and delete that owned instance when inspection ends.

| Subject | Demonstration IDs |
|---|---|
| Construction and linking | `wand-creation`, `rune-creation`, `portal-linking` |
| Portal appearance | `mirrors`, `portal-orientation`, `ambient-particles`, `atmosphere` |
| Projection | `render-panoptic`, `render-venticular`, `projection-toggle`, `live-views`, `nested-views` |
| Transit | `arrival-orientation`, `momentum`, `membrane-bounce` |
| Routing | `rtp-routing`, `rtp-personal`, `network-dialing`, `redstone-control`, `gateways`, `cross-server-gateways` |
| Dimensional doors | `door-crafting`, `pair-doors`, `door-open-state`, `trapdoor-travel` |
| Pockets | `personal-pockets`, `public-pockets` |

`door-crafting` shows real pair, personal and public recipes. `pair-doors` begins with a supplied kit and shows unpacking, placement, travel and relocation. Personal pockets, public pockets and personal RTP use the observer client as a second traveler; their observer clips are labeled Second player.

Use `--only cross-server-gateways` for the two-server demonstration. Before each client variant, the director disconnects both clients, prepares the disposable peer servers, reconnects the same clients to the primary server, and verifies their rendering and negotiated projection modes. Cleanup closes both servers and keeps their evidence in separate files; `--keep-open` preserves both for inspection. The `gateways` shot instead exercises a second world on one server.

Fresh studio servers enable `atmosphere.biome-tint` and `atmosphere.sky-light` in `plugins/Wormholes/wormholes.toml` before the first boot so all four atmosphere modes are available. Reused servers need both flags enabled for the same demonstration.

Raw captures, screenshots, assertions and timing records are retained under the selected output directory (`build/demo/` by default). Recording captures the game's live framebuffer, including its actual UI, effects and portal rendering. Capture acceptance requires at least 28 recorded frames per second and no encoder queue drops. Both camera angles must be visually reviewed against the shot's assertions; inventory footage must visibly show the actual screen and result. Confirm the final clips decode and remain under 25 MB each. Live captures are silent.

The recorder preserves elapsed time during shader rebuilds and other render stalls by holding the last captured framebuffer at the requested video frame rate. These pauses remain visible. `captureFrames` counts real captured frames; `captureEncodedFrames`, `captureRepeatedFrames`, and `captureMaxGapFrames` separately report total video frames, held frames, and the longest consecutive hold in frame slots. Repeated frames do not count toward the existing real-frame acceptance gate.

`captureHolds` lists writer-generated holds of at least 15 frames as `{startFrame, endFrame}` ranges in the encoded timeline. Indices start at zero; `startFrame` is included and `endFrame` is excluded. Every listed frame is a repeat, so removing only those ranges preserves real frames and shorter pacing holds. `captureRepeatedFrames` counts all repeats, including those shorter holds; the sum of listed range lengths counts only the longer holds. The recorder does not infer freezes from similar images or trim captures automatically.

Before capture, each client has five seconds to report a native 1920×1080 framebuffer and safe hidden state: `hiddenRenderer=true`, with `windowVisible`, `windowFocused`, `mouseGrabbed`, `windowMouseGrabbed` and `relativeMouseMode` all false. Missing or unsafe state aborts preparation. The recorder also rejects a source framebuffer whose dimensions differ from the requested output; export scaling cannot substitute for native render size.

To export accepted raw takes again without recording, run:

```sh
python3 - <<'PY'
import sys
sys.path.insert(0, 'src/test/demo')
from studio import export
for identifier in ('wand-creation', 'rune-creation', 'portal-linking'):
    for variant in ('standard', 'clientview'):
        export(identifier, variant)
PY
python3 src/test/demo/docs_sync.py --only wand-creation --only rune-creation --only portal-linking
```

Intentional wiki assets live in `../docs/wormholes-assets/demos/`. `docs_sync.py` maps each selected demonstration into its matching reference section and preserves existing prose and unselected demonstrations. Without `--only`, it requires all four clips for every mapped ID. Repeating synchronization leaves unchanged pages untouched. Visible clips autoplay muted and loop. Each demo has No client mod and Client mod tabs with an independent client selection. A separate Camera dropdown selects First person or Third person (Second player for the traveler comparisons), synchronizes with Adapt's demos, and remembers the selection across pages.

## Automator bridge

The studio stores per-profile `automator.port`, `automator.token` and `automator.output` JVM properties. Existing profiles reuse their bridge port and token when their stored output path matches the selected studio; a mismatched output path is rejected. New profiles receive fresh credentials. `automator.hidden=true` is the bridge default, including when the launcher omits the property. Hidden mode configures SDL as a background application before video initialization, creates an invisible window, skips desktop presentation, and suppresses native cursor selection, grabs, releases and warps. Bridge input and GPU framebuffer rendering remain active. The authenticated HTTP bridge listens only on IPv4 loopback, rejects browser origins, and dispatches input on the game thread. Keep `build/demo/clients.json`, profile JVM settings, server properties and logs local and ignored; do not include them with wiki assets.
