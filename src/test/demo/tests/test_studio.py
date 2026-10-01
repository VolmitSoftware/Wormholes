import importlib.util
import json
import sys
import tempfile
import unittest
from pathlib import Path
from unittest.mock import patch

MODULE = Path(__file__).resolve().parents[1] / 'studio.py'


class StudioTests(unittest.TestCase):
    def setUp(self) -> None:
        spec = importlib.util.spec_from_file_location('wormholes_studio', MODULE)
        self.assertIsNotNone(spec)
        self.studio = importlib.util.module_from_spec(spec)
        sys.modules[spec.name] = self.studio
        spec.loader.exec_module(self.studio)

    def test_client_profiles_have_distinct_owned_paths(self) -> None:
        self.assertEqual(self.studio.client_numbers('standard'), (1, 2))
        self.assertEqual(self.studio.client_numbers('clientview'), (3, 4))
        with self.assertRaises(ValueError):
            self.studio.client_numbers('unknown')

    def test_profile_updates_preserve_unowned_settings(self) -> None:
        with tempfile.TemporaryDirectory() as directory:
            config = Path(directory) / 'instance.cfg'
            config.write_text('[General]\nname=Original\nCustomSetting=keep\nuuid=old\n[Other]\nname=Other\n')
            self.studio.update_config(config, {'name': 'Instance Automator - 1'}, remove={'uuid'})
            result = config.read_text()
            self.assertIn('name=Instance Automator - 1', result)
            self.assertIn('CustomSetting=keep', result)
            self.assertNotIn('uuid=old', result)
            self.assertIn('[Other]\nname=Other', result)

    def test_take_contract_requires_all_three_demonstrations(self) -> None:
        sheet = json.loads((MODULE.parent / 'shots.json').read_text())
        self.assertEqual({shot['id'] for shot in sheet['shots']}, {'wand-creation', 'rune-creation', 'portal-linking'})
        for shot in sheet['shots']:
            self.assertEqual(shot['aperture'], [3, 3])
            self.assertTrue(shot['assertions'])
            self.assertGreater(shot['timeoutSeconds'], 0)

    def test_capture_stop_failure_does_not_leave_clients_or_server_running(self) -> None:
        calls: list[str] = []

        class Client:
            def __init__(self, name: str, failing: bool) -> None:
                self.name = name
                self.failing = failing

            def state(self) -> dict:
                return {'capturing': True}

            def command(self, operation: str, **values: object) -> None:
                calls.append(self.name + ':' + operation)
                if operation == 'capture' and self.failing:
                    raise RuntimeError('Encoder failed during capture stop')

        studio = self.studio.Studio()
        studio.created = True
        studio.clients = [(Path('owned-actor'), Client('actor', True)), (Path('owned-observer'), Client('observer', False))]
        commands: list[tuple[str, ...]] = []
        with patch.object(self.studio, 'mux', side_effect=lambda *arguments: commands.append(arguments) or ''), \
                patch.object(self.studio.time, 'sleep'), patch('traceback.print_exception'):
            with self.assertRaises(RuntimeError):
                studio.close()
        self.assertEqual(calls, ['actor:capture', 'actor:quit', 'observer:capture', 'observer:quit'])
        self.assertEqual(commands, [('runtime', 'stop', studio.name), ('instance', 'delete', studio.name)])

    def test_concurrent_recording_is_rejected_and_lock_is_released_after_failure(self) -> None:
        self.assertTrue(hasattr(self.studio, 'claim_studio'), 'The studio has no recording lock')
        with tempfile.TemporaryDirectory() as directory:
            lock = Path(directory) / 'run.lock'
            with self.assertRaises(ValueError):
                with self.studio.claim_studio(lock):
                    with self.assertRaises(RuntimeError):
                        with self.studio.claim_studio(lock):
                            self.fail('A second recording acquired the active studio')
                    raise ValueError('A take failed')
            with self.studio.claim_studio(lock):
                self.assertTrue(lock.is_file())

    def test_stalled_or_dropped_capture_cannot_be_published(self) -> None:
        self.assertTrue(hasattr(self.studio, 'verify_capture'), 'Capture quality is not checked')
        self.studio.verify_capture({'captureFrames': 590, 'captureSeconds': 20.0, 'captureDroppedFrames': 0, 'captureSource': '1920x1080'})
        for state in (
            {'captureFrames': 120, 'captureSeconds': 20.0, 'captureDroppedFrames': 0, 'captureSource': '1920x1080'},
            {'captureFrames': 590, 'captureSeconds': 20.0, 'captureDroppedFrames': 1, 'captureSource': '1920x1080'},
            {'captureFrames': 0, 'captureSeconds': 20.0, 'captureDroppedFrames': 0, 'captureSource': '1920x1080'},
            {'captureFrames': 590, 'captureSeconds': 20.0, 'captureDroppedFrames': 0, 'captureSource': '1504x818'},
        ):
            with self.subTest(state=state), self.assertRaises(AssertionError):
                self.studio.verify_capture(state)

    def test_modded_demonstration_requires_both_named_clients_to_negotiate(self) -> None:
        self.assertTrue(hasattr(self.studio, 'verify_sessions'), 'Both client sessions are not checked')
        players = ('DemoBuilder', 'DemoWatcher')
        self.studio.verify_sessions('§eDemoBuilder §7CLIENT_VIEW | caps plates\n§eDemoWatcher §7CLIENT_VIEW | caps plates', 'clientview', players)
        self.studio.verify_sessions('§eDemoBuilder §7VANILLA | caps -\n§eDemoWatcher §7VANILLA | caps -', 'standard', players)
        for status in ('DemoBuilder CLIENT_VIEW | caps plates\nDemoWatcher VANILLA | caps -',
                       'DemoBuilder CLIENT_VIEW | caps plates\nUnrelatedClient CLIENT_VIEW | caps plates'):
            with self.subTest(status=status), self.assertRaises(AssertionError):
                self.studio.verify_sessions(status, 'clientview', players)


if __name__ == '__main__':
    unittest.main()
