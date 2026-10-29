import json
from pathlib import Path
import shutil
import sys
import tempfile
import unittest
from typing import Callable
from unittest.mock import patch


DIRECTORY: Path = Path(__file__).resolve().parents[1]


class MediaPublicationTest(unittest.TestCase):
    def setUp(self) -> None:
        sys.path.insert(0, str(DIRECTORY))
        self.addCleanup(lambda: sys.path.remove(str(DIRECTORY)))
        import docs_sync
        import native_capture
        import studio
        self.docs_sync = docs_sync
        self.native_capture = native_capture
        self.studio = studio
        temporary: tempfile.TemporaryDirectory = tempfile.TemporaryDirectory()
        self.addCleanup(temporary.cleanup)
        self.root: Path = Path(temporary.name)
        self.output: Path = self.root / 'recording'
        self.docs: Path = self.root / 'docs'
        self.assets: Path = self.docs / 'wormholes-assets/demos'
        self.assets.mkdir(parents=True)
        (self.docs / 'wormholes').mkdir()
        (self.docs / 'wormholes/03-building-portals.md').write_text(
            '---\ntitle: Portals\ndate: 2026-01-01\n---\n\n## Wand box construction\n\nBuild a portal.\n')
        for folder in ('exports', 'intermediate', 'screenshots'):
            (self.output / folder).mkdir(parents=True)
        for variant in ('standard', 'clientview'):
            for folder, suffix in (('exports', '-pov.webm'), ('intermediate', '-pov.mp4'), ('screenshots', '-start.png')):
                (self.output / folder / ('wand-creation-' + variant + suffix)).write_bytes(b'valid')
        (self.output / 'manifest.json').write_text('{}')
        (self.output / 'skin-profile.json').write_text('{}')
        (self.output / 'intermediate/unselected-pov.mp4').write_bytes(b'keep')

    def validate(self, path: Path) -> None:
        if path.read_bytes() != b'valid':
            raise AssertionError('Invalid clip')

    def publish(self, reviewed: bool = True) -> int:
        with patch.object(self.studio, 'validate_export', side_effect=self.validate):
            return self.docs_sync.publish(self.docs, self.output, '2026-10-26', ['wand-creation'], frozenset(), reviewed)

    def test_reviewed_publication_keeps_only_final_media_and_preserves_state(self) -> None:
        self.publish()
        self.assertEqual(len(list(self.assets.glob('*.webm'))), 2)
        self.assertEqual(list((self.output / 'exports').iterdir()), [])
        self.assertEqual(list((self.output / 'screenshots').iterdir()), [])
        self.assertEqual([path.name for path in (self.output / 'intermediate').iterdir()], ['unselected-pov.mp4'])
        self.assertTrue((self.output / 'manifest.json').is_file())
        self.assertTrue((self.output / 'skin-profile.json').is_file())
        self.assertIn('/wormholes-assets/demos/wand-creation-standard-pov.webm',
                      (self.docs / 'wormholes/03-building-portals.md').read_text())

    def test_unreviewed_and_invalid_publication_preserve_all_sources(self) -> None:
        with self.assertRaisesRegex(ValueError, 'reviewed'):
            self.publish(False)
        (self.output / 'exports/wand-creation-clientview-pov.webm').write_bytes(b'invalid')
        with self.assertRaisesRegex(AssertionError, 'Invalid clip'):
            self.publish()
        self.assertEqual(list(self.assets.iterdir()), [])
        self.assertEqual(len(list((self.output / 'exports').iterdir())), 2)
        self.assertEqual(len(list((self.output / 'screenshots').iterdir())), 2)

    def test_symbolic_link_cleanup_is_rejected_before_publication(self) -> None:
        external: Path = self.root / 'outside'
        external.mkdir()
        (external / 'wand-creation-standard-start.png').write_bytes(b'keep')
        shutil.rmtree(self.output / 'screenshots')
        (self.output / 'screenshots').symlink_to(external, target_is_directory=True)
        with self.assertRaisesRegex(ValueError, 'symbolic link'):
            self.publish()
        self.assertEqual(list(self.assets.iterdir()), [])
        self.assertTrue((external / 'wand-creation-standard-start.png').is_file())

    def test_copy_failure_retains_original_clip_and_capture(self) -> None:
        target: Path = self.assets / 'wand-creation-standard-pov.webm'
        target.write_bytes(b'previous')
        with patch.object(self.studio.shutil, 'copyfile', side_effect=OSError('Disk full')):
            with self.assertRaisesRegex(OSError, 'Disk full'):
                self.publish()
        self.assertEqual(target.read_bytes(), b'previous')
        self.assertTrue((self.output / 'intermediate/wand-creation-standard-pov.mp4').is_file())

    def test_missing_camera_refuses_publication_and_preserves_sources(self) -> None:
        (self.output / 'exports/wand-creation-clientview-pov.webm').unlink()
        with self.assertRaises(self.docs_sync.MissingClips):
            self.publish()
        self.assertEqual(list(self.assets.iterdir()), [])
        self.assertTrue((self.output / 'intermediate/wand-creation-standard-pov.mp4').is_file())

    def test_selected_replacement_preserves_accepted_other_variant(self) -> None:
        (self.output / 'exports/wand-creation-clientview-pov.webm').unlink()
        target: Path = self.assets / 'wand-creation-clientview-pov.webm'
        target.write_bytes(b'accepted')
        self.publish()
        self.assertEqual(target.read_bytes(), b'accepted')
        self.assertTrue((self.output / 'intermediate/wand-creation-clientview-pov.mp4').is_file())
        self.assertFalse((self.output / 'intermediate/wand-creation-standard-pov.mp4').exists())

    def test_interrupted_cleanup_retries_after_verifying_published_hashes(self) -> None:
        retained: Path = self.output / 'screenshots/wand-creation-standard-start.png'
        unlink: Callable[..., None] = Path.unlink

        def interrupt(path: Path, *arguments: object, **options: object) -> None:
            if path == retained:
                raise OSError('Cleanup interrupted')
            unlink(path, *arguments, **options)

        with patch.object(Path, 'unlink', interrupt):
            with self.assertRaisesRegex(OSError, 'Cleanup interrupted'):
                self.publish()
        self.assertFalse((self.output / 'intermediate/wand-creation-standard-pov.mp4').exists())
        self.assertTrue((self.output / 'exports/wand-creation-standard-pov.webm').is_file())
        receipt: Path = self.output / 'publication.json'
        self.assertFalse(json.loads(receipt.read_text())['complete'])
        published: Path = self.assets / 'wand-creation-standard-pov.webm'
        published.write_bytes(b'changed')
        with self.assertRaisesRegex(ValueError, 'changed or is missing'):
            self.publish()
        self.assertTrue(retained.is_file())
        published.write_bytes(b'valid')
        self.publish()
        self.assertFalse(retained.exists())
        self.assertEqual(list((self.output / 'exports').iterdir()), [])
        self.assertTrue(json.loads(receipt.read_text())['complete'])

    def test_native_retry_finishes_cleanup_without_reencoding_deleted_source(self) -> None:
        recordings: Path = self.output / 'native-demo'
        recordings.mkdir()
        source: Path = recordings / 'gravity-pov.mp4'
        source.write_bytes(b'valid')
        staged: Path = recordings / 'gravity-clientview-pov.webm'
        unlink: Callable[..., None] = Path.unlink

        def interrupt(path: Path, *arguments: object, **options: object) -> None:
            if path == staged:
                raise OSError('Cleanup interrupted')
            unlink(path, *arguments, **options)

        def encode(command: list[str], **options: object) -> None:
            Path(command[-1]).write_bytes(b'valid')

        with patch.object(self.native_capture, 'accepted', return_value={'gravity-pov': {}}), \
                patch.object(self.native_capture.subprocess, 'run', side_effect=encode), \
                patch.object(self.studio, 'validate_export', side_effect=self.validate), \
                patch.object(Path, 'unlink', interrupt):
            with self.assertRaisesRegex(OSError, 'Cleanup interrupted'):
                self.native_capture.export(self.output, self.assets, ['gravity-pov'], True)
        self.assertFalse(source.exists())
        self.assertTrue(staged.is_file())
        with patch.object(self.native_capture, 'accepted', side_effect=AssertionError('Must not reencode')):
            self.native_capture.export(self.output, self.assets, ['gravity-pov'], True)
        self.assertFalse(staged.exists())
        receipt: Path = recordings / 'publication-gravity-pov.json'
        self.assertTrue(json.loads(receipt.read_text())['complete'])

    def test_native_staged_symlink_is_rejected_before_encoding(self) -> None:
        recordings: Path = self.output / 'native-demo'
        recordings.mkdir()
        (recordings / 'gravity-pov.mp4').write_bytes(b'valid')
        outside: Path = self.root / 'outside.webm'
        outside.write_bytes(b'keep')
        (recordings / 'gravity-clientview-pov.webm').symlink_to(outside)
        with patch.object(self.native_capture, 'accepted', return_value={'gravity-pov': {}}), \
                patch.object(self.native_capture.subprocess, 'run') as encode:
            with self.assertRaisesRegex(ValueError, 'symbolic link'):
                self.native_capture.export(self.output, self.assets, ['gravity-pov'], True)
            encode.assert_not_called()
        self.assertEqual(outside.read_bytes(), b'keep')

    def test_native_publication_removes_only_reviewed_successful_takes(self) -> None:
        recordings: Path = self.output / 'native-demo'
        recordings.mkdir()
        source: Path = recordings / 'gravity-pov.mp4'
        source.write_bytes(b'valid')
        failed: Path = recordings / 'scale-pov.mp4'
        failed.write_bytes(b'failed')
        (self.output / 'result.json').write_text(json.dumps({'passed': False}))

        def encode(command: list[str], **options: object) -> None:
            Path(command[-1]).write_bytes(b'valid')

        with patch.object(self.native_capture, 'accepted', return_value={'gravity-pov': {}}), \
                patch.object(self.native_capture.subprocess, 'run', side_effect=encode), \
                patch.object(self.studio, 'validate_export', side_effect=self.validate):
            with self.assertRaisesRegex(ValueError, 'reviewed'):
                self.native_capture.export(self.output, self.assets, ['gravity-pov'])
            with patch.object(self.native_capture, 'publish_exports', side_effect=OSError('Disk full')):
                with self.assertRaises(OSError):
                    self.native_capture.export(self.output, self.assets, ['gravity-pov'], True)
            self.assertTrue(source.is_file())
            self.native_capture.export(self.output, self.assets, ['gravity-pov'], True)
        self.assertFalse(source.exists())
        self.assertFalse((recordings / 'gravity-clientview-pov.webm').exists())
        self.assertTrue(failed.is_file())
        self.assertTrue((self.output / 'result.json').is_file())
        self.assertEqual((self.assets / 'gravity-clientview-pov.webm').read_bytes(), b'valid')


if __name__ == '__main__':
    unittest.main()
