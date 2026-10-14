import argparse
import json
import os
from pathlib import Path
import re
import statistics
import subprocess
import sys

from gametest import supported_versions


ROOT = Path(__file__).resolve().parents[3]
CLIENT = ROOT / "src/test/client"
CLIENT_WINDOW_MS = 250
POST_WINDOW_MS = 3000
STEADY_MARGIN_MS = 200


def jvm_arguments(args: argparse.Namespace) -> list[str]:
    arguments: list[str] = ["-Dwormholes.clientgametest.crossbench=true", "-Dwormholes.crossbench.routes=" + args.routes,
                            "-Dwormholes.crossbench.warmTrips=" + str(args.warm_trips), "-Dwormholes.crossbench.rapidTrips=" + str(args.rapid_trips),
                            "-Dwormholes.crossbench.settleTicks=" + str(args.settle_ticks)]
    if args.dedicated:
        arguments.append("-Dwormholes.crossbench.dedicated=true")
    if args.jfr:
        arguments.append("-XX:StartFlightRecording=filename=crossbench/recording.jfr,settings=profile,method-profiling=" + args.jfr_sampling
                         + ",dumponexit=true")
    return arguments + args.jvm_arg


def launch(args: argparse.Namespace) -> tuple[int, Path | None]:
    if args.loader == "fabric":
        command: list[str] = [sys.executable, str(CLIENT / "gametest.py"), "CrossingBench", "--renderer", args.renderer,
                              "--minecraft-version", args.minecraft_version, "--timeout", str(args.timeout)]
    else:
        if args.dedicated:
            raise ValueError("--dedicated is supported on Fabric only")
        command = [sys.executable, str(CLIENT / "platformtest.py"), "--platform", args.loader, "--filter", "crossing-bench",
                   "--renderer", args.renderer, "--minecraft-version", args.minecraft_version, "--timeout", str(args.timeout)]
    command += ["--jvm-arg=" + argument for argument in jvm_arguments(args)]
    evidence: Path | None = None
    with subprocess.Popen(command, cwd=ROOT, stdout=subprocess.PIPE, stderr=subprocess.STDOUT, text=True) as process:
        for line in process.stdout:
            print(line, end="", flush=True)
            match = re.match(r"Evidence: (.+)$", line.strip())
            if match:
                evidence = Path(match.group(1))
        return process.wait(), evidence


def merged(output: Path) -> dict | None:
    files: list[Path] = sorted(output.glob("crossing-bench-*.json"))
    if not files:
        return None
    documents: list[dict] = [json.loads(path.read_text()) for path in files]
    first: dict = documents[0]
    return {"labels": [document["label"] for document in documents], "loader": first["loader"], "minecraft": first["minecraft"],
            "renderer": first["renderer"], "samples": [sample for document in documents for sample in document["samples"]]}


def windows(results: dict) -> list[str]:
    lines: list[str] = []
    for sample in results["samples"]:
        label: str = sample["label"]
        crossing: int = sample["crossingEpochMs"]
        if crossing - sample["openEpochMs"] > 2 * STEADY_MARGIN_MS:
            lines.append("\t".join([label, "steady", str(sample["openEpochMs"] + STEADY_MARGIN_MS), str(crossing - STEADY_MARGIN_MS)]))
        lines.append("\t".join([label, "client-crossing", str(crossing), str(crossing + CLIENT_WINDOW_MS)]))
        lines.append("\t".join([label, "post", str(crossing), str(min(sample["endEpochMs"], crossing + POST_WINDOW_MS))]))
        cycle: dict | None = (sample.get("server") or {}).get("crossingCycle")
        if cycle is not None:
            lines.append("\t".join([label, "server-crossing", str(cycle["startEpochMs"]), str(max(cycle["endEpochMs"], cycle["startEpochMs"] + 1))]))
    return lines


def hotspots(output: Path, results: dict) -> None:
    recording: Path = output / "recording.jfr"
    if not recording.is_file():
        print("No JFR recording at " + str(recording), flush=True)
        return
    windows_file: Path = output / "windows.tsv"
    windows_file.write_text("\n".join(windows(results)) + "\n")
    java: str = str(Path(os.environ["JAVA_HOME"]) / "bin/java") if "JAVA_HOME" in os.environ else "java"
    report = subprocess.run([java, str(CLIENT / "jfr/CrossingHotspots.java"), str(recording), str(windows_file), str(output / "hotspots.json")],
                            check=True, capture_output=True, text=True)
    (output / "hotspots.txt").write_text(report.stdout)
    print("Hotspots: " + str(output / "hotspots.txt"), flush=True)


def value(sample: dict, *path: str) -> float | None:
    current: object = sample
    for key in path:
        if not isinstance(current, dict) or current.get(key) is None:
            return None
        current = current[key]
    return float(current)


COLUMNS: list[tuple[str, tuple[str, ...]]] = [
    ("srvCycle", ("server", "crossingCycle", "tickMs")),
    ("srvPkts", ("server", "crossingCycle", "packetsMs")),
    ("srvTasks", ("server", "crossingCycle", "tasksMs")),
    ("inbound", ("server", "crossingCycle", "inboundMs")),
    ("move", ("server", "moveMs")),
    ("srvMax", ("server", "postMaxBusyMs")),
    (">50", ("server", "postCyclesOver50")),
    ("whMs", ("server", "postWormholesMs")),
    ("cliX", ("clientCrossMs",)),
    ("frame0", ("client", "crossingFrameMs")),
    ("frame1", ("client", "nextFrameMs")),
    ("worst", ("client", "postWorstMs")),
    ("p99", ("client", "postP99Ms")),
    (">33", ("client", "postOver30")),
    ("readyF", ("client", "settledFrames")),
    ("readyMs", ("client", "settledMs")),
    ("retView", ("returnViewTicks",)),
]


def table(results: dict) -> str:
    rows: list[str] = ["%-22s " % "crossing" + " ".join("%8s" % name for name, _ in COLUMNS) + " %7s %9s" % ("chunks", "routedKB")]
    for sample in results["samples"]:
        cells: list[str] = []
        for _, path in COLUMNS:
            number = value(sample, *path)
            cells.append("%8s" % ("-" if number is None else ("%.2f" % number if number % 1 else "%d" % number)))
        chunks: int = sum(sample["chunkLoads"].values())
        routed: float = (sample["packets"]["routedBefore"]["bytes"] + sample["packets"]["routedAfter"]["bytes"]) / 1024.0
        rows.append("%-22s " % sample["label"] + " ".join(cells) + " %7d %9.1f" % (chunks, routed))
    return "\n".join(rows)


def aggregate(results: dict) -> dict:
    groups: dict[str, dict[str, list[float]]] = {}
    for sample in results["samples"]:
        key: str = sample["route"] + "-" + sample["direction"] + "-" + sample["phase"]
        bucket = groups.setdefault(key, {})
        for name, path in COLUMNS:
            number = value(sample, *path)
            if number is not None:
                bucket.setdefault(name, []).append(number)
    return {key: {name: round(statistics.median(numbers), 2) for name, numbers in bucket.items()} for key, bucket in groups.items()}


def compare(current: dict, baseline: dict) -> str:
    rows: list[str] = ["%-22s %-8s %10s %10s %9s" % ("group", "metric", "baseline", "current", "delta")]
    for key, metrics in current.items():
        reference = baseline.get(key)
        if reference is None:
            continue
        for name in ["srvCycle", "srvPkts", "srvTasks", "inbound", "move", "srvMax", "whMs", "cliX", "frame0", "frame1", "worst", "p99", "readyMs"]:
            if name in metrics and name in reference:
                delta = metrics[name] - reference[name]
                rows.append("%-22s %-8s %10.2f %10.2f %+9.2f" % (key, name, reference[name], metrics[name], delta))
    return "\n".join(rows)


def main() -> int:
    parser = argparse.ArgumentParser(description="Repeatable seamless-crossing benchmark for the modded client rigs")
    parser.add_argument("--loader", choices=["fabric", "neoforge", "forge"], default="fabric")
    parser.add_argument("--renderer", choices=["vanilla", "sodium"], default="vanilla")
    parser.add_argument("--minecraft-version", choices=supported_versions(), default="26.3")
    parser.add_argument("--routes", default="nether,end,same")
    parser.add_argument("--warm-trips", type=int, default=2)
    parser.add_argument("--rapid-trips", type=int, default=3)
    parser.add_argument("--settle-ticks", type=int, default=60)
    parser.add_argument("--dedicated", action="store_true")
    parser.add_argument("--jfr", action="store_true")
    parser.add_argument("--jfr-sampling", choices=["normal", "high", "max"], default="max")
    parser.add_argument("--jvm-arg", action="append", default=[])
    parser.add_argument("--timeout", type=int, default=1800)
    parser.add_argument("--compare", type=Path)
    args = parser.parse_args()
    load_before: tuple[float, float, float] = os.getloadavg()
    code, evidence = launch(args)
    load_after: tuple[float, float, float] = os.getloadavg()
    if evidence is None:
        print("The rig did not report an evidence directory", flush=True)
        return 1
    output: Path = evidence / "crossbench"
    results: dict | None = merged(output)
    if results is None:
        print("No crossing bench results in " + str(output), flush=True)
        return 1
    (output / "results.json").write_text(json.dumps(results, indent=2) + "\n")
    summary: dict = {"loader": args.loader, "renderer": args.renderer, "minecraftVersion": args.minecraft_version, "dedicated": args.dedicated,
                     "jfr": args.jfr, "passed": code == 0, "loadAverageBefore": load_before, "loadAverageAfter": load_after,
                     "medians": aggregate(results)}
    text: str = table(results)
    (output / "summary.json").write_text(json.dumps(summary, indent=2) + "\n")
    (output / "summary.txt").write_text(text + "\n")
    print(text, flush=True)
    print("Load average before %.2f %.2f %.2f, after %.2f %.2f %.2f" % (*load_before, *load_after), flush=True)
    if args.compare is not None:
        reference: dict = json.loads((args.compare / "crossbench/summary.json").read_text())
        comparison: str = compare(summary["medians"], reference["medians"])
        (output / "comparison.txt").write_text(comparison + "\n")
        print(comparison, flush=True)
    if args.jfr:
        hotspots(output, results)
    print("Crossing bench: " + str(output), flush=True)
    return code


if __name__ == "__main__":
    raise SystemExit(main())
