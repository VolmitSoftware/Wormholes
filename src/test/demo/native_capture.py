import argparse
import json
from pathlib import Path
import re
import subprocess

from studio import Bridge, DOCS, FFMPEG, FFPROBE, verify_capture, verify_hidden_renderer


TAKES: dict[str, str] = {
    'gravity-pov': 'gravity-clientview-pov.webm',
    'gravity-third-person': 'gravity-clientview-observer.webm',
    'scale-pov': 'scale-clientview-pov.webm',
    'scale-third-person': 'scale-clientview-observer.webm',
}


class NativeCaptureRecorder:
    def __init__(self, output: Path, token: str, selected: list[str] | None = None) -> None:
        self.output: Path = output / 'native-demo'
        self.token: str = token
        self.sequence: int = 0
        self.active: str | None = None
        self.takes: dict[str, dict] = {}
        self.selected: frozenset[str] = frozenset(TAKES if selected is None else selected)
        if not self.selected or not self.selected <= TAKES.keys():
            raise ValueError('Unknown or empty native demo selection')

    def tick(self, port: int) -> None:
        phase: Path = self.output / 'phase.json'
        if not phase.is_file():
            return
        message: dict = json.loads(phase.read_text())
        sequence: int = message['sequence']
        if sequence <= self.sequence:
            return
        identifier: str = message['id']
        if identifier not in self.selected:
            raise ValueError('Unknown native demo take: ' + identifier)
        bridge: Bridge = Bridge(port, self.token)
        try:
            self.handle(bridge, identifier, message)
        except (RuntimeError, AssertionError, OSError, ValueError) as error:
            self.ack(sequence, False, str(error))
            raise
        self.sequence = sequence
        self.ack(sequence, True, '')

    def handle(self, bridge: Bridge, identifier: str, message: dict) -> None:
        stage: str = message['stage']
        state: dict = bridge.state(timeout=5)
        verify_hidden_renderer(state)
        if (state.get('frameWidth'), state.get('frameHeight')) != (1920, 1080):
            raise AssertionError('Native demo framebuffer must be 1920x1080')
        if stage == 'ready':
            if self.active is not None or identifier in self.takes:
                raise AssertionError('Native demo recordings must be unique and sequential')
            path: Path = self.output / (identifier + '.mp4')
            started: dict = bridge.command('capture', action='start', path=str(path), ffmpeg=str(FFMPEG), width=1920, height=1080, fps=30)
            if started.get('captureSource') != '1920x1080':
                raise AssertionError('Native capture source dimensions differ from the requested framebuffer')
            self.active = identifier
            print('Recording ' + identifier, flush=True)
        elif stage == 'turn':
            if self.active != identifier:
                raise AssertionError('Timed turn requires its active recording')
            bridge.command('look', yaw=message['yaw'], pitch=message['pitch'], ticks=message['ticks'])
        elif stage == 'complete':
            if self.active != identifier:
                raise AssertionError('Native capture completion does not match the active take')
            stopped: dict = bridge.command('capture', action='stop', request_timeout=60)
            self.active = None
            self.takes[identifier] = verify_capture(stopped, identifier)
            (self.output / 'manifest.json').write_text(json.dumps({'takes': self.takes}, indent=2) + '\n')
            print('Recorded ' + identifier + ': ' + str(self.takes[identifier]['raw']['captureFrames']) + ' real frames', flush=True)
        else:
            raise ValueError('Unknown native capture phase: ' + stage)

    def ack(self, sequence: int, success: bool, error: str) -> None:
        pending: Path = self.output / 'ack.tmp'
        pending.write_text(json.dumps({'sequence': sequence, 'success': success, 'error': error}) + '\n')
        pending.replace(self.output / 'ack.json')

    def verify_complete(self) -> None:
        if self.active is not None or set(self.takes) != self.selected:
            raise AssertionError('Native demo requires every selected perspective to complete')


def accepted(evidence: Path, selected: list[str] | None) -> dict[str, dict]:
    result: dict = json.loads((evidence / 'result.json').read_text())
    identifiers: frozenset[str] = frozenset(TAKES if selected is None else selected)
    if not identifiers or not identifiers <= TAKES.keys():
        raise ValueError('Unknown or empty native export selection')
    if selected is None and result.get('passed') is not True:
        raise AssertionError('Only accepted native demo runs can be exported')
    recordings: Path = evidence / 'native-demo'
    manifest: dict = json.loads((recordings / 'manifest.json').read_text())
    if not identifiers <= manifest['takes'].keys():
        raise AssertionError('Missing native demo perspective')
    runtime: str = (evidence / 'runtime.log').read_text()
    for identifier in identifiers:
        if not re.search(r'WORMHOLES_NATIVE_DEMO_PASS: ' + re.escape(identifier) + r' ', runtime):
            raise AssertionError('Missing successful gameplay proof for ' + identifier)
        verify_capture(manifest['takes'][identifier]['raw'], identifier)
    return {identifier: manifest['takes'][identifier] for identifier in TAKES if identifier in identifiers}


def export(evidence: Path, destination: Path, selected: list[str] | None = None) -> None:
    takes: dict[str, dict] = accepted(evidence, selected)
    recordings: Path = evidence / 'native-demo'
    destination.mkdir(parents=True, exist_ok=True)
    for identifier in takes:
        filename: str = TAKES[identifier]
        source: Path = recordings / (identifier + '.mp4')
        target: Path = destination / filename
        subprocess.run([str(FFMPEG), '-y', '-v', 'error', '-i', str(source), '-an', '-c:v', 'libvpx-vp9',
                        '-crf', '27', '-b:v', '0', '-row-mt', '1', '-pix_fmt', 'yuv420p', str(target)], check=True)
        probe: dict = json.loads(subprocess.check_output([str(FFPROBE), '-v', 'error', '-select_streams', 'v:0',
            '-show_entries', 'stream=width,height,avg_frame_rate:format=duration', '-of', 'json', str(target)], text=True))
        stream: dict = probe['streams'][0]
        if (stream['width'], stream['height'], stream['avg_frame_rate']) != (1920, 1080, '30/1'):
            raise AssertionError('Native demo export changed capture dimensions or frame rate')
        if target.stat().st_size >= 25 * 1024 * 1024:
            raise AssertionError('Native demo export exceeds the clip size budget')
        subprocess.run([str(FFMPEG), '-v', 'error', '-i', str(target), '-f', 'null', '-'], check=True)
        print(str(target), flush=True)


if __name__ == '__main__':
    parser = argparse.ArgumentParser()
    parser.add_argument('evidence', type=Path)
    parser.add_argument('--destination', type=Path, default=DOCS)
    parser.add_argument('--only', action='append', choices=list(TAKES))
    arguments = parser.parse_args()
    export(arguments.evidence, arguments.destination, arguments.only)
