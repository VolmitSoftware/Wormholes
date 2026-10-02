import json
import math
import re
import time
from typing import Callable

from studio import ACTOR, OBSERVER, Bridge, Rcon, look

IDENTIFIERS: tuple[str, ...] = ('door-crafting', 'pair-doors', 'personal-pockets', 'public-pockets', 'door-open-state', 'trapdoor-travel')
SOURCE: tuple[int, int, int] = (22, 70, 0)
DESTINATION: tuple[int, int, int] = (30, 70, 0)
MOVED: tuple[int, int, int] = (30, 70, 6)


def read(rcon: Rcon, command: str) -> dict:
    return json.loads(rcon.command('whdemo doors ' + command))


def endpoint(rcon: Rcon, position: tuple[int, int, int]) -> dict:
    return read(rcon, 'endpoint ' + ' '.join(map(str, position)))


def player(rcon: Rcon, name: str) -> dict:
    return read(rcon, 'player ' + name)


def await_report(probe: Callable[[], dict], predicate: Callable[[dict], bool], label: str, timeout: float = 15) -> dict:
    deadline: float = time.monotonic() + timeout
    result: dict = {}
    while time.monotonic() < deadline:
        result = probe()
        if predicate(result):
            return result
        time.sleep(0.15)
    raise AssertionError(label + ': ' + json.dumps(result))


def window_action(bridge: Bridge, action: str, slot: int, button: int = 0) -> dict:
    container: dict = bridge.state()['container']
    bridge.command('window', action=action, slot=slot, button=button, containerId=container['id'])
    time.sleep(0.2)
    state: dict = bridge.wait(lambda state: not state.get('windowPending', False), 'inventory cursor')
    if state.get('windowError'):
        raise AssertionError(state['windowError'])
    return state


def show_inventory(bridge: Bridge, item: str, capture_id: str) -> None:
    from demo import screenshot
    bridge.command('click', key='inventory')
    state: dict = bridge.wait(lambda state: state.get('container') is not None, 'inventory opened')
    matches: list[dict] = [slot for slot in state['container']['slots'] if slot.get('item') == item and slot.get('count', 0) > 0]
    if not matches:
        raise AssertionError('Missing inventory item: ' + item)
    window_action(bridge, 'hover', matches[0]['index'])
    time.sleep(1.5)
    screenshot(bridge, capture_id)
    bridge.command('window', action='close')
    time.sleep(0.4)


def select(bridge: Bridge, item: str, name: str = '.*') -> None:
    state: dict = bridge.wait(lambda state: any(entry.get('item') == item and re.fullmatch(name, entry.get('name', ''))
                                              for entry in state['inventory']), 'held item ' + item)
    entry: dict = next(entry for entry in state['inventory'] if entry.get('item') == item and re.fullmatch(name, entry.get('name', '')))
    index: int = entry['index']
    if index > 8:
        bridge.command('click', key='inventory')
        bridge.wait(lambda state: state.get('container') is not None, 'inventory opened')
        window_action(bridge, 'click', index)
        moved: dict = window_action(bridge, 'click', 36)
        if moved['container']['carried'].get('count', 0) > 0:
            window_action(bridge, 'click', index)
        bridge.command('window', action='close')
        index = 0
    bridge.command('slot', index=index)


def walk(bridge: Bridge, x: float, z: float, timeout: float = 10) -> None:
    for attempt in range(3):
        position: dict = bridge.state()['position']
        dx: float = x - position['x']
        dz: float = z - position['z']
        distance: float = math.hypot(dx, dz)
        if distance <= 0.65:
            return
        direction_x: float = dx / distance
        direction_z: float = dz / distance
        look(bridge, (x, position['y'] + 1.62, z), ticks=18)
        duration: float = min(timeout, distance / 3.0 + 2.0)
        bridge.command('keys', forward=True, leaseTicks=int(duration * 20))
        try:
            bridge.wait(lambda state: (state['position']['x'] - position['x']) * direction_x
                        + (state['position']['z'] - position['z']) * direction_z >= distance - 0.3,
                        'walk to ' + str((x, z)), timeout=duration)
        finally:
            bridge.command('release')
        time.sleep(0.3)
    position = bridge.state()['position']
    if math.hypot(position['x'] - x, position['z'] - z) > 0.65:
        raise AssertionError('Walking did not settle near ' + str((x, z)) + ': ' + json.dumps(position))


def place(bridge: Bridge, rcon: Rcon, position: tuple[int, int, int], item: str) -> dict:
    x, y, z = position
    walk(bridge, x + 0.5, z + 3.1)
    select(bridge, item)
    look(bridge, (x + 0.5, y - 0.001, z + 0.5), ticks=18)
    bridge.command('click', key='use')
    result: dict = await_report(lambda: endpoint(rcon, position), lambda report: report['exists'], 'door placed')
    bridge.command('slot', index=8)
    look(bridge, (x + 0.5, y + 1, z + 0.5), ticks=14)
    time.sleep(1)
    return result


def unpack(actor: Bridge, capture_id: str = '') -> None:
    if capture_id:
        show_inventory(actor, 'minecraft:bundle', capture_id + '-kit')
    actor.command('slot', index=0)
    actor.command('look', yaw=180, pitch=-35, ticks=16)
    time.sleep(0.9)
    actor.wait(lambda state: not state.get('turning', False), 'kit use turn')
    actor.command('click', key='use')
    actor.wait(lambda state: not any(entry.get('item') == 'minecraft:bundle' for entry in state['inventory']), 'kit consumed')
    time.sleep(0.6)
    if capture_id:
        show_inventory(actor, 'minecraft:oak_door', capture_id + '-linked-items')


def prepare(identifier: str, actor: Bridge, observer: Bridge, rcon: Rcon) -> None:
    if identifier not in IDENTIFIERS:
        raise ValueError(identifier)
    actor.command('release')
    observer.command('release')
    rcon.command('whdemo doors prepare ' + identifier + ' ' + ACTOR + ' ' + OBSERVER)
    time.sleep(1)
    if identifier == 'door-open-state':
        unpack(actor)
        place(actor, rcon, SOURCE, 'minecraft:oak_door')
        place(actor, rcon, DESTINATION, 'minecraft:oak_door')
        rcon.command('tp ' + ACTOR + ' 22.5 70 3.5 180 0')
    elif identifier == 'trapdoor-travel':
        unpack(actor)
        for x in (22, 30):
            rcon.command('tp ' + ACTOR + ' ' + str(x + 0.5) + ' 74 2.5 180 25')
            time.sleep(0.5)
            select(actor, 'minecraft:oak_trapdoor')
            look(actor, (x + 0.5, 73.8, -0.001), ticks=12)
            actor.command('click', key='use')
            await_report(lambda: endpoint(rcon, (x, 73, 0)), lambda report: report['exists'], 'trapdoor placed')
        actor.command('slot', index=8)
        rcon.command('tp ' + ACTOR + ' 22.5 74 2.5 180 25')
    look(observer, (26, 71.5 if identifier != 'trapdoor-travel' else 73.5, 1), ticks=1)
    time.sleep(0.5)


def interact(bridge: Bridge, rcon: Rcon, position: tuple[int, int, int], opened: bool) -> dict:
    x, y, z = position
    bridge.command('slot', index=8)
    look(bridge, (x + 0.5, y + (0.1 if y == 73 else 0.8), z + 0.5), ticks=18)
    current: dict = endpoint(rcon, position)
    if current['open'] != opened:
        bridge.command('click', key='use')
    return await_report(lambda: endpoint(rcon, position), lambda report: report['open'] == opened, 'physical door state')


def enter_pair(bridge: Bridge, rcon: Rcon, source: tuple[int, int, int], destination: tuple[int, int, int], open_door: bool = True) -> None:
    x, y, z = source
    walk(bridge, x + 0.5, z + 2.4)
    if open_door:
        interact(bridge, rcon, source, True)
    look(bridge, (x + 0.5, y + 1.62, z - 1), ticks=16)
    time.sleep(1)
    bridge.command('keys', forward=True, leaseTicks=100)
    try:
        bridge.wait(lambda state: abs(state['position']['x'] - destination[0] - 0.5) < 1.5
                    and abs(state['position']['z'] - destination[2]) < 3, 'paired door traversal', timeout=5)
    finally:
        bridge.command('release')
    time.sleep(1.2)


def relocate(actor: Bridge, rcon: Rcon, source: tuple[int, int, int], destination: tuple[int, int, int], item: str, capture_id: str) -> dict:
    identity: str = endpoint(rcon, source)['itemId']
    x, y, z = source
    walk(actor, x + 0.5, z + 2.5)
    interact(actor, rcon, source, False)
    select(actor, 'minecraft:diamond_axe')
    look(actor, (x + 0.5, y + 0.5, z + 0.8), ticks=14)
    actor.command('keys', attack=True, leaseTicks=80)
    try:
        await_report(lambda: endpoint(rcon, source), lambda report: not report['exists'], 'bound door broken', timeout=4)
    finally:
        actor.command('release')
    walk(actor, x + 0.5, z + 0.5)
    actor.wait(lambda state: any(entry.get('item') == item for entry in state['inventory']), 'bound item collected')
    show_inventory(actor, item, capture_id + '-recovered-item')
    walk(actor, x + 0.5, z + 3.5)
    moved: dict = place(actor, rcon, destination, item)
    if moved['itemId'] != identity:
        raise AssertionError('Relocation changed door identity')
    return moved


def enter_pocket(bridge: Bridge, rcon: Rcon, name: str, source: tuple[int, int, int]) -> dict:
    x, y, z = source
    walk(bridge, x + 0.5, z + 2.4)
    interact(bridge, rcon, source, True)
    look(bridge, (x + 0.5, y + 1.62, z - 1), ticks=16)
    time.sleep(1)
    bridge.command('keys', forward=True, leaseTicks=200)
    try:
        result: dict = await_report(lambda: player(rcon, name), lambda report: report.get('pocket') is not None, 'pocket entry', timeout=10)
    finally:
        bridge.command('release')
    time.sleep(1.5)
    return result


def show_room(bridge: Bridge, room: dict, capture_id: str) -> None:
    from demo import screenshot
    look(bridge, (room['returnX'] + 0.5, room['returnY'] + 2, room['returnZ'] - 8), ticks=36)
    time.sleep(1.5)
    look(bridge, (room['markerX'] + 0.5, room['markerY'] + 0.5, room['markerZ'] + 0.5), ticks=22)
    time.sleep(1.5)
    screenshot(bridge, capture_id)


def place_marker(bridge: Bridge, rcon: Rcon, name: str, room: dict, material: str) -> dict:
    bridge.command('slot', index=7)
    look(bridge, (room['markerX'] + 0.5, room['markerY'] - 0.001, room['markerZ'] + 0.5), ticks=20)
    bridge.command('click', key='use')
    result: dict = await_report(lambda: player(rcon, name), lambda report: report.get('marker') == material, 'room marker placed')
    bridge.command('slot', index=5)
    look(bridge, (room['markerX'] + 0.5, room['markerY'] + 0.999, room['markerZ'] + 0.5), ticks=18)
    bridge.command('click', key='use')
    result = await_report(lambda: player(rcon, name), lambda report: report.get('markerLight') == 'LANTERN', 'room marker lantern placed')
    bridge.command('slot', index=8)
    time.sleep(0.8)
    return result


def leave_pocket(bridge: Bridge, rcon: Rcon, name: str, room: dict, destination: tuple[int, int, int]) -> dict:
    x: int = room['returnX']
    y: int = room['returnY']
    z: int = room['returnZ']
    walk(bridge, x + 0.5, z - 1.4)
    bridge.command('slot', index=8)
    look(bridge, (x + 0.5, y + 0.8, z + 0.5), ticks=24)
    if not player(rcon, name)['returnOpen']:
        bridge.command('click', key='use')
    look(bridge, (x + 0.5, y + 1.62, z + 2), ticks=16)
    time.sleep(0.8)
    bridge.command('keys', forward=True, leaseTicks=120)
    try:
        result: dict = await_report(lambda: player(rcon, name), lambda report: report.get('pocket') is None, 'return door traversal', timeout=6)
    finally:
        bridge.command('release')
    if result['world'] == 'wormholes:pockets' or math.hypot(result['x'] - destination[0] - 0.5, result['z'] - destination[2]) > 4:
        raise AssertionError('Return ticket did not restore the entrance: ' + json.dumps(result))
    time.sleep(1)
    walk(bridge, destination[0] + 0.5, destination[2] + 3.5)
    return result


def pair_shot(actor: Bridge, rcon: Rcon, capture_id: str) -> dict:
    unpack(actor, capture_id)
    first: dict = place(actor, rcon, SOURCE, 'minecraft:oak_door')
    second: dict = place(actor, rcon, DESTINATION, 'minecraft:oak_door')
    if first['pairId'] != second['pairId'] or first['itemId'] == second['itemId']:
        raise AssertionError('The kit did not create distinct linked endpoints')
    walk(actor, 22.5, 3.5)
    enter_pair(actor, rcon, SOURCE, DESTINATION)
    enter_pair(actor, rcon, DESTINATION, SOURCE)
    moved: dict = relocate(actor, rcon, DESTINATION, MOVED, 'minecraft:oak_door', capture_id)
    walk(actor, 22.5, 9.5)
    walk(actor, 22.5, 3.5)
    enter_pair(actor, rcon, SOURCE, MOVED)
    enter_pair(actor, rcon, MOVED, SOURCE)
    return {'source': first, 'destination': second, 'relocated': moved, 'outboundAndReturnBeforeAndAfterMove': True}


def personal_shot(actor: Bridge, observer: Bridge, rcon: Rcon, capture_id: str) -> dict:
    show_inventory(actor, 'minecraft:dark_oak_door', capture_id + '-personal-item')
    place(actor, rcon, SOURCE, 'minecraft:dark_oak_door')
    place(actor, rcon, DESTINATION, 'minecraft:dark_oak_door')
    walk(actor, 22.5, 3.5)
    first: dict = enter_pocket(actor, rcon, ACTOR, SOURCE)
    first = place_marker(actor, rcon, ACTOR, first, 'GOLD_BLOCK')
    show_room(actor, first, capture_id + '-actor-room')
    leave_pocket(actor, rcon, ACTOR, first, SOURCE)
    walk(actor, 26.5, 3.5)
    second: dict = enter_pocket(observer, rcon, OBSERVER, SOURCE)
    if second['pocket'] == first['pocket'] or second['marker'] != 'AIR':
        raise AssertionError('Personal travelers did not receive distinct empty rooms')
    second = place_marker(observer, rcon, OBSERVER, second, 'LAPIS_BLOCK')
    show_room(observer, second, capture_id + '-observer-distinct-room')
    repeat: dict = enter_pocket(actor, rcon, ACTOR, DESTINATION)
    if repeat['pocket'] != first['pocket'] or repeat['marker'] != 'GOLD_BLOCK':
        raise AssertionError('A second personal door did not retain the traveler room')
    show_room(actor, repeat, capture_id + '-same-room-another-door')
    leave_pocket(actor, rcon, ACTOR, repeat, DESTINATION)
    leave_pocket(observer, rcon, OBSERVER, second, SOURCE)
    return {'actorFirst': first, 'observer': second, 'actorSecondDoor': repeat, 'returnsMatchEntrances': True}


def public_shot(actor: Bridge, observer: Bridge, rcon: Rcon, capture_id: str) -> dict:
    show_inventory(actor, 'minecraft:pale_oak_door', capture_id + '-public-item')
    identity: dict = place(actor, rcon, SOURCE, 'minecraft:pale_oak_door')
    first: dict = enter_pocket(actor, rcon, ACTOR, SOURCE)
    first = place_marker(actor, rcon, ACTOR, first, 'GOLD_BLOCK')
    show_room(actor, first, capture_id + '-shared-room-marker')
    walk(actor, first['returnX'] - 1.5, first['returnZ'] - 2.5)
    second: dict = enter_pocket(observer, rcon, OBSERVER, SOURCE)
    if second['pocket'] != first['pocket'] or second['marker'] != 'GOLD_BLOCK':
        raise AssertionError('Public travelers did not enter the same marked room')
    show_room(observer, second, capture_id + '-observer-shared-room')
    observer_position: dict = observer.state()['position']
    look(actor, (observer_position['x'], observer_position['y'] + 1.4, observer_position['z']), ticks=24)
    time.sleep(2)
    leave_pocket(observer, rcon, OBSERVER, second, SOURCE)
    walk(observer, 25.5, 8.5)
    leave_pocket(actor, rcon, ACTOR, first, SOURCE)
    moved: dict = relocate(actor, rcon, SOURCE, MOVED, 'minecraft:pale_oak_door', capture_id)
    repeat: dict = enter_pocket(actor, rcon, ACTOR, MOVED)
    if repeat['pocket'] != first['pocket'] or repeat['marker'] != 'GOLD_BLOCK':
        raise AssertionError('Moving the public identity lost its room or contents')
    show_room(actor, repeat, capture_id + '-moved-door-same-room')
    leave_pocket(actor, rcon, ACTOR, repeat, MOVED)
    return {'identity': identity, 'actor': first, 'observer': second, 'relocated': moved, 'revisited': repeat}


def toggle_state(actor: Bridge, rcon: Rcon, position: tuple[int, int, int], capture_id: str) -> dict:
    from demo import screenshot
    x, y, z = position
    actor.command('slot', index=8)
    look(actor, (x + 0.5, y + (0.1 if y == 73 else 0.8), z + 0.5), ticks=20)
    actor.command('keys', sneak=True, leaseTicks=200)
    try:
        actor.command('click', key='use')
        state: dict = actor.wait(lambda state: state.get('container') is not None, 'door access menu')
    finally:
        actor.command('release')
    target: dict = next(slot for slot in state['container']['slots'] if slot.get('item') in ('minecraft:lime_dye', 'minecraft:gray_dye'))
    window_action(actor, 'hover', target['index'])
    time.sleep(1.5)
    screenshot(actor, capture_id + '-open-state-menu')
    window_action(actor, 'click', target['index'])
    result: dict = await_report(lambda: endpoint(rcon, position), lambda report: report['openState'] == 'CLOSED', 'Closed OpenState stored')
    time.sleep(1.5)
    screenshot(actor, capture_id + '-closed-state-menu')
    actor.command('window', action='close')
    return result


def state_shot(actor: Bridge, rcon: Rcon, capture_id: str) -> dict:
    before: dict = endpoint(rcon, SOURCE)
    if before['open'] or before['openState'] != 'OPEN':
        raise AssertionError('OpenState baseline is not a closed dormant door')
    enter_pair(actor, rcon, SOURCE, DESTINATION)
    await_report(lambda: endpoint(rcon, SOURCE), lambda report: not report['open'], 'living transit closed the source')
    enter_pair(actor, rcon, DESTINATION, SOURCE)
    walk(actor, 22.5, 2.5)
    interact(actor, rcon, SOURCE, False)
    closed: dict = toggle_state(actor, rcon, SOURCE, capture_id)
    time.sleep(2)
    enter_pair(actor, rcon, SOURCE, DESTINATION, open_door=False)
    after: dict = endpoint(rcon, SOURCE)
    if after['open'] or after['openState'] != 'CLOSED':
        raise AssertionError('Closed contact pad swung open during transit')
    return {'baseline': before, 'closedMenu': closed, 'contactPadAfterTransit': after, 'openCycleClosedAfterUse': True}


def trapdoor_shot(actor: Bridge, rcon: Rcon, capture_id: str) -> dict:
    from demo import screenshot
    source: tuple[int, int, int] = (22, 73, 0)
    destination: tuple[int, int, int] = (30, 73, 0)
    before: dict = endpoint(rcon, source)
    mate: dict = endpoint(rcon, destination)
    if before['form'] != 'TRAPDOOR' or before['pairId'] != mate['pairId']:
        raise AssertionError('Trapdoor scene is not a linked trapdoor pair')
    walk(actor, 22.5, 1.5)
    interact(actor, rcon, source, False)
    look(actor, (22.5, 75.62, -1), ticks=16)
    actor.command('keys', forward=True, sneak=True, leaseTicks=60)
    try:
        actor.wait(lambda state: state['position']['z'] <= 0.72, 'center on the closed trapdoor', timeout=3)
    finally:
        actor.command('release')
    time.sleep(0.4)
    state: dict = actor.wait(lambda state: not state['sneaking'], 'release sneak before opening hatch')
    if abs(state['position']['x'] - 22.5) > 0.1 or not 0.50 < state['position']['z'] < 0.70:
        raise AssertionError('Actor is not centered above the trapdoor: ' + json.dumps(state['position']))
    look(actor, (22.5, 73.9, 0.5), ticks=20)
    time.sleep(1.5)
    screenshot(actor, capture_id + '-closed-horizontal-hatch')
    actor.command('click', key='use')
    actor.wait(lambda state: state['position']['x'] > 28 and state['position']['y'] < 73,
               'drop through source and emerge below destination', timeout=5)
    actor.wait(lambda state: state['onGround'], 'trapdoor landing')
    look(actor, (30.5, 73.8, 0.5), ticks=28)
    time.sleep(2)
    screenshot(actor, capture_id + '-below-destination')
    after: dict = await_report(lambda: endpoint(rcon, source), lambda report: not report['open'], 'trapdoor source closed after living transit')
    return {'source': before, 'destination': mate, 'sourceAfterDrop': after, 'arrival': player(rcon, ACTOR)}


def craft_ingredient(actor: Bridge, target: int, material: str) -> None:
    container: dict = actor.state()['container']
    matches: list[dict] = [slot for slot in container['slots'] if slot['index'] >= 10
                           and slot.get('item') == material and slot.get('count', 0) > 0
                           and (material != 'minecraft:dark_prismarine' or slot.get('name') == 'Wormhole Rune')]
    if not matches:
        raise AssertionError('Missing crafting ingredient: ' + material)
    source: int = matches[0]['index']
    window_action(actor, 'click', source)
    placed: dict = window_action(actor, 'click', target, button=1)
    if placed['container']['carried'].get('count', 0) > 0:
        window_action(actor, 'click', source)
    actor.wait(lambda state: any(slot['index'] == target and slot.get('item') == material and slot.get('count') == 1
                                for slot in state['container']['slots']), 'ingredient in crafting grid')


def crafting_shot(actor: Bridge, rcon: Rcon, capture_id: str) -> dict:
    from demo import screenshot
    baseline: dict = read(rcon, 'inventory ' + ACTOR)
    if any('kitId' in item or 'itemId' in item for item in baseline['items']):
        raise AssertionError('Crafting scene already contains a finished dimensional product')
    actor.command('slot', index=8)
    look(actor, (22.5, 70.8, 0.5), ticks=20)
    actor.command('click', key='use')
    state: dict = actor.wait(lambda state: state.get('container') is not None, 'crafting table opened')
    if len(state['container']['slots']) != 46:
        raise AssertionError('Expected a crafting table with result, nine grid cells, and player inventory')
    recipes: tuple[tuple[str, str, dict[int, str]], ...] = (
        ('pair', 'minecraft:bundle', {1: 'ender_eye', 2: 'oak_door', 3: 'ender_eye', 4: 'obsidian',
                                    5: 'dark_prismarine', 6: 'obsidian', 8: 'oak_door'}),
        ('personal', 'minecraft:dark_oak_door', {2: 'dark_prismarine', 4: 'recovery_compass',
                                               5: 'oak_door', 6: 'ender_chest'}),
        ('public', 'minecraft:pale_oak_door', {1: 'dark_prismarine', 2: 'oak_door', 3: 'dark_prismarine',
                                             5: 'ender_chest', 8: 'lodestone'}),
    )
    for product_index, (kind, result_item, grid) in enumerate(recipes):
        for target, material in grid.items():
            craft_ingredient(actor, target, 'minecraft:' + material)
        actor.wait(lambda state: any(slot['index'] == 0 and slot.get('item') == result_item
                                    and slot.get('count') == 1 for slot in state['container']['slots']),
                   kind + ' crafting result')
        window_action(actor, 'hover', 19)
        time.sleep(2)
        screenshot(actor, capture_id + '-' + kind + '-recipe-grid')
        window_action(actor, 'hover', 0)
        time.sleep(2)
        screenshot(actor, capture_id + '-' + kind + '-recipe')
        window_action(actor, 'click', 0)
        actor.wait(lambda state: state['container']['carried'].get('item') == result_item
                   and state['container']['carried'].get('count') == 1, kind + ' crafted item on cursor')
        inventory_slot: int = 37 + product_index
        window_action(actor, 'click', inventory_slot)
        actor.wait(lambda state: all(slot.get('count', 0) == 0 for slot in state['container']['slots']
                                    if 1 <= slot['index'] <= 9), kind + ' ingredients consumed')
        window_action(actor, 'hover', inventory_slot)
        time.sleep(2)
        screenshot(actor, capture_id + '-' + kind + '-crafted')
    actor.command('window', action='close')
    result: dict = await_report(lambda: read(rcon, 'inventory ' + ACTOR),
                               lambda report: len(report['items']) == 3, 'only three crafted products remain')
    kits: list[dict] = [item for item in result['items'] if 'kitId' in item]
    personal: list[dict] = [item for item in result['items'] if item.get('kind') == 'PERSONAL']
    public: list[dict] = [item for item in result['items'] if item.get('kind') == 'PUBLIC']
    if len(kits) != 1 or len(personal) != 1 or len(public) != 1 or personal[0]['itemId'] == public[0]['itemId']:
        raise AssertionError('Crafting did not mint one kit and two distinct pocket door identities: ' + json.dumps(result))
    actor.command('slot', index=0)
    time.sleep(1)
    return {'before': baseline, 'crafted': result, 'exactRunesConsumed': 4, 'normalCraftClicks': 3}


def perform(identifier: str, actor: Bridge, observer: Bridge, rcon: Rcon, capture_id: str) -> str:
    if identifier == 'door-crafting':
        result: dict = crafting_shot(actor, rcon, capture_id)
    elif identifier == 'pair-doors':
        result = pair_shot(actor, rcon, capture_id)
    elif identifier == 'personal-pockets':
        result = personal_shot(actor, observer, rcon, capture_id)
    elif identifier == 'public-pockets':
        result = public_shot(actor, observer, rcon, capture_id)
    elif identifier == 'door-open-state':
        result = state_shot(actor, rcon, capture_id)
    elif identifier == 'trapdoor-travel':
        result = trapdoor_shot(actor, rcon, capture_id)
    else:
        raise ValueError(identifier)
    return json.dumps(result, sort_keys=True)
