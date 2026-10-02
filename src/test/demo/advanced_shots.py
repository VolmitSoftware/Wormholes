import json
import math
import re
import time
from collections.abc import Callable

from studio import ACTOR, OBSERVER, Bridge, Rcon, look


def source_report(rcon: Rcon, prefix: str = 'portal') -> str:
    report: str = rcon.command('whdemo feature report')
    source: re.Match[str] | None = re.search(r'portal=([^,\s]+), name=Garden Arch,', report)
    if source is None:
        raise AssertionError('Source portal is absent: ' + report)
    record: re.Match[str] | None = re.search(re.escape(prefix + '=' + source.group(1) + ',') + r'[^\r\n]*', report)
    if record is None:
        raise AssertionError('Missing ' + prefix + ' source state: ' + report)
    return record.group(0)


def wait_report(rcon: Rcon, prefix: str, predicate: Callable[[str], bool], label: str, timeout: float = 20) -> str:
    deadline: float = time.monotonic() + timeout
    report: str = ''
    while time.monotonic() < deadline:
        report = source_report(rcon, prefix)
        if predicate(report):
            return report
        time.sleep(0.25)
    raise AssertionError(label + ': ' + report)


def cycle(actor: Bridge, control: str, target: str, limit: int = 8, force: bool = False) -> None:
    from demo import click_menu, menu
    if force:
        click_menu(actor, control)
    for _ in range(limit + 1):
        names: list[str] = [slot.get('name', '') for slot in menu(actor)['slots']]
        if any(re.fullmatch(target, name, re.I) for name in names):
            return
        click_menu(actor, control)
    raise AssertionError('Menu did not reach ' + target)


def extension(actor: Bridge, name: str) -> None:
    from feature_shots import open_path
    open_path(actor, 'Settings', 'More settings', name)


def cross(bridge: Bridge, target: tuple[float, float, float], arrived: Callable[[dict], bool], label: str,
          sprint: bool = False, jump: bool = False) -> dict:
    from feature_shots import close
    close(bridge)
    bridge.command('slot', index=2)
    look(bridge, target, ticks=24)
    bridge.command('keys', forward=True, sprint=sprint, jump=jump, leaseTicks=160)
    try:
        return bridge.wait(arrived, label, timeout=8)
    finally:
        bridge.command('release')


def return_source(actor: Bridge, offset: float = 200.5, normal_axis: str = 'z') -> None:
    from feature_shots import travel_to
    if normal_axis not in ('x', 'z'):
        raise ValueError('Unsupported return portal normal: ' + normal_axis)
    position: dict = actor.state()['position']
    center: float = 0.5 if normal_axis == 'x' else offset
    side: float = 1.0 if position[normal_axis] > center else -1.0
    staging: float = center + side * 3.0
    if normal_axis == 'x':
        travel_to(actor, (staging, 70.0, position['z']))
        travel_to(actor, (staging, 70.0, offset))
    else:
        travel_to(actor, (position['x'], 70.0, staging))
        travel_to(actor, (0.5, 70.0, staging))
    cross(actor, (0.5, 71.5, offset), lambda state: abs(state['position']['z']) < 20, 'return through portal')
    travel_to(actor, (0.5, 70.0, 3.8))
    time.sleep(1)


def prepare(identifier: str, actor: Bridge, observer: Bridge, rcon: Rcon) -> None:
    if identifier == 'cross-server-gateways':
        from gateway_studio import gateway_state, secondary_connection
        state: dict = gateway_state()
        if 'portal=' + state['sourceGateway'] + ',' not in source_report(rcon):
            raise AssertionError('Prepare the gateway network again after changing the primary scene')
        with secondary_connection() as destination:
            if 'portal=' + state['destinationGateway'] + ',' not in destination.command('whdemo feature report'):
                raise AssertionError('Prepared remote destination portal no longer exists')
            for connection, local, remote in ((rcon, state['sourceServer'], state['destinationServer']),
                                               (destination, state['destinationServer'], state['sourceServer'])):
                report: dict = network_snapshot(connection)
                if report['server'] != local or not any(peer['name'] == remote and peer['ready'] for peer in report['peers']):
                    raise AssertionError('Prepared gateway peers are not mutually ready')
    if identifier == 'rtp-personal':
        rcon.command('gamemode creative ' + OBSERVER)
        rcon.command('tp ' + OBSERVER + ' 0.5 70 7.5 180 0')
        observer.wait(lambda state: abs(state['position']['z'] - 7.5) < 0.5, 'second RTP traveler ready')
        look(observer, (0.5, 71.5, 0.5), ticks=1)
    if identifier == 'live-views':
        rcon.command('gamemode creative ' + OBSERVER)
        rcon.command('tp ' + OBSERVER + ' 4.5 70 196.5 180 0')
        rcon.command('item replace entity ' + OBSERVER + ' hotbar.0 with minecraft:wheat 1')
        observer.command('slot', index=0)
        observer.wait(lambda state: abs(state['position']['x'] - 4.5) < 0.5
                      and abs(state['position']['z'] - 196.5) < 0.5
                      and state.get('heldItem', {}).get('item') == 'minecraft:wheat',
                      'destination observer ready with wheat')
        look(observer, (4.5, 70.7, 192.5), ticks=1)


def atmosphere(actor: Bridge, observer: Bridge, rcon: Rcon, capture_id: str) -> str:
    from demo import screenshot
    from feature_shots import close, open_path, view
    reports: list[str] = []
    for mode in ('off', 'tint', 'tint_light', 'full'):
        extension(actor, 'Fidelity')
        cycle(actor, 'Atmosphere: .*', 'Atmosphere: ' + mode)
        screenshot(actor, capture_id + '-ui-' + mode)
        reports.append(wait_report(rcon, 'fidelityPortal', lambda value: 'atmosphere=' + mode.upper() in value,
                                   'atmosphere ' + mode))
        for projection in ('Off', 'On'):
            open_path(actor)
            cycle(actor, 'Projection .*', 'Projection ' + projection)
            reports.append(wait_report(rcon, 'portal', lambda value: 'projection=' + projection.upper() in value,
                                       mode + ' projection ' + projection))
            close(actor)
            time.sleep(1)
        actor.command('slot', index=2)
        look(actor, (0.5, 71.5, 0.5), ticks=16)
        look(observer, (0.5, 71.5, 0.5), ticks=16)
        positions: list[dict] = [bridge.state()['position'] for bridge in (actor, observer)]
        for direction in ('left', 'right'):
            try:
                for bridge in (actor, observer):
                    bridge.command('keys', leaseTicks=12, **{direction: True})
                time.sleep(0.7)
            finally:
                actor.command('release')
                observer.command('release')
            time.sleep(0.6)
            if direction == 'left':
                for bridge, start in zip((actor, observer), positions):
                    shifted: dict = bridge.state()['position']
                    if math.hypot(shifted['x'] - start['x'], shifted['z'] - start['z']) < 0.15:
                        raise AssertionError('Atmosphere camera did not shift laterally')
        for bridge, start in zip((actor, observer), positions):
            end: dict = bridge.state()['position']
            if math.dist(tuple(start[axis] for axis in ('x', 'y', 'z')),
                         tuple(end[axis] for axis in ('x', 'y', 'z'))) > 1:
                raise AssertionError('Atmosphere camera did not return near its starting position: ' + str(end))
        look(observer, (0.5, 71.5, 0.5), ticks=16)
        view(actor, observer, capture_id, mode, 5)
    return '\n'.join(reports)


def arrival_orientation(actor: Bridge, observer: Bridge, rcon: Rcon, capture_id: str) -> str:
    from demo import screenshot
    reports: list[str] = []
    headings: dict[str, float] = {}
    for mode in ('frame', 'look', 'snap', 'mirror'):
        extension(actor, 'Transit')
        cycle(actor, 'Orientation: .*', 'Orientation: ' + mode, force=True)
        screenshot(actor, capture_id + '-ui-' + mode)
        reports.append(wait_report(rcon, 'transitPortal', lambda value: 'orientation=' + mode.upper() in value,
                                   'arrival orientation ' + mode))
        state: dict = cross(actor, (0.1, 71.5, 0.5), lambda value: value['position']['z'] > 180,
                            'arrival orientation crossing')
        print('Arrival orientation ' + mode + ': ' + json.dumps({key: state[key] for key in ('position', 'yaw', 'pitch', 'velocity')}), flush=True)
        reports.append(mode + ' arrival=' + json.dumps({key: state[key] for key in ('position', 'yaw', 'pitch')}))
        headings[mode] = float(state['yaw'])
        reports.append(rcon.command('data get entity ' + ACTOR + ' Rotation'))
        screenshot(actor, capture_id + '-arrival-' + mode)
        screenshot(observer, capture_id + '-observer-' + mode)
        time.sleep(2)
        return_source(actor, normal_axis='x')
    if abs((headings['frame'] - headings['look'] + 180) % 360 - 180) < 45:
        raise AssertionError('Frame and preserved-look arrivals did not show the rotated exit: ' + str(headings))
    return '\n'.join(reports)


def momentum(actor: Bridge, observer: Bridge, rcon: Rcon, capture_id: str) -> str:
    from demo import click_menu, menu, screenshot
    reports: list[str] = []
    measurements: dict[str, dict[str, float]] = {}
    for mode in ('preserve', 'scale', 'zero'):
        extension(actor, 'Transit')
        cycle(actor, 'Momentum: .*', 'Momentum: ' + mode, force=True)
        if mode == 'scale':
            click_menu(actor, 'Momentum: .*', button=1)
            actor.wait(lambda state: state.get('container') is None, 'momentum factor prompt')
            actor.command('chat', text='3')
            menu(actor)
        screenshot(actor, capture_id + '-ui-' + mode)
        reports.append(wait_report(rcon, 'transitPortal', lambda value: 'mode=' + mode.upper() in value,
                                   'momentum ' + mode))
        arrival: dict = cross(actor, (0.5, 71.5, 0.5), lambda state: state['position']['z'] > 180,
                              'momentum crossing', sprint=True, jump=True)
        print('Momentum ' + mode + ' arrival: ' + json.dumps({key: arrival[key] for key in ('position', 'velocity', 'onGround')}), flush=True)
        reports.append(mode + ' arrival=' + json.dumps({key: arrival[key] for key in ('position', 'velocity', 'onGround')}))
        time.sleep(1.2)
        settled: dict = actor.state()
        if settled['position']['z'] < 180:
            raise AssertionError('Momentum observation left the destination: ' + json.dumps(settled['position']))
        measurements[mode] = {
            'speed': math.hypot(arrival['velocity']['x'], arrival['velocity']['z']),
            'coast': math.hypot(settled['position']['x'] - arrival['position']['x'],
                               settled['position']['z'] - arrival['position']['z']),
        }
        reports.append(mode + ' measured=' + json.dumps(measurements[mode]))
        screenshot(actor, capture_id + '-arrival-' + mode)
        screenshot(observer, capture_id + '-observer-' + mode)
        return_source(actor)
    for greater, lesser in (('scale', 'preserve'), ('preserve', 'zero')):
        if not (measurements[greater]['speed'] > measurements[lesser]['speed'] + 0.02
                or measurements[greater]['coast'] > measurements[lesser]['coast'] + 0.15):
            raise AssertionError('Momentum policies did not produce distinct arrival motion: ' + json.dumps(measurements))
    return '\n'.join(reports)


def repelled(actor: Bridge, front: bool) -> list[float]:
    from feature_shots import close
    close(actor)
    actor.command('slot', index=2)
    look(actor, (0.5, 71.5, 0.5), ticks=20)
    samples: list[float] = []
    actor.command('keys', forward=True, leaseTicks=45)
    try:
        deadline: float = time.monotonic() + 2.5
        while time.monotonic() < deadline:
            state: dict = actor.state()
            z: float = float(state['position']['z'])
            if abs(z) > 50:
                raise AssertionError('Denied traversal teleported the actor: ' + str(state['position']))
            samples.append(z)
            time.sleep(0.06)
    finally:
        actor.command('release')
    reversed_motion: bool = any((later - earlier > 0.08 if front else earlier - later > 0.08)
                               for earlier, later in zip(samples, samples[1:]))
    if not reversed_motion:
        raise AssertionError('No reflected movement observed: ' + str(samples))
    return samples


def membrane_bounce(actor: Bridge, observer: Bridge, rcon: Rcon, capture_id: str) -> str:
    from demo import screenshot
    from feature_shots import close, travel_to
    extension(actor, 'Transit')
    cycle(actor, 'Membrane: .*', 'Membrane: On', limit=2)
    screenshot(actor, capture_id + '-membrane-ui')
    report: str = wait_report(rcon, 'transitPortal', lambda value: 'membrane=true' in value, 'membrane enabled')
    cross(actor, (0.5, 71.5, 0.5), lambda state: state['position']['z'] > 180, 'membrane front permits travel')
    screenshot(actor, capture_id + '-front-arrival')
    return_source(actor)
    for target in ((3.5, 70.0, 3.8), (3.5, 70.0, -3.2), (0.5, 70.0, -3.2)):
        travel_to(actor, target)
    rear: list[float] = repelled(actor, False)
    screenshot(actor, capture_id + '-rear-repelled')
    screenshot(observer, capture_id + '-observer-rear')
    for target in ((3.5, 70.0, -3.2), (3.5, 70.0, 3.8), (0.5, 70.0, 3.8)):
        travel_to(actor, target)
    extension(actor, 'Transit')
    cycle(actor, 'Membrane: .*', 'Membrane: Off', limit=2)
    cycle(actor, 'Bounce: .*', 'Bounce: On', limit=2)
    screenshot(actor, capture_id + '-bounce-ui')
    report += '\n' + wait_report(rcon, 'transitPortal', lambda value: 'bounce=true' in value, 'bounce enabled')
    close(actor)
    front: list[float] = repelled(actor, True)
    screenshot(actor, capture_id + '-front-repelled')
    screenshot(observer, capture_id + '-observer-bounce')
    return report + '\nrear=' + str(rear) + '\nfront=' + str(front)


def dial(actor: Bridge, address: str) -> None:
    from demo import click_menu
    extension(actor, 'Network')
    click_menu(actor, 'Dial')
    click_menu(actor, re.escape(address) + ' .*')


def network_dialing(actor: Bridge, observer: Bridge, rcon: Rcon, capture_id: str) -> str:
    from demo import click_menu, screenshot
    from feature_shots import open_path, view
    extension(actor, 'Network')
    cycle(actor, 'Return link: .*', 'Return link: true', limit=2)
    screenshot(actor, capture_id + '-network-ui')
    reports: list[str] = []
    for address, offset in (('SUN', 200.5), ('AMBER', 400.5)):
        dial(actor, address)
        reports.append(wait_report(rcon, 'nexusPortal', lambda value: 'dial=' + address in value, 'dial ' + address))
        open_path(actor, 'Pair & Destination')
        click_menu(actor, 'Link and return')
        source: str = source_report(rcon)
        source_id: str = source.split(',', 1)[0].split('=', 1)[1]
        target: re.Match[str] | None = re.search(r'\bdestination=([^,\s]+)', source)
        if target is None or target.group(1) == 'none':
            raise AssertionError('Dialed portal has no destination: ' + source)
        deadline: float = time.monotonic() + 15
        report: str = ''
        while time.monotonic() < deadline:
            report = rcon.command('whdemo feature report')
            returned: re.Match[str] | None = re.search(r'(?m)^portal=' + re.escape(target.group(1)) + r',[^\r\n]+', report)
            if returned is not None and re.search(r'\bdestination=' + re.escape(source_id) + r'(?:,|$)', returned.group(0)):
                reports.append(returned.group(0))
                break
            time.sleep(0.25)
        else:
            raise AssertionError('Dialed destination has no verified return link: ' + report)
        view(actor, observer, capture_id, address, 4)
        cross(actor, (0.5, 71.5, 0.5), lambda state: abs(state['position']['z'] - offset) < 20, 'dialed arrival')
        screenshot(actor, capture_id + '-arrival-' + address)
        return_source(actor, offset)
    return '\n'.join(reports)


def redstone_control(actor: Bridge, observer: Bridge, rcon: Rcon, capture_id: str) -> str:
    from demo import screenshot
    from feature_shots import close, travel_to
    dial(actor, 'SUN')
    extension(actor, 'Network')
    cycle(actor, 'Redstone: .*', 'Redstone: DIAL_NEXT/.*')
    screenshot(actor, capture_id + '-wiring-ui')
    close(actor)
    travel_to(actor, (-2.5, 70.0, 3.8))
    actor.command('slot', index=2)
    reports: list[str] = []
    for index in range(2):
        before: str = source_report(rcon, 'nexusPortal')
        look(actor, (-3.5, 71.2, 3.5), ticks=24)
        actor.command('click', key='use')
        time.sleep(0.8)
        reports.append(wait_report(rcon, 'nexusPortal', lambda value: value != before, 'redstone changed destination'))
        look(actor, (0.5, 71.5, 0.5), ticks=28)
        time.sleep(4)
        screenshot(actor, capture_id + '-route-' + str(index))
        screenshot(observer, capture_id + '-observer-' + str(index))
        look(actor, (-3.5, 71.2, 3.5), ticks=24)
        actor.command('click', key='use')
        time.sleep(0.8)
    return '\n'.join(reports)


def personal_reservations(actor: Bridge, observer: Bridge, rcon: Rcon) -> str:
    source: str = source_report(rcon).split(',', 1)[0].split('=', 1)[1]
    players: set[str] = {str(actor.state()['uuid']), str(observer.state()['uuid'])}
    if len(players) != 2:
        raise AssertionError('Private RTP requires two distinct travelers')
    deadline: float = time.monotonic() + 45
    report: str = ''
    while time.monotonic() < deadline:
        report = rcon.command('whdemo feature report')
        ready: set[str] = {
            match.group(1) for match in re.finditer(
                r'rtpPlayer=([^,\s]+), portal=' + re.escape(source)
                + r', destination=[^\r\n]+:-?\d+,-?\d+,-?\d+(?=\r?$)', report, re.M)
        }
        if players <= ready:
            return '\n'.join(line for line in report.splitlines() if line.startswith('rtpPlayer='))
        time.sleep(0.25)
    raise AssertionError('Both private RTP reservations were not ready: ' + report)


def rtp(actor: Bridge, observer: Bridge, rcon: Rcon, capture_id: str, personal: bool) -> str:
    from demo import click_menu, screenshot
    from feature_shots import open_path, view
    open_path(actor, 'Random Destination', 'Rotation & Pool')
    click_menu(actor, 'Per-player Destinations' if personal else 'Shared Destination')
    if not personal:
        click_menu(actor, 'Static')
    screenshot(actor, capture_id + '-routing-ui')
    report: str = wait_report(rcon, 'rtpPortal', lambda value: 'allocation=' + ('PER_PLAYER' if personal else 'SHARED') in value,
                              'RTP allocation applied')
    initial_route: str = ''
    if not personal:
        runtime: str = wait_report(rcon, 'rtpRuntimePortal', lambda value: 'ready=true' in value,
                                   'shared RTP route ready', timeout=45)
        initial_match: re.Match[str] | None = re.search(r'active=(.*?), standby=', runtime)
        if initial_match is None or initial_match.group(1) in ('none', 'null'):
            raise AssertionError('Shared route has no reported active destination: ' + runtime)
        initial_route = initial_match.group(1)
        report += '\n' + runtime
    if personal:
        from feature_shots import close
        close(actor)
        report += '\n' + personal_reservations(actor, observer, rcon)
    view(actor, observer, capture_id, 'ready', 3)
    if not personal:
        open_path(actor, 'Random Destination', 'Rotation & Pool')
        click_menu(actor, 'Manual Reroll')
        screenshot(actor, capture_id + '-reroll-ui')
        click_menu(actor, 'Confirm')
        runtime = wait_report(rcon, 'rtpRuntimePortal',
                              lambda value: 'ready=true' in value and 'active=' + initial_route + ', standby=' not in value,
                              'manual reroll replaced the active destination', timeout=45)
        report += '\n' + runtime
        view(actor, observer, capture_id, 'rerolled', 8)
    first: dict = cross(actor, (0.5, 71.5, 0.5), lambda state: state['position']['z'] > 170, 'RTP landing')
    screenshot(actor, capture_id + '-arrival')
    first_position: dict = first['position']
    report += '\nfirst=' + json.dumps(first_position)
    if personal:
        second: dict = cross(observer, (0.5, 71.5, 0.5), lambda state: state['position']['z'] > 170, 'second private RTP landing')
        second_position: dict = second['position']
        if math.dist(tuple(first_position[axis] for axis in ('x', 'y', 'z')),
                     tuple(second_position[axis] for axis in ('x', 'y', 'z'))) < 2:
            raise AssertionError('Private RTP destinations are not visibly distinct')
        screenshot(observer, capture_id + '-second-arrival')
        report += '\nsecond=' + json.dumps(second_position)
        report += '\n' + rcon.command('whdemo feature report')
    time.sleep(4)
    return report


def live_views(actor: Bridge, observer: Bridge, rcon: Rcon, capture_id: str) -> str:
    from concurrent.futures import Future, ThreadPoolExecutor
    from demo import click_menu, screenshot
    from feature_shots import close, open_path, travel_to, view

    def lure() -> list[dict]:
        positions: list[dict] = []
        for x in (3.5, 5.5):
            travel_to(observer, (x, 70.0, 196.5))
            look(observer, (4.5, 70.7, 193.5), ticks=20)
            time.sleep(3)
            state: dict = observer.state()
            if state.get('heldItem', {}).get('item') != 'minecraft:wheat':
                raise AssertionError('Destination observer stopped holding wheat')
            positions.append(state['position'])
            screenshot(observer, capture_id + '-observer-lure-' + str(x))
        return positions

    before: str = rcon.command('whdemo feature report')
    entity_pattern: str = r'liveEntity=([^,]+), entityType=SHEEP, x=([^,]+), y=([^,]+), z=([^\r\n]+)'
    initial_entity: re.Match[str] | None = re.search(entity_pattern, before)
    if initial_entity is None:
        raise AssertionError('Live-view fixture has no moving entity: ' + before)
    close(actor)
    actor.command('slot', index=2)
    look(actor, (0.5, 71.5, 0.5), ticks=16)
    with ThreadPoolExecutor(max_workers=1) as executor:
        movement: Future[list[dict]] = executor.submit(lure)
        time.sleep(6)
        screenshot(actor, capture_id + '-live')
        lure_positions: list[dict] = movement.result(timeout=20)
    screenshot(observer, capture_id + '-observer-live')
    after: str = rcon.command('whdemo feature report')
    moved_entity: re.Match[str] | None = re.search(entity_pattern, after)
    if moved_entity is None or moved_entity.group(1) != initial_entity.group(1):
        raise AssertionError('Live destination sheep changed identity or disappeared: ' + after)
    displacement: float = math.hypot(float(moved_entity.group(2)) - float(initial_entity.group(2)),
                                    float(moved_entity.group(4)) - float(initial_entity.group(4)))
    if displacement < 0.25:
        raise AssertionError('Live destination sheep did not move at least 0.25 blocks: ' + after)
    open_path(actor)
    click_menu(actor, 'Projection On')
    view(actor, observer, capture_id, 'off', 3)
    open_path(actor)
    click_menu(actor, 'Projection Off')
    view(actor, observer, capture_id, 'restored', 6)
    cross(actor, (0.5, 71.5, 0.5), lambda state: state['position']['z'] > 180, 'live destination arrival')
    screenshot(actor, capture_id + '-destination')
    time.sleep(4)
    return (source_report(rcon) + '\n' + initial_entity.group(0) + '\n' + moved_entity.group(0)
            + '\nlurePositions=' + json.dumps(lure_positions) + '\nsheepDisplacement=' + str(displacement))


def nested_views(actor: Bridge, observer: Bridge, rcon: Rcon, capture_id: str) -> str:
    from demo import screenshot
    from feature_shots import view
    view(actor, observer, capture_id, 'nested', 7)
    first: dict = cross(actor, (0.5, 71.5, 0.5), lambda state: 180 < state['position']['z'] < 210, 'outer portal arrival')
    screenshot(actor, capture_id + '-courtyard')
    look(actor, (0.5, 71.5, 188.5), ticks=28)
    time.sleep(3)
    second: dict = cross(actor, (0.5, 71.5, 188.5), lambda state: state['position']['z'] > 380, 'inner portal arrival')
    screenshot(actor, capture_id + '-inner-arrival')
    time.sleep(3)
    return rcon.command('whdemo feature report') + '\nfirst=' + json.dumps(first['position']) + '\nsecond=' + json.dumps(second['position'])


def gateways(actor: Bridge, observer: Bridge, rcon: Rcon, capture_id: str) -> str:
    from demo import screenshot
    from feature_shots import open_path, view
    source: str = source_report(rcon)
    source_world: re.Match[str] | None = re.search(r'world=([^,\r\n]+)', source)
    destination_id: re.Match[str] | None = re.search(r'destination=([^,\r\n]+)', source)
    report: str = rcon.command('whdemo feature report')
    destination: re.Match[str] | None = None if destination_id is None else re.search(
        r'portal=' + re.escape(destination_id.group(1)) + r',[^\r\n]+', report)
    destination_world: re.Match[str] | None = None if destination is None else re.search(
        r'world=([^,\r\n]+)', destination.group(0))
    if source_world is None or destination_world is None or source_world.group(1) == destination_world.group(1):
        raise RuntimeError('Gateway footage requires a verified second world fixture. A same-world local gateway '
                           'does not demonstrate cross-world travel; cross-server recording additionally needs a second server and transport setup.')
    before: str = rcon.command('data get entity ' + ACTOR + ' Dimension')
    open_path(actor, 'Pair & Destination')
    screenshot(actor, capture_id + '-destination-ui')
    view(actor, observer, capture_id, 'cross-world-view', 6)
    cross(actor, (0.5, 71.5, 0.5), lambda state: state['position']['z'] > 180, 'gateway arrival')
    after: str = rcon.command('data get entity ' + ACTOR + ' Dimension')
    if before == after:
        raise AssertionError('Gateway traversal did not change worlds: ' + after)
    screenshot(actor, capture_id + '-cross-world-arrival')
    time.sleep(4)
    return 'Cross-world gateway only; cross-server transport was not exercised.\n' + report + '\n' + before + '\n' + after


def network_snapshot(rcon: Rcon, player: str = ACTOR) -> dict:
    response: str = rcon.command('whdemo feature network-report ' + player)
    if 'networkReport=' not in response:
        raise AssertionError('Network fixture report is unavailable')
    return json.JSONDecoder().raw_decode(response.split('networkReport=', 1)[1])[0]


def remote_destination(actor: Bridge, peer: str, capture_id: str) -> None:
    from demo import menu, screenshot
    from feature_shots import open_path
    open_path(actor, 'Pair & Destination')
    container: dict = menu(actor)
    matches: list[dict] = [slot for slot in container['slots'] if slot.get('name') == 'Sun Court'
                          and slot.get('item') == 'minecraft:end_crystal'
                          and any(re.fullmatch(r'on server\s+' + re.escape(peer), line.strip(), re.I)
                                  for line in slot.get('lore', []))]
    if len(matches) != 1:
        raise AssertionError('Remote Sun Court entry is absent or ambiguous for ' + peer)
    slot: dict = matches[0]
    actor.command('window', action='hover', slot=slot['index'], containerId=container['id'])
    actor.wait(lambda state: not state.get('windowPending', False), 'remote destination tooltip')
    time.sleep(1.5)
    screenshot(actor, capture_id + '-remote-destination-ui')
    actor.command('window', action='click', slot=slot['index'], containerId=container['id'], button=0)
    result: dict = actor.wait(lambda state: not state.get('windowPending', False), 'remote destination click')
    if result.get('windowError'):
        raise AssertionError(result['windowError'])
    time.sleep(1)


def network_cross(actor: Bridge, portal: tuple[float, float, float], destination: Rcon,
                  identity: str, player_id: str, arrival_z: float) -> dict:
    from feature_shots import close
    close(actor)
    actor.command('slot', index=2)
    look(actor, portal, ticks=28)
    actor.command('keys', forward=True, leaseTicks=180)
    deadline: float = time.monotonic() + 35
    try:
        while time.monotonic() < deadline:
            report: dict = network_snapshot(destination)
            player: dict = report['player']
            client: dict = actor.state()
            if player['online'] and client.get('connected'):
                if report['server'] != identity or player.get('uuid') != player_id or not player.get('transferred'):
                    raise AssertionError('Gateway transfer changed identity or did not use native transfer: ' + json.dumps(report))
                rendered: dict = client.get('position', {})
                if (abs(float(player['y']) - 70.0) < 4 and abs(float(player['x']) - 0.5) < 4
                        and abs(float(player['z']) - arrival_z) < 10
                        and rendered and abs(float(rendered['z']) - float(player['z'])) < 2):
                    return report
            if not client.get('connected') and (client.get('screen') or '').endswith('DisconnectedScreen'):
                raise AssertionError('Client was disconnected during gateway transfer: ' + str(client.get('screenTitle')))
            time.sleep(0.25)
    finally:
        actor.command('release')
    raise TimeoutError('Actor did not arrive on ' + identity + ' through the gateway')


def handoff_receipt(rcon: Rcon, baseline: dict) -> dict:
    deadline: float = time.monotonic() + 20
    report: dict = {}
    while time.monotonic() < deadline:
        report = network_snapshot(rcon)
        counters: dict = report['handoffs']
        if not counters['available'] or counters['failed'] != baseline['failed']:
            raise AssertionError('Gateway handoff failed: ' + json.dumps(report))
        if counters['completed'] == baseline['completed'] + 1 and counters['inFlight'] == 0:
            return report
        time.sleep(0.25)
    raise TimeoutError('Source did not receive its completed gateway arrival receipt: ' + json.dumps(report))


def projection_sessions(rcon: Rcon, variant: str, players: tuple[str, ...]) -> str:
    from studio import verify_sessions
    deadline: float = time.monotonic() + 10
    while True:
        status: str = rcon.command('wh clientview status')
        try:
            verify_sessions(status, variant, players)
            return status
        except AssertionError:
            if time.monotonic() >= deadline:
                raise
        time.sleep(0.25)


def cross_server_gateways(actor: Bridge, observer: Bridge, rcon: Rcon, capture_id: str) -> str:
    from demo import screenshot
    from feature_shots import view
    from gateway_studio import gateway_state, secondary_connection
    state: dict = gateway_state()
    variant: str = 'clientview' if capture_id.endswith('-clientview') else 'standard'
    evidence: dict = {'sourceServer': state['sourceServer'], 'destinationServer': state['destinationServer']}
    with secondary_connection() as destination:
        before_source: dict = network_snapshot(rcon)
        before_destination: dict = network_snapshot(destination)
        if not before_source['player']['online'] or before_destination['player']['online']:
            raise AssertionError('Traveler must begin only on the source server')
        player_id: str = before_source['player']['uuid']
        evidence['beforeSource'] = before_source
        evidence['beforeDestination'] = before_destination
        previous: str = source_report(rcon)
        remote_destination(actor, state['destinationServer'], capture_id)
        if 'remoteServer=' + state['destinationServer'] in previous:
            remote_destination(actor, state['destinationServer'], capture_id + '-relink')
        evidence['link'] = wait_report(rcon, 'portal',
                                      lambda report: 'remoteServer=' + state['destinationServer'] in report
                                      and 'destination=' + state['destinationGateway'] in report
                                      and 'linked=true' in report, 'remote destination stored')
        view(actor, observer, capture_id, 'remote-view', 7)
        arrival: dict = network_cross(actor, (0.5, 71.5, 0.5), destination, state['destinationServer'], player_id, 200.5)
        if not 190 < float(arrival['player']['z']) < 210:
            raise AssertionError('Remote arrival missed the Sun Court gateway: ' + json.dumps(arrival))
        evidence['arrival'] = arrival
        evidence['outboundReceipt'] = handoff_receipt(rcon, before_source['handoffs'])
        if evidence['outboundReceipt']['player']['online']:
            raise AssertionError('Traveler remained online at source after remote arrival')
        evidence['arrivalSessions'] = projection_sessions(destination, variant, (ACTOR,))
        evidence['sourceObserverSessions'] = projection_sessions(rcon, variant, (OBSERVER,))
        screenshot(actor, capture_id + '-remote-arrival')
        screenshot(observer, capture_id + '-observer-departure')
        look(actor, (7.5, 72.0, 190.5), ticks=32)
        time.sleep(3)
        returned: dict = network_cross(actor, (0.5, 71.5, 200.5), rcon, state['sourceServer'], player_id, 0.5)
        if abs(float(returned['player']['z'])) > 10:
            raise AssertionError('Return transfer missed the Garden Arch gateway: ' + json.dumps(returned))
        evidence['return'] = returned
        evidence['returnReceipt'] = handoff_receipt(destination, before_destination['handoffs'])
        if evidence['returnReceipt']['player']['online']:
            raise AssertionError('Traveler remained online at destination after return')
        evidence['returnSessions'] = projection_sessions(rcon, variant, (ACTOR, OBSERVER))
        if not network_snapshot(rcon, OBSERVER)['player']['online']:
            raise AssertionError('Observer did not remain on the source server')
        screenshot(actor, capture_id + '-return')
        screenshot(observer, capture_id + '-observer-return')
        time.sleep(3)
    return json.dumps(evidence, sort_keys=True)


def perform(identifier: str, actor: Bridge, observer: Bridge, rcon: Rcon, capture_id: str) -> str:
    if identifier in ('rtp-routing', 'rtp-personal'):
        return rtp(actor, observer, rcon, capture_id, identifier == 'rtp-personal')
    handlers: dict[str, Callable[[Bridge, Bridge, Rcon, str], str]] = {
        'atmosphere': atmosphere, 'arrival-orientation': arrival_orientation, 'momentum': momentum,
        'membrane-bounce': membrane_bounce, 'network-dialing': network_dialing, 'redstone-control': redstone_control,
        'live-views': live_views, 'nested-views': nested_views, 'gateways': gateways,
        'cross-server-gateways': cross_server_gateways,
    }
    if identifier not in handlers:
        raise ValueError('No advanced sequence for ' + identifier)
    return handlers[identifier](actor, observer, rcon, capture_id)
