import json
from pathlib import Path
import tempfile
import unittest

import crossbench


def traffic(count: int, bytes_sent: int) -> dict:
    return {"count": count, "bytes": bytes_sent, "types": []}


def sample(label: str, route: str, direction: str, phase: str, move_ms: float, frame_ms: float, cycle: dict | None) -> dict:
    return {
        "label": label, "route": route, "direction": direction, "trip": 1, "phase": phase,
        "openEpochMs": 1_000, "crossingEpochMs": 2_000, "endEpochMs": 6_000,
        "clientCrossMs": 0.5, "clientDeclines": 0, "clientCrossings": 1, "returnViewTicks": 3,
        "client": {"crossingFrameMs": frame_ms, "nextFrameMs": 2.0, "postWorstMs": frame_ms, "postP99Ms": 3.0, "postOver30": 0,
                   "settledFrames": 1, "settledMs": 8.0},
        "server": {"moveMs": move_ms, "postMaxBusyMs": 12.0, "postCyclesOver50": 0, "postWormholesMs": 40.0, "crossingCycle": cycle},
        "chunkLoads": {"minecraft:the_nether after": 4},
        "chunkWaits": [],
        "packets": {"sentBefore": traffic(1, 0), "sentAfter": traffic(2, 0), "routedBefore": traffic(0, 0), "routedAfter": traffic(3, 2048),
                    "encodedBefore": traffic(0, 0), "encodedAfter": traffic(0, 0)},
    }


CYCLE: dict = {"tick": 7, "packetsMs": 3.0, "tickMs": 2.0, "tasksMs": 0.25, "tasks": 4, "inboundMs": 2.5, "wormholesTickMs": 1.0,
               "chunkWaitMs": 0.0, "startEpochMs": 2_010, "endEpochMs": 2_015}


class CrossingBenchTest(unittest.TestCase):
    def test_merged_combines_every_route_file(self) -> None:
        with tempfile.TemporaryDirectory() as directory:
            output = Path(directory)
            for route in ["nether", "end"]:
                document = {"label": "crossing-bench-" + route + "-singleplayer", "loader": "fabric", "minecraft": "26.3", "renderer": "vanilla",
                            "samples": [sample(route + "-out-cold-1", route, "out", "cold", 1.0, 4.0, CYCLE)]}
                (output / (document["label"] + ".json")).write_text(json.dumps(document))
            (output / "summary.json").write_text("{}")
            results = crossbench.merged(output)
        self.assertIsNotNone(results)
        self.assertEqual(["end-out-cold-1", "nether-out-cold-1"], [entry["label"] for entry in results["samples"]])
        self.assertEqual("fabric", results["loader"])

    def test_merged_without_results_is_none(self) -> None:
        with tempfile.TemporaryDirectory() as directory:
            self.assertIsNone(crossbench.merged(Path(directory)))

    def test_windows_cover_steady_client_post_and_server_cycle(self) -> None:
        lines = crossbench.windows({"samples": [sample("nether-out-cold-1", "nether", "out", "cold", 1.0, 4.0, CYCLE)]})
        kinds = {line.split("\t")[1]: line.split("\t")[2:] for line in lines}
        self.assertEqual(["1200", "1800"], kinds["steady"])
        self.assertEqual(["2000", "2250"], kinds["client-crossing"])
        self.assertEqual(["2000", "5000"], kinds["post"])
        self.assertEqual(["2010", "2015"], kinds["server-crossing"])

    def test_windows_skip_missing_server_cycle(self) -> None:
        lines = crossbench.windows({"samples": [sample("same-out-rapid-1", "same", "out", "rapid", 1.0, 4.0, None)]})
        self.assertNotIn("server-crossing", [line.split("\t")[1] for line in lines])

    def test_table_reports_each_crossing(self) -> None:
        text = crossbench.table({"samples": [sample("nether-out-cold-1", "nether", "out", "cold", 1.25, 4.0, CYCLE),
                                             sample("nether-out-rapid-1", "nether", "out", "rapid", 1.0, 3.0, None)]})
        rows = text.splitlines()
        self.assertEqual(3, len(rows))
        self.assertTrue(rows[1].startswith("nether-out-cold-1"))
        self.assertIn("1.25", rows[1])
        self.assertIn("-", rows[2].split()[1])
        self.assertTrue(rows[1].endswith("2.0"))

    def test_aggregate_takes_group_medians(self) -> None:
        medians = crossbench.aggregate({"samples": [sample("a", "nether", "out", "warm", 1.0, 2.0, CYCLE),
                                                    sample("b", "nether", "out", "warm", 3.0, 6.0, CYCLE),
                                                    sample("c", "nether", "out", "warm", 2.0, 4.0, CYCLE)]})
        self.assertEqual(2.0, medians["nether-out-warm"]["move"])
        self.assertEqual(4.0, medians["nether-out-warm"]["frame0"])

    def test_compare_reports_deltas_for_shared_groups(self) -> None:
        text = crossbench.compare({"nether-out-warm": {"move": 1.5}, "end-out-warm": {"move": 1.0}}, {"nether-out-warm": {"move": 2.0}})
        rows = text.splitlines()
        self.assertEqual(2, len(rows))
        self.assertIn("-0.50", rows[1])


if __name__ == "__main__":
    unittest.main()
