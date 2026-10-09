#!/usr/bin/env python3
"""Exercise both native NPC ticker entry points without starting a server."""
import argparse
import os
import pathlib
import subprocess
import tempfile


def run(artifact, server, javac, java, libraries=None):
    root = pathlib.Path(__file__).resolve().parents[1]
    source = root / "v1_8_R3/src/test/java/net/citizensnpcs/nms/v1_8_R3/entity/PitHurtClockCompatibility.java"
    dependencies = [artifact, server]
    if libraries:
        dependencies.extend(sorted(libraries.rglob("*-relocated.jar")))
    classpath = os.pathsep.join(str(path) for path in dependencies)
    with tempfile.TemporaryDirectory(prefix="citizens-hurt-clock-") as temporary:
        classes = pathlib.Path(temporary)
        subprocess.run([str(javac), "-source", "8", "-target", "8", "-cp", classpath,
                        "-d", str(classes), str(source)], check=True)
        subprocess.run([str(java), "-Xmx256m", "-cp", str(classes) + os.pathsep + classpath,
                        "net.citizensnpcs.nms.v1_8_R3.entity.PitHurtClockCompatibility"], cwd=classes, check=True)


if __name__ == "__main__":
    parser = argparse.ArgumentParser(description=__doc__)
    for name in ("artifact", "server", "javac", "java"):
        parser.add_argument("--" + name, type=pathlib.Path, required=True)
    parser.add_argument("--libraries", type=pathlib.Path)
    args = parser.parse_args()
    run(*(value.resolve() for value in (args.artifact, args.server, args.javac, args.java)),
        libraries=args.libraries.resolve() if args.libraries else None)
