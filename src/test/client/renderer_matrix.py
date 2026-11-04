"""Runs one client fixture against every renderer combination this machine can assemble, one hidden client at a time."""

from __future__ import annotations

import argparse
import json
import re
import subprocess
import sys
import time
from dataclasses import dataclass, field
from pathlib import Path

ROOT = Path(__file__).resolve().parents[3]
GAMETEST = Path(__file__).resolve().with_name("gametest.py")
FIXTURE_PACK = Path(__file__).resolve().with_name("fixtures") / "arrival-shaders"
PATH_LINE = "WORMHOLES_RENDER_PATH"


@dataclass(frozen=True)
class Combination:
    name: str
    arguments: tuple[str, ...]
    requirement: str = ""


@dataclass
class Outcome:
    combination: Combination
    status: str
    path: str = ""
    evidence: str = ""
    seconds: float = 0.0
    detail: str = ""
    fields: dict[str, str] = field(default_factory=dict)


def combinations(args: argparse.Namespace) -> list[Combination]:
    sys.path.insert(0, str(GAMETEST.parent))
    from gametest import cached_mod

    pack: Path = args.shader_pack.resolve() if args.shader_pack is not None else FIXTURE_PACK
    dh_pack_requirement = "" if args.shader_pack is not None else "pass --shader-pack with a pack that ships Distant Horizons stages"
    distant_horizons: Path | None = args.distant_horizons.resolve() if args.distant_horizons is not None else None
    dh_requirement = ""
    if distant_horizons is None:
        try:
            distant_horizons = cached_mod("distanthorizons", args.minecraft_version)
        except FileNotFoundError as missing:
            dh_requirement = str(missing)
    metal: Path | None = args.metal.resolve() if args.metal is not None else None
    selected: list[Combination] = [
        Combination("vanilla", ("--renderer", "vanilla")),
        Combination("sodium", ("--renderer", "sodium")),
        Combination("sodium+iris", ("--renderer", "sodium", "--iris")),
        Combination("sodium+iris+pack", ("--renderer", "sodium", "--shader-pack", str(pack))),
        Combination("sodium+dh", ("--renderer", "sodium", "--distant-horizons", str(distant_horizons)) if distant_horizons else (), dh_requirement),
        Combination("sodium+iris+pack+dh", ("--renderer", "sodium", "--shader-pack", str(pack), "--distant-horizons", str(distant_horizons))
                    if distant_horizons else (), dh_requirement or dh_pack_requirement),
        Combination("vanilla+metal", ("--renderer", "vanilla", "--metal", str(metal)) if metal else (), "" if metal else "pass --metal <metal-shaders.jar>"),
        Combination("vanilla+vulkan", ("--renderer", "vanilla", "--graphics-backend", "vulkan")),
    ]
    if args.only:
        wanted = set(args.only.split(","))
        unknown = wanted - {combination.name for combination in selected}
        if unknown:
            raise ValueError("Unknown combinations: " + ", ".join(sorted(unknown)))
        selected = [combination for combination in selected if combination.name in wanted]
    return selected


def run(combination: Combination, args: argparse.Namespace, log: Path) -> Outcome:
    if combination.requirement:
        return Outcome(combination, "SKIPPED", detail=combination.requirement)
    command = [sys.executable, str(GAMETEST), args.test, "--minecraft-version", args.minecraft_version, "--timeout", str(args.timeout),
               *combination.arguments, *("--jvm-arg=" + value for value in args.jvm_arg)]
    started = time.monotonic()
    with log.open("w") as output:
        completed = subprocess.run(command, stdout=output, stderr=subprocess.STDOUT, cwd=ROOT, check=False)
    text = log.read_text(errors="replace")
    evidence_match = re.search(r"^Evidence: (.+)$", text, re.M)
    outcome = Outcome(combination, "PASS" if completed.returncode == 0 else "FAIL", seconds=time.monotonic() - started,
                      evidence=evidence_match.group(1).strip() if evidence_match else "")
    runtime = Path(outcome.evidence) / "runtime.log" if outcome.evidence else None
    if runtime is not None and runtime.is_file():
        for line in runtime.read_text(errors="replace").splitlines():
            if PATH_LINE in line:
                outcome.fields = fields(line.split(PATH_LINE, 1)[1])
                outcome.path = outcome.fields.get("path", "")
    if outcome.status == "FAIL":
        tail = [line for line in text.splitlines() if line.strip()][-3:]
        outcome.detail = " | ".join(tail)[-400:]
    return outcome


def fields(tail: str) -> dict[str, str]:
    """The key=value pairs after the path marker; reasons come last and run to the end of the line, spaces included."""
    head, separator, reasons = tail.partition(" reasons=")
    values: dict[str, str] = dict(re.findall(r"(\w+)=(\S+)", head))
    if separator:
        values["reasons"] = reasons.strip()
    return values


def table(outcomes: list[Outcome]) -> str:
    rows = ["| combination | result | path | device | backends | reasons | seconds | detail |", "|---|---|---|---|---|---|---|---|"]
    for outcome in outcomes:
        rows.append("| " + " | ".join([outcome.combination.name, outcome.status, outcome.path or "-", outcome.fields.get("device", "-"),
                                       outcome.fields.get("backends", "-"), outcome.fields.get("reasons", "-").replace("|", "/"),
                                       f"{outcome.seconds:.0f}" if outcome.seconds else "-", outcome.detail.replace("|", "/") or "-"]) + " |")
    return "\n".join(rows)


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--test", default="MeshBackendView")
    parser.add_argument("--minecraft-version", default="26.3")
    parser.add_argument("--metal", type=Path)
    parser.add_argument("--distant-horizons", type=Path)
    parser.add_argument("--shader-pack", type=Path)
    parser.add_argument("--only", help="comma separated combination names")
    parser.add_argument("--timeout", type=int, default=900)
    parser.add_argument("--jvm-arg", action="append", default=[])
    args = parser.parse_args()
    stamp = time.strftime("%Y%m%d-%H%M%S")
    output = ROOT / ".qa/native-client" / ("renderer-matrix-" + stamp)
    output.mkdir(parents=True)
    outcomes: list[Outcome] = []
    for combination in combinations(args):
        print(combination.name + ": " + ("skipped, " + combination.requirement if combination.requirement else "running"), flush=True)
        outcome = run(combination, args, output / (combination.name.replace("+", "-") + ".txt"))
        outcomes.append(outcome)
        print(combination.name + ": " + outcome.status + (" via " + outcome.path if outcome.path else ""), flush=True)
    summary = table(outcomes)
    print(summary)
    (output / "matrix.md").write_text(summary + "\n")
    (output / "matrix.json").write_text(json.dumps([{"combination": outcome.combination.name, "arguments": list(outcome.combination.arguments),
        "status": outcome.status, "path": outcome.path, "fields": outcome.fields, "evidence": outcome.evidence, "seconds": round(outcome.seconds, 1),
        "detail": outcome.detail} for outcome in outcomes], indent=2) + "\n")
    print("Matrix: " + str(output))
    return 0 if all(outcome.status != "FAIL" for outcome in outcomes) else 1


if __name__ == "__main__":
    sys.exit(main())
