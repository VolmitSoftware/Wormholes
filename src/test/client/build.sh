#!/usr/bin/env bash
set -euo pipefail
AUTOMATOR_SOURCE_DIR="$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")" && pwd)"
export AUTOMATOR_SOURCE_DIR
python3 - <<'PY'
import json
import os
import pathlib
import shutil
import subprocess
import tempfile
import zipfile

source: pathlib.Path = pathlib.Path(os.environ['AUTOMATOR_SOURCE_DIR'])
project: pathlib.Path = source.parents[2]
game_version: str = os.environ.get('AUTOMATOR_MINECRAFT_VERSION', '26.3')
platform: str = os.environ.get('AUTOMATOR_PLATFORM', 'fabric')
if platform not in ('fabric', 'forge', 'neoforge'):
    raise SystemExit('Unsupported client platform: ' + platform)
if game_version not in ('26.2', '26.3'):
    raise SystemExit('Unsupported client game version: ' + game_version)
output: pathlib.Path = project / 'build' / 'client-automator'
if game_version != '26.3':
    output = output / game_version
if platform != 'fabric':
    output = output / platform
libraries: pathlib.Path = pathlib.Path(os.environ.get('PRISM_LIBRARIES', str(pathlib.Path.home() / 'Library/Application Support/PrismLauncher/libraries')))
metadata: pathlib.Path = pathlib.Path(os.environ.get('PRISM_META', str(libraries.parent / 'meta')))
game_metadata: dict = json.loads((metadata / 'net.minecraft' / (game_version + '.json')).read_text())
lwjgl_version: str = next(entry['suggests'] for entry in game_metadata['requires'] if entry['uid'] == 'org.lwjgl3')
components: list[tuple[str, str]] = [('net.minecraft', game_version), ('net.fabricmc.fabric-loader', '0.19.5'), ('org.lwjgl3', lwjgl_version)]
dependencies: dict[tuple[str, str, str], pathlib.Path] = {}
for component, version in components:
    document: pathlib.Path = metadata / component / f'{version}.json'
    if not document.is_file():
        raise SystemExit(f'Missing cached client metadata: {document}')
    data: dict = json.loads(document.read_text())
    entries: list[dict] = ([data['mainJar']] if 'mainJar' in data else []) + data['libraries']
    for entry in entries:
        coordinate: str = entry['name']
        parts: list[str] = coordinate.split(':')
        if len(parts) not in (3, 4):
            raise SystemExit(f'Unsupported Maven coordinate: {coordinate}')
        group, artifact, dependency_version = parts[:3]
        classifier: str = parts[3] if len(parts) == 4 else ''
        if '-natives-' in artifact or classifier.startswith('natives-'):
            continue
        if group == 'io.netty' and artifact.startswith('netty-transport-native-') and classifier:
            continue
        suffix: str = f'-{classifier}' if classifier else ''
        relative: str | None = entry.get('downloads', {}).get('artifact', {}).get('path')
        if relative is None:
            relative = f'{group.replace(".", "/")}/{artifact}/{dependency_version}/{artifact}-{dependency_version}{suffix}.jar'
        dependency: pathlib.Path = libraries / relative
        if not dependency.is_file():
            raise SystemExit(f'Missing cached class library {coordinate}: {dependency}')
        key: tuple[str, str, str] = (group, artifact, classifier)
        if key in dependencies and dependencies[key] != dependency:
            raise SystemExit(f'Conflicting metadata versions for {group}:{artifact}:{classifier}')
        dependencies[key] = dependency
if platform != 'fabric':
    dependency_cache: pathlib.Path = pathlib.Path.home() / '.gradle/caches/modules-2/files-2.1'
    mixin_coordinate: str = 'org.spongepowered:mixin:0.8.7'
    if platform == 'neoforge':
        target: dict = json.loads((project / 'gradle/minecraft-versions' / (game_version + '.json')).read_text())
        loader: str = target['loaders']['neoforge']
        userdev_candidates: list[pathlib.Path] = sorted((dependency_cache / 'net.neoforged/neoforge' / loader).glob('*/*-userdev.jar'))
        if len(userdev_candidates) != 1:
            raise SystemExit('Missing native NeoForge userdev metadata; resolve the adapter build dependencies first')
        with zipfile.ZipFile(userdev_candidates[0]) as archive:
            userdev: dict = json.loads(archive.read('config.json'))
        mixin_coordinate = next(entry for entry in userdev['libraries'] if entry.startswith('net.fabricmc:sponge-mixin:'))
    mixin_group, mixin_artifact, mixin_version = mixin_coordinate.split(':')
    mixin_candidates: list[pathlib.Path] = sorted((dependency_cache / mixin_group / mixin_artifact / mixin_version).glob('*/' + mixin_artifact + '-' + mixin_version + '.jar'))
    if len(mixin_candidates) != 1:
        raise SystemExit('Missing native ' + mixin_coordinate + '; resolve the adapter build dependencies first')
    dependencies = {key: path for key, path in dependencies.items() if key[1] not in ('mixin', 'sponge-mixin')}
    dependencies[(mixin_group, mixin_artifact, '')] = mixin_candidates[0]
output.mkdir(parents=True, exist_ok=True)
classpath: str = os.pathsep.join(str(path) for path in dependencies.values())
(output / 'compile-classpath.json').write_text(json.dumps({'components': dict(components), 'classpath': classpath}, indent=2) + '\n')
java_home: str | None = os.environ.get('JAVA_HOME')
javac: str = str(pathlib.Path(java_home) / 'bin/javac') if java_home else 'javac'
jar: str = str(pathlib.Path(java_home) / 'bin/jar') if java_home else 'jar'
artifact: pathlib.Path = output / 'InstanceAutomator.jar'
workspace: pathlib.Path = pathlib.Path(tempfile.mkdtemp(prefix='.build-', dir=output))
try:
    classes: pathlib.Path = workspace / 'classes'
    classes.mkdir()
    overrides: pathlib.Path = source / game_version / 'java'
    replacement_sources: dict[pathlib.Path, pathlib.Path] = {path.relative_to(overrides): path for path in overrides.rglob('*.java')} if overrides.is_dir() else {}
    sources: dict[pathlib.Path, pathlib.Path] = {path.relative_to(source / 'java'): path for path in (source / 'java').rglob('*.java')}
    sources.update(replacement_sources)
    if platform != 'fabric':
        hidden_source: pathlib.Path = sources[pathlib.Path('art/arcane/automator/HiddenRenderer.java')]
        hidden_text: str = hidden_source.read_text().replace('import net.fabricmc.loader.api.entrypoint.PreLaunchEntrypoint;\n', '').replace(' implements PreLaunchEntrypoint', '').replace('    @Override\n', '')
        hidden_staged: pathlib.Path = workspace / 'HiddenRenderer.java'
        hidden_staged.write_text(hidden_text)
        sources[pathlib.Path('art/arcane/automator/HiddenRenderer.java')] = hidden_staged
    subprocess.run([javac, '--release', '25', '-proc:none', '-parameters', '-classpath', classpath, '-d', str(classes),
                    *map(str, sorted(sources.values()))], check=True)
    shutil.copytree(source / 'resources', classes, dirs_exist_ok=True)
    mixin_metadata: pathlib.Path = classes / 'instance-automator.mixins.json'
    mixin_document: dict = json.loads(mixin_metadata.read_text())
    if game_version == '26.2':
        mixin_document['client'].remove('WindowMixin$NeoForgeEarlyWindowHandoff')
    mixin_metadata.write_text(json.dumps(mixin_document, indent=2) + '\n')
    mod_metadata: pathlib.Path = classes / 'fabric.mod.json'
    mod_document: dict = json.loads(mod_metadata.read_text())
    mod_document['depends']['minecraft'] = game_version
    mod_metadata.write_text(json.dumps(mod_document, indent=2) + '\n')
    if platform != 'fabric':
        mod_metadata.unlink()
        mixin_document['compatibilityLevel'] = 'JAVA_21'
        mixin_metadata.write_text(json.dumps(mixin_document, indent=2) + '\n')
    staged: pathlib.Path = workspace / (artifact.name + '.tmp')
    subprocess.run([jar, '--create', '--file', str(staged), '-C', str(classes), '.'], check=True)
    os.replace(staged, artifact)
finally:
    shutil.rmtree(workspace, ignore_errors=True)
print(artifact)
PY
