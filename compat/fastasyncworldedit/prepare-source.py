#!/usr/bin/env python3

import argparse
import hashlib
import pathlib
import subprocess
import tarfile
import tempfile

COMMIT = "9e5e49efe4f48ab6418831d241113e3f3b8d1691"
ARCHIVE_SHA256 = "1c2feda3a660097c84e63f47231cec601c58ac96f2b0e5fff5b4d2665229b8c1"
SOURCE_DATE = "26.10.09"


def main() -> None:
    parser = argparse.ArgumentParser(description="Prepare pinned FAWE sources with applied-change and lifecycle patches.")
    parser.add_argument("destination", type=pathlib.Path)
    arguments = parser.parse_args()
    destination = arguments.destination.expanduser().resolve()
    patches = pathlib.Path(__file__).resolve().parent
    repository = patches.parent.parent
    if destination.is_relative_to(repository):
        parser.error("FAWE source and build outputs must be outside the Wormholes repository")
    if destination.exists():
        parser.error("destination must not exist")
    destination.parent.mkdir(parents=True, exist_ok=True)
    with tempfile.TemporaryDirectory(prefix="fawe-source-", dir=destination.parent) as temporary:
        temporary_path = pathlib.Path(temporary)
        archive = temporary_path / "source.tar.gz"
        subprocess.run([
            "curl", "--fail", "--location", "--silent", "--show-error",
            f"https://codeload.github.com/IntellectualSites/FastAsyncWorldEdit/tar.gz/{COMMIT}",
            "--output", str(archive),
        ], check=True)
        if hashlib.sha256(archive.read_bytes()).hexdigest() != ARCHIVE_SHA256:
            raise RuntimeError("FAWE source archive checksum does not match the pinned commit")
        with tarfile.open(archive, "r:gz") as source:
            source.extractall(temporary_path, filter="data")
        checkout = temporary_path / f"FastAsyncWorldEdit-{COMMIT}"
        for patch in sorted(patches.glob("*.patch")):
            subprocess.run(["patch", "--batch", "--forward", "--dry-run", "-p1", "-i", str(patch)],
                           cwd=checkout, check=True)
            subprocess.run(["patch", "--batch", "--forward", "-p1", "-i", str(patch)],
                           cwd=checkout, check=True)
        checkout.rename(destination)
    print(destination)
    print(f"Build metadata: -PsourceCommit={COMMIT} -PsourceDate={SOURCE_DATE}")


if __name__ == "__main__":
    main()
