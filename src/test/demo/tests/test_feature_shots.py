import importlib
import sys
import unittest
from pathlib import Path
from unittest.mock import Mock, patch


MODULE: Path = Path(__file__).resolve().parents[1]


class ProjectionFreezeTests(unittest.TestCase):
    def setUp(self) -> None:
        with patch.object(sys, 'path', [str(MODULE), *sys.path]):
            self.shots = importlib.import_module('feature_shots')
        self.rcon = Mock()

    def test_freeze_returns_runtime_deadline_as_proof(self) -> None:
        self.rcon.command.return_value = 'frozenUntil=220000'
        with patch.object(self.shots.time, 'time', return_value=100):
            self.assertEqual(self.shots.freeze(self.rcon, 120), 'frozenUntil=220000')
        self.rcon.command.assert_called_once_with('whdemo feature freeze 120')

    def test_rejects_missing_or_expired_deadline(self) -> None:
        with patch.object(self.shots.time, 'time', return_value=100):
            for response in ('', 'Projections frozen', 'frozenUntil=0', 'frozenUntil=100000'):
                with self.subTest(response=response):
                    self.rcon.command.return_value = response
                    with self.assertRaises(AssertionError):
                        self.shots.freeze(self.rcon, 120)

    def test_resume_requires_zero_deadline(self) -> None:
        self.rcon.command.return_value = 'frozenUntil=0'
        self.assertEqual(self.shots.freeze(self.rcon, 0), 'frozenUntil=0')
        self.rcon.command.assert_called_once_with('whdemo feature freeze 0')
        self.rcon.command.return_value = 'frozenUntil=220000'
        with self.assertRaises(AssertionError):
            self.shots.freeze(self.rcon, 0)
