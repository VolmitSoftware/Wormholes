import json
import os
from pathlib import Path
import subprocess
import shutil

SOURCE: Path = Path(__file__).resolve().parent
ROOT: Path = SOURCE.parents[2]
BUILD: Path = ROOT / 'build' / 'client-automator'


def main() -> int:
    document: dict = json.loads((BUILD / 'compile-classpath.json').read_text())
    classpath: str = str(BUILD / 'InstanceAutomator.jar') + os.pathsep + document['classpath']
    classes: Path = BUILD / 'tests'
    if classes.is_dir():
        shutil.rmtree(classes)
    classes.mkdir(parents=True, exist_ok=True)
    java_home: str | None = os.environ.get('JAVA_HOME')
    javac: str = str(Path(java_home) / 'bin/javac') if java_home else 'javac'
    java: str = str(Path(java_home) / 'bin/java') if java_home else 'java'
    subprocess.run([javac, '--release', '25', '-proc:none', '-parameters', '-cp', classpath, '-d', str(classes),
                    *map(str, sorted((SOURCE / 'tests' / 'java').rglob('*.java')))], check=True)
    test_classpath: str = str(classes) + os.pathsep + classpath
    subprocess.run([java, '-cp', test_classpath, 'art.arcane.automator.LoopbackServerTest'], check=True)
    subprocess.run([java, '-cp', test_classpath, 'art.arcane.automator.LiveCaptureSettingsTest', str(BUILD / 'validation')], check=True)
    return 0


if __name__ == '__main__':
    raise SystemExit(main())
