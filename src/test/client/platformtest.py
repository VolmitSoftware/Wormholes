import argparse
import fcntl
import json
from pathlib import Path
import re
import shutil
import subprocess
import time
import tomllib
import urllib.error
import urllib.request
import uuid
import zipfile

from gametest import QA_WORKSPACE, ROOT, dh_jar, groovy, stop, stop_owned_client, supported_dh_pack, version_manifest


def native_bridge(game_version: str, platform: str) -> Path:
    directory: Path = ROOT / "build/client-automator"
    if game_version != "26.3":
        directory /= game_version
    directory /= platform
    jar: Path = directory / "InstanceAutomator.jar"
    if not jar.is_file():
        raise FileNotFoundError("Build the hidden bridge first: AUTOMATOR_MINECRAFT_VERSION=" + game_version
                                + " AUTOMATOR_PLATFORM=" + platform + " bash src/test/client/build.sh")
    metadata: dict = json.loads((directory / "compile-classpath.json").read_text())
    if metadata.get("components", {}).get("net.minecraft") != game_version:
        raise ValueError("The native bridge was not compiled for Minecraft " + game_version)
    with zipfile.ZipFile(jar) as archive:
        mixins: dict = json.loads(archive.read("instance-automator.mixins.json"))
        required: set[str] = {"WindowMixin", "RenderSystemMixin", "MouseHandlerMixin", "InputConstantsMixin"}
        if mixins.get("required") is not True or not required.issubset(mixins.get("client", [])):
            raise ValueError("The native bridge is missing required hidden precreation/input mixins")
        package: str = mixins["package"].replace(".", "/")
        for name in required:
            if package + "/" + name + ".class" not in archive.namelist():
                raise ValueError("The native bridge is missing " + name)
        if "fabric.mod.json" in archive.namelist():
            raise ValueError("The native platform requires its own bridge build")
    return jar


def native_mod(path: Path, mod_id: str) -> dict:
    with zipfile.ZipFile(path) as archive:
        if "META-INF/neoforge.mods.toml" not in archive.namelist():
            raise ValueError("The " + mod_id + " jar does not provide a native NeoForge mod")
        metadata: dict = tomllib.loads(archive.read("META-INF/neoforge.mods.toml").decode())
    if not any(mod.get("modId") == mod_id for mod in metadata.get("mods", [])):
        raise ValueError("The native jar does not declare mod " + mod_id)
    return metadata


def native_dh(path: Path, game_version: str) -> dict:
    metadata: dict = dh_jar(path, game_version)
    native: dict = native_mod(path, "distanthorizons")
    dependencies: list[dict] = native.get("dependencies", {}).get("distanthorizons", [])
    declared: list[str] = [dependency.get("versionRange", "") for dependency in dependencies
                           if dependency.get("modId") == "minecraft"]
    if len(declared) != 1 or declared[0] not in {"[" + game_version + "]", "[" + game_version + ".0]"}:
        raise ValueError("The DH native metadata does not declare Minecraft " + game_version + " support")
    if not any(dependency.get("modId") == "neoforge" for dependency in dependencies):
        raise ValueError("The DH jar does not declare a NeoForge runtime")
    return metadata


def cached_native_mod(coordinate: str, mod_id: str) -> Path:
    group, artifact, version = coordinate.split(":")
    directory: Path = Path.home() / ".gradle/caches/modules-2/files-2.1" / group / artifact / version
    candidates: list[Path] = list(directory.glob("*/" + artifact + "-" + version + ".jar"))
    if len(candidates) != 1:
        raise FileNotFoundError("Build the selected platform to resolve its pinned " + coordinate + " dependency")
    native_mod(candidates[0], mod_id)
    return candidates[0]


def launch_command(args: argparse.Namespace, script: Path, target: dict) -> list[str]:
    task: str = "runClientGameTest" if args.platform == "neoforge" else "runClientGameTestClient"
    loader_property: str = "neoForgeVersion" if args.platform == "neoforge" else "forgeVersion"
    return [str(ROOT / "gradlew"), "--init-script", str(script), task,
            "-PminecraftVersion=" + args.minecraft_version,
            "-P" + loader_property + "=" + target["loaders"][args.platform], "--console=plain", "--no-daemon"]


def init_script(platform: str, run: Path, resources: Path, bridge: Path, properties: Path) -> str:
    configure = """
        project.extensions.getByName('neoForge').runs.named('clientGameTest').configure { run ->
            run.gameDirectory.set(runDirectory)
            run.systemProperties.putAll(properties)
            if (System.getProperty('os.name').toLowerCase(Locale.ROOT).contains('mac')) {
                run.jvmArgument('-XstartOnFirstThread')
            }
        }
""" if platform == "neoforge" else """
        project.extensions.getByName('minecraft').runs.named('client').configure { run ->
            run.workingDir.set(runDirectory)
            run.systemProperties(properties)
            if (System.getProperty('os.name').toLowerCase(Locale.ROOT).contains('mac')) {
                run.jvmArgs('-XstartOnFirstThread')
            }
        }
        project.tasks.named('clientGameTestJar').configure {
            manifest.attributes('MixinConfigs': 'wormholes_clientgametest.mixins.json,wormholes_clientgametest_driver.mixins.json,instance-automator.mixins.json')
        }
        project.tasks.named('prepareClientGameTestMods').configure {
            destinationDir = new File(runDirectory, 'mods')
        }
"""
    return """import groovy.json.JsonSlurper
gradle.beforeProject { Project project ->
    project.afterEvaluate {
        if (project != project.rootProject || project.name != PLATFORM_PROJECT) {
            return
        }
        File runDirectory = new File(RUN)
        Map<String, String> properties = new JsonSlurper().parse(new File(PROPERTIES)).collectEntries { key, value -> [(key): String.valueOf(value)] }
        project.sourceSets.clientGameTest.resources.setSrcDirs([new File(RESOURCES)])
        project.sourceSets.clientGameTest.output.dir(new File(BRIDGE))
        CONFIGURE
    }
}
""".replace("RUN", groovy(str(run))).replace("PROPERTIES", groovy(str(properties))).replace("RESOURCES", groovy(str(resources))).replace("BRIDGE", groovy(str(bridge))).replace("PLATFORM_PROJECT", groovy("wormholes-" + platform)).replace("CONFIGURE", configure)


def execute(args: argparse.Namespace) -> int:
    target: dict = version_manifest(args.minecraft_version)
    dh_acceptance: bool = args.suite == "dh" or args.filter == "dh-acceptance-singleplayer"
    if dh_acceptance:
        if args.platform != "neoforge":
            raise ValueError("Native DH acceptance requires --platform neoforge")
        if args.filter not in {None, "dh-acceptance-singleplayer"}:
            raise ValueError("--suite dh requires the dh-acceptance-singleplayer filter")
        if args.distant_horizons is None:
            raise ValueError("Native DH acceptance requires a version-matched --distant-horizons jar")
    if args.filter is None:
        args.filter = "dh-acceptance-singleplayer" if dh_acceptance else "cross-dimension-singleplayer-frame"
    shader: Path | None = args.shader_pack.resolve() if args.shader_pack is not None else None
    distant_horizons: Path | None = args.distant_horizons.resolve() if args.distant_horizons is not None else None
    if args.platform not in target["renderRuntimeLoaders"] and (args.renderer != "vanilla" or shader is not None or distant_horizons is not None):
        raise ValueError("Minecraft " + args.minecraft_version + " " + args.platform + " has no native Sodium, Iris or DH release")
    if shader is not None and (not shader.exists() or args.renderer != "sodium"):
        raise ValueError("--shader-pack requires an existing pack and --renderer sodium")
    dh_metadata: dict | None = None
    if distant_horizons is not None:
        dh_metadata = native_dh(distant_horizons, args.minecraft_version)
        if shader is not None:
            supported_dh_pack(shader)
    bridge_jar: Path = native_bridge(args.minecraft_version, args.platform)
    dependencies: dict[str, str] = target["renderDependencies"][args.platform]
    renderer_mods: dict[str, Path] = {}
    if args.renderer == "sodium":
        for name in ["sodium"] + (["iris"] if shader is not None else []):
            renderer_mods[name] = cached_native_mod(dependencies[name], name)
    run_id = str(uuid.uuid4())
    evidence = ROOT / ".qa/native-client" / run_id
    evidence.mkdir(parents=True)
    run = QA_WORKSPACE / ("wormholes-client-" + run_id)
    run.mkdir(parents=True)
    marker = run / ".wormholes-hidden-qa"
    marker.write_text(run_id)
    token = uuid.uuid4().hex
    states: list[dict] = []
    process: subprocess.Popen | None = None
    success = False
    failure: str | None = None
    try:
        bridge = evidence / "bridge"
        with zipfile.ZipFile(bridge_jar) as archive:
            archive.extractall(bridge)
        resources = evidence / "resources"
        resources.mkdir()
        for source in [ROOT / "adapters/modded-common/src/clientGameTest/resources",
                       ROOT / "adapters/modded-common/src/clientGameTestDriver/resources",
                       ROOT / "adapters" / args.platform / "src/clientGameTest/resources",
                       ROOT / "adapters/modded-common/src" / args.minecraft_version / "clientGameTest/resources",
                       ROOT / "adapters" / args.platform / "src" / args.minecraft_version / "clientGameTest/resources"]:
            if source.is_dir():
                shutil.copytree(source, resources, dirs_exist_ok=True)
        shutil.copy2(bridge / "instance-automator.mixins.json", resources)
        (bridge / "instance-automator.mixins.json").unlink()
        (bridge / "META-INF/MANIFEST.MF").unlink(missing_ok=True)
        if args.platform == "neoforge":
            metadata = resources / "META-INF/neoforge.mods.toml"
            metadata.write_text(metadata.read_text() + '\n[[mixins]]\nconfig = "instance-automator.mixins.json"\n')
        config = run / "config"
        config.mkdir()
        (config / "fml.toml").write_text("earlyWindowControl = false\nversionCheck = false\n")
        if renderer_mods:
            mods = run / "mods"
            mods.mkdir()
            for mod in renderer_mods.values():
                shutil.copy2(mod, mods / mod.name)
        if distant_horizons is not None:
            mods = run / "mods"
            mods.mkdir(exist_ok=True)
            shutil.copy2(distant_horizons, mods / distant_horizons.name)
        if shader is not None:
            packs = run / "shaderpacks"
            packs.mkdir()
            if shader.is_dir():
                shutil.copytree(shader, packs / shader.name)
            else:
                shutil.copy2(shader, packs / shader.name)
            (config / "iris.properties").write_text("enableShaders=true\nshaderPack=" + shader.name + "\n")
        (run / "options.txt").write_text("soundCategory_master:0.0\nonboardAccessibility:false\n")
        properties = evidence / "properties.json"
        properties.write_text(json.dumps({"automator.hidden": "true", "automator.muted": "true",
            "automator.frameWidth": "1920", "automator.frameHeight": "1080", "automator.port": "0",
            "automator.token": token, "automator.output": str(run / "automator"),
            "wormholes.test.dhShutdown": str(distant_horizons is not None).lower(),
            "wormholes.test.dhAcceptance": str(dh_acceptance).lower(),
            "wormholes.test.dhShaders": str(shader is not None).lower(),
            "wormholes.clientgametest": "true", "wormholes.clientgametest.filter": args.filter}))
        script = evidence / "launch.init.gradle"
        script.write_text(init_script(args.platform, run, resources, bridge, properties))
        log = evidence / "runtime.log"
        print("Evidence: " + str(evidence), flush=True)
        if distant_horizons is not None and not dh_acceptance:
            print("DH coverage: native startup/traversal/shutdown; indexed LOD and composed pixels are not asserted", flush=True)
        with log.open("w") as output:
            process = subprocess.Popen(launch_command(args, script, target),
                cwd=ROOT / "adapters" / args.platform, stdout=output, stderr=subprocess.STDOUT, start_new_session=True)
            started = time.monotonic()
            port: int | None = None
            progress = started
            while process.poll() is None:
                text = log.read_text(errors="replace")
                if "Closing FML Loader" in text and "Instance Automator listening on" not in text:
                    raise RuntimeError("Native loader closed before hidden client startup; inspect " + str(log))
                if "WORMHOLES_CLIENT_GAME_TEST_FAIL" in text or "WORMHOLES_DH_ACCEPTANCE_FAIL" in text:
                    raise RuntimeError("Native platform fixture failed; inspect " + str(log))
                if time.monotonic() - started > args.timeout:
                    raise TimeoutError("Native platform fixture exceeded its timeout")
                match = re.search(r"Instance Automator listening on 127\.0\.0\.1:(\d+)", text)
                if match is not None:
                    port = int(match.group(1))
                if port is not None:
                    request = urllib.request.Request("http://127.0.0.1:" + str(port) + "/state", headers={"X-Automator-Token": token})
                    try:
                        with urllib.request.urlopen(request, timeout=3) as response:
                            state = json.load(response)
                    except (urllib.error.URLError, TimeoutError, ConnectionError):
                        state = None
                    if state is not None:
                        states.append({key: state.get(key) for key in ["frames", "ticks", "connected", "hiddenRenderer",
                            "frameWidth", "frameHeight", "masterVolume", "windowVisible", "windowFocused",
                            "windowMouseGrabbed", "relativeMouseMode", "mouseGrabbed"]})
                        if state.get("hiddenRenderer") is not True or any(state.get(key) is not False for key in
                            ["windowVisible", "windowFocused", "windowMouseGrabbed", "relativeMouseMode", "mouseGrabbed"]):
                            raise AssertionError("Hidden client visibility/focus/mouse invariant failed: " + json.dumps(states[-1], sort_keys=True))
                        if state.get("masterVolume") != 0.0 or (state.get("frameWidth"), state.get("frameHeight")) != (1920, 1080):
                            raise AssertionError("Native client must stay muted at 1920x1080")
                if time.monotonic() - progress >= 20:
                    print("Hidden " + args.platform + " client running: " + str(round(time.monotonic() - started)) + "s", flush=True)
                    progress = time.monotonic()
                time.sleep(1)
            text = log.read_text(errors="replace")
            if "WORMHOLES_CLIENT_GAME_TEST_FAIL" in text or "WORMHOLES_DH_ACCEPTANCE_FAIL" in text:
                raise RuntimeError("Native platform fixture failed; inspect " + str(log))
            if process.returncode != 0 or not re.search(r"WORMHOLES_CLIENT_GAME_TEST_PASS \[[^\]]+\]", text):
                raise RuntimeError("Missing successful native platform fixture result")
            if not all(marker in text for marker in ["WORMHOLES_CLIENT_MIXIN_AUDIT complete",
                "WORMHOLES_CLIENT_LIGHTING_MIXIN_PASS", "WORMHOLES_CLIENT_LIGHTING_SCOPE_PASS"]):
                raise RuntimeError("Missing successful native mixin audit or lighting assertions; inspect " + str(log))
            if dh_acceptance:
                required: list[str] = ["WORMHOLES_DH_ACCEPTANCE_PASS shaders=" + str(shader is not None).lower()
                    + " native=true fixture=dh-acceptance-singleplayer", "WORMHOLES_DH_CLIP_SCOPE_PASS",
                    "WORMHOLES_DH_COMPOSED_DESTINATION_PASS", "WORMHOLES_DH_COMPOSED_FLOOR_PASS",
                    "WORMHOLES_DH_HISTORY_EXPIRY_PASS", "WORMHOLES_DH_WORLD_CLOSE_PASS", "WORMHOLES_DH_SESSION_PASS"]
                if not all(marker in text for marker in required):
                    raise AssertionError("Missing successful native DH image, history, clipping, session or world-close assertions")
                dh_evidence: dict = json.loads((run / "dh-acceptance.json").read_text())
                if dh_evidence.get("failure") is not None or dh_evidence.get("shaders") is not (shader is not None):
                    raise AssertionError("Native DH GPU evidence contains a failure or incorrect shader mode")
            if any(marker in text for marker in ["View stream protocol reset",
                "after repeated protocol violations", "is unavailable after FRAME failure", "Could not force-load"]):
                raise RuntimeError("Native rendering or protocol failure; inspect " + str(log))
            if "Exception stopping the server" in text or "Wormholes shutdown failed" in text:
                raise RuntimeError("Native server shutdown failed; inspect " + str(log))
            if len(states) < 2 or states[-1]["frames"] <= states[0]["frames"] or not any(state["connected"] for state in states):
                raise AssertionError("Missing connected hidden client with advancing GPU frames")
            success = True
    except (RuntimeError, AssertionError, TimeoutError, OSError, json.JSONDecodeError, KeyboardInterrupt) as error:
        failure = str(error) or type(error).__name__
        print(failure, flush=True)
    finally:
        if process is not None:
            stop(process)
        stop_owned_client(run)
        for name in ["logs", "crash-reports", "automator", "screenshots", "dh-shaders"]:
            if (run / name).is_dir():
                shutil.copytree(run / name, evidence / name, dirs_exist_ok=True)
        if (run / "dh-acceptance.json").is_file():
            shutil.copy2(run / "dh-acceptance.json", evidence / "dh-acceptance.json")
        (evidence / "hidden-states.json").write_text(json.dumps(states, indent=2) + "\n")
        (evidence / "result.json").write_text(json.dumps({"passed": success, "platform": args.platform,
            "minecraftVersion": args.minecraft_version, "renderer": args.renderer,
            "loaderVersion": target["loaders"][args.platform], "bridge": str(bridge_jar),
            "renderDependencies": {name: dependencies[name] for name in renderer_mods},
            "shaderPack": str(shader) if shader is not None else None,
            "distantHorizons": str(distant_horizons) if distant_horizons is not None else None,
            "distantHorizonsVersion": dh_metadata["version"] if dh_metadata is not None else None,
            "distantHorizonsPixelAssertions": success and dh_acceptance,
            "suite": "dh" if dh_acceptance else "traversal",
            "filter": args.filter, "failure": failure}, indent=2) + "\n")
        (evidence / "properties.json").unlink(missing_ok=True)
        assert run.parent.resolve() == QA_WORKSPACE.resolve() and marker.read_text() == run_id
        shutil.rmtree(run)
    print("PASS" if success else "FAIL", flush=True)
    return 0 if success else 1


def main() -> int:
    parser = argparse.ArgumentParser()
    parser.add_argument("--platform", choices=["forge", "neoforge"], required=True)
    parser.add_argument("--minecraft-version", choices=["26.2", "26.3"], default="26.2")
    parser.add_argument("--suite", choices=["traversal", "dh"], default="traversal")
    parser.add_argument("--filter")
    parser.add_argument("--renderer", choices=["vanilla", "sodium"], default="vanilla")
    parser.add_argument("--shader-pack", type=Path)
    parser.add_argument("--distant-horizons", type=Path)
    parser.add_argument("--timeout", type=int, default=1200)
    args = parser.parse_args()
    lock_path = ROOT / ".qa/native-client.lock"
    lock_path.parent.mkdir(parents=True, exist_ok=True)
    with lock_path.open("w") as lock:
        fcntl.flock(lock, fcntl.LOCK_EX | fcntl.LOCK_NB)
        return execute(args)


if __name__ == "__main__":
    raise SystemExit(main())
