import contextlib
import importlib
import sys
import tempfile
import unittest
from pathlib import Path
from collections.abc import Iterator
from unittest.mock import Mock, patch


MODULE: Path = Path(__file__).resolve().parents[1]


class GatewayLifecycleTests(unittest.TestCase):
    def setUp(self) -> None:
        self.path = patch.object(sys, 'path', [str(MODULE), *sys.path])
        self.path.start()
        self.addCleanup(self.path.stop)
        self.demo = importlib.import_module('demo')
        self.temporary = tempfile.TemporaryDirectory()
        self.addCleanup(self.temporary.cleanup)
        self.primary = Mock()
        self.secondary = Mock()
        self.actor = Mock()
        self.observer = Mock()
        self.primary.open_clients.return_value = (self.actor, self.observer)
        self.events: list[str] = []

    @contextlib.contextmanager
    def run_configuration(self, arguments: list[str]) -> Iterator[tuple[Mock, Mock, Mock]]:
        def prepare(primary: Mock) -> Mock:
            self.assertIs(primary, self.primary)
            self.events.append('prepare')
            return self.secondary

        def record(primary: Mock, actor: Mock, observer: Mock, shot: dict, variant: str) -> dict:
            self.assertIs(primary, self.primary)
            self.assertIs(actor, self.actor)
            self.assertIs(observer, self.observer)
            self.events.append('record:' + variant)
            return {'id': shot['id'], 'variant': variant}

        with patch.object(sys, 'argv', ['demo.py', '--only', 'cross-server-gateways', '--record-only', *arguments]), \
                patch.object(self.demo, 'OUTPUT', Path(self.temporary.name)), \
                patch.object(self.demo, 'Studio', return_value=self.primary), \
                patch.object(self.demo, 'disconnect_gateway_clients', side_effect=lambda *args: self.events.append('disconnect')), \
                patch.object(self.demo, 'reconnect_gateway_clients', side_effect=lambda *args: self.events.append('reconnect:' + args[-1])), \
                patch.object(self.demo.gateway_studio, 'prepare_network', side_effect=prepare) as preparation, \
                patch.object(self.demo.gateway_studio, 'reuse_secondary', return_value=self.secondary) as reuse, \
                patch.object(self.demo.gateway_studio, 'close_secondary', side_effect=lambda value: self.events.append('secondary-close')) as cleanup, \
                patch.object(self.demo, 'record', side_effect=record), patch('builtins.print'):
            self.primary.close.side_effect = lambda: self.events.append('primary-close')
            yield preparation, reuse, cleanup

    def test_prepares_each_variant_and_preserves_client_handles(self) -> None:
        with self.run_configuration(['--variant', 'all']) as (preparation, reuse, cleanup):
            self.demo.main()
            self.assertEqual(preparation.call_count, 2)
            reuse.assert_not_called()
            cleanup.assert_called_once_with(self.secondary)
        self.assertEqual(self.events, ['disconnect', 'prepare', 'reconnect:standard', 'record:standard',
                                      'disconnect', 'prepare', 'reconnect:clientview', 'record:clientview',
                                      'secondary-close', 'primary-close'])

    def test_primary_cleanup_runs_when_secondary_cleanup_fails(self) -> None:
        with self.run_configuration(['--variant', 'standard']) as (_, _, cleanup):
            cleanup.side_effect = RuntimeError('Secondary stop failed')
            with self.assertRaisesRegex(RuntimeError, 'Secondary stop failed'):
                self.demo.main()
            self.primary.close.assert_called_once()

    def test_failed_preparation_recovers_owned_secondary_for_cleanup(self) -> None:
        with self.run_configuration(['--variant', 'standard']) as (preparation, reuse, cleanup):
            preparation.side_effect = RuntimeError('Setup failed')
            with self.assertRaisesRegex(RuntimeError, 'Setup failed'):
                self.demo.main()
            reuse.assert_called_once_with(self.primary, connect=False)
            cleanup.assert_called_once_with(self.secondary)
            self.primary.close.assert_called_once()

    def test_keep_open_preserves_both_servers(self) -> None:
        with self.run_configuration(['--variant', 'standard', '--keep-open']) as (_, reuse, cleanup):
            self.demo.main()
            reuse.assert_not_called()
            cleanup.assert_not_called()
            self.primary.close.assert_not_called()


if __name__ == '__main__':
    unittest.main()
