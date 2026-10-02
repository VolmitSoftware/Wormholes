import argparse
import datetime
import json
import os
import re
import sys
import tempfile
from html import escape
from pathlib import Path


ROOT: Path = Path(__file__).resolve().parents[3]
DEMOS: dict[str, tuple[tuple[str, str], ...]] = {
    '03-building-portals.md': (('Wand box construction', 'wand-creation'), ('Rune construction', 'rune-creation')),
    '04-portal-types-menus-settings.md': (
        ('Destination menu', 'portal-linking'),
        ('Type menu', 'mirrors'),
        ('Type menu', 'gateways'),
        ('Orientation menu', 'portal-orientation'),
        ('Cosmetics and blackout', 'ambient-particles'),
        ('Transit menu', 'arrival-orientation'),
        ('Transit menu', 'momentum'),
        ('Transit menu', 'membrane-bounce'),
        ('Nexus networks and dialing', 'network-dialing'),
        ('Nexus networks and dialing', 'redstone-control'),
    ),
    '05-projection-modes-settings.md': (
        ('ProjectionMode (ON / OFF)', 'projection-toggle'),
        ('ProjectionRenderMode', 'render-panoptic'),
        ('ProjectionRenderMode', 'render-venticular'),
        ('Optional destination colors and lighting', 'atmosphere'),
        ('Surface and entity rendering', 'live-views'),
        ('Primary, recursive, and remote views', 'nested-views'),
    ),
    '06-random-teleport-portals.md': (
        ('Editor surfaces', 'rtp-routing'),
        ('Editor surfaces', 'rtp-personal'),
    ),
    '07-dimensional-doors.md': (
        ('Recipes', 'door-crafting'),
        ('Kinds', 'pair-doors'),
        ('OpenState', 'door-open-state'),
        ('Forms', 'trapdoor-travel'),
    ),
    '08-pocket-dimensions.md': (
        ('Allocation', 'personal-pockets'),
        ('Allocation', 'public-pockets'),
    ),
    '10-cross-server-networking.md': (('Transfer mode', 'cross-server-gateways'),),
}
CLIENTS: tuple[tuple[str, str], ...] = (('standard', 'No client mod'), ('clientview', 'Client mod'))
PERSPECTIVES: dict[str, str] = {'pov': 'First person', 'observer': 'Third person'}
SHOT_VIEWS: dict[str, tuple[str, ...]] = {shot['id']: tuple(shot['perspectives']) for shot in
    json.loads((ROOT / 'src/test/demo/shots.json').read_text())['shots']}
OBSERVER_LABELS: dict[str, str] = {'personal-pockets': 'Second player', 'public-pockets': 'Second player',
                                   'rtp-personal': 'Second player', 'live-views': 'Destination view'}
RENDER_CAPTION: str = ('Standard projection is frozen for the rear comparison. '
                       'ClientView remains clipped to the aperture and does not expose the block volume.')
CAPTIONS: dict[str, tuple[str, str]] = {
    'mirrors': ('Mirrors', 'Reflect the player and rotate the reflected image.'),
    'portal-orientation': ('Portal orientation', 'Flip the portal face and rotate its frame.'),
    'ambient-particles': ('Ambient particles and color', 'Choose a particle style, dye color, and custom RGB color.'),
    'render-panoptic': ('PanOptic: full volume', RENDER_CAPTION),
    'render-venticular': ('Venticular: culled surfaces', RENDER_CAPTION),
    'projection-toggle': ('Projection on and off', 'Toggle standard projection; native client views remain active.'),
    'atmosphere': ('Destination atmosphere', 'Compare atmosphere modes with different destination colors, lighting, and weather.'),
    'arrival-orientation': ('Arrival orientation', 'Compare frame, look, snap, and mirror policies at a rotated exit.'),
    'momentum': ('Traversal momentum', 'Compare preserved, scaled, and zero velocity on arrival.'),
    'membrane-bounce': ('Membrane and bounce', 'Pass through the permitted face, then compare rejected entry and bounce.'),
    'rtp-routing': ('Shared random destinations', 'Keep a shared destination static, reroll it manually, and travel.'),
    'rtp-personal': ('Personal random destinations', 'Two players receive separate destination reservations.'),
    'network-dialing': ('Nexus dialing', 'Dial two addresses, link each return portal, and travel both ways.'),
    'redstone-control': ('Redstone dialing', 'Configure the input action and use a lever to change destinations.'),
    'live-views': ('Live destination views', 'A player at the destination lures a sheep with wheat while the other watches through the portal.'),
    'nested-views': ('Nested portal views', 'Look through a second portal at the destination, then cross both portals.'),
    'gateways': ('Travel between worlds', 'View and enter a gateway to another world.'),
    'cross-server-gateways': ('Travel between servers', 'Select a remote gateway, travel to its server, and return.'),
    'door-crafting': ('Crafting dimensional doors', 'Craft a pair kit, a personal pocket door, and a public pocket door.'),
    'pair-doors': ('Paired doors', 'Place, traverse, and relocate a linked pair.'),
    'personal-pockets': ('Personal pockets', 'Two players enter separate rooms and use their return doors.'),
    'public-pockets': ('Public pockets', 'Two players share one persistent room and its return door.'),
    'door-open-state': ('Door OpenState', 'Compare open-door entry with the closed-door contact pad.'),
    'trapdoor-travel': ('Paired trapdoors', 'Fall through a trapdoor and arrive at its linked exit.'),
}
FRONTMATTER: re.Pattern[str] = re.compile(r'\A---\n.*?\n---\n', re.DOTALL)
DATE: re.Pattern[str] = re.compile(r'^date: .*$', re.MULTILINE)


class MissingClips(RuntimeError):
    pass


class InvalidPage(RuntimeError):
    pass


def select_demos(only: tuple[str, ...] | list[str] | None) -> dict[str, tuple[tuple[str, str], ...]]:
    if only is None:
        return DEMOS
    selected: set[str] = set(only)
    known: set[str] = {identifier for demonstrations in DEMOS.values() for _, identifier in demonstrations}
    unknown: set[str] = selected - known
    if unknown:
        raise ValueError('Unknown demonstrations: ' + ', '.join(sorted(unknown)))
    result: dict[str, tuple[tuple[str, str], ...]] = {}
    for page, demonstrations in DEMOS.items():
        filtered: tuple[tuple[str, str], ...] = tuple(demo for demo in demonstrations if demo[1] in selected)
        if filtered:
            result[page] = filtered
    return result


def block(identifier: str, loading_pauses_shortened: bool = False) -> str:
    observer_label: str = OBSERVER_LABELS.get(identifier, 'Third person')
    observer_attribute: str = ' data-observer-label="' + observer_label + '"' if identifier in OBSERVER_LABELS and 'observer' in SHOT_VIEWS[identifier] else ''
    lines: list[str] = ['<div class="wormholes-demo" data-demo="' + identifier + '"' + observer_attribute + '>']
    caption: tuple[str, str] | None = CAPTIONS.get(identifier)
    if caption is not None:
        description: str = caption[1] + (' Loading pauses shortened.' if loading_pauses_shortened else '')
        lines.append('<p><strong>' + escape(caption[0]) + '</strong> ' + escape(description) + '</p>')
    for client, label in CLIENTS:
        lines.extend(['<div class="wormholes-demo-variant" data-client="' + client + '">', '<p>' + label + '</p>'])
        for perspective in SHOT_VIEWS[identifier]:
            name: str = PERSPECTIVES[perspective]
            if perspective == 'observer':
                name = observer_label
            source: str = '/wormholes-assets/demos/' + identifier + '-' + client + '-' + perspective + '.webm'
            lines.append('<video src="' + source + '" aria-label="' + label + ', ' + name.lower()
                         + ' demonstration" autoplay muted loop playsinline controls preload="metadata"></video>')
        lines.append('</div>')
    lines.append('</div>')
    return '\n'.join(lines)


def require_clips(docs: Path, demos: dict[str, tuple[tuple[str, str], ...]]) -> None:
    directory: Path = docs / 'wormholes-assets' / 'demos'
    missing: list[str] = []
    for demonstrations in demos.values():
        for _, identifier in demonstrations:
            for client, _ in CLIENTS:
                for perspective in SHOT_VIEWS[identifier]:
                    clip: Path = directory / (identifier + '-' + client + '-' + perspective + '.webm')
                    if not clip.is_file() or clip.stat().st_size == 0:
                        missing.append(str(clip))
    if missing:
        raise MissingClips('Missing or empty demonstration clips:\n' + '\n'.join(missing))


def shortened_demos(manifest: Path) -> frozenset[str]:
    content: dict = json.loads(manifest.read_text())
    return frozenset(take['id'] for take in content['takes']
                     if any(capture.get('edits') for capture in take.get('capture', [])))


def render_page(page: Path, demonstrations: tuple[tuple[str, str], ...], now: str,
                shortened: frozenset[str] = frozenset()) -> tuple[str, int]:
    if not page.is_file():
        raise InvalidPage('Missing page: ' + str(page))
    text: str = page.read_text(encoding='utf-8')
    front: re.Match[str] | None = FRONTMATTER.match(text)
    if front is None or DATE.search(front.group(0)) is None:
        raise InvalidPage('Missing frontmatter date in ' + str(page))
    changed: int = 0
    for section, identifier in reversed(demonstrations):
        heading: re.Match[str] | None = re.search(r'^## ' + re.escape(section) + r'[ \t]*$', text, re.MULTILINE)
        if heading is None:
            raise InvalidPage('Missing section ' + section + ' in ' + str(page))
        fresh: str = '\n\n' + block(identifier, identifier in shortened)
        next_heading: re.Match[str] | None = re.search(r'^## ', text[heading.end():], re.MULTILINE)
        section_end: int = len(text) if next_heading is None else heading.end() + next_heading.start()
        existing: re.Match[str] | None = re.compile(
            r'\n\n<div class="wormholes-demo" data-demo="' + re.escape(identifier)
            + r'"[^>]*>\n.*?\n</div>\n</div>(?=\n|\Z)', re.DOTALL).search(text, heading.end(), section_end)
        if existing is not None and existing.group(0) == fresh:
            continue
        start: int = heading.end() if existing is None else existing.start()
        end: int = start if existing is None else existing.end()
        text = text[:start] + fresh + text[end:]
        changed += 1
    if changed:
        text = DATE.sub('date: ' + now, text[:front.end()], count=1) + text[front.end():]
    return text, changed


def sync(docs: Path, now: str, only: tuple[str, ...] | list[str] | None = None,
         shortened: frozenset[str] = frozenset()) -> int:
    demos: dict[str, tuple[tuple[str, str], ...]] = select_demos(only)
    require_clips(docs, demos)
    updates: list[tuple[Path, str, int]] = []
    for name, demonstrations in demos.items():
        page: Path = docs / 'wormholes' / name
        text, changed = render_page(page, demonstrations, now, shortened)
        updates.append((page, text, changed))
    for page, text, changed in updates:
        if changed:
            temporary: Path | None = None
            try:
                with tempfile.NamedTemporaryFile(mode='w', encoding='utf-8', dir=page.parent, delete=False) as output:
                    temporary = Path(output.name)
                    output.write(text)
                temporary.chmod(page.stat().st_mode)
                temporary.replace(page)
            finally:
                if temporary is not None:
                    temporary.unlink(missing_ok=True)
    return sum(changed for _, _, changed in updates)


def main(argv: list[str] | None = None) -> int:
    parser: argparse.ArgumentParser = argparse.ArgumentParser(description='Embed paired Wormholes demonstration clips into their wiki reference sections.')
    parser.add_argument('--docs', type=Path, default=ROOT.parent / 'docs')
    parser.add_argument('--manifest', type=Path, help='Accepted recording manifest for loading-pause captions.')
    parser.add_argument('--only', action='append', choices=sorted(
        identifier for demonstrations in DEMOS.values() for _, identifier in demonstrations),
        help='Synchronize only this demonstration; repeat to select more than one.')
    args: argparse.Namespace = parser.parse_args(argv)
    now: str = datetime.datetime.now(datetime.timezone.utc).strftime('%Y-%m-%dT%H:%M:%S.000Z')
    try:
        manifest: Path = args.manifest or Path(os.environ.get('WORMHOLES_DEMO_OUTPUT', str(ROOT / 'build/demo'))) / 'manifest.json'
        shortened: frozenset[str] = shortened_demos(manifest) if args.manifest or manifest.is_file() else frozenset()
        changed: int = sync(args.docs, now, args.only, shortened)
    except (MissingClips, InvalidPage, ValueError, OSError) as error:
        print(str(error), file=sys.stderr)
        return 2
    print('Updated ' + str(changed) + ' demonstration block(s).')
    return 0


if __name__ == '__main__':
    sys.exit(main())
