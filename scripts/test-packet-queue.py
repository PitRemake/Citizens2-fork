#!/usr/bin/env python3
"""Gate the final artifact, then execute offline real-WindSpigot queue regressions."""
import argparse
import os
import pathlib
import subprocess
import tempfile

ROOT = pathlib.Path(__file__).resolve().parent


def run(artifact, old_artifact, server, javac, java, baseline=None, libraries=None):
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
                        str(ROOT / "tests/NpcBotOptimizationCompatibility.java"),
                        str(ROOT / "tests/NpcSkinVisibilityCompatibility.java"),
                        str(ROOT / "tests/NpcSkinCpuRecoveryCompatibility.java"),
                        str(ROOT / "tests/NpcUtilityCompatibility.java")], check=True)
        subprocess.run([str(java), "-cp", str(classes) + os.pathsep + classpath,
                        "NpcUtilityCompatibility"], cwd=classes, check=True)
        subprocess.run([str(java), "-cp", str(classes) + os.pathsep + classpath,
                        "NpcPacketQueueCompatibility"], cwd=classes, check=True)
        subprocess.run([str(java), "-cp", str(classes) + os.pathsep + classpath,
                        "NpcBotOptimizationCompatibility"], cwd=classes, check=True)
        subprocess.run([str(java), "-cp", str(classes) + os.pathsep + classpath,
                        "NpcSkinVisibilityCompatibility"], cwd=classes, check=True)
        if libraries:
            dependencies = sorted(libraries.rglob("*-relocated.jar"))
            if not dependencies:
                raise ValueError("Missing Citizens runtime libraries for CPU/skin recovery checks")
            extra = os.pathsep.join(str(path) for path in dependencies)
            subprocess.run([str(java), "-cp", str(classes) + os.pathsep + classpath + os.pathsep + extra,
                            "NpcSkinCpuRecoveryCompatibility"], cwd=classes, check=True)
            if baseline:
                subprocess.run([str(java), "-cp", str(classes) + os.pathsep + str(baseline) + os.pathsep + str(server) + os.pathsep + extra,
                                "NpcSkinCpuRecoveryCompatibility", "baseline"], cwd=classes, check=True)
        else:
            print("CPU/skin recovery checks not run: supply --libraries with Citizens' cached runtime lib directory")


if __name__ == "__main__":
    parser = argparse.ArgumentParser(description=__doc__)
    for name in ("artifact", "old-artifact", "server", "javac", "java"):
        parser.add_argument("--" + name, type=pathlib.Path, required=True)
    parser.add_argument("--baseline-artifact", type=pathlib.Path)
    parser.add_argument("--libraries", type=pathlib.Path)
    args = parser.parse_args()
    run(*(value.resolve() for value in (args.artifact, args.old_artifact, args.server,
                                        args.javac, args.java)), baseline=args.baseline_artifact.resolve() if args.baseline_artifact else None,
        libraries=args.libraries.resolve() if args.libraries else None)
