import importlib.util
from html.parser import HTMLParser
import tempfile
import unittest
from pathlib import Path


SCRIPT: Path = Path(__file__).resolve().parents[1] / 'docs_sync.py'
FRONT: str = '---\ntitle: Portals\ndate: 2026-09-30T00:00:00.000Z\ndateCreated: 2026-01-01T00:00:00.000Z\n---\n'
BUILD: str = FRONT + '\n## Wand box construction\n\nSelect the corners.\n\n## Rune construction\n\nPlace matching runes.\n\n## Surface skin\n\nKeep the opening clear.\n'
LINK: str = FRONT + '\n## Destination menu\n\nChoose a destination.\n\n## Type menu\n\nChoose a type.\n'


class DocsSyncTest(unittest.TestCase):
    def setUp(self) -> None:
        self.assertTrue(SCRIPT.is_file(), 'The demonstration documentation synchronizer is missing')
        specification = importlib.util.spec_from_file_location('wormholes_docs_sync', SCRIPT)
        self.assertIsNotNone(specification)
        self.module = importlib.util.module_from_spec(specification)
        specification.loader.exec_module(self.module)
        self.temporary = tempfile.TemporaryDirectory()
        self.addCleanup(self.temporary.cleanup)
        self.docs: Path = Path(self.temporary.name)
        (self.docs / 'wormholes').mkdir()
        self.build: Path = self.docs / 'wormholes' / '03-building-portals.md'
        self.link: Path = self.docs / 'wormholes' / '04-portal-types-menus-settings.md'
        self.build.write_text(BUILD, encoding='utf-8')
        self.link.write_text(LINK, encoding='utf-8')
        self.assets: Path = self.docs / 'wormholes-assets' / 'demos'
        self.assets.mkdir(parents=True)

    def clips(self) -> None:
        for demo in ('wand-creation', 'rune-creation', 'portal-linking'):
            for client in ('standard', 'clientview'):
                for perspective in ('pov', 'observer'):
                    (self.assets / f'{demo}-{client}-{perspective}.webm').write_bytes(b'clip')

    def test_missing_clips_refuse_all_page_writes(self) -> None:
        self.clips()
        (self.assets / 'portal-linking-clientview-observer.webm').unlink()
        with self.assertRaisesRegex(self.module.MissingClips, 'portal-linking-clientview-observer.webm'):
            self.module.sync(self.docs, '2026-10-01T12:00:00.000Z')
        self.assertEqual(self.build.read_text(), BUILD)
        self.assertEqual(self.link.read_text(), LINK)

    def test_empty_clips_are_missing(self) -> None:
        self.clips()
        (self.assets / 'rune-creation-standard-pov.webm').write_bytes(b'')
        with self.assertRaisesRegex(self.module.MissingClips, 'rune-creation-standard-pov.webm'):
            self.module.sync(self.docs, '2026-10-01T12:00:00.000Z')

    def test_generated_clips_autoplay_like_adapt(self) -> None:
        class Videos(HTMLParser):
            def __init__(self) -> None:
                super().__init__()
                self.attributes: list[dict] = []

            def handle_starttag(self, tag: str, attrs: list[tuple[str, str | None]]) -> None:
                if tag == 'video':
                    self.attributes.append(dict(attrs))

        self.clips()
        self.module.sync(self.docs, '2026-10-01T12:00:00.000Z')
        videos: Videos = Videos()
        videos.feed(self.build.read_text() + self.link.read_text())
        self.assertEqual(len(videos.attributes), 12)
        for attributes in videos.attributes:
            for attribute in ('autoplay', 'muted', 'loop', 'playsinline', 'controls'):
                self.assertIn(attribute, attributes)
            self.assertEqual(attributes['preload'], 'metadata')

    def test_exact_sections_and_all_paired_views_preserve_prose(self) -> None:
        self.clips()
        self.assertEqual(self.module.sync(self.docs, '2026-10-01T12:00:00.000Z'), 3)
        build: str = self.build.read_text()
        link: str = self.link.read_text()
        for section, demo in [('Wand box construction', 'wand-creation'), ('Rune construction', 'rune-creation')]:
            self.assertIn(f'## {section}\n\n<div class="wormholes-demo" data-demo="{demo}">', build)
        self.assertIn('## Destination menu\n\n<div class="wormholes-demo" data-demo="portal-linking">', link)
        for text, demos in [(build, ('wand-creation', 'rune-creation')), (link, ('portal-linking',))]:
            self.assertIn('date: 2026-10-01T12:00:00.000Z', text)
            self.assertIn('dateCreated: 2026-01-01T00:00:00.000Z', text)
            self.assertEqual(text.count('<video '), 4 * len(demos))
            self.assertEqual(text.count('No client mod</p>'), len(demos))
            self.assertEqual(text.count('Client mod</p>'), len(demos))
            for demo in demos:
                for client in ('standard', 'clientview'):
                    for perspective in ('pov', 'observer'):
                        self.assertIn(f'/wormholes-assets/demos/{demo}-{client}-{perspective}.webm', text)
        self.assertIn('Select the corners.', build)
        self.assertIn('Place matching runes.', build)
        self.assertIn('Choose a destination.', link)
        self.assertTrue(build.endswith('## Surface skin\n\nKeep the opening clear.\n'))
        self.assertTrue(link.endswith('## Type menu\n\nChoose a type.\n'))

    def test_second_run_preserves_content_and_date(self) -> None:
        self.clips()
        self.module.sync(self.docs, '2026-10-01T12:00:00.000Z')
        before: tuple[str, str] = (self.build.read_text(), self.link.read_text())
        self.assertEqual(self.module.sync(self.docs, '2026-10-02T12:00:00.000Z'), 0)
        self.assertEqual(before, (self.build.read_text(), self.link.read_text()))

    def test_invalid_later_page_does_not_modify_first_page(self) -> None:
        self.clips()
        self.link.write_text(LINK.replace('## Destination menu', '## Destinations'), encoding='utf-8')
        with self.assertRaisesRegex(self.module.InvalidPage, 'Destination menu'):
            self.module.sync(self.docs, '2026-10-01T12:00:00.000Z')
        self.assertEqual(self.build.read_text(), BUILD)

    def test_existing_demo_updates_in_place_without_duplicates(self) -> None:
        self.clips()
        self.module.sync(self.docs, '2026-10-01T12:00:00.000Z')
        self.build.write_text(self.build.read_text().replace('preload="metadata"', 'preload="none"'), encoding='utf-8')
        self.assertEqual(self.module.sync(self.docs, '2026-10-02T12:00:00.000Z'), 2)
        self.assertEqual(self.build.read_text().count('class="wormholes-demo"'), 2)
        self.assertNotIn('preload="none"', self.build.read_text())


if __name__ == '__main__':
    unittest.main()
