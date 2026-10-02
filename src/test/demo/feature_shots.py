import concurrent.futures
import math
import re
import time
from collections.abc import Callable

from studio import ACTOR, OBSERVER, Bridge, Rcon, look

SCENES: dict[str, str] = {
    'mirrors': 'linked', 'portal-orientation': 'linked', 'ambient-particles': 'linked',
    'render-panoptic': 'hills', 'render-venticular': 'hills', 'projection-toggle': 'linked',
    'atmosphere': 'atmosphere', 'arrival-orientation': 'arrival', 'momentum': 'transit',
    'membrane-bounce': 'transit', 'rtp-routing': 'rtp', 'rtp-personal': 'rtp',
    'network-dialing': 'nexus', 'redstone-control': 'nexus', 'live-views': 'live',
    'nested-views': 'nested', 'gateways': 'gateway',
    'cross-server-gateways': 'remote-gateway',
}


def close(bridge: Bridge) -> None:
    deadline: float = time.monotonic() + 4
    while time.monotonic() < deadline:
        if bridge.state().get('container') is not None:
            bridge.command('window', action='close')
        time.sleep(0.5)
        if bridge.state().get('container') is None:
            return
    raise AssertionError('Portal menus did not close before showing the result')


def open_path(actor: Bridge, *labels: str) -> None:
    from demo import click_menu, open_menu
    close(actor)
    open_menu(actor)
    for label in labels:
        click_menu(actor, label)


def view(actor: Bridge, observer: Bridge, capture_id: str, label: str, seconds: float = 3) -> None:
    from demo import screenshot
    close(actor)
    actor.command('slot', index=2)
    look(actor, (0.5, 71.5, 0.5), ticks=16)
    time.sleep(seconds)
    screenshot(actor, capture_id + '-' + label)
    screenshot(observer, capture_id + '-observer-' + label)


def move(bridge: Bridge, ticks: int, **keys: bool) -> None:
    bridge.command('keys', leaseTicks=ticks, **keys)
    time.sleep(ticks / 20 + 0.1)
    bridge.command('release')


def travel_to(bridge: Bridge, target: tuple[float, float, float], timeout: float = 8, coast_ticks: float = 0) -> None:
    position: dict = bridge.state()['position']
    dx: float = target[0] - position['x']
    dz: float = target[2] - position['z']
    distance: float = math.hypot(dx, dz)
    if distance < 0.8:
        return
    look(bridge, (target[0], target[1] + 1.62, target[2]), ticks=20)
    bridge.command('keys', forward=True, leaseTicks=min(200, int(timeout * 20)))
    try:
        bridge.wait(lambda state: ((state['position']['x'] - position['x']) * dx
                    + (state['position']['z'] - position['z']) * dz) / distance >= distance
                    - max(0.5, coast_ticks * math.hypot(state['velocity']['x'], state['velocity']['z'])),
                    'camera reached ' + str(target) + ' from ' + str(position), timeout=timeout)
    finally:
        bridge.command('release')
    time.sleep(0.5)


def fly_to(bridge: Bridge, target: tuple[float, float, float]) -> None:
    position: dict = bridge.state()['position']
    direction: int = 1 if target[1] > position['y'] else -1
    if abs(target[1] - position['y']) > 0.8:
        bridge.command('keys', jump=direction > 0, sneak=direction < 0, leaseTicks=160)
        try:
            bridge.wait(lambda state: direction * (state['position']['y'] - target[1]) >= -0.3,
                        'camera altitude', timeout=8)
        finally:
            bridge.command('release')
        time.sleep(0.5)
    travel_to(bridge, target, coast_ticks=9)
    position = bridge.state()['position']
    if math.dist(tuple(position[axis] for axis in ('x', 'y', 'z')), target) > 2.5:
        raise AssertionError('Camera did not settle near ' + str(target) + ': ' + str(position))


def freeze(rcon: Rcon, seconds: int) -> str:
    response: str = rcon.command('whdemo feature freeze ' + str(seconds))
    match: re.Match[str] | None = re.search(r'\bfrozenUntil=(\d+)\b', response)
    if match is None:
        raise AssertionError('Projection freeze deadline was not reported: ' + response)
    deadline: int = int(match.group(1))
    if (seconds == 0 and deadline != 0) or (seconds > 0 and deadline <= time.time() * 1000):
        raise AssertionError('Unexpected projection freeze deadline: ' + response)
    return response


def prepare(identifier: str, actor: Bridge, observer: Bridge, rcon: Rcon) -> None:
    close(actor)
    close(observer)
    rcon.command('whdemo pose actor')
    rcon.command('whdemo pose observer')
    freeze(rcon, 0)
    if identifier != 'cross-server-gateways':
        rcon.command('whdemo feature prepare ' + SCENES[identifier])
    rcon.command('whdemo equip-wand ' + ACTOR)
    rcon.command('execute as ' + ACTOR + ' at @s run tp @s 0.5 70 3.8 180 0')
    if identifier == 'mirrors':
        rcon.command('execute as ' + OBSERVER + ' at @s run tp @s 2.8 71.5 7.5 180 0')
    if identifier.startswith('render-'):
        rcon.command('execute as ' + OBSERVER + ' at @s run tp @s 0.5 70 3.0 180 0')
    actor.wait(lambda state: abs(state['position']['z'] - 3.8) < 0.5, 'actor source position')
    look(actor, (0.5, 71.5, 0.5), ticks=1)
    look(observer, (0.5, 71.5, 0.5), ticks=1)
    time.sleep(3)
    if identifier not in ('mirrors', 'portal-orientation', 'ambient-particles', 'render-panoptic', 'render-venticular', 'projection-toggle'):
        import advanced_shots
        advanced_shots.prepare(identifier, actor, observer, rcon)


def mirrors(actor: Bridge, observer: Bridge, rcon: Rcon, capture_id: str) -> str:
    from demo import click_menu, screenshot
    open_path(actor, 'Mode')
    screenshot(actor, capture_id + '-mode')
    click_menu(actor, 'Mirror')
    view(actor, observer, capture_id, 'reflection')
    move(actor, 5, left=True)
    look(actor, (0.5, 71.5, 0.5), ticks=16)
    actor.command('click', key='attack')
    time.sleep(2)
    move(actor, 5, right=True)
    open_path(actor, 'Mode')
    click_menu(actor, 'Mirror', button=1)
    screenshot(actor, capture_id + '-rotation-ui')
    view(actor, observer, capture_id, 'rotated', 4)
    report: str = rcon.command('whdemo feature report')
    if 'mirror=true' not in report:
        raise AssertionError('Mirror mode did not activate: ' + report)
    return report


def orientation(actor: Bridge, observer: Bridge, rcon: Rcon, capture_id: str) -> str:
    from demo import click_menu, screenshot
    reports: list[str] = []
    for control, label in (('Flip Face', 'flipped'), ('Flip Face', 'restored'),
                           ('Rotate Clockwise', 'clockwise'), ('Rotate Counterclockwise', 'counterclockwise')):
        open_path(actor, 'Orientation')
        screenshot(actor, capture_id + '-ui-' + label)
        click_menu(actor, control)
        view(actor, observer, capture_id, label)
        reports.append(rcon.command('whdemo feature report'))
    if reports[0] == reports[1] or reports[2] == reports[3]:
        raise AssertionError('Orientation controls did not change portal frame')
    return '\n'.join(reports)


def particles(actor: Bridge, observer: Bridge, rcon: Rcon, capture_id: str) -> str:
    from demo import click_menu, screenshot
    reports: list[str] = []
    view(actor, observer, capture_id, 'sparks')
    for style in ('outline', 'corners', 'off', 'sparks'):
        open_path(actor, 'Settings')
        click_menu(actor, 'Ambient Particles')
        screenshot(actor, capture_id + '-ui-' + style)
        view(actor, observer, capture_id, style)
        reports.append(rcon.command('whdemo feature report'))
    for color in ('Cyan', 'Orange'):
        if color == 'Cyan':
            open_path(actor, 'Settings')
            click_menu(actor, 'Ambient Particles')
            view(actor, observer, capture_id, 'colored-outline')
        open_path(actor, 'Settings')
        click_menu(actor, 'Ambient Particles', button=1)
        click_menu(actor, color)
        screenshot(actor, capture_id + '-color-ui-' + color)
        view(actor, observer, capture_id, color)
        reports.append(rcon.command('whdemo feature report'))
    open_path(actor, 'Settings')
    click_menu(actor, 'Ambient Particles', button=1)
    click_menu(actor, 'Blue .*', shift=True)
    screenshot(actor, capture_id + '-rgb-ui')
    view(actor, observer, capture_id, 'rgb')
    if len(set(reports[:4])) != 4:
        raise AssertionError('Particle styles did not produce distinct state')
    return '\n'.join(reports)


def render_volume(identifier: str, actor: Bridge, observer: Bridge, rcon: Rcon, capture_id: str) -> str:
    from demo import click_menu, menu, screenshot
    desired: str = 'PanOptic' if identifier.endswith('panoptic') else 'Venticular'
    open_path(actor, 'Settings')
    if not any(slot.get('name') == 'Render Mode ' + desired for slot in menu(actor)['slots']):
        click_menu(actor, 'Render Mode .*')
    container: dict = menu(actor)
    target: dict = next(slot for slot in container['slots'] if slot.get('name') == 'Render Mode ' + desired)
    actor.command('window', action='hover', slot=target['index'], containerId=container['id'])
    actor.wait(lambda state: not state.get('windowPending', False), 'render mode tooltip')
    time.sleep(1.5)
    screenshot(actor, capture_id + '-mode-ui')
    view(actor, observer, capture_id, 'front', 10)
    report: str = rcon.command('whdemo feature report')
    frozen: str = freeze(rcon, 120)
    screenshot(actor, capture_id + '-frozen-front')
    rcon.command('gamemode spectator ' + ACTOR)
    actor.command('hud', visible=False)
    modded: bool = 'CLIENT_VIEW' in rcon.command('wh clientview status')
    paths: tuple = (((5.5, 73, 4.5), (5.5, 73, -4.5)), ((-4.5, 73, 4.5), (-4.5, 73, -4.5))) if modded else (
        ((14.5, 80, 4.5), (17.5, 82, -14.5), (7.5, 82, -32.5)),
        ((-14.5, 80, 4.5), (-17.5, 82, -14.5), (-7.5, 82, -32.5)))
    focus: tuple[float, float, float] = (0.5, 71.5, 0.5) if modded else (0.5, 75, -13.5)
    try:
        with concurrent.futures.ThreadPoolExecutor(max_workers=2) as executor:
            for destinations in zip(*paths):
                futures = [executor.submit(fly_to, bridge, target) for bridge, target in zip((actor, observer), destinations)]
                for future in futures:
                    future.result()
                futures = [executor.submit(look, bridge, focus, 24) for bridge in (actor, observer)]
                for future in futures:
                    future.result()
                time.sleep(2)
        screenshot(actor, capture_id + '-frozen-rear')
        screenshot(observer, capture_id + '-observer-frozen-rear')
        time.sleep(5)
    finally:
        actor.command('release')
        observer.command('release')
        freeze(rcon, 0)
    return report + '\n' + frozen


def projection_toggle(actor: Bridge, observer: Bridge, rcon: Rcon, capture_id: str) -> str:
    from demo import click_menu
    for state in ('Off', 'On'):
        open_path(actor)
        click_menu(actor, 'Projection .*')
        view(actor, observer, capture_id, state)
    return rcon.command('whdemo feature report')


def perform(identifier: str, actor: Bridge, observer: Bridge, rcon: Rcon, capture_id: str) -> str:
    if identifier in ('render-panoptic', 'render-venticular'):
        return render_volume(identifier, actor, observer, rcon, capture_id)
    handlers: dict[str, Callable[[Bridge, Bridge, Rcon, str], str]] = {'mirrors': mirrors, 'portal-orientation': orientation, 'ambient-particles': particles,
                'projection-toggle': projection_toggle}
    if identifier not in handlers:
        import advanced_shots
        return advanced_shots.perform(identifier, actor, observer, rcon, capture_id)
    return handlers[identifier](actor, observer, rcon, capture_id)
