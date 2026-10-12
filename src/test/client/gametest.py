import argparse
import fcntl
import json
import os
from pathlib import Path
import re
import shutil
import signal
import subprocess
import sys
import time
import urllib.error
import urllib.request
import uuid
import zipfile


ROOT = Path(__file__).resolve().parents[3]
FABRIC = ROOT / "adapters/fabric"
QA_WORKSPACE = ROOT.parent.parent / "[Minecraft Server]/.multiplexor/qa"


def groovy(value: str) -> str:
    return "'" + value.replace("\\", "\\\\").replace("'", "\\'") + "'"


def version_manifest(version: str) -> dict:
    path = ROOT / "gradle/minecraft-versions" / (version + ".json")
    if not path.is_file():
        raise ValueError("Unsupported Minecraft version " + version)
    return json.loads(path.read_text())


def cached_mod(name: str, version: str) -> Path:
    key = "distantHorizons" if name == "distanthorizons" else name
    coordinate = version_manifest(version)["renderDependencies"]["fabric"][key]
    group, artifact, mod_version = coordinate.split(":")
    cache = Path.home() / ".gradle/caches/modules-2/files-2.1" / group / artifact / mod_version
    candidates = list(cache.glob("*/*.jar"))
    if len(candidates) != 1:
        raise FileNotFoundError("Run the Fabric build to resolve its pinned " + name + " dependency first")
    return candidates[0]


def selected_tests(suite: str, names: list[str]) -> list[str]:
    metadata = json.loads((FABRIC / "src/clientGameTest/resources/fabric.mod.json").read_text())
    if not names:
        key = {"edge": "fabric-client-gametest", "compatibility": "wormholes-compatibility-gametest", "shader": "wormholes-shader-gametest", "dh": "wormholes-dh-gametest"}[suite]
        return metadata["entrypoints"][key]
    selected: list[str] = []
    for name in names:
        class_name = name if name.endswith("ClientGameTest") else name + "ClientGameTest"
        source = FABRIC / "src/clientGameTest/java/art/arcane/wormholes/clientgametest" / (class_name + ".java")
        if not source.is_file():
            raise ValueError("Unknown client test: " + name)
        selected.append("art.arcane.wormholes.clientgametest." + class_name)
    return selected


def init_script(run: Path, resources: Path, bridge: Path, sodium: Path | None, token: str, iris: Path | None,
                distant_horizons: Path | None, shader: bool, jvm_args: list[str], baseline: Path | None,
                baseline_resources: Path | None, replaced: list[Path]) -> str:
    dependencies = "fabric.dependencies.add('clientGameTestRuntimeOnly', fabric.files(" + groovy(str(bridge)) + "))"
    if sodium is not None:
        dependencies += "\n    fabric.dependencies.add('clientGameTestRuntimeOnly', fabric.files(" + groovy(str(sodium)) + "))"
    if iris is not None:
        dependencies += "\n    fabric.dependencies.add('clientGameTestRuntimeOnly', fabric.files(" + groovy(str(iris)) + "))"
    if distant_horizons is not None:
        dependencies += "\n    fabric.dependencies.add('clientGameTestRuntimeOnly', fabric.files(" + groovy(str(distant_horizons)) + "))"
    overlay = ""
    if baseline is not None:
        originals = ", ".join(groovy(str(path.resolve())) for path in replaced)
        overlay = """Set replacedSources = [ORIGINALS] as Set
    fabric.sourceSets.main.java.exclude { entry -> replacedSources.contains(entry.file.canonicalPath) || entry.file.name == 'IrisPortalPreparationMixin.java' }
    fabric.sourceSets.main.java.srcDir(OVERLAY)
    fabric.sourceSets.main.resources.exclude { entry -> entry.file.canonicalPath == ORIGINAL_MIXINS }
    fabric.sourceSets.main.resources.srcDir(OVERLAY_RESOURCES)
    fabric.sourceSets.clientGameTest.java.exclude { entry -> entry.file.name == 'ShaderHistoryClientGameTest.java' }
""".replace("ORIGINALS", originals).replace("OVERLAY_RESOURCES", groovy(str(baseline_resources))).replace("OVERLAY", groovy(str(baseline))).replace("ORIGINAL_MIXINS", groovy(str((ROOT / "adapters/modded-common/src/main/resources/wormholes.mixins.json").resolve())))
    arguments = "\n".join("        vmArg(" + groovy(argument) + ")" for argument in jvm_args)
    return """gradle.projectsEvaluated {
    Project fabric = gradle.rootProject
    if (fabric.name != 'wormholes-fabric') {
        return
    }
    File runRoot = new File(RUN_ROOT)
    File resources = new File(TEST_RESOURCES)
    DEPENDENCIES
    OVERLAY_CONFIGURATION
    fabric.sourceSets.clientGameTest.resources.setSrcDirs([resources])
    fabric.tasks.named('processResources').configure {
        outputs.upToDateWhen { false }
    }
    fabric.extensions.getByName('loom').runs.named('clientGameTest').configure {
        runDirectory.set(runRoot)
        startFirstThread()
        programArgs('--width', '1920', '--height', '1080')
        systemProperties.put('fabric.client.gametest.testModResourcesPath', resources.absolutePath)
        systemProperties.put('automator.hidden', 'true')
        systemProperties.put('automator.muted', 'true')
        systemProperties.put('automator.frameWidth', '1920')
        systemProperties.put('automator.frameHeight', '1080')
        systemProperties.put('automator.port', '0')
        systemProperties.put('automator.token', TOKEN)
        systemProperties.put('automator.output', new File(runRoot, 'automator').absolutePath)
        systemProperties.put('wormholes.test.dhShaders', DH_SHADERS)
        systemProperties.put('wormholes.test.fabricPhasedShutdown', 'true')
        systemProperties.put('wormholes.clientgametest.baseline', BASELINE)
        JVM_ARGUMENTS
    }
    fabric.tasks.matching { it.name == 'deleteGameTestRunDir' }.configureEach {
        enabled = false
    }
    fabric.tasks.matching { it.name == 'acceptGameTestEula' }.configureEach {
        eulaFile.set(new File(runRoot, 'eula.txt'))
    }
}
""".replace("RUN_ROOT", groovy(str(run))).replace("TEST_RESOURCES", groovy(str(resources))).replace("DEPENDENCIES", dependencies).replace("TOKEN", groovy(token)).replace("DH_SHADERS", groovy(str(shader).lower())).replace("OVERLAY_CONFIGURATION", overlay).replace("JVM_ARGUMENTS", arguments).replace("BASELINE", groovy(str(baseline is not None).lower()))


def dh_jar(path: Path, game_version: str) -> dict:
    with zipfile.ZipFile(path) as archive:
        metadata = json.loads(archive.read("fabric.mod.json"))
    if metadata.get("id") != "distanthorizons":
        raise ValueError("--distant-horizons requires an official Distant Horizons jar")
    if game_version not in str(metadata.get("depends", {}).get("minecraft", "")):
        raise ValueError("The DH jar does not declare Minecraft " + game_version + " support")
    return metadata


def supported_dh_pack(path: Path) -> None:
    if path.is_dir():
        names = [entry.relative_to(path).as_posix() for entry in path.rglob("*") if entry.is_file()]
    else:
        with zipfile.ZipFile(path) as archive:
            names = archive.namelist()
    for dimension in ["world0", "world-1", "world1"]:
        for program in ["dh_terrain", "dh_water"]:
            for stage in ["vsh", "fsh"]:
                if "shaders/" + dimension + "/" + program + "." + stage not in names:
                    raise ValueError("DH acceptance requires terrain/water shader stages for " + dimension)


def baseline_overlay(source: Path, evidence: Path) -> tuple[Path, Path, list[Path]]:
    replacements: list[Path] = []
    overlay = evidence / "baseline-java"
    resources = evidence / "baseline-resources"
    resources.mkdir()
    prefix = Path("adapters/modded-common/src/main/java")
    for path in source.rglob("*.java"):
        relative = path.relative_to(source)
        if not relative.is_relative_to(prefix):
            raise ValueError("Baseline Java source is outside the modded-common main source root: " + str(relative))
        destination = overlay / relative.relative_to(prefix)
        destination.parent.mkdir(parents=True, exist_ok=True)
        shutil.copy2(path, destination)
        replacements.append(ROOT / relative)
    required = {"IrisViewPipelines.java", "IrisPipelines.java", "IrisViewPipelineCache.java", "IrisPipelineSchedule.java",
                "PipelineBackend.java", "IrisPipelineBackend.java", "PortalStencilRenderer.java", "PortalViews.java"}
    if not required.issubset({path.name for path in replacements}):
        raise ValueError("Iris baseline is incomplete; missing " + ", ".join(sorted(required - {path.name for path in replacements})))
    renderer_mixin = prefix / "art/arcane/wormholes/modded/mixin/client/PortalLevelRendererMixin.java"
    mixin_source = (ROOT / renderer_mixin).read_text()
    preparation = "        PortalStencilRenderer.instance().prepareFrame((LevelRenderer) (Object) this, levelRenderState.cameraRenderState);\n"
    drawing = "PortalStencilRenderer.instance().renderPortals(levelRenderState.cameraRenderState);"
    if mixin_source.count(preparation) != 1 or mixin_source.count(drawing) != 1:
        raise ValueError("Iris baseline renderer hook does not match the current render ordering")
    mixin_source = mixin_source.replace(preparation, "").replace(drawing,
        "PortalStencilRenderer.instance().renderPortals((LevelRenderer) (Object) this, levelRenderState.cameraRenderState);")
    mixin_overlay = overlay / renderer_mixin.relative_to(prefix)
    mixin_overlay.parent.mkdir(parents=True, exist_ok=True)
    mixin_overlay.write_text(mixin_source)
    replacements.append(ROOT / renderer_mixin)
    mixins = json.loads((ROOT / "adapters/modded-common/src/main/resources/wormholes.mixins.json").read_text())
    for key in ["client", "mixins"]:
        if key in mixins:
            mixins[key] = [name for name in mixins[key] if name != "client.IrisPortalPreparationMixin"]
    (resources / "wormholes.mixins.json").write_text(json.dumps(mixins, indent=2) + "\n")
    return overlay, resources, replacements


def stop(process: subprocess.Popen) -> None:
    if process.poll() is not None:
        return
    try:
        os.killpg(process.pid, signal.SIGTERM)
    except ProcessLookupError:
        return
    try:
        process.wait(timeout=10)
    except subprocess.TimeoutExpired:
        os.killpg(process.pid, signal.SIGKILL)
        process.wait(timeout=10)


def stop_owned_client(run: Path) -> None:
    processes = subprocess.check_output(["ps", "-axo", "pid=,command="], text=True)
    for line in processes.splitlines():
        parts = line.strip().split(maxsplit=1)
        if len(parts) != 2 or str(run) not in parts[1] or "/bin/java" not in parts[1]:
            continue
        pid = int(parts[0])
        try:
            os.kill(pid, signal.SIGTERM)
            for attempt in range(20):
                time.sleep(0.1)
                os.kill(pid, 0)
            os.kill(pid, signal.SIGKILL)
        except ProcessLookupError:
            continue


def execute(args: argparse.Namespace) -> int:
    if args.record_demo:
        sys.path.insert(0, str(ROOT / 'src/test/demo'))
        from native_capture import NativeCaptureRecorder
    if args.iris_baseline_source is not None and args.minecraft_version != "26.3":
        raise ValueError("The saved Iris baseline targets Minecraft 26.3")
    tests = selected_tests(args.suite, args.tests)
    if args.iris_baseline_source is not None and tests != ["art.arcane.wormholes.clientgametest.ShaderPipelineReuseClientGameTest"]:
        raise ValueError("The saved Iris baseline supports only ShaderPipelineReuse")
    shader: Path | None = args.shader_pack.resolve() if args.shader_pack is not None else None
    distant_horizons: Path | None = args.distant_horizons.resolve() if args.distant_horizons is not None else None
    dh_metadata: dict | None = dh_jar(distant_horizons, args.minecraft_version) if distant_horizons is not None else None
    if (args.suite == "dh" or any(test.endswith(".DistantHorizonsClientGameTest") for test in tests)) and distant_horizons is None:
        raise ValueError("DH acceptance requires --distant-horizons with a local jar for the selected Minecraft version")
    if args.suite == "shader" and shader is None:
        raise ValueError("The shader suite requires --shader-pack with an actual shader pack")
    if shader is not None and (not shader.exists() or args.renderer != "sodium"):
        raise ValueError("--shader-pack requires an existing pack and --renderer sodium")
    if distant_horizons is not None and shader is not None:
        supported_dh_pack(shader)
    bridge_root = ROOT / "build/client-automator"
    if args.minecraft_version != "26.3":
        bridge_root /= args.minecraft_version
    bridge = bridge_root / "InstanceAutomator.jar"
    if not bridge.is_file():
        raise FileNotFoundError("Build the hidden client bridge with bash src/test/client/build.sh first")
    sodium: Path | None = None
    if args.renderer == "sodium":
        sodium = cached_mod("sodium", args.minecraft_version)
    iris: Path | None = None
    if shader is not None:
        iris = cached_mod("iris", args.minecraft_version)
    run_id = str(uuid.uuid4())
    evidence = ROOT / ".qa/native-client" / run_id
    evidence.mkdir(parents=True)
    run = QA_WORKSPACE / ("wormholes-client-" + run_id)
    run.mkdir(parents=True)
    marker = run / ".wormholes-hidden-qa"
    marker.write_text(run_id)
    script = evidence / "launch.init.gradle"
    states: list[dict] = []
    process: subprocess.Popen | None = None
    recorder: NativeCaptureRecorder | None = None
    success = False
    failure: str | None = None
    try:
        (run / "options.txt").write_text("soundCategory_master:0.0\n")
        if shader is not None:
            packs = run / "shaderpacks"
            packs.mkdir()
            if shader.is_dir():
                shutil.copytree(shader, packs / shader.name)
            else:
                shutil.copy2(shader, packs / shader.name)
            config = run / "config"
            config.mkdir()
            (config / "iris.properties").write_text("enableShaders=true\nshaderPack=" + shader.name + "\n")
        resources = evidence / "resources"
        resources.mkdir()
        metadata = json.loads((FABRIC / "src/clientGameTest/resources/fabric.mod.json").read_text())
        metadata["entrypoints"] = {"fabric-client-gametest": tests}
        (resources / "fabric.mod.json").write_text(json.dumps(metadata, indent=2) + "\n")
        for path in (ROOT / "adapters/modded-common/src/clientGameTest/resources").iterdir():
            if path.is_file():
                shutil.copy2(path, resources / path.name)
        for path in (FABRIC / "src/clientGameTest/resources").iterdir():
            if path.is_file() and path.name != "fabric.mod.json":
                shutil.copy2(path, resources / path.name)
        override_resources = ROOT / "adapters/modded-common/src" / args.minecraft_version / "clientGameTest/resources"
        if override_resources.is_dir():
            shutil.copytree(override_resources, resources, dirs_exist_ok=True)
        token = uuid.uuid4().hex
        if args.record_demo:
            recorder = NativeCaptureRecorder(run / 'automator', token, args.demo_take or None)
        baseline: Path | None = None
        baseline_resources: Path | None = None
        replaced: list[Path] = []
        if args.iris_baseline_source is not None:
            baseline, baseline_resources, replaced = baseline_overlay(args.iris_baseline_source.resolve(), evidence)
        jvm_args = args.jvm_arg + (["-Dwormholes.test.dhShutdown=true"] if distant_horizons is not None else [])
        script.write_text(init_script(run, resources, bridge, sodium, token, iris, distant_horizons, shader is not None,
                                     jvm_args, baseline, baseline_resources, replaced))
        log = evidence / "runtime.log"
        print("Tests: " + ", ".join(test.rsplit(".", 1)[1] for test in tests), flush=True)
        print("Evidence: " + str(evidence), flush=True)
        with log.open("w") as output:
            process = subprocess.Popen([str(ROOT / "gradlew"), "--init-script", str(script), "runClientGameTest", "-PminecraftVersion=" + args.minecraft_version, "--no-daemon", "--console=plain"],
                                       cwd=FABRIC, stdout=output, stderr=subprocess.STDOUT, start_new_session=True)
            started = time.monotonic()
            last_progress = started
            port: int | None = None
            while process.poll() is None:
                runtime_output = log.read_text(errors="replace")
                if "WORMHOLES_DH_ACCEPTANCE_FAIL" in runtime_output:
                    raise RuntimeError("DH acceptance failed; inspect " + str(log))
                if time.monotonic() - started > args.timeout:
                    raise TimeoutError("Client tests exceeded " + str(args.timeout) + " seconds")
                if port is None:
                    match = re.search(r"Instance Automator listening on 127\.0\.0\.1:(\d+)", runtime_output)
                    if match:
                        port = int(match.group(1))
                if port is not None:
                    if recorder is not None:
                        recorder.tick(port)
                    request = urllib.request.Request("http://127.0.0.1:" + str(port) + "/state", headers={"X-Automator-Token": token})
                    try:
                        with urllib.request.urlopen(request, timeout=3) as response:
                            state = json.load(response)
                    except (urllib.error.URLError, TimeoutError, ConnectionError):
                        state = None
                    if state is not None:
                        states.append({key: state.get(key) for key in ["frames", "ticks", "connected", "screen", "processId", "hiddenRenderer",
                            "frameWidth", "frameHeight", "masterVolume", "windowVisible", "windowFocused", "windowMouseGrabbed", "relativeMouseMode", "mouseGrabbed"]})
                        if state.get("hiddenRenderer") is not True or any(state.get(key) is not False for key in
                            ["windowVisible", "windowFocused", "windowMouseGrabbed", "relativeMouseMode", "mouseGrabbed"]):
                            raise AssertionError("Hidden client visibility/focus/mouse invariant failed: " + json.dumps(states[-1], sort_keys=True))
                        if state.get("masterVolume") != 0.0:
                            raise AssertionError("Test client master audio must stay muted")
                        if (state.get("frameWidth"), state.get("frameHeight")) != (1920, 1080):
                            raise AssertionError("Client framebuffer must be native 1920x1080")
                if time.monotonic() - last_progress >= 20:
                    print("Hidden client tests running: " + str(round(time.monotonic() - started)) + "s", flush=True)
                    last_progress = time.monotonic()
                time.sleep(1)
            runtime_output = log.read_text(errors="replace")
            if process.returncode != 0:
                raise RuntimeError("Client GameTest failed; inspect " + str(log))
            if any(marker in runtime_output for marker in ["View stream protocol reset",
                "after repeated protocol violations", "is unavailable after FRAME failure", "Could not force-load"]):
                raise RuntimeError("Client rendering or protocol failure; inspect " + str(log))
            if "Exception stopping the server" in runtime_output or "Wormholes shutdown failed" in runtime_output:
                raise RuntimeError("Native server shutdown failed; inspect " + str(log))
            if any(test.endswith(".DistantHorizonsClientGameTest") for test in tests) and "WORMHOLES_DH_ACCEPTANCE_PASS" not in runtime_output:
                raise AssertionError("Missing successful DH acceptance result")
            if len(states) < 2 or states[-1]["frames"] <= states[0]["frames"]:
                raise AssertionError("Missing evidence that hidden GPU frames advanced")
            if recorder is not None:
                recorder.verify_complete()
            success = True
    except (RuntimeError, AssertionError, TimeoutError, OSError, KeyboardInterrupt) as error:
        failure = str(error) or type(error).__name__
        print(failure, flush=True)
    finally:
        if process is not None:
            stop(process)
        stop_owned_client(run)
        try:
            for name in ["screenshots", "crash-reports", "logs", "dh-acceptance.json", "dh-shaders"]:
                source = run / name
                if source.exists():
                    if source.is_dir():
                        shutil.copytree(source, evidence / name, dirs_exist_ok=True)
                    else:
                        shutil.copy2(source, evidence / name)
            recordings = run / 'automator/native-demo'
            if recordings.is_dir():
                shutil.copytree(recordings, evidence / 'native-demo', dirs_exist_ok=True)
            (evidence / "hidden-states.json").write_text(json.dumps(states, indent=2) + "\n")
            (evidence / "result.json").write_text(json.dumps({"passed": success, "tests": tests, "minecraftVersion": args.minecraft_version, "renderer": args.renderer,
                "shaderPack": shader.name if shader is not None else None,
                "distantHorizons": {"path": str(distant_horizons), "version": dh_metadata["version"]} if dh_metadata is not None else None,
                "failure": failure}, indent=2) + "\n")
        finally:
            script.unlink(missing_ok=True)
            assert run.parent.resolve() == QA_WORKSPACE.resolve() and marker.read_text() == run_id
            shutil.rmtree(run)
    print("PASS" if success else "FAIL", flush=True)
    return 0 if success else 1


def main() -> int:
    parser = argparse.ArgumentParser()
    parser.add_argument("tests", nargs="*")
    parser.add_argument("--suite", choices=["edge", "compatibility", "shader", "dh"], default="edge")
    parser.add_argument("--minecraft-version", choices=["26.2", "26.3"], default="26.3")
    parser.add_argument("--renderer", choices=["vanilla", "sodium"], default="sodium")
    parser.add_argument("--shader-pack", type=Path)
    parser.add_argument("--distant-horizons", type=Path)
    parser.add_argument("--iris-baseline-source", type=Path)
    parser.add_argument("--jvm-arg", action="append", default=[])
    parser.add_argument("--timeout", type=int, default=1200)
    parser.add_argument("--record-demo", action="store_true")
    parser.add_argument("--demo-take", action="append", choices=["gravity-pov", "gravity-third-person", "scale-pov", "scale-third-person"], default=[])
    args = parser.parse_args()
    if args.demo_take:
        if not args.record_demo:
            parser.error("--demo-take requires --record-demo")
        args.jvm_arg.append("-Dwormholes.demo.takes=" + ",".join(dict.fromkeys(args.demo_take)))
    lock_path = ROOT / ".qa/native-client.lock"
    lock_path.parent.mkdir(parents=True, exist_ok=True)
    with lock_path.open("w") as lock:
        fcntl.flock(lock, fcntl.LOCK_EX | fcntl.LOCK_NB)
        return execute(args)


if __name__ == "__main__":
    raise SystemExit(main())
