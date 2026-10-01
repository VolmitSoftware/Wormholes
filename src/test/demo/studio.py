import concurrent.futures
import contextlib
import fcntl
import json
import math
import os
import re
import secrets
import shutil
import socket
import struct
import subprocess
import time
import traceback
import urllib.request
from pathlib import Path
from typing import Callable, Iterator

ROOT: Path = Path(__file__).resolve().parents[3]
MUX: Path = ROOT.parent.parent / '[Minecraft Server]'
PRISM: Path = Path.home() / 'Library/Application Support/PrismLauncher'
LAUNCHER: Path = Path('/Applications/Prism Launcher.app/Contents/MacOS/prismlauncher')
BASE: Path = PRISM / 'instances/Fabulously Optimized 14.0.0-alpha.4 for 26.2'
OUTPUT: Path = ROOT / 'build/demo'
ACTOR: str = 'Magic_Psycho'
OBSERVER: str = 'DemoObserver'
FFMPEG: Path = Path.home() / '.local/bin/ffmpeg'
FFPROBE: Path = Path.home() / '.local/bin/ffprobe'
DOCS: Path = ROOT.parent / 'docs/wormholes-assets/demos'


@contextlib.contextmanager
def claim_studio(path: Path = OUTPUT / 'run.lock') -> Iterator[None]:
    path.parent.mkdir(parents=True, exist_ok=True)
    with path.open('a') as lock:
        try:
            fcntl.flock(lock.fileno(), fcntl.LOCK_EX | fcntl.LOCK_NB)
        except BlockingIOError as failure:
            raise RuntimeError('Another Wormholes demonstration run owns this studio') from failure
        try:
            yield
        finally:
            fcntl.flock(lock.fileno(), fcntl.LOCK_UN)


def client_numbers(variant: str) -> tuple[int, int]:
    if variant == 'standard':
        return 1, 2
    if variant == 'clientview':
        return 3, 4
    raise ValueError('Unknown client variant: ' + variant)


def verify_sessions(status: str, variant: str, players: tuple[str, ...]) -> None:
    plain: str = re.sub(r'§.', '', status)
    expected: str = 'CLIENT_VIEW' if variant == 'clientview' else 'VANILLA'
    states: dict[str, str] = {}
    for line in plain.splitlines():
        columns: list[str] = line.split()
        if len(columns) >= 2 and columns[0] in players:
            states[columns[0]] = columns[1]
    if any(states.get(player) != expected for player in players):
        raise AssertionError('Both demonstration clients must use ' + expected + ': ' + plain)


def update_config(path: Path, values: dict[str, str], remove: set[str] | None = None) -> None:
    pending: dict[str, str] = dict(values)
    removed: set[str] = set() if remove is None else remove
    general: bool = False
    lines: list[str] = []
    for line in path.read_text().splitlines():
        if line.startswith('[') and line.endswith(']'):
            if general:
                lines.extend(key + '=' + value for key, value in pending.items())
                pending.clear()
            general = line == '[General]'
        elif general and '=' in line:
            key: str = line.split('=', 1)[0]
            if key in removed:
                continue
            if key in pending:
                line = key + '=' + pending.pop(key)
        lines.append(line)
    lines.extend(key + '=' + value for key, value in pending.items())
    path.write_text('\n'.join(lines) + '\n')


def free_port() -> int:
    with socket.socket() as listener:
        listener.bind(('127.0.0.1', 0))
        return int(listener.getsockname()[1])


def mux(*arguments: str) -> str:
    result: subprocess.CompletedProcess[str] = subprocess.run(
        [str(MUX / 'start.sh'), '--consumer', 'plugin', *arguments], cwd=MUX,
        capture_output=True, text=True, timeout=180)
    if result.returncode != 0:
        raise RuntimeError('Multiplexor ' + ' '.join(arguments) + ': ' + (result.stdout + result.stderr)[-2500:])
    return result.stdout.strip()


class Rcon:
    def __init__(self, server: Path) -> None:
        properties: dict[str, str] = dict(line.split('=', 1) for line in (server / 'server.properties').read_text().splitlines()
                                         if '=' in line and not line.startswith('#'))
        self.socket: socket.socket = socket.create_connection(('127.0.0.1', int(properties['rcon.port'])), timeout=60)
        self.identifier: int = 0
        self.history: list[dict[str, str]] = []
        self.exchange(3, properties['rcon.password'])

    def receive(self, size: int) -> bytes:
        result: bytes = b''
        while len(result) < size:
            part: bytes = self.socket.recv(size - len(result))
            if not part:
                raise ConnectionError('RCON disconnected')
            result += part
        return result

    def exchange(self, kind: int, command: str) -> str:
        self.identifier += 1
        payload: bytes = struct.pack('<ii', self.identifier, kind) + command.encode() + b'\0\0'
        self.socket.sendall(struct.pack('<i', len(payload)) + payload)
        size: int = struct.unpack('<i', self.receive(4))[0]
        if size < 10 or size > 1048576:
            raise ValueError('Invalid RCON response')
        reply: bytes = self.receive(size)
        if struct.unpack('<i', reply[:4])[0] != self.identifier:
            raise RuntimeError('RCON request correlation failed')
        return reply[8:-2].decode()

    def command(self, text: str) -> str:
        reply: str = self.exchange(2, text)
        self.history.append({'command': text, 'response': reply})
        if any(value in reply for value in ('Unknown or incomplete command', 'Incorrect argument', 'No player was found', 'Demo command failed:')):
            raise AssertionError(text + ': ' + reply)
        return reply


class Bridge:
    def __init__(self, port: int, token: str) -> None:
        self.url: str = 'http://127.0.0.1:' + str(port)
        self.token: str = token

    def request(self, operation: dict[str, object] | None = None) -> dict:
        body: bytes | None = None if operation is None else json.dumps(operation).encode()
        request: urllib.request.Request = urllib.request.Request(
            self.url + ('/state' if body is None else '/command'), data=body,
            headers={'X-Automator-Token': self.token, 'Content-Type': 'application/json'})
        with urllib.request.urlopen(request, timeout=30) as response:
            result: dict = json.load(response)
        if result.get('error'):
            raise RuntimeError(str(result['error']))
        return result

    def state(self) -> dict:
        return self.request()

    def command(self, op: str, **values: object) -> dict:
        return self.request({'op': op, **values})

    def wait(self, predicate: Callable[[dict], bool], label: str, timeout: float = 15.0) -> dict:
        deadline: float = time.monotonic() + timeout
        last: dict = {}
        while time.monotonic() < deadline:
            last = self.state()
            if predicate(last):
                return last
            if str(last.get('screen') or '').endswith('DisconnectedScreen'):
                raise RuntimeError('Client disconnected: ' + str(last.get('screenTitle')))
            time.sleep(0.08)
        raise TimeoutError(label + ': ' + json.dumps(last)[:1800])


def prepare_client(number: int, port: int, token: str, variant: str) -> Path:
    name: str = 'Instance Automator - ' + str(number)
    instance: Path = PRISM / 'instances' / name
    if not instance.exists():
        instance.mkdir()
        shutil.copy2(BASE / 'mmc-pack.json', instance / 'mmc-pack.json')
        shutil.copy2(BASE / 'instance.cfg', instance / 'instance.cfg')
        source: Path = BASE / 'minecraft'
        game: Path = instance / '.minecraft'
        game.mkdir()
        for folder in ('mods', 'config', 'resourcepacks', 'shaderpacks'):
            if (source / folder).is_dir():
                shutil.copytree(source / folder, game / folder, ignore=shutil.ignore_patterns('accounts.json', 'logs', 'cache'))
        if (source / 'options.txt').is_file():
            shutil.copy2(source / 'options.txt', game / 'options.txt')
    game = instance / '.minecraft'
    game.mkdir(exist_ok=True)
    options: dict[str, str] = {'pauseOnLostFocus': 'false', 'tutorialStep': 'none', 'autoJump': 'false',
                               'maxFps': '60', 'enableVsync': 'false', 'renderDistance': '10', 'simulationDistance': '5',
                               'fov': '0.0', 'guiScale': '3', 'soundCategory_music': '0.0', 'chatVisibility': '0',
                               'chatOpacity': '0.0', 'chatBackgroundOpacity': '0.0'}
    option_file: Path = game / 'options.txt'
    lines: list[str] = []
    for line in option_file.read_text().splitlines() if option_file.is_file() else []:
        key: str = line.split(':', 1)[0]
        lines.append(key + ':' + options.pop(key) if key in options else line)
    lines.extend(key + ':' + value for key, value in options.items())
    option_file.write_text('\n'.join(lines) + '\n')
    update_config(instance / 'instance.cfg', {
        'name': name, 'OverrideJavaArgs': 'true', 'JvmArgs': '-Dautomator.port=' + str(port)
        + ' -Dautomator.token=' + token + ' -Dautomator.output=' + str(OUTPUT) + ' --enable-native-access=ALL-UNNAMED',
        'OverrideWindow': 'true', 'MinecraftWinWidth': '1920', 'MinecraftWinHeight': '1080',
        'OverrideMemory': 'true', 'MinMemAlloc': '512', 'MaxMemAlloc': '3072',
        'ShowConsole': 'false', 'ShowConsoleOnError': 'false'}, remove={'uuid'})
    mods: Path = game / 'mods'
    mods.mkdir(exist_ok=True)
    for installed in mods.glob('*.jar'):
        if 'Wormholes' in installed.name or 'InstanceAutomator' in installed.name:
            installed.unlink()
    shutil.copy2(ROOT / 'build/client-automator/InstanceAutomator.jar', mods / 'InstanceAutomator.jar')
    if variant == 'clientview':
        source_mod: Path = ROOT / 'adapters/fabric/build/libs/Wormholes v2.1.1 [Fabric] 26.3+0.19.5.jar'
        shutil.copy2(source_mod, mods / source_mod.name)
    return instance


class Studio:
    def __init__(self) -> None:
        OUTPUT.mkdir(parents=True, exist_ok=True)
        self.name: str = 'wormholes-demo-' + str(int(time.time())) + '-' + secrets.token_hex(2)
        self.server: Path | None = None
        self.port: int = free_port()
        self.rcon: Rcon | None = None
        self.clients: list[tuple[Path, Bridge]] = []
        self.created: bool = False

    def start(self) -> None:
        mux('server', 'create', self.name, '--type', 'paper', '--mc', '26.3', '--isolated')
        self.created = True
        mux('instance', 'port', self.name, str(self.port))
        mux('gameplay', 'prepare', self.name)
        self.server = Path(mux('instance', 'path', self.name).splitlines()[-1])
        properties: dict[str, str] = dict(line.split('=', 1) for line in (self.server / 'server.properties').read_text().splitlines()
                                         if '=' in line and not line.startswith('#'))
        properties.update({'level-type': 'minecraft:flat', 'generate-structures': 'false', 'level-seed': '9438107',
            'view-distance': '10', 'simulation-distance': '5', 'motd': 'Wormholes demonstrations',
            'enable-rcon': 'true', 'rcon.port': str(free_port()), 'rcon.password': secrets.token_hex(20),
            'generator-settings': json.dumps({'biome': 'minecraft:plains', 'layers': [
                {'block': 'minecraft:bedrock', 'height': 1}, {'block': 'minecraft:stone', 'height': 64},
                {'block': 'minecraft:dirt', 'height': 4}, {'block': 'minecraft:grass_block', 'height': 1}]}, separators=(',', ':'))})
        (self.server / 'server.properties').write_text(''.join(key + '=' + value + '\n' for key, value in properties.items()))
        plugins: Path = self.server / 'plugins'
        shutil.copy2(ROOT / 'build/libs/Wormholes-2.1.1-packed.jar', plugins / 'Wormholes.jar')
        shutil.copy2(ROOT / 'build/demo-fixture/WormholesDemoFixture.jar', plugins / 'WormholesDemoFixture.jar')
        (plugins / 'WormholesDemoFixture').mkdir(exist_ok=True)
        shutil.copy2(OUTPUT / 'skin-profile.json', plugins / 'WormholesDemoFixture/skin-profile.json')
        mux('runtime', 'start', self.name, '--no-console')
        log: Path = self.server / 'logs/latest.log'
        deadline: float = time.monotonic() + 180
        while time.monotonic() < deadline:
            text: str = log.read_text(errors='replace') if log.is_file() else ''
            if 'Done (' in text and 'WormholesDemoFixture' in text:
                self.rcon = Rcon(self.server)
                self.rcon.command('op ' + ACTOR)
                self.rcon.command('op ' + OBSERVER)
                self.rcon.command('gamerule send_command_feedback false')
                self.rcon.command('gamerule advance_time false')
                self.rcon.command('gamerule advance_weather false')
                self.rcon.command('gamerule spawn_mobs false')
                self.rcon.command('time set 6000')
                self.rcon.command('weather clear')
                (OUTPUT / 'server.json').write_text(json.dumps({'instance': self.name, 'path': str(self.server), 'port': self.port}))
                print('Server ready: ' + self.name, flush=True)
                return
            time.sleep(1)
        raise TimeoutError('Server startup: ' + text[-4000:])

    def open_clients(self, variant: str) -> tuple[Bridge, Bridge]:
        self.close_clients()
        sessions: list[dict[str, object]] = []
        for number, player in zip(client_numbers(variant), (ACTOR, OBSERVER)):
            port: int = free_port()
            token: str = secrets.token_hex(24)
            instance: Path = prepare_client(number, port, token, variant)
            bridge: Bridge = Bridge(port, token)
            self.clients.append((instance, bridge))
            sessions.append({'instance': str(instance), 'port': port, 'token': token, 'variant': variant, 'player': player})
            (OUTPUT / 'clients.json').write_text(json.dumps(sessions))
            command: list[str] = [str(LAUNCHER), '--launch', instance.name, '--offline', player,
                                  '--server', '127.0.0.1:' + str(self.port)]
            log: Path = OUTPUT / ('launcher-' + str(number) + '.log')
            with log.open('w') as sink:
                subprocess.Popen(command, stdout=sink, stderr=subprocess.STDOUT)
            deadline: float = time.monotonic() + 180
            retried: bool = False
            while time.monotonic() < deadline:
                try:
                    if bridge.state().get('connected'):
                        break
                except (OSError, ValueError):
                    pass
                if not retried and time.monotonic() > deadline - 160:
                    with log.open('a') as sink:
                        subprocess.Popen(command, stdout=sink, stderr=subprocess.STDOUT)
                    retried = True
                time.sleep(0.5)
            else:
                raise TimeoutError('Prism client did not connect: ' + instance.name)
            try:
                bridge.command('fit-window', width=1920, height=1080)
            except RuntimeError as failure:
                print('Window fit: ' + str(failure), flush=True)
            print('Client ready: ' + instance.name + ' (' + variant + ')', flush=True)
        actor: Bridge = self.clients[0][1]
        observer: Bridge = self.clients[1][1]
        actor.wait(lambda state: state.get('skinLoaded', False), 'Magic_Psycho skin', timeout=45)
        self.rcon.command('gamemode survival ' + ACTOR)
        self.rcon.command('gamemode spectator ' + OBSERVER)
        observer.command('hud', visible=False)
        status: str = self.rcon.command('wh clientview status')
        verify_sessions(status, variant, (ACTOR, OBSERVER))
        print('Projection sessions: ' + re.sub(r'§.', '', status), flush=True)
        return actor, observer

    def close_clients(self) -> None:
        errors: list[str] = []
        for instance, bridge in self.clients:
            try:
                if bridge.state().get('capturing'):
                    bridge.command('capture', action='stop')
            except (OSError, RuntimeError, ValueError) as failure:
                errors.append(instance.name + ' capture cleanup: ' + str(failure))
                traceback.print_exception(failure)
            try:
                bridge.command('quit')
            except (OSError, RuntimeError, ValueError) as failure:
                errors.append(instance.name + ' quit: ' + str(failure))
                traceback.print_exception(failure)
        if self.clients:
            time.sleep(3)
        self.clients.clear()
        if errors:
            raise RuntimeError('Client cleanup failed: ' + '; '.join(errors))

    def close(self) -> None:
        errors: list[str] = []
        try:
            self.close_clients()
        except RuntimeError as failure:
            errors.append(str(failure))
        if self.rcon is not None:
            try:
                (OUTPUT / 'commands.json').write_text(json.dumps(self.rcon.history, indent=2))
            except OSError as failure:
                errors.append('Command log: ' + str(failure))
                traceback.print_exception(failure)
            finally:
                self.rcon.socket.close()
                self.rcon = None
        if self.created:
            stopped: bool = False
            try:
                mux('runtime', 'stop', self.name)
                stopped = True
            except (OSError, RuntimeError, subprocess.TimeoutExpired) as failure:
                errors.append('Server stop: ' + str(failure))
                traceback.print_exception(failure)
            try:
                if self.server is not None and (self.server / 'logs/latest.log').is_file():
                    shutil.copy2(self.server / 'logs/latest.log', OUTPUT / 'server-latest.log')
            except OSError as failure:
                errors.append('Server log: ' + str(failure))
                traceback.print_exception(failure)
            if stopped:
                try:
                    mux('instance', 'delete', self.name)
                    self.created = False
                    print('Disposable server removed: ' + self.name, flush=True)
                except (OSError, RuntimeError, subprocess.TimeoutExpired) as failure:
                    errors.append('Server deletion: ' + str(failure))
                    traceback.print_exception(failure)
        if errors:
            raise RuntimeError('Studio cleanup failed: ' + '; '.join(errors))


def angles(bridge: Bridge, target: tuple[float, float, float]) -> tuple[float, float]:
    position: dict = bridge.state()['position']
    dx: float = target[0] - position['x']
    dy: float = target[1] - position['y'] - 1.62
    dz: float = target[2] - position['z']
    return math.degrees(math.atan2(-dx, dz)), math.degrees(-math.atan2(dy, math.hypot(dx, dz)))


def look(bridge: Bridge, target: tuple[float, float, float], ticks: int = 12) -> None:
    yaw: float
    pitch: float
    yaw, pitch = angles(bridge, target)
    bridge.command('look', yaw=yaw, pitch=pitch, ticks=ticks)
    time.sleep(ticks / 20 + 0.05)
    bridge.wait(lambda state: not state.get('turning', False), 'camera turn')


def verify_capture(state: dict) -> None:
    frames: int = int(state.get('captureFrames', 0))
    seconds: float = float(state.get('captureSeconds', 0.0))
    dropped: int = int(state.get('captureDroppedFrames', 0))
    if frames < 30 or seconds <= 0 or frames / seconds < 28.0 or dropped != 0 or state.get('captureSource') != '1920x1080':
        raise AssertionError('Capture quality failed: frames=' + str(frames) + ', seconds=' + str(seconds)
                             + ', dropped=' + str(dropped) + ', source=' + str(state.get('captureSource')))


def export(identifier: str, variant: str) -> list[Path]:
    DOCS.mkdir(parents=True, exist_ok=True)
    def encode(view: str) -> Path:
        source: Path = OUTPUT / 'intermediate' / (identifier + '-' + variant + '-' + view + '.mp4')
        target: Path = DOCS / (identifier + '-' + variant + '-' + view + '.webm')
        result: subprocess.CompletedProcess[str] = subprocess.run([str(FFMPEG), '-y', '-i', str(source),
            '-vf', 'scale=1920:1080:flags=lanczos', '-r', '30', '-c:v', 'libvpx-vp9', '-crf', '27', '-b:v', '0',
            '-row-mt', '1', '-deadline', 'good', '-cpu-used', '2', '-pix_fmt', 'yuv420p', '-an', str(target)],
            capture_output=True, text=True)
        if result.returncode:
            raise RuntimeError('Export failed: ' + result.stderr[-2000:])
        if target.stat().st_size > 25 * 1024 * 1024:
            raise AssertionError('Clip exceeds 25 MB: ' + str(target))
        return target
    with concurrent.futures.ThreadPoolExecutor(max_workers=2) as executor:
        return list(executor.map(encode, ('pov', 'observer')))
