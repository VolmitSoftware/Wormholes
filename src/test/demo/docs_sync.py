import argparse
import datetime
import re
import sys
from pathlib import Path


ROOT: Path = Path(__file__).resolve().parents[3]
DEMOS: dict[str, tuple[tuple[str, str], ...]] = {
    '03-building-portals.md': (('Wand box construction', 'wand-creation'), ('Rune construction', 'rune-creation')),
    '04-portal-types-menus-settings.md': (('Destination menu', 'portal-linking'),),
}
CLIENTS: tuple[tuple[str, str], ...] = (('standard', 'WITHOUT Wormholes mod'), ('clientview', 'WITH Wormholes mod'))
PERSPECTIVES: tuple[tuple[str, str], ...] = (('pov', 'First person'), ('observer', 'Third person'))
FRONTMATTER: re.Pattern[str] = re.compile(r'\A---\n.*?\n---\n', re.DOTALL)
DATE: re.Pattern[str] = re.compile(r'^date: .*$', re.MULTILINE)


class MissingClips(RuntimeError):
    pass


class InvalidPage(RuntimeError):
    pass


def block(identifier: str) -> str:
    lines: list[str] = ['<div class="wormholes-demo" data-demo="' + identifier + '">']
    for client, label in CLIENTS:
        lines.extend(['<div class="wormholes-demo-variant" data-client="' + client + '">', '<p>' + label + '</p>'])
        for perspective, name in PERSPECTIVES:
            source: str = '/wormholes-assets/demos/' + identifier + '-' + client + '-' + perspective + '.webm'
            lines.append('<video src="' + source + '" aria-label="' + label + ', ' + name.lower()
                         + ' demonstration" autoplay muted loop playsinline controls preload="metadata"></video>')
        lines.append('</div>')
    lines.append('</div>')
    return '\n'.join(lines)


def require_clips(docs: Path) -> None:
    directory: Path = docs / 'wormholes-assets' / 'demos'
    missing: list[str] = []
    for demonstrations in DEMOS.values():
        for _, identifier in demonstrations:
            for client, _ in CLIENTS:
                for perspective, _ in PERSPECTIVES:
                    clip: Path = directory / (identifier + '-' + client + '-' + perspective + '.webm')
                    if not clip.is_file() or clip.stat().st_size == 0:
                        missing.append(str(clip))
    if missing:
        raise MissingClips('Missing or empty demonstration clips:\n' + '\n'.join(missing))


def render_page(page: Path, demonstrations: tuple[tuple[str, str], ...], now: str) -> tuple[str, int]:
    if not page.is_file():
        raise InvalidPage('Missing page: ' + str(page))
    text: str = page.read_text(encoding='utf-8')
    front: re.Match[str] | None = FRONTMATTER.match(text)
    if front is None or DATE.search(front.group(0)) is None:
        raise InvalidPage('Missing frontmatter date in ' + str(page))
    changed: int = 0
    for section, identifier in demonstrations:
        heading: re.Match[str] | None = re.search(r'^## ' + re.escape(section) + r'[ \t]*$', text, re.MULTILINE)
        if heading is None:
            raise InvalidPage('Missing section ' + section + ' in ' + str(page))
        fresh: str = '\n\n' + block(identifier)
        existing: re.Match[str] | None = re.compile(
            r'\n\n<div class="wormholes-demo" data-demo="' + re.escape(identifier)
            + r'">\n.*?\n</div>\n</div>(?=\n|\Z)', re.DOTALL).match(text, heading.end())
        if existing is not None and existing.group(0) == fresh:
            continue
        end: int = heading.end() if existing is None else existing.end()
        text = text[:heading.end()] + fresh + text[end:]
        changed += 1
    if changed:
        text = DATE.sub('date: ' + now, text[:front.end()], count=1) + text[front.end():]
    return text, changed


def sync(docs: Path, now: str) -> int:
    require_clips(docs)
    updates: list[tuple[Path, str, int]] = []
    for name, demonstrations in DEMOS.items():
        page: Path = docs / 'wormholes' / name
        text, changed = render_page(page, demonstrations, now)
        updates.append((page, text, changed))
    for page, text, changed in updates:
        if changed:
            page.write_text(text, encoding='utf-8')
    return sum(changed for _, _, changed in updates)


def main(argv: list[str] | None = None) -> int:
    parser: argparse.ArgumentParser = argparse.ArgumentParser(description='Embed paired Wormholes demonstration clips into their wiki reference sections.')
    parser.add_argument('--docs', type=Path, default=ROOT.parent / 'docs')
    args: argparse.Namespace = parser.parse_args(argv)
    now: str = datetime.datetime.now(datetime.timezone.utc).strftime('%Y-%m-%dT%H:%M:%S.000Z')
    try:
        changed: int = sync(args.docs, now)
    except (MissingClips, InvalidPage) as error:
        print(str(error), file=sys.stderr)
        return 2
    print('Updated ' + str(changed) + ' demonstration block(s).')
    return 0


if __name__ == '__main__':
    sys.exit(main())
