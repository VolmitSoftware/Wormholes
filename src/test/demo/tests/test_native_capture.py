import importlib.util
import json
from pathlib import Path
import sys
import tempfile
import unittest
from unittest.mock import Mock, patch


DIRECTORY: Path = Path(__file__).resolve().parents[1]


class NativeCaptureTest(unittest.TestCase):
    def setUp(self) -> None:
        sys.path.insert(0, str(DIRECTORY))
        self.addCleanup(lambda: sys.path.remove(str(DIRECTORY)))
        from studio import HIDDEN_RENDERER_STATE
        specification = importlib.util.spec_from_file_location('wormholes_native_capture', DIRECTORY / 'native_capture.py')
        self.module = importlib.util.module_from_spec(specification)
        specification.loader.exec_module(self.module)
        temporary = tempfile.TemporaryDirectory()
        self.addCleanup(temporary.cleanup)
        self.recorder = self.module.NativeCaptureRecorder(Path(temporary.name), 'local-token')
        self.recorder.output.mkdir()
        self.bridge = Mock()
        self.bridge.state.return_value = {**HIDDEN_RENDERER_STATE,
                                        'frameWidth': 1920, 'frameHeight': 1080}
        self.bridge.command.return_value = {'captureSource': '1920x1080'}

    def phase(self, sequence: int, identifier: str, stage: str) -> None:
        (self.recorder.output / 'phase.json').write_text(json.dumps({'sequence': sequence, 'id': identifier, 'stage': stage}))
        with patch.object(self.module, 'Bridge', return_value=self.bridge):
            self.recorder.tick(61234)

    def test_repeated_phase_cannot_restart_encoder(self) -> None:
        self.phase(1, 'gravity-pov', 'ready')
        self.phase(1, 'gravity-pov', 'ready')
        self.assertEqual(self.bridge.command.call_count, 1)
        self.assertEqual(json.loads((self.recorder.output / 'ack.json').read_text())['sequence'], 1)
        with self.assertRaises(AssertionError):
            self.phase(2, 'scale-pov', 'ready')
        self.assertFalse(json.loads((self.recorder.output / 'ack.json').read_text())['success'])

    def test_failed_quality_does_not_accept_or_publish_take(self) -> None:
        self.phase(1, 'gravity-pov', 'ready')
        self.bridge.command.return_value = {**self.bridge.state.return_value,
            'captureSource': '1920x1080', 'captureFrames': 590, 'captureSeconds': 20.0, 'captureDroppedFrames': 1}
        with self.assertRaises(AssertionError):
            self.phase(2, 'gravity-pov', 'complete')
        self.assertEqual(self.recorder.takes, {})
        self.assertFalse((self.recorder.output / 'manifest.json').exists())
        with self.assertRaises(AssertionError):
            self.recorder.verify_complete()

    def test_wrong_framebuffer_cannot_start_recording(self) -> None:
        self.bridge.state.return_value['frameWidth'] = 1280
        with self.assertRaises(AssertionError):
            self.phase(1, 'scale-pov', 'ready')
        self.bridge.command.assert_not_called()

    def test_export_retains_proven_takes_without_accepting_a_failed_take(self) -> None:
        evidence: Path = self.recorder.output.parent
        (evidence / 'result.json').write_text(json.dumps({'passed': False}))
        (evidence / 'runtime.log').write_text('WORMHOLES_NATIVE_DEMO_PASS: gravity-pov quarter-turn crossing\n')
        raw: dict = {**self.bridge.state.return_value, 'captureSource': '1920x1080',
                     'captureFrames': 590, 'captureSeconds': 20.0, 'captureDroppedFrames': 0}
        (self.recorder.output / 'manifest.json').write_text(json.dumps({'takes': {
            'gravity-pov': {'raw': raw}, 'scale-third-person': {'raw': raw}}}))
        self.assertEqual(set(self.module.accepted(evidence, ['gravity-pov'])), {'gravity-pov'})
        with self.assertRaises(AssertionError):
            self.module.accepted(evidence, ['scale-third-person'])
        with self.assertRaises(AssertionError):
            self.module.accepted(evidence, None)

    def test_selected_recording_requires_each_selected_take(self) -> None:
        recorder = self.module.NativeCaptureRecorder(self.recorder.output.parent, 'local-token', ['scale-pov', 'scale-third-person'])
        recorder.takes['scale-pov'] = {}
        with self.assertRaises(AssertionError):
            recorder.verify_complete()
        recorder.takes['scale-third-person'] = {}
        recorder.verify_complete()


if __name__ == '__main__':
    unittest.main()
