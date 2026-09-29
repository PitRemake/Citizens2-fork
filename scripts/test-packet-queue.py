#!/usr/bin/env python3
"""Gate the final artifact, then execute offline real-WindSpigot queue regressions."""
import argparse
import os
import pathlib
import subprocess
import tempfile

ROOT = pathlib.Path(__file__).resolve().parent


def run(artifact, old_artifact, server, javac, java):
    import sys
    subprocess.run([sys.executable, str(ROOT / "build-pitremake.py"),
                    "--verify-artifact", str(artifact)], check=True)
    env = dict(os.environ, CITIZENS_TEST_ARTIFACT=str(artifact),
               CITIZENS_OLD_ARTIFACT=str(old_artifact))
    subprocess.run([sys.executable, "-m", "unittest", "discover", "-s",
                    str(ROOT / "tests"), "-p", "test_*.py", "-v"], env=env, check=True)
    with tempfile.TemporaryDirectory(prefix="citizens-wind-queues-") as temporary:
        classes = pathlib.Path(temporary)
        classpath = str(artifact) + os.pathsep + str(server)
        subprocess.run([str(javac), "-source", "8", "-target", "8", "-cp", classpath,
                        "-d", str(classes), str(ROOT / "tests/NpcPacketQueueCompatibility.java"),
                        str(ROOT / "tests/NpcBotOptimizationCompatibility.java")], check=True)
        subprocess.run([str(java), "-cp", str(classes) + os.pathsep + classpath,
                        "NpcPacketQueueCompatibility"], cwd=classes, check=True)
        subprocess.run([str(java), "-cp", str(classes) + os.pathsep + classpath,
                        "NpcBotOptimizationCompatibility"], cwd=classes, check=True)


if __name__ == "__main__":
    parser = argparse.ArgumentParser(description=__doc__)
    for name in ("artifact", "old-artifact", "server", "javac", "java"):
        parser.add_argument("--" + name, type=pathlib.Path, required=True)
    args = parser.parse_args()
    run(*(value.resolve() for value in (args.artifact, args.old_artifact, args.server,
                                        args.javac, args.java)))
