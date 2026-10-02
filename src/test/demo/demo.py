import argparse
import concurrent.futures
import json
import re
import time
from pathlib import Path

import gateway_studio
from studio import ACTOR, OBSERVER, OUTPUT, FFMPEG, Bridge, Rcon, Studio, capture_metrics, claim_studio, export, perspectives, fit_hidden_renderer, look, verify_capture, verify_hidden_renderer, verify_sessions


def menu(bridge: Bridge) -> dict:
    return bridge.wait(lambda state: state.get('container') is not None, 'portal menu')['container']


def click_menu(bridge: Bridge, label: str, pause: float = 0.7, button: int = 0, shift: bool = False) -> None:
    container: dict = menu(bridge)
    target: dict | None = next((slot for slot in container['slots'] if re.fullmatch(label, slot.get('name', ''), re.I)), None)
    if target is None:
        raise AssertionError('Missing menu control ' + label + ': ' + json.dumps(container))
    bridge.command('window', action='shift' if shift else 'click', slot=target['index'], containerId=container['id'], button=button)
    time.sleep(0.5)
    state: dict = bridge.wait(lambda state: not state.get('windowPending', False), 'menu cursor and click')
    if state.get('windowError'):
        raise AssertionError(state['windowError'])
    time.sleep(pause)


def screenshot(bridge: Bridge, identifier: str) -> None:
    bridge.command('screenshot', name=identifier)
    bridge.wait(lambda state: state.get('screenshotPath') is not None, 'screenshot readback')


def open_menu(actor: Bridge, offset: int = 0) -> None:
    actor.command('slot', index=0)
    look(actor, (0.5, 71.5, offset + 0.5))
    actor.command('click', key='use')
    menu(actor)
    time.sleep(0.8)


def check_portals(rcon: Rcon, count: int, linked: bool = False) -> str:
    deadline: float = time.monotonic() + 15
    while time.monotonic() < deadline:
        reply: str = rcon.command('whdemo report')
        if 'portals=' + str(count) in reply and reply.count('width=3, height=3, cells=9') == count:
            if 'sourceFrame=true' not in reply or 'destinationFrame=true' not in reply:
                raise AssertionError('Physical portal frame changed: ' + reply)
            if linked and reply.count('linked=true') != count:
                time.sleep(0.25)
                continue
            return reply
        time.sleep(0.25)
    raise AssertionError('Portal construction/link state: ' + reply)


def wand(actor: Bridge, rcon: Rcon, offset: int = 0, expected: int = 1, photographed: str = '') -> str:
    rcon.command('whdemo equip-wand ' + ACTOR)
    actor.command('slot', index=0)
    for target, button in (((-0.5, 70.5, offset + 1.0), 'attack'), ((1.5, 72.5, offset + 1.0), 'use')):
        look(actor, target, ticks=16)
        actor.command('click', key=button)
        time.sleep(1.0)
    if photographed:
        screenshot(actor, photographed + '-selection')
    look(actor, (0.5, 71.5, offset + 0.5), ticks=16)
    actor.command('click', key='attack')
    report: str = check_portals(rcon, expected)
    time.sleep(2)
    actor.command('slot', index=2)
    for target in ((-0.5, 70.5, offset + 1.0), (1.5, 72.5, offset + 1.0)):
        look(actor, target, ticks=12)
        actor.command('keys', attack=True, leaseTicks=22)
        time.sleep(1.15)
        actor.command('release')
    rcon.command('whdemo clear-markers ' + ('source' if offset == 0 else 'destination'))
    look(actor, (0.5, 71.5, offset + 0.5), ticks=16)
    actor.command('keys', back=True, leaseTicks=8)
    time.sleep(0.5)
    actor.command('release')
    time.sleep(1.5)
    return report


def rune(actor: Bridge, rcon: Rcon, identifier: str) -> str:
    rcon.command('whdemo equip-runes ' + ACTOR)
    actor.wait(lambda state: any(item.get('name') == 'Wormhole Rune' and item['count'] == 9 for item in state['inventory']), 'nine runes')
    actor.command('slot', index=1)
    for row in range(3):
        columns: range = range(3) if row % 2 == 0 else range(2, -1, -1)
        for column in columns:
            x: int = column - 1
            y: int = 70 + row
            look(actor, (x + 0.5, y + 0.5, -0.001), ticks=12)
            actor.command('click', key='use')
            time.sleep(0.5)
            reply: str = rcon.command('execute if block ' + str(x) + ' ' + str(y) + ' 0 minecraft:dark_prismarine run whdemo skin-status')
            if 'skinApplied=true' not in reply:
                raise AssertionError('Rune placement failed at ' + str((x, y, 0)) + ': ' + reply)
    screenshot(actor, identifier + '-placed')
    time.sleep(1)
    actor.command('slot', index=0)
    look(actor, (0.5, 71.5, 1.0), ticks=16)
    actor.command('click', key='attack')
    report: str = check_portals(rcon, 1)
    time.sleep(2)
    for x in range(-1, 2):
        for y in range(70, 73):
            reply = rcon.command('execute if block ' + str(x) + ' ' + str(y) + ' 0 minecraft:air run whdemo skin-status')
            if 'skinApplied=true' not in reply:
                raise AssertionError('Constructed rune was not consumed: ' + reply)
    rcon.command('whdemo clear-markers source')
    actor.command('slot', index=2)
    look(actor, (0.5, 71.5, 0.5), ticks=16)
    actor.command('keys', back=True, leaseTicks=8)
    time.sleep(0.5)
    actor.command('release')
    time.sleep(2)
    return report


def rename(actor: Bridge, name: str, offset: int = 0) -> None:
    open_menu(actor, offset)
    click_menu(actor, 'Rename Portal')
    actor.wait(lambda state: state.get('container') is None, 'rename chat prompt')
    actor.command('chat', text=name)
    actor.wait(lambda state: state.get('container') is not None
               and any(name in str(item.get('lore', [])) for item in state['container']['slots']), 'portal renamed')
    actor.command('window', action='close')
    time.sleep(0.6)


def linking_setup(actor: Bridge, rcon: Rcon) -> None:
    rcon.command('tp ' + ACTOR + ' 0.5 70 3.8 180 0')
    wand(actor, rcon)
    rcon.command('tp ' + ACTOR + ' 0.5 70 3.8 180 0')
    time.sleep(0.5)
    rename(actor, 'Garden Arch')
    rcon.command('tp ' + ACTOR + ' 0.5 70 203.8 180 0')
    time.sleep(1)
    wand(actor, rcon, 200, 2)
    rcon.command('tp ' + ACTOR + ' 0.5 70 203.8 180 0')
    time.sleep(0.5)
    rename(actor, 'Sun Court', 200)
    rcon.command('tp ' + ACTOR + ' 0.5 70 3.8 180 0')
    time.sleep(1)


def linking(actor: Bridge, observer: Bridge, rcon: Rcon, identifier: str) -> str:
    open_menu(actor)
    screenshot(actor, identifier + '-home')
    click_menu(actor, 'Destination')
    screenshot(actor, identifier + '-destinations')
    time.sleep(1.5)
    click_menu(actor, 'Sun Court')
    time.sleep(1)
    if actor.state().get('container') is not None:
        actor.command('window', action='close')
    open_menu(actor)
    click_menu(actor, 'Destination')
    screenshot(actor, identifier + '-return-control')
    click_menu(actor, 'Link and return')
    time.sleep(1)
    if actor.state().get('container') is not None:
        actor.command('window', action='close')
    report: str = check_portals(rcon, 2, linked=True)
    actor.command('slot', index=2)
    look(actor, (0.5, 71.4, 0.5), ticks=16)
    time.sleep(2.5)
    screenshot(actor, identifier + '-projection')
    look(actor, (-0.5, 71.4, 0.5), ticks=18)
    look(actor, (1.5, 71.4, 0.5), ticks=24)
    look(actor, (0.5, 71.4, 0.5), ticks=18)
    actor.command('keys', forward=True, leaseTicks=50)
    actor.wait(lambda state: state['position']['z'] > 180, 'outbound portal crossing', timeout=5)
    actor.command('release')
    time.sleep(2)
    screenshot(actor, identifier + '-arrival')
    actor.command('look', yaw=0, pitch=0, ticks=30)
    time.sleep(1.7)
    actor.wait(lambda state: not state.get('turning', False), 'return turn')
    actor.command('keys', forward=True, leaseTicks=80)
    actor.wait(lambda state: state['position']['z'] < 20, 'return portal crossing', timeout=6)
    actor.command('release')
    time.sleep(2)
    return report


def record(studio: Studio, actor: Bridge, observer: Bridge, shot: dict, variant: str) -> dict:
    identifier: str = shot['id'] + '-' + variant
    print('Preparing ' + identifier, flush=True)
    if shot.get('group') == 'doors':
        import door_shots
        door_shots.prepare(shot['id'], actor, observer, studio.rcon)
    elif shot.get('group') == 'features':
        import feature_shots
        feature_shots.prepare(shot['id'], actor, observer, studio.rcon)
    else:
        studio.rcon.command('whdemo scene ' + shot['scene'])
        studio.rcon.command('gamemode survival ' + ACTOR)
        studio.rcon.command('tp ' + ACTOR + ' 0.5 70 3.8 180 0')
        studio.rcon.command('whdemo pose observer')
        time.sleep(2)
        if shot['id'] == 'portal-linking':
            linking_setup(actor, studio.rcon)
        else:
            studio.rcon.command('whdemo equip-' + ('runes' if shot['id'] == 'rune-creation' else 'wand'))
        look(observer, (0.5, 71.5, 0.5), ticks=1)
    actor.command('release')
    observer.command('release')
    actor.command('hud', visible=True)
    observer.command('hud', visible=False)
    time.sleep(2)
    for bridge in (actor, observer):
        bridge.command('clear-errors')
        fit_hidden_renderer(bridge)
    time.sleep(1)
    captures: list[Bridge] = []
    views: tuple[str, ...] = perspectives(shot['id'])
    started: float = time.monotonic()
    (OUTPUT / 'intermediate').mkdir(exist_ok=True)
    try:
        for view, bridge in (('pov', actor), ('observer', observer)):
            if view not in views:
                continue
            bridge.command('capture', action='start', path=str(OUTPUT / 'intermediate' / (identifier + '-' + view + '.mp4')),
                           ffmpeg=str(FFMPEG), width=1920, height=1080, fps=30)
            captures.append(bridge)
            if bridge.state().get('captureSource') != '1920x1080':
                raise AssertionError('Capture did not start from a native 1920x1080 render target')
        print('Recording ' + identifier, flush=True)
        time.sleep(1.5)
        screenshot(actor, identifier + '-start')
        screenshot(observer, identifier + '-observer-start')
        if shot.get('group') == 'doors':
            proof = door_shots.perform(shot['id'], actor, observer, studio.rcon, identifier)
        elif shot.get('group') == 'features':
            proof = feature_shots.perform(shot['id'], actor, observer, studio.rcon, identifier)
        elif shot['id'] == 'wand-creation':
            proof: str = wand(actor, studio.rcon, photographed=identifier)
        elif shot['id'] == 'rune-creation':
            proof = rune(actor, studio.rcon, identifier)
        else:
            proof = linking(actor, observer, studio.rcon, identifier)
        screenshot(actor, identifier + '-end')
        screenshot(observer, identifier + '-observer-end')
        time.sleep(2)
        if time.monotonic() - started > shot['timeoutSeconds']:
            raise TimeoutError('Take exceeded shot budget')
    finally:
        actor.command('release')
        with concurrent.futures.ThreadPoolExecutor(max_workers=2) as executor:
            list(executor.map(lambda bridge: bridge.command('capture', action='stop'), captures))
        states: list[dict] = [bridge.state() for bridge in captures]
        reports: Path = OUTPUT / 'capture-reports'
        reports.mkdir(parents=True, exist_ok=True)
        (reports / (identifier + '.json')).write_text(json.dumps({
            'id': shot['id'], 'variant': variant,
            'capture': [{'view': view, 'raw': capture_metrics(state)} for view, state in zip(views, states)],
        }, indent=2))
    accepted: list[dict] = [{'view': view, **verify_capture(state, shot['id'])}
                            for view, state in zip(views, states)]
    return {'id': shot['id'], 'variant': variant, 'seconds': time.monotonic() - started, 'proof': proof,
            'skin': studio.rcon.command('whdemo skin-status'), 'sessions': studio.rcon.command('wh clientview status'),
            'capture': accepted}


def disconnect_gateway_clients(studio: Studio, actor: Bridge, observer: Bridge) -> None:
    for bridge in (actor, observer):
        if bridge.state().get('capturing'):
            raise RuntimeError('Cannot restart the gateway studio while a capture is active')
    for bridge in (actor, observer):
        bridge.command('release')
        bridge.command('disconnect')
    for bridge in (actor, observer):
        bridge.wait(lambda state: not state.get('connected'), 'client disconnected before gateway setup', timeout=15)
    deadline: float = time.monotonic() + 45
    while time.monotonic() < deadline:
        if all(not gateway_studio.network_report(studio, player)['player']['online'] for player in (ACTOR, OBSERVER)):
            return
        time.sleep(0.25)
    raise TimeoutError('Primary server did not release the demonstration client sessions')


def reconnect_gateway_clients(studio: Studio, actor: Bridge, observer: Bridge, variant: str) -> None:
    for bridge in (actor, observer):
        bridge.command('connect', address='127.0.0.1:' + str(studio.port))
    for player, bridge in ((ACTOR, actor), (OBSERVER, observer)):
        state: dict = bridge.wait(lambda value: value.get('connected') and value.get('player') == player,
                                  player + ' reconnected after gateway setup', timeout=60)
        verify_hidden_renderer(state)
    actor.wait(lambda state: state.get('skinLoaded', False), 'actor skin after gateway setup', timeout=45)
    if studio.rcon is None:
        raise RuntimeError('Primary RCON is unavailable after gateway setup')
    studio.rcon.command('gamemode creative ' + ACTOR)
    studio.rcon.command('gamemode spectator ' + OBSERVER)
    observer.command('hud', visible=False)
    deadline: float = time.monotonic() + 20
    while True:
        status: str = studio.rcon.command('wh clientview status')
        try:
            verify_sessions(status, variant, (ACTOR, OBSERVER))
            return
        except AssertionError:
            if time.monotonic() >= deadline:
                raise
        time.sleep(0.25)


def main() -> None:
    parser: argparse.ArgumentParser = argparse.ArgumentParser(description='Record Wormholes construction and linking in real Minecraft clients.')
    parser.add_argument('--variant', choices=('standard', 'clientview', 'all'), default='all')
    sheet: dict = json.loads((Path(__file__).parent / 'shots.json').read_text())
    parser.add_argument('--only', choices=tuple(shot['id'] for shot in sheet['shots']), action='append')
    parser.add_argument('--reuse-server', action='store_true')
    parser.add_argument('--reuse-clients', action='store_true')
    parser.add_argument('--keep-open', action='store_true')
    parser.add_argument('--record-only', action='store_true')
    args: argparse.Namespace = parser.parse_args()
    studio: Studio = Studio()
    shots: list[dict] = [shot for shot in sheet['shots'] if args.only is None or shot['id'] in args.only]
    variants: list[str] = sheet['variants'] if args.variant == 'all' else [args.variant]
    manifest_path: Path = OUTPUT / 'manifest.json'
    manifest: dict = json.loads(manifest_path.read_text()) if manifest_path.is_file() else {'takes': []}
    exports: list[tuple[str, str]] = []
    secondary: Studio | None = None
    gateway_requested: bool = any(shot['id'] == 'cross-server-gateways' for shot in shots)
    try:
        if args.reuse_server:
            info: dict = json.loads((OUTPUT / 'server.json').read_text())
            if not info['instance'].startswith('wormholes-demo-'):
                raise ValueError('Existing server is not an owned demo instance')
            studio.name = info['instance']
            studio.server = Path(info['path'])
            studio.port = info['port']
            studio.created = True
            studio.rcon = Rcon(studio.server)
        else:
            studio.start()
        for variant in variants:
            if args.reuse_clients:
                sessions: list[dict] = json.loads((OUTPUT / 'clients.json').read_text())
                if len(sessions) != 2 or any(session['variant'] != variant for session in sessions):
                    raise ValueError('Prepared client pair does not match requested variant')
                studio.clients = [(Path(session['instance']), Bridge(session['port'], session['token'])) for session in sessions]
                actor, observer = [bridge for _, bridge in studio.clients]
            else:
                actor, observer = studio.open_clients(variant)
            for shot in shots:
                if shot['id'] == 'cross-server-gateways':
                    disconnect_gateway_clients(studio, actor, observer)
                    secondary = gateway_studio.prepare_network(studio)
                    reconnect_gateway_clients(studio, actor, observer, variant)
                take: dict = record(studio, actor, observer, shot, variant)
                manifest['takes'] = [entry for entry in manifest['takes'] if (entry['id'], entry['variant']) != (shot['id'], variant)]
                manifest['takes'].append(take)
                manifest_path.write_text(json.dumps(manifest, indent=2))
                exports.append((shot['id'], variant))
                print('Recorded ' + shot['id'] + '-' + variant, flush=True)
    finally:
        if not args.keep_open:
            try:
                if gateway_requested:
                    if secondary is None:
                        secondary = gateway_studio.reuse_secondary(studio, connect=False)
                    if secondary is not None:
                        gateway_studio.close_secondary(secondary)
            finally:
                studio.close()
    if not args.record_only:
        for identifier, variant in exports:
            print('Exporting ' + identifier + '-' + variant, flush=True)
            print('Exported ' + ', '.join(str(path) for path in export(identifier, variant)), flush=True)


if __name__ == '__main__':
    with claim_studio():
        main()
