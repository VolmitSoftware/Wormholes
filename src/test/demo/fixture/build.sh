#!/usr/bin/env bash
set -euo pipefail
FIXTURE_SOURCE_DIR="$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")" && pwd)"
export FIXTURE_SOURCE_DIR
python3 - <<'PY'
import os
import pathlib
import shutil
import subprocess
import tempfile

source = pathlib.Path(os.environ['FIXTURE_SOURCE_DIR'])
project = source.parents[3]
output = project / 'build' / 'demo-fixture'
cache = pathlib.Path.home() / '.gradle/caches/modules-2/files-2.1'
paper = sorted((cache / 'io.papermc.paper/paper-api').glob('26.3*/*/*.jar'))
paper = [path for path in paper if not path.name.endswith('-sources.jar')]
if not paper:
    raise SystemExit('A cached Paper 26.3 API jar is required')
wormholes = pathlib.Path(os.environ.get('WORMHOLES_JAR', str(project / 'build/libs/Wormholes-2.2.0.jar')))
volmlib = project.parent / 'VolmLib/shared/build/libs/shared-local-SNAPSHOT.jar'
if not wormholes.is_file() or not volmlib.is_file():
    raise SystemExit('Build Wormholes and the local VolmLib shared library first')
dependencies = [paper[-1], wormholes, volmlib]
for group in ['net.kyori', 'com.google.code.gson', 'com.google.guava', 'org.jetbrains', 'org.joml', 'net.md-5']:
    for path in sorted((cache / group).glob('*/*/*/*.jar')):
        if not path.name.endswith(('-sources.jar', '-javadoc.jar')):
            dependencies.append(path)
java_home = os.environ.get('JAVA_HOME') or subprocess.check_output(['/usr/libexec/java_home', '-v', '25'], text=True).strip()
output.mkdir(parents=True, exist_ok=True)
with tempfile.TemporaryDirectory(prefix='.compile-', dir=output) as temporary:
    classes = pathlib.Path(temporary) / 'classes'
    classes.mkdir()
    subprocess.run([str(pathlib.Path(java_home) / 'bin/javac'), '--release', '25', '-parameters', '-proc:none',
                    '-classpath', os.pathsep.join(map(str, dependencies)), '-d', str(classes),
                    *map(str, sorted((source / 'java').rglob('*.java')))], check=True)
    shutil.copytree(source / 'resources', classes, dirs_exist_ok=True)
    artifact = output / 'WormholesDemoFixture.jar'
    subprocess.run([str(pathlib.Path(java_home) / 'bin/jar'), '--create', '--file', str(artifact), '-C', str(classes), '.'], check=True)
print(artifact)
PY
