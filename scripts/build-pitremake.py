#!/usr/bin/env python3
"""Build the pinned 1.8.8 Citizens compatibility fork from its source patch.

The 2022 upstream reactor requires several historical Maven repositories. We
compile the changed v1_8_R3 source against the exact installed upstream build
and WindSpigot API, then replace only that class in the distributable JAR.
"""
import argparse
import hashlib
import os
import pathlib
import re
import subprocess
import tempfile
import zipfile

ROOT = pathlib.Path(__file__).resolve().parents[1]
SOURCES = {
    "net/citizensnpcs/nms/v1_8_R3/util/PlayerlistTrackerEntry.class": ROOT / "v1_8_R3/src/main/java/net/citizensnpcs/nms/v1_8_R3/util/PlayerlistTrackerEntry.java",
    "net/citizensnpcs/nms/v1_8_R3/entity/HumanController.class": ROOT / "v1_8_R3/src/main/java/net/citizensnpcs/nms/v1_8_R3/entity/HumanController.java",
}
BASE_SHA256 = "54e5ef9db95a6a6f68a2bbbb1a3eeb2770087afd8ad880218855292a4618fda7"
UPSTREAM_VERSION = "2.0.30-SNAPSHOT (build 2803)"
FORK_VERSION = "2.0.30-PitRemake.1"


def digest(path):
    return hashlib.sha256(path.read_bytes()).hexdigest()


def build(base, server, javac, output):
    if digest(base) != BASE_SHA256:
        raise ValueError("Citizens base JAR does not match the pinned build 2803 hash")
    if not server.is_file():
        raise ValueError("Missing 1.8.8 server API JAR")
    with tempfile.TemporaryDirectory(prefix="citizens-pitremake-") as temporary:
        classes = pathlib.Path(temporary)
        subprocess.run([str(javac), "-source", "8", "-target", "8", "-cp",
                        str(base) + os.pathsep + str(server), "-d", str(classes),
                        *[str(source) for source in SOURCES.values()]], check=True)
        # Anonymous delayed-packet tasks are compiled as companion classes.
        # Ship them with their outer classes so their synthetic accessors match.
        replacements = {item.relative_to(classes).as_posix(): item.read_bytes()
                        for item in classes.rglob("*.class")}
        if not set(SOURCES).issubset(replacements):
            raise ValueError("Compiler did not produce every patched outer class")
        output.parent.mkdir(parents=True, exist_ok=True)
        with zipfile.ZipFile(base) as original, zipfile.ZipFile(output, "w") as target:
            if not set(replacements).issubset(original.namelist()):
                raise ValueError("Pinned Citizens build is missing a patched class")
            for item in original.infolist():
                data = replacements.get(item.filename, original.read(item.filename))
                if item.filename == "plugin.yml":
                    text = data.decode("utf-8")
                    text, count = re.subn(r"(?m)^version: " + re.escape(UPSTREAM_VERSION) + r"\r?$",
                                          "version: " + FORK_VERSION, text)
                    if count != 1:
                        raise ValueError("Pinned Citizens plugin version changed")
                    data = text.encode("utf-8")
                target.writestr(item, data)
    with zipfile.ZipFile(output) as result:
        for entry, replacement in replacements.items():
            assert result.read(entry) == replacement
        assert ("version: " + FORK_VERSION).encode() in result.read("plugin.yml")
    print(str(output))
    print(digest(output))


if __name__ == "__main__":
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--base", type=pathlib.Path, required=True)
    parser.add_argument("--server", type=pathlib.Path, required=True)
    parser.add_argument("--javac", type=pathlib.Path, required=True)
    parser.add_argument("--output", type=pathlib.Path, default=ROOT / "build/libs/Citizens-PitRemake-2.0.30.jar")
    args = parser.parse_args()
    build(args.base.resolve(), args.server.resolve(), args.javac.resolve(), args.output.resolve())
