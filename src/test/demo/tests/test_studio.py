import importlib.util
import json
import shlex
import sys
import tempfile
import unittest
from pathlib import Path
from unittest.mock import Mock, patch

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

    def test_existing_recording_profile_is_preserved(self) -> None:
        bridge: Mock = Mock()
        bridge.state.return_value = {'capturing': True}
        with patch.object(self.studio, 'client_credentials', return_value=(12345, 'private')), \
                patch.object(self.studio, 'Bridge', return_value=bridge), \
                patch.object(self.studio, 'prepare_client') as prepare:
            with self.assertRaisesRegex(RuntimeError, 'already recording'):
                self.studio.Studio().open_clients('standard')
        bridge.command.assert_not_called()
        prepare.assert_not_called()

    def test_disconnected_profiles_quit_before_relaunch(self) -> None:
        bridges: dict[int, Mock] = {11111: Mock(), 22222: Mock()}
        for bridge in bridges.values():
            bridge.state.return_value = {'capturing': False, 'connected': False}
        with patch.object(self.studio, 'client_credentials', side_effect=[(11111, 'first'), (22222, 'second')]), \
                patch.object(self.studio, 'Bridge', side_effect=lambda port, token: bridges[port]), \
                patch.object(self.studio.time, 'sleep'), \
                patch.object(self.studio, 'prepare_client', side_effect=RuntimeError('before launch')):
            with self.assertRaisesRegex(RuntimeError, 'before launch'):
                self.studio.Studio().open_clients('standard')
        for bridge in bridges.values():
            bridge.command.assert_called_once_with('quit')

    def test_rcon_rejects_optional_argument_syntax_error(self) -> None:
        rcon = self.studio.Rcon.__new__(self.studio.Rcon)
        rcon.history = []
        reply: str = 'Unexpected argument "120". Optional parameters must be keyed, e.g. seed=123'
        with patch.object(rcon, 'exchange', return_value=reply), self.assertRaisesRegex(AssertionError, 'Unexpected argument'):
            rcon.command('wh admin freeze 120')
        self.assertEqual(rcon.history, [{'command': 'wh admin freeze 120', 'response': reply}])

    def test_rcon_accepts_confirmed_freeze(self) -> None:
        rcon = self.studio.Rcon.__new__(self.studio.Rcon)
        rcon.history = []
        reply: str = 'Portal projections frozen for 120 seconds.'
        with patch.object(rcon, 'exchange', return_value=reply):
            self.assertEqual(rcon.command('wh admin freeze seconds=120'), reply)

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

    def test_owned_profile_reuses_credentials_with_quoted_output(self) -> None:
        with tempfile.TemporaryDirectory() as directory:
            root: Path = Path(directory)
            output: Path = root / 'video output'
            instance: Path = root / 'instances' / 'Unique demo - 1'
            instance.mkdir(parents=True)
            arguments: str = '-Dautomator.port=61207 -Dautomator.token=' + 'a' * 48
            for value in (arguments + ' ' + shlex.quote('-Dautomator.output=' + str(output)),
                          json.dumps(arguments + ' -Dautomator.output="' + str(output) + '"'),
                          arguments + ' -Dautomator.output=' + str(output) + ' --enable-native-access=ALL-UNNAMED'):
                with self.subTest(value=value):
                    config: str = '[General]\nJvmArgs=' + value + '\n[Other]\nJvmArgs=ignored\n'
                    (instance / 'instance.cfg').write_text(config)
                    with patch.object(self.studio, 'PRISM', root), patch.object(self.studio, 'OUTPUT', output), \
                            patch.object(self.studio, 'PROFILE_PREFIX', 'Unique demo'):
                        self.assertEqual(self.studio.client_credentials(1), (61207, 'a' * 48))
                    self.assertEqual((instance / 'instance.cfg').read_text(), config)

    def test_existing_profile_rejects_invalid_or_other_output_credentials(self) -> None:
        with tempfile.TemporaryDirectory() as directory:
            root: Path = Path(directory)
            output: Path = root / 'output'
            instance: Path = root / 'instances' / 'Unique demo - 1'
            instance.mkdir(parents=True)
            valid: str = '-Dautomator.port=61207 -Dautomator.token=' + 'a' * 48 + ' -Dautomator.output=' + str(output)
            for value in (valid.replace(str(output), str(root / 'stale')), valid.replace('61207', '0'),
                          valid.replace('61207', '65536'), valid.replace('61207', 'invalid'),
                          valid.replace('a' * 48, ''), valid + ' -Dautomator.port=61208',
                          '"' + valid, '-Xmx3G'):
                with self.subTest(value=value):
                    config: str = '[General]\nJvmArgs=' + value + '\n'
                    (instance / 'instance.cfg').write_text(config)
                    with patch.object(self.studio, 'PRISM', root), patch.object(self.studio, 'OUTPUT', output), \
                            patch.object(self.studio, 'PROFILE_PREFIX', 'Unique demo'), self.assertRaises(ValueError):
                        self.studio.client_credentials(1)
                    self.assertEqual((instance / 'instance.cfg').read_text(), config)

    def test_new_profile_allocates_credentials_without_using_another_profile(self) -> None:
        with tempfile.TemporaryDirectory() as directory:
            root: Path = Path(directory)
            other: Path = root / 'instances' / 'Other demo - 1'
            other.mkdir(parents=True)
            (other / 'instance.cfg').write_text('[General]\nJvmArgs=invalid\n')
            with patch.object(self.studio, 'PRISM', root), patch.object(self.studio, 'PROFILE_PREFIX', 'Unique demo'), \
                    patch.object(self.studio, 'free_port', return_value=61207):
                port, token = self.studio.client_credentials(1)
            self.assertEqual(port, 61207)
            self.assertRegex(token, '^[0-9a-f]{48}$')
            self.assertFalse((root / 'instances' / 'Unique demo - 1').exists())

    def test_take_contract_preserves_construction_and_adds_visual_demonstrations(self) -> None:
        sheet = json.loads((MODULE.parent / 'shots.json').read_text())
        identifiers = {shot['id'] for shot in sheet['shots']}
        self.assertTrue({'wand-creation', 'rune-creation', 'portal-linking', 'mirrors',
                         'portal-orientation', 'render-panoptic', 'render-venticular',
                         'ambient-particles', 'pair-doors', 'personal-pockets', 'public-pockets',
                         'door-open-state', 'trapdoor-travel'} <= identifiers)
        self.assertEqual(len(identifiers), len(sheet['shots']))
        for shot in sheet['shots']:
            self.assertEqual(len(shot['aperture']), 2)
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
        self.studio.verify_capture({**self.studio.HIDDEN_RENDERER_STATE, 'captureFrames': 590, 'captureSeconds': 20.0, 'captureDroppedFrames': 0, 'captureSource': '1920x1080'})
        for state in (
            {'captureFrames': 120, 'captureSeconds': 20.0, 'captureDroppedFrames': 0, 'captureSource': '1920x1080'},
            {'captureFrames': 590, 'captureSeconds': 20.0, 'captureDroppedFrames': 1, 'captureSource': '1920x1080'},
            {'captureFrames': 0, 'captureSeconds': 20.0, 'captureDroppedFrames': 0, 'captureSource': '1920x1080'},
            {'captureFrames': 590, 'captureSeconds': 20.0, 'captureDroppedFrames': 0, 'captureSource': '1504x818'},
        ):
            with self.subTest(state=state), self.assertRaises(AssertionError):
                self.studio.verify_capture({**self.studio.HIDDEN_RENDERER_STATE, **state})

    def test_modded_demonstration_requires_both_named_clients_to_negotiate(self) -> None:
        self.assertTrue(hasattr(self.studio, 'verify_sessions'), 'Both client sessions are not checked')
        players = ('DemoBuilder', 'DemoWatcher')
        self.studio.verify_sessions('§eDemoBuilder §7CLIENT_VIEW | caps plates\n§eDemoWatcher §7CLIENT_VIEW | caps plates', 'clientview', players)
        self.studio.verify_sessions('§eDemoBuilder §7VANILLA | caps -\n§eDemoWatcher §7VANILLA | caps -', 'standard', players)
        for status in ('DemoBuilder CLIENT_VIEW | caps plates\nDemoWatcher VANILLA | caps -',
                       'DemoBuilder CLIENT_VIEW | caps plates\nUnrelatedClient CLIENT_VIEW | caps plates'):
            with self.subTest(status=status), self.assertRaises(AssertionError):
                self.studio.verify_sessions(status, 'clientview', players)

    def loading_capture(self) -> dict:
        return {**self.studio.HIDDEN_RENDERER_STATE, 'captureFrames': 450, 'captureEncodedFrames': 600,
                'captureRepeatedFrames': 150, 'captureSeconds': 20.0, 'captureDroppedFrames': 0,
                'captureSource': '1920x1080', 'captureHolds': [{'startFrame': 200, 'endFrame': 350}]}

    def test_only_approved_loading_holds_adjust_the_real_frame_rate(self) -> None:
        state: dict = self.loading_capture()
        accepted: dict = self.studio.verify_capture(state, 'personal-pockets')
        self.assertEqual(accepted['raw'], state)
        self.assertEqual(accepted['removedSeconds'], 5.0)
        self.assertEqual(accepted['trimmedSeconds'], 15.0)
        self.assertEqual(accepted['edits'], state['captureHolds'])
        for identifier in (None, 'mirrors', 'render-panoptic', 'atmosphere'):
            with self.subTest(identifier=identifier), self.assertRaises(AssertionError):
                self.studio.verify_capture(state, identifier)
        intact: dict = self.studio.verify_capture({**state, 'captureFrames': 5850, 'captureEncodedFrames': 6000,
                                                  'captureSeconds': 200.0}, 'mirrors')
        self.assertEqual(intact['edits'], [])
        self.assertEqual(intact['trimmedSeconds'], 200.0)
        self.assertEqual(intact['removedSeconds'], 0.0)
        self.assertEqual(intact['raw']['captureHolds'], state['captureHolds'])
        for changes in ({'captureDroppedFrames': 1}, {'captureSource': '1504x818'},
                        {'captureFrames': 400, 'captureRepeatedFrames': 200},
                        {'captureHolds': []}, {'captureSeconds': float('nan')},
                        {'captureEncodedFrames': 900, 'captureRepeatedFrames': 450}):
            with self.subTest(changes=changes), self.assertRaises(AssertionError):
                self.studio.verify_capture({**state, **changes}, 'personal-pockets')
        with self.assertRaises(RuntimeError):
            self.studio.verify_capture({**state, 'windowVisible': True}, 'personal-pockets')

    def test_untrimmed_capture_uses_original_real_frame_rate(self) -> None:
        state: dict = {**self.loading_capture(), 'captureFrames': 30, 'captureEncodedFrames': 32,
                       'captureRepeatedFrames': 2, 'captureHolds': [], 'captureSeconds': 1.05}
        intact: dict = self.studio.verify_capture(state, 'redstone-control')
        self.assertEqual(intact['edits'], [])
        self.assertEqual(intact['trimmedSeconds'], 1.05)
        with self.assertRaises(AssertionError):
            self.studio.verify_capture({**state, 'captureEncodedFrames': 60, 'captureRepeatedFrames': 30,
                'captureHolds': [{'startFrame': 15, 'endFrame': 45}], 'captureSeconds': 2.0}, 'redstone-control')

    def test_hold_ranges_and_real_frame_accounting_are_validated(self) -> None:
        state: dict = self.loading_capture()
        for holds in (None, [{}], [{'startFrame': -1, 'endFrame': 20}],
                      [{'startFrame': 200, 'endFrame': 214}], [{'startFrame': 590, 'endFrame': 620}],
                      [{'startFrame': 200.0, 'endFrame': 350}], [{'startFrame': True, 'endFrame': 350}],
                      [{'startFrame': 200, 'endFrame': 350}, {'startFrame': 340, 'endFrame': 360}],
                      [{'startFrame': 400, 'endFrame': 450}, {'startFrame': 200, 'endFrame': 250}],
                      [{'startFrame': 200, 'endFrame': 351}]):
            with self.subTest(holds=holds), self.assertRaises(AssertionError):
                self.studio.verify_capture({**state, 'captureHolds': holds}, 'personal-pockets')
        for changes in ({'captureRepeatedFrames': 151}, {'captureFrames': '450'},
                        {'captureEncodedFrames': -1}, {'captureRepeatedFrames': True}):
            with self.subTest(changes=changes), self.assertRaises(AssertionError):
                self.studio.verify_capture({**state, **changes}, 'personal-pockets')

    def test_export_trims_only_accepted_half_open_ranges_per_view(self) -> None:
        accepted: dict = self.studio.verify_capture(self.loading_capture(), 'personal-pockets')
        untrimmed: dict = self.studio.verify_capture({**self.loading_capture(), 'captureFrames': 600,
            'captureRepeatedFrames': 0, 'captureHolds': []}, 'personal-pockets')
        take: dict = {'capture': [{'view': 'pov', **accepted}, {'view': 'observer', **untrimmed}]}
        filters: dict = self.studio.export_filters('personal-pockets', take)
        self.assertEqual(filters['pov'], "select='not(between(n,200,349))',setpts=N/(30*TB),scale=1920:1080:flags=lanczos")
        self.assertEqual(filters['observer'], 'scale=1920:1080:flags=lanczos')
        take['capture'][0]['edits'] = [{'startFrame': 199, 'endFrame': 350}]
        with self.assertRaises(AssertionError):
            self.studio.export_filters('personal-pockets', take)
        with self.assertRaises(AssertionError):
            self.studio.export_filters('personal-pockets', {'capture': [take['capture'][1]]})

    def test_previously_accepted_metrics_need_no_invented_hidden_state_for_export(self) -> None:
        metrics: dict = {'captureFrames': 590, 'captureSeconds': 20.0,
                         'captureDroppedFrames': 0, 'captureSource': '1920x1080'}
        evidence: dict = self.studio.capture_evidence(metrics, 'mirrors')
        self.assertEqual(evidence['raw'], metrics)
        self.assertEqual(evidence['edits'], [])
        take: dict = {'capture': [{'view': view, **evidence} for view in ('pov', 'observer')]}
        self.assertEqual(self.studio.export_filters('mirrors', take),
                         {'pov': 'scale=1920:1080:flags=lanczos', 'observer': 'scale=1920:1080:flags=lanczos'})
        with self.assertRaises(RuntimeError):
            self.studio.verify_capture(metrics, 'mirrors')

    def test_hidden_renderer_rejects_unsafe_or_missing_native_state(self) -> None:
        safe = {'hiddenRenderer': True, 'windowVisible': False, 'windowFocused': False,
                'mouseGrabbed': False, 'relativeMouseMode': False, 'windowMouseGrabbed': False,
                'frameWidth': 1920, 'frameHeight': 1080}
        self.studio.verify_hidden_renderer(safe)
        for field in ('hiddenRenderer', 'windowVisible', 'windowFocused', 'mouseGrabbed',
                      'relativeMouseMode', 'windowMouseGrabbed'):
            for state in ({**safe, field: not safe[field]}, {key: value for key, value in safe.items() if key != field}):
                with self.subTest(field=field), self.assertRaises(RuntimeError):
                    self.studio.verify_hidden_renderer(state)

    def test_render_size_wait_is_bounded_and_rechecks_native_safety(self) -> None:
        safe = {'hiddenRenderer': True, 'windowVisible': False, 'windowFocused': False,
                'mouseGrabbed': False, 'relativeMouseMode': False, 'windowMouseGrabbed': False,
                'frameWidth': 1504, 'frameHeight': 818}

        class Client:
            def __init__(self, states: list[dict]) -> None:
                self.states = iter(states)
                self.timeouts: list[float] = []

            def command(self, operation: str, **values: object) -> dict:
                return safe

            def state(self, timeout: float = 30) -> dict:
                self.timeouts.append(timeout)
                return next(self.states)

        client = Client([safe, safe, {**safe, 'frameWidth': 1920, 'frameHeight': 1080}])
        with patch.object(self.studio.time, 'monotonic', side_effect=[0, 1, 2, 3]), patch.object(self.studio.time, 'sleep'):
            self.studio.fit_hidden_renderer(client)
        self.assertEqual(client.timeouts, [5, 3, 2])
        client = Client([{**safe, 'windowVisible': True}])
        with patch.object(self.studio.time, 'monotonic', side_effect=[0]), self.assertRaises(RuntimeError):
            self.studio.fit_hidden_renderer(client)
        client = Client([safe, safe])
        with patch.object(self.studio.time, 'monotonic', side_effect=[0, 1, 2, 6]), patch.object(self.studio.time, 'sleep'), self.assertRaises(TimeoutError):
            self.studio.fit_hidden_renderer(client)


if __name__ == '__main__':
    unittest.main()
