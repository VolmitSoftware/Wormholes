import importlib.util
import struct
import sys
import unittest
from pathlib import Path


MODULE = Path(__file__).resolve().parents[1] / 'studio.py'


def packet(identifier: int, kind: int, payload: bytes) -> bytes:
    body: bytes = struct.pack('<ii', identifier, kind) + payload + b'\0\0'
    return struct.pack('<i', len(body)) + body


class FragmentedSocket:
    def __init__(self, replies: bytes) -> None:
        self.replies: bytes = replies
        self.offset: int = 0
        self.sent: list[tuple[bytes, int]] = []

    def sendall(self, data: bytes) -> None:
        self.sent.append((data, self.offset))

    def recv(self, size: int) -> bytes:
        result: bytes = self.replies[self.offset:self.offset + min(size, 7)]
        self.offset += len(result)
        return result


class RconTests(unittest.TestCase):
    def setUp(self) -> None:
        spec = importlib.util.spec_from_file_location('rcon_test_studio', MODULE)
        self.studio = importlib.util.module_from_spec(spec)
        sys.modules[spec.name] = self.studio
        spec.loader.exec_module(self.studio)

    def connection(self, replies: bytes):
        rcon = self.studio.Rcon.__new__(self.studio.Rcon)
        rcon.identifier = 0
        rcon.history = []
        rcon.socket = FragmentedSocket(replies)
        return rcon

    def test_multipart_reply_does_not_contaminate_next_command(self) -> None:
        first: bytes = packet(1, 0, b'a' * 4096)
        payload: bytes = ('b' * 1900 + '\u2603').encode()
        rcon = self.connection(first + packet(1, 0, payload[:1901]) + packet(1, 0, payload[1901:])
                               + packet(2, 0, b'Unknown request 0') + packet(3, 0, b'next')
                               + packet(4, 0, b'Unknown request 0'))
        self.assertEqual(rcon.command('whdemo feature report'), 'a' * 4096 + payload.decode())
        self.assertEqual(rcon.command('list'), 'next')
        self.assertEqual(rcon.socket.sent[0], (packet(1, 2, b'whdemo feature report'), 0))
        self.assertEqual(rcon.socket.sent[1], (packet(2, 0, b''), len(first)))
        self.assertEqual(len(rcon.history), 2)
        self.assertEqual(rcon.identifier, 4)

    def test_authentication_reads_one_response_without_barrier(self) -> None:
        rcon = self.connection(packet(1, 2, b''))
        self.assertEqual(rcon.exchange(3, 'synthetic-secret'), '')
        self.assertEqual(len(rcon.socket.sent), 1)
        self.assertEqual(rcon.identifier, 1)

    def test_exact_chunk_and_empty_response_finish_at_barrier(self) -> None:
        for response in (b'', b'x' * 4096):
            with self.subTest(length=len(response)):
                rcon = self.connection(packet(1, 0, response) + packet(2, 0, b'Unknown request 0'))
                self.assertEqual(rcon.exchange(2, 'list'), response.decode())

    def test_invalid_response_packets_fail(self) -> None:
        first: bytes = packet(1, 0, b'first')
        cases: list[tuple[bytes, type[Exception]]] = [
            (struct.pack('<i', 9), ValueError),
            (struct.pack('<i', 1048577), ValueError),
            (packet(1, 0, b'broken')[:-2] + b'xx', ValueError),
            (packet(99, 0, b'wrong request'), RuntimeError),
            (packet(1, 2, b'wrong type'), ValueError),
            (first + packet(99, 0, b'wrong continuation'), RuntimeError),
            (first + packet(1, 2, b'wrong continuation type'), ValueError),
            (first + packet(2, 0, b'wrong barrier'), ValueError),
            (first, ConnectionError),
            (packet(1, 0, b'truncated')[:-3], ConnectionError),
        ]
        for replies, error in cases:
            with self.subTest(replies=replies):
                with self.assertRaises(error):
                    self.connection(replies).exchange(2, 'list')

    def test_authentication_failure_rejects_negative_request_id(self) -> None:
        with self.assertRaisesRegex(RuntimeError, 'correlation'):
            self.connection(packet(-1, 2, b'')).exchange(3, 'synthetic-secret')


if __name__ == '__main__':
    unittest.main()
