# NPC tracker queue packaging fix

Pit-3's September 28 heap dump contained 44,016,871 tracker packets in 2,821 Citizens fake-player connections. `EmptyNetHandler.queuePacket(Packet)` already prevented queue growth in source, but `scripts/build-pitremake.py` omitted that source from its compiled replacements. The September 28 distributable therefore retained the upstream handler despite reporting a fixed source commit.

The compatibility builder now compiles and packages `EmptyNetHandler.java` with its other patches. The distributable version is **2.0.30-PitRemake.4**. This changes packaging; the existing NPC connection no-op remains unchanged.

Before publishing its output, the builder checks the actual packaged class: exact class identity and `PlayerConnection` superclass, exactly one public concrete instance `queuePacket(net.minecraft.server.v1_8_R3.Packet): void`, and code containing only `RETURN`. It also checks every replacement against compiler output, rejects duplicate handler entries, and checks the fork version. Checks use explicit exceptions and remain active with Python optimization. An invalid build does not replace the previous output.

Run the standalone artifact gate with:

```text
python scripts/build-pitremake.py --verify-artifact /path/to/Citizens.jar
```

Run all artifact and native queue regressions with:

```text
python scripts/test-packet-queue.py --artifact /path/to/rebuilt/Citizens.jar --old-artifact /path/to/published/leaking/Citizens.jar --server /path/to/WindSpigot-2.1.4.jar --javac /path/to/java8/bin/javac --java /path/to/java17/bin/java
```

The builder retains its existing `--base`, `--server`, `--javac`, and `--output` interface. Build against the pinned upstream Citizens build 2803 and Java 8-compatible server API. The queue regression must use the actual WindSpigot runtime JAR and Java 17; a vanilla server without the tracker queue methods fails rather than skipping.

## Validation

- The published Citizens JAR failed the native tracker test on the very first NPC update.
- Nine artifact regressions passed, including the old artifact, forged new version with old bytecode, wrong method name/descriptor, non-no-op code, duplicate class, truncated class, and compiler-output mismatch.
- The rebuilt distributable passed the exact bytecode gate.
- Offline fixtures loaded the packaged Citizens `EmptyNetHandler` and `EntityHumanNPC` and dispatched packets through real WindSpigot `EntityTrackerEntry.broadcastInternal` and `broadcastIncludingSelfInternal`. Velocity, attribute and metadata packets exercised 200,000 NPC queue attempts with zero retained packets and no periodic clearing.
- An ordinary-player recording connection inherits the real WindSpigot queue and flush methods; only its final network send is recorded. All 100,000 queued packets flushed in order, the second flush sent no duplicates, and 100,000 direct tracker broadcasts were preserved.

These fixtures allocate offline NMS objects without starting a server or using real player data. They verify native method dispatch and queue behavior; they do not claim a signed-in client test or production memory observation.

The local release is under `releases/citizens-queue-packaging-20260929` in the workspace, with the JAR, before/after logs and verification receipt. Deployment remains separately authorized. Replacing a JAR on disk cannot release packets already retained by an existing running connection; graceful restart is required when installing this fix. No server restart, install, commit or push was performed in this batch.
