# Wormholes demonstration studio

Records framed 3×3 wand construction, rune construction and linking/traversal in real Minecraft 26.3 clients. Every demonstration has a first-person and observer recording, both with and without the Wormholes Fabric mod: six takes and twelve wiki clips.

## Preparation

Run commands from the Wormholes project root. Java 25, Prism Launcher, Multiplexor, ffmpeg/ffprobe and cached Minecraft 26.3/Fabric 0.19.5/LWJGL 3.4.3 libraries are required. The studio uses four persistent Prism profiles named `Instance Automator - 1` through `Instance Automator - 4`, with two running at a time. Keep these profiles available and their game windows visible during capture.

`studio.py` selects the base Prism profile and actor/observer names; the actor name must match `fixture/resources/config.yml`. The base profile must contain compatible 26.3 mods and the desired resource/shader settings. The scene fixture builds its own flat-world garden and courtyard, independent of Adapt's scenery.

Prepare `build/demo/skin-profile.json` with a Mojang session-profile response containing the chosen skin's signed `textures` property. This local file must contain a nonempty `properties` array with a `textures` entry carrying `value` and `signature`. It is required before server creation; client readiness also waits for the actor's non-default skin. Keep this profile and all runtime credentials in ignored output directories.

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
python3 src/test/demo/demo.py
python3 src/test/demo/docs_sync.py
```

The default run records both client variants, exports all twelve 1920×1080/30 fps VP9 WebM clips, and stops/deletes its disposable Multiplexor instance. The clients remain installed in Prism. A studio lock prevents simultaneous runs from sharing profiles or overwriting output.

Use `--variant standard` or `--variant clientview` for one pair of clients; use `--only wand-creation`, `--only rune-creation` or `--only portal-linking` for one demonstration. `--record-only` preserves raw MP4 captures without exporting. `--keep-open` leaves the owned server and clients running for inspection; subsequent `--reuse-server --reuse-clients` runs must select the same single variant. Stop and delete that owned instance when inspection ends.

Raw captures, screenshots, assertions and timing records are retained under `build/demo/`. Capture acceptance requires at least 28 recorded frames per second and no encoder queue drops. Both camera angles must be visually reviewed for the real trigger, retained frame and result; portal linking must show projection and both crossings. Confirm the final clips decode and remain under 25 MB each. Live captures are silent.

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
python3 src/test/demo/docs_sync.py
```

Intentional wiki assets live in `../docs/wormholes-assets/demos/`. `docs_sync.py` requires all twelve nonempty clips, inserts construction demonstrations in `03-building-portals.md` and linking in `04-portal-types-menus-settings.md`, and preserves existing prose. Repeating synchronization leaves unchanged pages untouched. Visible clips autoplay muted and loop. Each demo selects its client variant independently; First person and Third person tabs synchronize with Adapt's demos and remember the selection across pages.

## Automator bridge

The studio assigns fresh per-client `automator.port`, `automator.token` and `automator.output` JVM properties. The authenticated HTTP bridge listens only on IPv4 loopback, rejects browser origins, and dispatches input on the game thread. Keep `build/demo/clients.json`, profile JVM settings, server properties and logs local and ignored; do not include them with wiki assets.
