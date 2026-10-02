import concurrent.futures
import contextlib
import fcntl
import json
import math
import os
import re
import secrets
import shlex
import shutil
import socket
import struct
import subprocess
import time
import tempfile
import traceback
import urllib.request
from pathlib import Path
from typing import Callable, Iterator

ROOT: Path = Path(__file__).resolve().parents[3]
MUX: Path = ROOT.parent.parent / '[Minecraft Server]'
PRISM: Path = Path.home() / 'Library/Application Support/PrismLauncher'
LAUNCHER: Path = Path('/Applications/Prism Launcher.app/Contents/MacOS/prismlauncher')
BASE: Path = PRISM / 'instances/Fabulously Optimized 14.0.0-alpha.4 for 26.2'
OUTPUT: Path = Path(os.environ.get('WORMHOLES_DEMO_OUTPUT', str(ROOT / 'build/demo')))
PROFILE_PREFIX: str = os.environ.get('WORMHOLES_DEMO_PROFILE_PREFIX', 'Instance Automator')
ACTOR: str = 'Magic_Psycho'
OBSERVER: str = 'DemoObserver'
FFMPEG: Path = Path('/opt/homebrew/bin/ffmpeg') if Path('/opt/homebrew/bin/ffmpeg').is_file() else Path.home() / '.local/bin/ffmpeg'
FFPROBE: Path = Path('/opt/homebrew/bin/ffprobe') if Path('/opt/homebrew/bin/ffprobe').is_file() else Path.home() / '.local/bin/ffprobe'
DOCS: Path = ROOT.parent / 'docs/wormholes-assets/demos'
HIDDEN_RENDERER_STATE: dict[str, bool] = {'hiddenRenderer': True, 'windowVisible': False, 'windowFocused': False,
                                       'mouseGrabbed': False, 'relativeMouseMode': False, 'windowMouseGrabbed': False}
LOADING_PAUSE_DEMOS: frozenset[str] = frozenset(('personal-pockets', 'public-pockets', 'gateways',
                                              'cross-server-gateways', 'rtp-personal', 'rtp-routing'))


def verify_hidden_renderer(state: dict) -> None:
    for field, expected in HIDDEN_RENDERER_STATE.items():
        if state.get(field) is not expected:
            raise RuntimeError('Hidden renderer requires ' + field + '=' + str(expected) + '; received ' + str(state.get(field)))


def fit_hidden_renderer(bridge: 'Bridge') -> dict:
    deadline: float = time.monotonic() + 5
    verify_hidden_renderer(bridge.state(timeout=5))
    remaining: float = deadline - time.monotonic()
    if remaining <= 0:
        raise TimeoutError('Hidden render state did not settle within five seconds')
    bridge.command('fit-window', width=1920, height=1080, request_timeout=remaining)
    last: dict = {}
    while True:
        remaining = deadline - time.monotonic()
        if remaining <= 0:
            raise TimeoutError('Native 1920x1080 hidden render target did not settle: ' + json.dumps(last))
        last = bridge.state(timeout=remaining)
        verify_hidden_renderer(last)
        if last.get('frameWidth') == 1920 and last.get('frameHeight') == 1080:
            return last
        time.sleep(min(0.08, remaining))


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

    def send(self, kind: int, command: str) -> int:
        self.identifier += 1
        payload: bytes = struct.pack('<ii', self.identifier, kind) + command.encode() + b'\0\0'
        self.socket.sendall(struct.pack('<i', len(payload)) + payload)
        return self.identifier

    def receive_packet(self) -> tuple[int, int, bytes]:
        size: int = struct.unpack('<i', self.receive(4))[0]
        if size < 10 or size > 1048576:
            raise ValueError('Invalid RCON response')
        reply: bytes = self.receive(size)
        if reply[-2:] != b'\0\0':
            raise ValueError('Invalid RCON response terminator')
        identifier, kind = struct.unpack('<ii', reply[:8])
        return identifier, kind, reply[8:-2]

    def exchange(self, kind: int, command: str) -> str:
        request: int = self.send(kind, command)
        identifier, response_kind, response = self.receive_packet()
        if identifier != request:
            raise RuntimeError('RCON request correlation failed')
        if response_kind != (2 if kind == 3 else 0):
            raise ValueError('Unexpected RCON response type')
        if kind == 3:
            return response.decode()
        barrier: int = self.send(0, '')
        chunks: list[bytes] = [response]
        while True:
            identifier, response_kind, response = self.receive_packet()
            if response_kind != 0:
                raise ValueError('Unexpected RCON response type')
            if identifier == barrier:
                if response != b'Unknown request 0':
                    raise ValueError('Invalid RCON response barrier')
                return b''.join(chunks).decode()
            if identifier != request:
                raise RuntimeError('RCON request correlation failed')
            chunks.append(response)

    def command(self, text: str) -> str:
        reply: str = self.exchange(2, text)
        self.history.append({'command': text, 'response': reply})
        if any(value in reply for value in ('Unknown or incomplete command', 'Incorrect argument', 'Unexpected argument', 'No player was found', 'Demo command failed:')):
            raise AssertionError(text + ': ' + reply)
        return reply


class Bridge:
    def __init__(self, port: int, token: str) -> None:
        self.url: str = 'http://127.0.0.1:' + str(port)
        self.token: str = token

    def request(self, operation: dict[str, object] | None = None, timeout: float = 30) -> dict:
        body: bytes | None = None if operation is None else json.dumps(operation).encode()
        request: urllib.request.Request = urllib.request.Request(
            self.url + ('/state' if body is None else '/command'), data=body,
            headers={'X-Automator-Token': self.token, 'Content-Type': 'application/json'})
        with urllib.request.urlopen(request, timeout=timeout) as response:
            result: dict = json.load(response)
        if result.get('error'):
            raise RuntimeError(str(result['error']))
        return result

    def state(self, timeout: float = 30) -> dict:
        return self.request(timeout=timeout)

    def command(self, op: str, request_timeout: float = 30, **values: object) -> dict:
        return self.request({'op': op, **values}, timeout=request_timeout)

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


def client_credentials(number: int) -> tuple[int, str]:
    instance: Path = PRISM / 'instances' / (PROFILE_PREFIX + ' - ' + str(number))
    if not instance.exists():
        return free_port(), secrets.token_hex(24)
    config: Path = instance / 'instance.cfg'
    if not config.is_file():
        raise ValueError('Existing client profile has no instance.cfg: ' + instance.name)
    arguments: list[str] = []
    general: bool = False
    for line in config.read_text().splitlines():
        if line.startswith('[') and line.endswith(']'):
            general = line == '[General]'
        elif general and line.startswith('JvmArgs='):
            arguments.append(line.split('=', 1)[1])
    if len(arguments) != 1:
        raise ValueError('Existing client profile has no unique JVM arguments: ' + instance.name)
    try:
        tokens: list[str] = shlex.split(arguments[0])
        if len(tokens) == 1:
            tokens = shlex.split(tokens[0])
    except ValueError:
        raise ValueError('Existing client profile has invalid JVM quoting: ' + instance.name) from None
    properties: dict[str, str] = {}
    index: int = 0
    while index < len(tokens):
        argument: str = tokens[index]
        index += 1
        if not argument.startswith('-Dautomator.') or '=' not in argument:
            continue
        key, value = argument.split('=', 1)
        if key in properties:
            raise ValueError('Existing client profile has duplicate bridge properties: ' + instance.name)
        if key == '-Dautomator.output':
            while index < len(tokens) and not tokens[index].startswith('-'):
                value += ' ' + tokens[index]
                index += 1
        properties[key] = value
    output: str = properties.get('-Dautomator.output', '')
    if not output or Path(output).resolve() != OUTPUT.resolve():
        raise ValueError('Existing client profile belongs to a different recording output: ' + instance.name)
    token: str = properties.get('-Dautomator.token', '')
    port_text: str = properties.get('-Dautomator.port', '')
    if not port_text.isdecimal() or not 1 <= int(port_text) <= 65535 or not token or any(c.isspace() for c in token):
        raise ValueError('Existing client profile has invalid bridge credentials: ' + instance.name)
    return int(port_text), token


def prepare_client(number: int, port: int, token: str, variant: str) -> Path:
    name: str = PROFILE_PREFIX + ' - ' + str(number)
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
    options: dict[str, str] = {'pauseOnLostFocus': 'false', 'tutorialStep': 'none', 'autoJump': 'false', 'advancedItemTooltips': 'false',
                               'maxFps': '60', 'enableVsync': 'false', 'renderDistance': '10', 'simulationDistance': '5',
                               'fov': '0.0', 'guiScale': '3', 'fullscreen': 'false', 'soundCategory_music': '0.0', 'chatVisibility': '0',
                               'chatOpacity': '0.0', 'chatBackgroundOpacity': '0.0'}
    option_file: Path = game / 'options.txt'
    lines: list[str] = []
    for line in option_file.read_text().splitlines() if option_file.is_file() else []:
        key: str = line.split(':', 1)[0]
        lines.append(key + ':' + options.pop(key) if key in options else line)
    lines.extend(key + ':' + value for key, value in options.items())
    option_file.write_text('\n'.join(lines) + '\n')
    shader_options: Path = game / 'config/iris.properties'
    shader_options.parent.mkdir(exist_ok=True)
    shader_lines: list[str] = [line for line in shader_options.read_text().splitlines() if not line.startswith('enableShaders=')] if shader_options.is_file() else []
    shader_options.write_text('\n'.join([*shader_lines, 'enableShaders=false']) + '\n')
    update_config(instance / 'instance.cfg', {
        'name': name, 'OverrideJavaArgs': 'true', 'JvmArgs': shlex.join([
            '-Dautomator.hidden=true', '-Dautomator.port=' + str(port), '-Dautomator.token=' + token,
            '-Dautomator.output=' + str(OUTPUT), '--enable-native-access=ALL-UNNAMED']),
        'OverrideWindow': 'true', 'MinecraftWinWidth': '1920', 'MinecraftWinHeight': '1080',
        'OverrideMemory': 'true', 'MinMemAlloc': '512', 'MaxMemAlloc': '3072',
        'ShowConsole': 'false', 'ShowConsoleOnError': 'false'}, remove={'uuid'})
    mods: Path = game / 'mods'
    mods.mkdir(exist_ok=True)
    for installed in mods.glob('*.jar'):
        if installed.name.startswith('dynamic-fps-'):
            installed.rename(installed.with_suffix('.jar.disabled'))
            continue
        if 'Wormholes' in installed.name or 'InstanceAutomator' in installed.name:
            installed.unlink()
    shutil.copy2(ROOT / 'build/client-automator/InstanceAutomator.jar', mods / 'InstanceAutomator.jar')
    if variant == 'clientview':
        source_mod: Path = ROOT / 'adapters/fabric/build/libs/Wormholes v2.2.0 [Fabric] 26.3+0.19.5.jar'
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

    def start(self, state_file: Path = OUTPUT / 'server.json') -> None:
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
        shutil.copy2(ROOT / 'build/libs/Wormholes-2.2.0-packed.jar', plugins / 'Wormholes.jar')
        shutil.copy2(ROOT / 'build/demo-fixture/WormholesDemoFixture.jar', plugins / 'WormholesDemoFixture.jar')
        (plugins / 'Wormholes').mkdir(exist_ok=True)
        (plugins / 'Wormholes/wormholes.toml').write_text('schema = 3\n\n[atmosphere]\nbiome-tint = true\nsky-light = true\n')
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
                state_file.write_text(json.dumps({'instance': self.name, 'path': str(self.server), 'port': self.port}))
                print('Server ready: ' + self.name, flush=True)
                return
            time.sleep(1)
        raise TimeoutError('Server startup: ' + text[-4000:])

    def open_clients(self, variant: str) -> tuple[Bridge, Bridge]:
        credentials: dict[int, tuple[int, str]] = {number: client_credentials(number) for number in client_numbers(variant)}
        self.close_clients()
        for number, (port, token) in credentials.items():
            bridge: Bridge = Bridge(port, token)
            try:
                existing: dict = bridge.state(timeout=1)
            except OSError:
                continue
            if existing.get('capturing'):
                raise RuntimeError('Client profile is already recording: ' + str(number))
            self.clients.append((PRISM / 'instances' / (PROFILE_PREFIX + ' - ' + str(number)), bridge))
        self.close_clients()
        sessions: list[dict[str, object]] = []
        for number, player in zip(client_numbers(variant), (ACTOR, OBSERVER)):
            port, token = credentials[number]
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
            fit_hidden_renderer(bridge)
            shader_state: dict = bridge.command('shader', enabled=False).get('iris', {})
            if shader_state.get('shadersEnabled') or shader_state.get('shaderPackInUse'):
                raise AssertionError('Demonstration recording requires shaders disabled')
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


def verify_capture(state: dict, identifier: str | None = None) -> dict:
    verify_hidden_renderer(state)
    return capture_evidence(state, identifier)


def capture_metrics(state: dict) -> dict:
    return {key: value for key, value in state.items() if key.startswith('capture') or key in HIDDEN_RENDERER_STATE}


def capture_evidence(state: dict, identifier: str | None = None) -> dict:
    frames: int = state.get('captureFrames', 0)
    encoded: int = state.get('captureEncodedFrames', frames)
    dropped: int = state.get('captureDroppedFrames', 0)
    if any(type(value) is not int or value < 0 for value in (frames, encoded, dropped)):
        raise AssertionError('Capture frame counts must be nonnegative integers')
    repeated: int = state.get('captureRepeatedFrames', encoded - frames)
    if type(repeated) is not int or repeated < 0:
        raise AssertionError('Capture frame counts must be nonnegative integers')
    if encoded - frames != repeated:
        raise AssertionError('Encoded frames do not match real and repeated frame counts')
    holds: list[dict] = state.get('captureHolds', [])
    if not isinstance(holds, list):
        raise AssertionError('Capture holds must be frame ranges')
    edits: list[dict[str, int]] = []
    previous_end: int = 0
    removed_frames: int = 0
    for hold in holds:
        if not isinstance(hold, dict):
            raise AssertionError('Capture hold is not a frame range')
        start: int = hold.get('startFrame')
        end: int = hold.get('endFrame')
        if (type(start) is not int or type(end) is not int or start < previous_end
                or end > encoded or end - start < 15):
            raise AssertionError('Capture hold is unordered, overlapping, short, or out of bounds')
        edits.append({'startFrame': start, 'endFrame': end})
        removed_frames += end - start
        previous_end = end
    if removed_frames > repeated:
        raise AssertionError('Capture holds would remove real framebuffer frames')
    if identifier not in LOADING_PAUSE_DEMOS:
        edits = []
        removed_frames = 0
    seconds: float = float(state.get('captureSeconds', 0.0))
    if 'captureEncodedFrames' in state and abs(encoded / 30.0 - seconds) > 1.0 / 30.0 + 0.001:
        raise AssertionError('Encoded timeline does not match capture duration')
    removed_seconds: float = removed_frames / 30.0
    trimmed_seconds: float = seconds - removed_seconds
    if (frames < 30 or not math.isfinite(seconds) or trimmed_seconds <= 0 or frames / trimmed_seconds < 28.0
            or dropped != 0 or state.get('captureSource') != '1920x1080'):
        raise AssertionError('Capture quality failed: frames=' + str(frames) + ', seconds=' + str(seconds)
                             + ', dropped=' + str(dropped) + ', source=' + str(state.get('captureSource')))
    raw: dict = capture_metrics(state)
    return {'raw': raw, 'edits': edits, 'removedSeconds': removed_seconds, 'trimmedSeconds': trimmed_seconds}


def perspectives(identifier: str) -> tuple[str, ...]:
    sheet: dict = json.loads((ROOT / 'src/test/demo/shots.json').read_text())
    return tuple(next(shot['perspectives'] for shot in sheet['shots'] if shot['id'] == identifier))


def export_edits(capture: dict) -> list[dict]:
    cuts: list[dict] = capture.get('editorialEdits', [])
    previous_end: int = 0
    for cut in cuts:
        start: int = cut.get('startFrame')
        end: int = cut.get('endFrame')
        if (type(start) is not int or type(end) is not int or start < previous_end
                or end <= start or end > round(float(capture['raw']['captureSeconds']) * 30)):
            raise AssertionError('Editorial cuts must be ordered nonoverlapping frame ranges within the raw take')
        previous_end = end
    edits: list[dict] = sorted([*capture['edits'], *cuts], key=lambda edit: edit['startFrame'])
    merged: list[dict] = []
    for edit in edits:
        if merged and edit['startFrame'] <= merged[-1]['endFrame']:
            merged[-1]['endFrame'] = max(merged[-1]['endFrame'], edit['endFrame'])
        else:
            merged.append(dict(edit))
    if sum(edit['endFrame'] - edit['startFrame'] for edit in merged) >= round(float(capture['raw']['captureSeconds']) * 30):
        raise AssertionError('Editorial cuts must retain visible footage')
    return merged


def export_filters(identifier: str, take: dict) -> dict[str, str]:
    captures: list[dict] = take.get('capture', [])
    required: tuple[str, ...] = perspectives(identifier)
    views: list[str] = [entry.get('view') for entry in captures]
    if len(set(views)) != len(views) or not set(required).issubset(views):
        raise AssertionError('Export requires each selected validated capture view')
    filters: dict[str, str] = {}
    for capture in captures:
        if capture['view'] not in required:
            continue
        verified: dict = capture_evidence(capture['raw'], identifier)
        if any(capture.get(key) != verified[key] for key in ('edits', 'removedSeconds', 'trimmedSeconds')):
            raise AssertionError('Export edits do not match accepted capture evidence')
        merged: list[dict] = export_edits(capture)
        prefix: str = ''
        if merged:
            removed: str = '+'.join('between(n,' + str(edit['startFrame']) + ',' + str(edit['endFrame'] - 1) + ')'
                                   for edit in merged)
            prefix = "select='not(" + removed + ")',setpts=N/(30*TB),"
        filters[capture['view']] = prefix + 'scale=1920:1080:flags=lanczos'
    return filters


def export(identifier: str, variant: str, only_views: tuple[str, ...] | None = None) -> list[Path]:
    manifest: dict = json.loads((OUTPUT / 'manifest.json').read_text())
    matches: list[dict] = [take for take in manifest['takes'] if take['id'] == identifier and take['variant'] == variant]
    if len(matches) != 1:
        raise AssertionError('Export requires exactly one accepted take for ' + identifier + '-' + variant)
    filters: dict[str, str] = export_filters(identifier, matches[0])
    if only_views is not None:
        if not only_views or not set(only_views).issubset(filters):
            raise ValueError('Select only published perspectives for this demonstration')
        filters = {view: filters[view] for view in only_views}
    DOCS.mkdir(parents=True, exist_ok=True)
    def encode(view: str) -> Path:
        source: Path = OUTPUT / 'intermediate' / (identifier + '-' + variant + '-' + view + '.mp4')
        target: Path = DOCS / (identifier + '-' + variant + '-' + view + '.webm')
        limit: int = 25 * 1024 * 1024
        command: list[str] = [str(FFMPEG), '-y', '-i', str(source), '-vf', filters[view], '-r', '30',
            '-c:v', 'libvpx-vp9', '-row-mt', '1', '-threads', '4', '-deadline', 'good', '-cpu-used', '4',
            '-pix_fmt', 'yuv420p', '-an']
        if not target.is_file() or target.stat().st_size <= limit:
            result: subprocess.CompletedProcess[str] = subprocess.run(
                [*command, '-crf', '27', '-b:v', '0', str(target)], capture_output=True, text=True)
            if result.returncode:
                raise RuntimeError('Export failed: ' + result.stderr[-2000:])
        if target.stat().st_size > limit:
            capture: dict = next(entry for entry in matches[0]['capture'] if entry['view'] == view)
            bitrate: int = int(22 * 1024 * 1024 * 8 / (float(capture['raw']['captureSeconds']) - sum(
                cut['endFrame'] - cut['startFrame'] for cut in export_edits(capture)) / 30.0))
            with tempfile.TemporaryDirectory(prefix='vp9-', dir=OUTPUT / 'intermediate') as temporary:
                passlog: str = str(Path(temporary) / 'pass')
                for pass_number in (1, 2):
                    destination: list[str] = ['-f', 'null', os.devnull] if pass_number == 1 else [str(target)]
                    result = subprocess.run([*command, '-b:v', str(bitrate), '-pass', str(pass_number),
                        '-passlogfile', passlog, *destination], capture_output=True, text=True)
                    if result.returncode:
                        raise RuntimeError('Bounded export failed: ' + result.stderr[-2000:])
        if target.stat().st_size > limit:
            raise AssertionError('Clip exceeds 25 MB: ' + str(target))
        return target
    with concurrent.futures.ThreadPoolExecutor(max_workers=2) as executor:
        return list(executor.map(encode, filters))
