import json
import contextlib
import os
import re
import shutil
import time
import tomllib
from pathlib import Path
from collections.abc import Iterator

from studio import ACTOR, OBSERVER, MUX, OUTPUT, Rcon, Studio, free_port, mux

STATE_FILE: Path = OUTPUT / 'gateway-server.json'
OWNER: str = 'wormholes-demo-gateway'
SOURCE_NAME: str = 'demo-source'
TARGET_NAME: str = 'demo-target'


def _write(path: Path, contents: str) -> None:
    temporary: Path = path.with_name(path.name + '.gateway-tmp')
    temporary.write_text(contents)
    os.replace(temporary, path)


def _properties(path: Path) -> dict[str, str]:
    return dict(line.split('=', 1) for line in path.read_text().splitlines()
                if '=' in line and not line.startswith('#'))


def _network_config(contents: str, values: dict[str, object]) -> str:
    pending: dict[str, object] = dict(values)
    lines: list[str] = []
    active: bool = False
    found: bool = False
    for line in contents.splitlines():
        stripped: str = line.strip()
        if stripped.startswith('['):
            if active:
                lines.extend(key + ' = ' + json.dumps(value) for key, value in pending.items())
                pending.clear()
            active = stripped == '[network]'
            found = found or active
        elif active and '=' in line and not stripped.startswith('#'):
            key: str = line.split('=', 1)[0].strip()
            if key in pending:
                line = key + ' = ' + json.dumps(pending.pop(key))
        lines.append(line)
    if active:
        lines.extend(key + ' = ' + json.dumps(value) for key, value in pending.items())
    elif not found:
        lines.extend(['', '[network]'])
        lines.extend(key + ' = ' + json.dumps(value) for key, value in pending.items())
    result: str = '\n'.join(lines) + '\n'
    parsed: dict = tomllib.loads(result)
    if any(parsed['network'].get(key) != value for key, value in values.items()):
        raise ValueError('Network configuration did not retain the requested values')
    return result


def network_report(server: Studio, player: str = ACTOR) -> dict:
    if server.rcon is None:
        raise RuntimeError('Server RCON is unavailable')
    reply: str = server.rcon.command('whdemo feature network-report ' + player)
    marker: str = 'networkReport='
    if marker not in reply:
        raise RuntimeError('The deployed fixture does not provide network-report')
    report: dict = json.JSONDecoder().raw_decode(reply.split(marker, 1)[1])[0]
    return report


def _require_primary(primary: Studio) -> None:
    if primary.server is None or primary.rcon is None:
        raise RuntimeError('The primary studio must already be running')
    state: dict = json.loads((OUTPUT / 'server.json').read_text())
    if state['instance'] != primary.name or Path(state['path']).resolve() != primary.server.resolve():
        raise RuntimeError('Primary studio does not match its ownership manifest')
    expected: Path = MUX / 'consumers/plugin-consumers/instances' / primary.name
    if not primary.name.startswith('wormholes-demo-') or primary.server.resolve() != expected.resolve():
        raise RuntimeError('Primary is outside the disposable demonstration workspace')


def gateway_state() -> dict:
    state: dict = json.loads(STATE_FILE.read_text())
    primary: dict = json.loads((OUTPUT / 'server.json').read_text())
    expected: Path = MUX / 'consumers/plugin-consumers/instances' / state['instance']
    if state.get('owner') != OWNER or state.get('primary') != primary['instance']:
        raise RuntimeError('Gateway scene belongs to another studio')
    if Path(state['path']).resolve() != expected.resolve() or not expected.is_dir():
        raise RuntimeError('Gateway server directory is missing or outside its owned workspace')
    if not all(key in state for key in ('sourceGateway', 'destinationGateway', 'sourceServer', 'destinationServer')):
        raise RuntimeError('Gateway network preparation has not completed')
    return state


@contextlib.contextmanager
def secondary_connection() -> Iterator[Rcon]:
    state: dict = gateway_state()
    connection: Rcon = Rcon(Path(state['path']))
    try:
        yield connection
    finally:
        connection.socket.close()


def _state(secondary: Studio, primary: Studio) -> dict:
    return {'owner': OWNER, 'primary': primary.name, 'instance': secondary.name,
            'path': str(secondary.server), 'port': secondary.port}


def reuse_secondary(primary: Studio, connect: bool = True) -> Studio | None:
    if not STATE_FILE.is_file():
        return None
    state: dict = json.loads(STATE_FILE.read_text())
    if state.get('owner') != OWNER or state.get('primary') != primary.name:
        raise RuntimeError('Secondary manifest belongs to another run; preserve it until its owner cleans up')
    name: str = state['instance']
    server_path: Path = Path(state['path'])
    expected: Path = MUX / 'consumers/plugin-consumers/instances' / name
    if not name.startswith('wormholes-demo-') or server_path.resolve() != expected.resolve():
        raise RuntimeError('Secondary is outside the disposable demonstration workspace')
    if not server_path.is_dir():
        return None
    secondary: Studio = Studio()
    secondary.name = name
    secondary.server = server_path
    secondary.port = int(state['port'])
    secondary.created = True
    if connect:
        secondary.rcon = Rcon(server_path)
        network_report(secondary)
    return secondary


def _wait_started(server: Studio, identity: str) -> None:
    if server.server is None:
        raise RuntimeError('Missing server directory')
    deadline: float = time.monotonic() + 180
    last_error: str = ''
    while time.monotonic() < deadline:
        candidate: Rcon | None = None
        try:
            candidate = Rcon(server.server)
            server.rcon = candidate
            report: dict = network_report(server)
            if report['server'] == identity and report['running']:
                return
            last_error = 'Networking has not started with the requested identity'
        except (OSError, ValueError, RuntimeError, AssertionError) as failure:
            last_error = str(failure)
        if candidate is not None:
            candidate.socket.close()
        server.rcon = None
        time.sleep(1)
    raise TimeoutError('Gateway server startup failed: ' + last_error)


def _configure(server: Studio, identity: str, backup: bool = True) -> None:
    if server.server is None or server.rcon is None:
        raise RuntimeError('Cannot configure a server without its directory and live RCON')
    config_path: Path = server.server / 'plugins/Wormholes/wormholes.toml'
    properties_path: Path = server.server / 'server.properties'
    original_config: str = config_path.read_text()
    config: dict = tomllib.loads(original_config)
    properties: dict[str, str] = _properties(properties_path)
    if properties.get('online-mode') != 'false' or properties.get('server-ip') != '127.0.0.1':
        raise RuntimeError('Gateway studios must already use isolated offline loopback authentication')
    if config.get('network', {}).get('proxy', {}).get('enabled', False):
        raise RuntimeError('Gateway studio cannot reuse a configured proxy instance')
    network: dict = config['network']
    port: int = int(network.get('listen-port', 0)) if network.get('server-name') == identity else free_port()
    values: dict[str, object] = {'enabled': True, 'server-name': identity, 'transfer-mode': 'direct',
                               'listen-enabled': True, 'listen-port': port, 'advertise-host-override': '127.0.0.1',
                               'game-host-override': '127.0.0.1', 'private-game-host-override': '127.0.0.1',
                               'game-port-override': 0, 'private-game-port-override': 0, 'auto-accept-transfers': True}
    updated_config: str = _network_config(original_config, values)
    for player in (ACTOR, OBSERVER):
        if network_report(server, player)['player']['online']:
            raise RuntimeError('Disconnect demonstration clients before configuring gateway networking')
    history: list[dict[str, str]] = list(server.rcon.history)
    server.rcon.socket.close()
    server.rcon = None
    mux('runtime', 'stop', server.name)
    if backup:
        mux('backup', 'create', server.name, '--label', 'before-gateway-network')
    properties['accepts-transfers'] = 'true'
    _write(config_path, updated_config)
    _write(properties_path, ''.join(key + '=' + value + '\n' for key, value in properties.items()))
    mux('runtime', 'start', server.name, '--no-console')
    _wait_started(server, identity)
    if server.rcon is not None:
        server.rcon.history = history + server.rcon.history
    print('Gateway network ready: ' + identity + ' (' + server.name + ')', flush=True)


def _export(server: Studio) -> str:
    if server.rcon is None:
        raise RuntimeError('Cannot export from a disconnected server')
    response: str = server.rcon.exchange(2, 'whdemo feature server-export')
    matched: re.Match[str] | None = re.search(r'serverCode=(WHS2\.[A-Za-z0-9_-]+)', response)
    if matched is None:
        raise RuntimeError('Gateway fixture did not return its synchronous server code')
    return matched.group(1)


def _import(server: Studio, code: str) -> None:
    if server.rcon is None:
        raise RuntimeError('Cannot import on a disconnected server')
    response: str = server.rcon.exchange(2, 'wh server import ' + code)
    if any(error in response for error in ('Unknown or incomplete', 'Incorrect argument', 'not initialized')):
        raise RuntimeError('Server rejected the peer import command')


def _wait_peers(primary: Studio, secondary: Studio) -> None:
    deadline: float = time.monotonic() + 60
    while time.monotonic() < deadline:
        ready: bool = True
        for server, peer_name in ((primary, TARGET_NAME), (secondary, SOURCE_NAME)):
            report: dict = network_report(server)
            ready = ready and any(peer['name'] == peer_name and peer['ready'] for peer in report['peers'])
        if ready:
            return
        time.sleep(1)
    raise TimeoutError('Both gateway peers did not become ready within 60 seconds')


def _prepare_gateways(server: Studio) -> dict[str, str]:
    if server.rcon is None:
        raise RuntimeError('Gateway scene requires a connected server')
    response: str = server.rcon.command('whdemo feature prepare remote-gateway')
    portals: dict[str, str] = {name: portal_id for portal_id, name in
                               re.findall(r'portal=([0-9a-f-]{36}), name=([^,\r\n]+)', response)}
    if not all(name in portals for name in ('Garden Arch', 'Sun Court')):
        raise RuntimeError('Gateway scene did not report both named portal IDs')
    if 'sourceFrame=true' not in response or 'destinationFrame=true' not in response:
        raise RuntimeError('Gateway scene has incomplete physical frames')
    return portals


def prepare_network(primary: Studio) -> Studio:
    _require_primary(primary)
    secondary: Studio | None = reuse_secondary(primary)
    newly_created: bool = secondary is None
    if secondary is None:
        secondary = Studio()
    try:
        if newly_created:
            secondary.start(state_file=STATE_FILE)
            _write(STATE_FILE, json.dumps(_state(secondary, primary), indent=2) + '\n')
        _configure(primary, SOURCE_NAME)
        _configure(secondary, TARGET_NAME, backup=not newly_created)
        _import(primary, _export(secondary))
        _import(secondary, _export(primary))
        _wait_peers(primary, secondary)
        source_portals: dict[str, str] = _prepare_gateways(primary)
        target_portals: dict[str, str] = _prepare_gateways(secondary)
        deadline: float = time.monotonic() + 30
        while True:
            try:
                if secondary.rcon is None:
                    raise RuntimeError('Secondary RCON disconnected during setup')
                response: str = secondary.rcon.command('whdemo feature remote-link ' + target_portals['Sun Court']
                                                       + ' ' + SOURCE_NAME + ' ' + source_portals['Garden Arch'])
                if 'linked=true' in response and 'open=true' in response:
                    break
            except AssertionError as failure:
                if 'Destination portal is not in the ready peer directory' not in str(failure):
                    raise
            if time.monotonic() >= deadline:
                raise TimeoutError('Remote return gateway did not become linked and open')
            time.sleep(1)
        state: dict = _state(secondary, primary)
        state.update({'sourceGateway': source_portals['Garden Arch'], 'destinationGateway': target_portals['Sun Court'],
                      'sourceServer': SOURCE_NAME, 'destinationServer': TARGET_NAME})
        _write(STATE_FILE, json.dumps(state, indent=2) + '\n')
        print('Gateway pair ready: ' + SOURCE_NAME + ' -> ' + TARGET_NAME + '; return link prepared', flush=True)
        return secondary
    except BaseException:
        if newly_created and secondary.created:
            if secondary.server is None:
                secondary.server = Path(mux('instance', 'path', secondary.name).splitlines()[-1])
            _write(STATE_FILE, json.dumps(_state(secondary, primary), indent=2) + '\n')
            close_secondary(secondary)
        raise


def close_secondary(secondary: Studio) -> None:
    state: dict = json.loads(STATE_FILE.read_text())
    if state.get('owner') != OWNER or state.get('instance') != secondary.name:
        raise RuntimeError('Refusing to clean up a secondary without its matching ownership manifest')
    if secondary.server is None or Path(state['path']).resolve() != secondary.server.resolve():
        raise RuntimeError('Secondary cleanup directory does not match its manifest')
    if secondary.rcon is not None:
        _write(OUTPUT / 'gateway-commands.json', json.dumps(secondary.rcon.history, indent=2) + '\n')
        secondary.rcon.socket.close()
        secondary.rcon = None
    mux('runtime', 'stop', secondary.name)
    log: Path = secondary.server / 'logs/latest.log'
    if log.is_file():
        shutil.copy2(log, OUTPUT / 'gateway-server-latest.log')
    mux('instance', 'delete', secondary.name)
    secondary.created = False
    STATE_FILE.unlink()
    print('Disposable gateway server removed: ' + secondary.name, flush=True)
