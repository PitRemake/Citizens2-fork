#!/usr/bin/env python3
"""Build the pinned 1.8.8 Citizens compatibility fork from its source patch.

The 2022 upstream reactor requires several historical Maven repositories. We
compile the compatibility sources against the pinned upstream build and server
API, then verify the replacements in the final distributable JAR.
"""
import argparse
import hashlib
import os
import pathlib
import re
import subprocess
import struct
import tempfile
import urllib.request
import zipfile

ROOT = pathlib.Path(__file__).resolve().parents[1]
SOURCES = {
    "net/citizensnpcs/nms/v1_8_R3/util/PitControlFrame.class": ROOT / "v1_8_R3/src/main/java/net/citizensnpcs/nms/v1_8_R3/util/PitControlFrame.java",
    "net/citizensnpcs/util/Util.class": ROOT / "main/src/main/java/net/citizensnpcs/util/Util.java",
    "net/citizensnpcs/nms/v1_8_R3/entity/EntityHumanNPC.class": ROOT / "v1_8_R3/src/main/java/net/citizensnpcs/nms/v1_8_R3/entity/EntityHumanNPC.java",
    "net/citizensnpcs/nms/v1_8_R3/util/PlayerlistTrackerEntry.class": ROOT / "v1_8_R3/src/main/java/net/citizensnpcs/nms/v1_8_R3/util/PlayerlistTrackerEntry.java",
    "net/citizensnpcs/nms/v1_8_R3/entity/HumanController.class": ROOT / "v1_8_R3/src/main/java/net/citizensnpcs/nms/v1_8_R3/entity/HumanController.java",
    "net/citizensnpcs/npc/skin/SkinPacketTracker.class": ROOT / "main/src/main/java/net/citizensnpcs/npc/skin/SkinPacketTracker.java",
    "net/citizensnpcs/nms/v1_8_R3/network/EmptyNetHandler.class": ROOT / "v1_8_R3/src/main/java/net/citizensnpcs/nms/v1_8_R3/network/EmptyNetHandler.java",
    "net/citizensnpcs/nms/v1_8_R3/util/PitSkinProfiles.class": ROOT / "v1_8_R3/src/main/java/net/citizensnpcs/nms/v1_8_R3/util/PitSkinProfiles.java",
    "net/citizensnpcs/npc/skin/SkinUpdateTracker.class": ROOT / "main/src/main/java/net/citizensnpcs/npc/skin/SkinUpdateTracker.java",
    "net/citizensnpcs/npc/skin/Skin.class": ROOT / "main/src/main/java/net/citizensnpcs/npc/skin/Skin.java",
    "net/citizensnpcs/npc/profile/ProfileFetchThread.class": ROOT / "main/src/main/java/net/citizensnpcs/npc/profile/ProfileFetchThread.java",
    "net/citizensnpcs/npc/profile/ProfileRequest.class": ROOT / "main/src/main/java/net/citizensnpcs/npc/profile/ProfileRequest.java",
    "net/citizensnpcs/api/trait/trait/Equipment.class": ROOT / "compat-api/src/main/java/net/citizensnpcs/api/trait/trait/Equipment.java",
}
BASE_SHA256 = "54e5ef9db95a6a6f68a2bbbb1a3eeb2770087afd8ad880218855292a4618fda7"
UPSTREAM_VERSION = "2.0.30-SNAPSHOT (build 2803)"
FORK_VERSION = "2.0.30-PitRemake.19"
EQUIPMENT_CLASS = "net/citizensnpcs/api/trait/trait/Equipment.class"
EQUIPMENT_BASE_SHA256 = "87df8502faec6b0eca8afb27519396c26688bed6e862cb4e1dce83359e69f39b"
EQUIPMENT_API_NAME = "spigot-api-1.13.2-R0.1-20190423.030901-214.jar"
EQUIPMENT_API_SHA256 = "9103f5e2c8789c7ac37c3b50b4cdfe2fca176f6400928c5228af5749138e23b2"
EQUIPMENT_API_URL = ("https://hub.spigotmc.org/nexus/content/repositories/snapshots/"
                     "org/spigotmc/spigot-api/1.13.2-R0.1-SNAPSHOT/" + EQUIPMENT_API_NAME)
QUEUE_HANDLER = "net/citizensnpcs/nms/v1_8_R3/network/EmptyNetHandler.class"
QUEUE_DESCRIPTOR = "(Lnet/minecraft/server/v1_8_R3/Packet;)V"


def verify_queue_override(data):
    """Check the JVM descriptor and exact no-op code, not a version/string marker."""
    offset = 0

    def take(size):
        nonlocal offset
        value = data[offset:offset + size]
        if len(value) != size:
            raise ValueError("Truncated NPC connection class")
        offset += size
        return value

    def u2():
        return struct.unpack(">H", take(2))[0]

    def u4():
        return struct.unpack(">I", take(4))[0]

    if u4() != 0xCAFEBABE:
        raise ValueError("Invalid NPC connection class")
    take(4)  # minor/major
    pool = [None] * u2()
    index = 1
    while index < len(pool):
        tag = take(1)[0]
        if tag == 1:
            pool[index] = take(u2()).decode("utf-8", errors="replace")
        elif tag in (7, 8, 16, 19, 20):
            pool[index] = u2()
        elif tag in (3, 4, 9, 10, 11, 12, 17, 18):
            take(4)
        elif tag in (5, 6):
            take(8)
            index += 1
        elif tag == 15:
            take(3)
        else:
            raise ValueError("Unsupported NPC connection constant pool tag")
        index += 1
    take(2)  # access
    if pool[pool[u2()]] != QUEUE_HANDLER[:-6]:
        raise ValueError("Wrong NPC connection class identity")
    if pool[pool[u2()]] != "net/minecraft/server/v1_8_R3/PlayerConnection":
        raise ValueError("NPC connection does not extend PlayerConnection")
    take(u2() * 2)  # interfaces

    def attributes():
        return [(pool[u2()], take(u4())) for _ in range(u2())]

    for _ in range(u2()):  # fields
        take(6)
        attributes()
    matches = []
    for _ in range(u2()):
        access, name, descriptor = u2(), pool[u2()], pool[u2()]
        attrs = attributes()
        if name == "queuePacket" and descriptor == QUEUE_DESCRIPTOR:
            matches.append((access, attrs))
    if len(matches) != 1:
        raise ValueError("Final Citizens artifact lacks the exact NPC queuePacket override")
    access, attrs = matches[0]
    if not access & 0x0001 or access & (0x0008 | 0x0100 | 0x0400):
        raise ValueError("NPC queue override must be public, concrete and non-static")
    codes = [value for name, value in attrs if name == "Code"]
    # max_stack=0, max_locals=2, code_length=1, RETURN, no exception handlers.
    if len(codes) != 1 or codes[0][:11] != b"\x00\x00\x00\x02\x00\x00\x00\x01\xb1\x00\x00":
        raise ValueError("NPC queue override must contain only RETURN")


def verify_artifact(path, replacements=None):
    with zipfile.ZipFile(path) as result:
        if result.namelist().count(QUEUE_HANDLER) != 1:
            raise ValueError("Citizens artifact must contain one NPC connection class")
        verify_queue_override(result.read(QUEUE_HANDLER))
        for entry, replacement in (replacements or {}).items():
            if result.read(entry) != replacement:
                raise ValueError("Packaged compatibility class differs from compiler output: " + entry)
        if ("version: " + FORK_VERSION).encode() not in result.read("plugin.yml"):
            raise ValueError("Citizens artifact has the wrong fork version")


def digest(path):
    return hashlib.sha256(path.read_bytes()).hexdigest()


def equipment_compile_api(path=None):
    """Compile-only API for upstream's dormant offhand/1.13 branches; never shipped."""
    explicit = path is not None
    path = pathlib.Path(path) if explicit else ROOT / "build/dependencies" / EQUIPMENT_API_NAME
    if not path.is_file():
        if explicit:
            raise ValueError("Missing explicitly supplied Equipment compile API")
        with urllib.request.urlopen(EQUIPMENT_API_URL, timeout=30) as response:
            data = response.read()
        if hashlib.sha256(data).hexdigest() != EQUIPMENT_API_SHA256:
            raise ValueError("Downloaded Equipment compile API hash does not match")
        path.parent.mkdir(parents=True, exist_ok=True)
        with tempfile.NamedTemporaryFile(dir=path.parent, delete=False) as staged:
            staged.write(data)
            temporary = pathlib.Path(staged.name)
        try:
            os.replace(temporary, path)
        finally:
            if temporary.exists():
                temporary.unlink()
    if digest(path) != EQUIPMENT_API_SHA256:
        raise ValueError("Equipment compile API hash does not match")
    return path


def build(base, server, javac, output, equipment_api=None):
    if digest(base) != BASE_SHA256:
        raise ValueError("Citizens base JAR does not match the pinned build 2803 hash")
    if not server.is_file():
        raise ValueError("Missing 1.8.8 server API JAR")
    with zipfile.ZipFile(base) as original:
        if hashlib.sha256(original.read(EQUIPMENT_CLASS)).hexdigest() != EQUIPMENT_BASE_SHA256:
            raise ValueError("Pinned Equipment trait class changed")
    equipment_api = equipment_compile_api(equipment_api)
    with tempfile.TemporaryDirectory(prefix="citizens-pitremake-") as temporary:
        classes = pathlib.Path(temporary)
        subprocess.run([str(javac), "-source", "8", "-target", "8", "-cp",
                        str(base) + os.pathsep + str(server), "-d", str(classes),
                        *[str(source) for name, source in SOURCES.items() if name != EQUIPMENT_CLASS]], check=True)
        # This one unchanged cross-version API class contains dormant post-1.8
        # methods. Compile it separately; the actual server and all runtime tests
        # remain 1.8.8, and no classes from this API are packaged.
        subprocess.run([str(javac), "-source", "8", "-target", "8", "-cp",
                        os.pathsep.join(map(str, (equipment_api, base, server))), "-d", str(classes),
                        str(SOURCES[EQUIPMENT_CLASS])], check=True)
        # Anonymous delayed-packet tasks are compiled as companion classes.
        # Ship them with their outer classes so their synthetic accessors match.
        replacements = {item.relative_to(classes).as_posix(): item.read_bytes()
                        for item in classes.rglob("*.class")}
        if not set(SOURCES).issubset(replacements):
            raise ValueError("Compiler did not produce every patched outer class")
        output.parent.mkdir(parents=True, exist_ok=True)
        staged = classes / "Citizens.jar"
        with zipfile.ZipFile(base) as original, zipfile.ZipFile(staged, "w") as target:
            original_names = set(original.namelist())
            new_classes = {"net/citizensnpcs/nms/v1_8_R3/util/PitSkinProfiles.class",
                           "net/citizensnpcs/nms/v1_8_R3/util/PitControlFrame.class"}
            if not (set(SOURCES) - new_classes).issubset(original_names):
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
            # A patch may add an anonymous helper class that was absent from
            # build 2803; ship it alongside its patched outer class.
            for name, data in replacements.items():
                if name not in original_names:
                    target.writestr(name, data)
        verify_artifact(staged, replacements)
        # Publish from the destination directory. On Windows, moving the JAR
        # directly out of a private TEMP directory preserves its restrictive
        # ACL and prevents the server's user from reading the resulting file.
        with tempfile.NamedTemporaryFile(dir=output.parent, prefix="." + output.name + "-",
                                         suffix=".tmp", delete=False) as publish:
            publish.write(staged.read_bytes())
            candidate = pathlib.Path(publish.name)
        try:
            verify_artifact(candidate, replacements)
            os.replace(candidate, output)
        finally:
            if candidate.exists():
                candidate.unlink()
    print(str(output))
    print(digest(output))


if __name__ == "__main__":
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--verify-artifact", type=pathlib.Path)
    parser.add_argument("--base", type=pathlib.Path)
    parser.add_argument("--server", type=pathlib.Path)
    parser.add_argument("--javac", type=pathlib.Path)
    parser.add_argument("--output", type=pathlib.Path, default=ROOT / "build/libs/Citizens-PitRemake-2.0.30.jar")
    parser.add_argument("--equipment-api", type=pathlib.Path,
                        help="Pinned compile-only API; default uses/downloads checksum-verified build/dependencies cache")
    args = parser.parse_args()
    if args.verify_artifact:
        verify_artifact(args.verify_artifact.resolve())
        print("Citizens NPC queue artifact gate passed")
    else:
        if not all((args.base, args.server, args.javac)):
            parser.error("building requires --base, --server and --javac")
        build(args.base.resolve(), args.server.resolve(), args.javac.resolve(), args.output.resolve(),
              args.equipment_api.resolve() if args.equipment_api else None)
