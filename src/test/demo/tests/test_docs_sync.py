import importlib.util
import json
import subprocess
import sys
from html.parser import HTMLParser
import tempfile
import unittest
from pathlib import Path


SCRIPT: Path = Path(__file__).resolve().parents[1] / 'docs_sync.py'
FRONT: str = '---\ntitle: Portals\ndate: 2026-09-30T00:00:00.000Z\ndateCreated: 2026-01-01T00:00:00.000Z\n---\n'
BUILD: str = FRONT + '\n## Wand box construction\n\nSelect the corners.\n\n## Rune construction\n\nPlace matching runes.\n\n## Surface skin\n\nKeep the opening clear.\n'
LINK: str = FRONT + '\n## Destination menu\n\nChoose a destination.\n\n## Type menu\n\nChoose a type.\n'
ORIGINAL_BLOCK: str = '''<div class="wormholes-demo" data-demo="{id}">
<div class="wormholes-demo-variant" data-client="standard">
<p>No client mod</p>
<video src="/wormholes-assets/demos/{id}-standard-pov.webm" aria-label="No client mod, first person demonstration" autoplay muted loop playsinline controls preload="metadata"></video>
<video src="/wormholes-assets/demos/{id}-standard-observer.webm" aria-label="No client mod, third person demonstration" autoplay muted loop playsinline controls preload="metadata"></video>
</div>
<div class="wormholes-demo-variant" data-client="clientview">
<p>Client mod</p>
<video src="/wormholes-assets/demos/{id}-clientview-pov.webm" aria-label="Client mod, first person demonstration" autoplay muted loop playsinline controls preload="metadata"></video>
<video src="/wormholes-assets/demos/{id}-clientview-observer.webm" aria-label="Client mod, third person demonstration" autoplay muted loop playsinline controls preload="metadata"></video>
</div>
</div>'''


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

    def sync(self, now: str) -> int:
        return self.module.sync(self.docs, now, ('wand-creation', 'rune-creation', 'portal-linking'))

    def clips(self, demos: tuple[str, ...] = ('wand-creation', 'rune-creation', 'portal-linking')) -> None:
        for demo in demos:
            for client in ('standard', 'clientview'):
                for perspective in ('pov', 'observer'):
                    (self.assets / f'{demo}-{client}-{perspective}.webm').write_bytes(b'clip')

    def test_selected_cli_demos_do_not_require_unselected_assets_or_pages(self) -> None:
        self.clips(('wand-creation', 'portal-linking'))
        result: subprocess.CompletedProcess[str] = subprocess.run(
            [sys.executable, str(SCRIPT), '--docs', str(self.docs), '--only', 'wand-creation',
             '--only', 'portal-linking', '--only', 'wand-creation'], capture_output=True, text=True)
        self.assertEqual(result.returncode, 0, result.stderr)
        self.assertIn('Updated 2 demonstration block(s).', result.stdout)
        self.assertNotIn('data-demo="rune-creation"', self.build.read_text())
        self.assertIn('Place matching runes.', self.build.read_text())

    def test_two_demos_share_a_section_without_duplicate_blocks(self) -> None:
        demonstrations: tuple[tuple[str, str], ...] = (
            ('Destination menu', 'portal-linking'), ('Destination menu', 'network-dialing'))
        updated, count = self.module.render_page(self.link, demonstrations, '2026-10-01T12:00:00.000Z')
        self.assertEqual(count, 2)
        self.link.write_text(updated, encoding='utf-8')
        rerendered, count = self.module.render_page(self.link, demonstrations, '2026-10-02T12:00:00.000Z')
        self.assertEqual(count, 0)
        self.assertEqual(rerendered, updated)
        self.assertEqual(updated.count('class="wormholes-demo"'), 2)
        self.assertLess(updated.index('data-demo="portal-linking"'), updated.index('data-demo="network-dialing"'))
        self.assertIn('Choose a destination.', updated)

    def test_original_demonstrations_keep_exact_markup(self) -> None:
        for identifier in ('wand-creation', 'rune-creation', 'portal-linking'):
            with self.subTest(identifier=identifier):
                expected: str = ORIGINAL_BLOCK.replace('{id}', identifier)
                if identifier != 'portal-linking':
                    expected = '\n'.join(line for line in expected.splitlines() if '-observer.webm' not in line)
                self.assertEqual(self.module.block(identifier), expected)

    def test_loading_caption_requires_actual_manifest_edits(self) -> None:
        manifest: Path = self.docs / 'manifest.json'
        manifest.write_text(json.dumps({'takes': [
            {'id': 'personal-pockets', 'capture': [{'view': 'pov', 'edits': [{'startFrame': 40, 'endFrame': 60}]}]},
            {'id': 'public-pockets', 'capture': [{'view': 'pov', 'edits': []}]},
        ]}))
        shortened: frozenset[str] = self.module.shortened_demos(manifest)
        self.assertEqual(shortened, frozenset(('personal-pockets',)))
        self.assertIn('Loading pauses shortened.', self.module.block('personal-pockets', True))
        self.assertNotIn('Loading pauses shortened.', self.module.block('public-pockets'))
        self.clips(('gateways',))
        self.assertEqual(self.module.sync(self.docs, '2026-10-01T12:00:00.000Z', ('gateways',),
                                         frozenset(('gateways',))), 1)
        self.assertIn('Loading pauses shortened.', self.link.read_text())
        self.assertEqual(self.module.sync(self.docs, '2026-10-02T12:00:00.000Z', ('gateways',),
                                         frozenset(('gateways',))), 0)

    def test_every_new_demo_has_a_caption_before_its_variants(self) -> None:
        original: set[str] = {'wand-creation', 'rune-creation', 'portal-linking'}
        known: set[str] = {identifier for entries in self.module.DEMOS.values() for _, identifier in entries}
        self.assertEqual(set(self.module.CAPTIONS), known - original)
        for identifier in known - original:
            with self.subTest(identifier=identifier):
                rendered: str = self.module.block(identifier)
                self.assertLess(rendered.index('<p><strong>'), rendered.index('class="wormholes-demo-variant"'))
                self.assertEqual(rendered.count('<strong>'), 1)

    def test_render_captions_distinguish_frozen_standard_geometry_from_clientview(self) -> None:
        for identifier in ('render-panoptic', 'render-venticular'):
            rendered: str = self.module.block(identifier)
            self.assertIn('Standard projection is frozen for the rear comparison.', rendered)
            self.assertIn('ClientView remains clipped to the aperture and does not expose the block volume.', rendered)

    def test_traveler_observer_is_labeled_as_second_player(self) -> None:
        for identifier in ('personal-pockets', 'public-pockets', 'rtp-personal'):
            rendered: str = self.module.block(identifier)
            self.assertIn('data-observer-label="Second player"', rendered)
            self.assertIn('Client mod, second player demonstration', rendered)
            self.assertNotIn('third person', rendered)
        self.assertNotIn('data-observer-label', self.module.block('mirrors'))

    def test_live_observer_is_labeled_as_destination_view(self) -> None:
        rendered: str = self.module.block('live-views')
        self.assertIn('data-observer-label="Destination view"', rendered)
        self.assertIn('No client mod, destination view demonstration', rendered)
        self.assertIn('Client mod, destination view demonstration', rendered)
        self.assertIn('lures a sheep with wheat', rendered)
        self.assertNotIn('third person', rendered)

    def test_selective_update_preserves_neighboring_demo_and_assets(self) -> None:
        self.clips(('render-panoptic', 'render-venticular'))
        page: Path = self.docs / 'wormholes' / '05-projection-modes-settings.md'
        page.write_text(FRONT + '\n## ProjectionRenderMode\n\nCompare the render modes.\n', encoding='utf-8')
        self.module.sync(self.docs, '2026-10-01T12:00:00.000Z', ('render-panoptic', 'render-venticular'))
        page.write_text(page.read_text().replace('preload="metadata"', 'preload="none"'), encoding='utf-8')
        self.assertEqual(self.module.sync(self.docs, '2026-10-02T12:00:00.000Z', ('render-panoptic',)), 1)
        updated: str = page.read_text()
        self.assertEqual(updated.count('class="wormholes-demo"'), 2)
        self.assertEqual(updated.count('preload="none"'), 4)
        self.assertEqual(updated.count('preload="metadata"'), 4)
        self.assertIn('Compare the render modes.', updated)
        self.assertTrue(all(path.read_bytes() == b'clip' for path in self.assets.iterdir()))

    def test_unknown_cli_demo_refuses_page_writes(self) -> None:
        self.clips()
        result: subprocess.CompletedProcess[str] = subprocess.run(
            [sys.executable, str(SCRIPT), '--docs', str(self.docs), '--only', 'not-a-demo'],
            capture_output=True, text=True)
        self.assertEqual(result.returncode, 2)
        self.assertEqual(self.build.read_text(), BUILD)
        self.assertEqual(self.link.read_text(), LINK)

    def test_selected_clip_requires_each_client_and_camera(self) -> None:
        self.clips(('wand-creation',))
        (self.assets / 'wand-creation-clientview-pov.webm').write_bytes(b'')
        result: subprocess.CompletedProcess[str] = subprocess.run(
            [sys.executable, str(SCRIPT), '--docs', str(self.docs), '--only', 'wand-creation'],
            capture_output=True, text=True)
        self.assertEqual(result.returncode, 2)
        self.assertIn('wand-creation-clientview-pov.webm', result.stderr)
        self.assertEqual(self.build.read_text(), BUILD)

    def test_missing_clips_refuse_all_page_writes(self) -> None:
        self.clips()
        (self.assets / 'portal-linking-clientview-observer.webm').unlink()
        with self.assertRaisesRegex(self.module.MissingClips, 'portal-linking-clientview-observer.webm'):
            self.sync('2026-10-01T12:00:00.000Z')
        self.assertEqual(self.build.read_text(), BUILD)
        self.assertEqual(self.link.read_text(), LINK)

    def test_empty_clips_are_missing(self) -> None:
        self.clips()
        (self.assets / 'rune-creation-standard-pov.webm').write_bytes(b'')
        with self.assertRaisesRegex(self.module.MissingClips, 'rune-creation-standard-pov.webm'):
            self.sync('2026-10-01T12:00:00.000Z')

    def test_generated_clips_autoplay_like_adapt(self) -> None:
        class Videos(HTMLParser):
            def __init__(self) -> None:
                super().__init__()
                self.attributes: list[dict] = []

            def handle_starttag(self, tag: str, attrs: list[tuple[str, str | None]]) -> None:
                if tag == 'video':
                    self.attributes.append(dict(attrs))

        self.clips()
        self.sync('2026-10-01T12:00:00.000Z')
        videos: Videos = Videos()
        videos.feed(self.build.read_text() + self.link.read_text())
        self.assertEqual(len(videos.attributes), 8)
        for attributes in videos.attributes:
            for attribute in ('autoplay', 'muted', 'loop', 'playsinline', 'controls'):
                self.assertIn(attribute, attributes)
            self.assertEqual(attributes['preload'], 'metadata')

    def test_exact_sections_and_all_paired_views_preserve_prose(self) -> None:
        self.clips()
        self.assertEqual(self.sync('2026-10-01T12:00:00.000Z'), 3)
        build: str = self.build.read_text()
        link: str = self.link.read_text()
        for section, demo in [('Wand box construction', 'wand-creation'), ('Rune construction', 'rune-creation')]:
            self.assertIn(f'## {section}\n\n<div class="wormholes-demo" data-demo="{demo}">', build)
        self.assertIn('## Destination menu\n\n<div class="wormholes-demo" data-demo="portal-linking">', link)
        for text, demos in [(build, ('wand-creation', 'rune-creation')), (link, ('portal-linking',))]:
            self.assertIn('date: 2026-10-01T12:00:00.000Z', text)
            self.assertIn('dateCreated: 2026-01-01T00:00:00.000Z', text)
            self.assertEqual(text.count('<video '), sum(2 * len(self.module.SHOT_VIEWS[demo]) for demo in demos))
            self.assertEqual(text.count('No client mod</p>'), len(demos))
            self.assertEqual(text.count('Client mod</p>'), len(demos))
            for demo in demos:
                for client in ('standard', 'clientview'):
                    for perspective in self.module.SHOT_VIEWS[demo]:
                        self.assertIn(f'/wormholes-assets/demos/{demo}-{client}-{perspective}.webm', text)
        self.assertIn('Select the corners.', build)
        self.assertIn('Place matching runes.', build)
        self.assertIn('Choose a destination.', link)
        self.assertTrue(build.endswith('## Surface skin\n\nKeep the opening clear.\n'))
        self.assertTrue(link.endswith('## Type menu\n\nChoose a type.\n'))

    def test_single_camera_sync_removes_observer_embeds_and_accepts_missing_observer_files(self) -> None:
        self.clips(('wand-creation',))
        for clip in self.assets.glob('*-observer.webm'):
            clip.unlink()
        self.build.write_text(BUILD.replace('## Wand box construction', '## Wand box construction\n\n' +
            ORIGINAL_BLOCK.replace('{id}', 'wand-creation')))
        self.assertEqual(self.module.sync(self.docs, '2026-10-02T12:00:00.000Z', ('wand-creation',)), 1)
        self.assertNotIn('-observer.webm', self.build.read_text())
        self.assertEqual(self.build.read_text().count('class="wormholes-demo"'), 1)

    def test_second_run_preserves_content_and_date(self) -> None:
        self.clips()
        self.sync('2026-10-01T12:00:00.000Z')
        before: tuple[str, str] = (self.build.read_text(), self.link.read_text())
        self.assertEqual(self.sync('2026-10-02T12:00:00.000Z'), 0)
        self.assertEqual(before, (self.build.read_text(), self.link.read_text()))

    def test_invalid_later_page_does_not_modify_first_page(self) -> None:
        self.clips()
        self.link.write_text(LINK.replace('## Destination menu', '## Destinations'), encoding='utf-8')
        with self.assertRaisesRegex(self.module.InvalidPage, 'Destination menu'):
            self.sync('2026-10-01T12:00:00.000Z')
        self.assertEqual(self.build.read_text(), BUILD)

    def test_existing_demo_updates_in_place_without_duplicates(self) -> None:
        self.clips()
        self.sync('2026-10-01T12:00:00.000Z')
        self.build.write_text(self.build.read_text().replace('preload="metadata"', 'preload="none"'), encoding='utf-8')
        self.assertEqual(self.sync('2026-10-02T12:00:00.000Z'), 2)
        self.assertEqual(self.build.read_text().count('class="wormholes-demo"'), 2)
        self.assertNotIn('preload="none"', self.build.read_text())


if __name__ == '__main__':
    unittest.main()
