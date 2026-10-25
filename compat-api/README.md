# Pinned CitizensAPI Equipment source

`src/main/java/net/citizensnpcs/api/trait/trait/Equipment.java` is the historical
CitizensAPI source from commit `baee9f0dd62cadf1a23d8a3b86e3a6fe88b24c4a`:

https://github.com/CitizensDev/CitizensAPI/blob/baee9f0dd62cadf1a23d8a3b86e3a6fe88b24c4a/src/main/java/net/citizensnpcs/api/trait/trait/Equipment.java

It remains under the project's Open Software License 3.0. Unmodified source
SHA-256: `51a00bd4ae2baa30033d06f424237b1ce1a80889a1278787005fa642323da9a6`.
The packaged build-2803 Equipment class is pinned separately by the builder:
`87df8502faec6b0eca8afb27519396c26688bed6e862cb4e1dce83359e69f39b`.
Its disassembled methods match the historical source; javac constant-pool
ordering changes some `ldc` instruction widths, not behavior.

The local change adds detached snapshot reuse inside `run()` for marked Pit
player bots. Constructors, public API, slot mapping, load/save, spawn and set
behavior remain upstream. Item comparison is limited to native Wind
CraftItemStack pairs, whose equality compares count/type/durability/NBT without
creating ItemMeta. Other item implementations retain the original clone path.

Compile-only API:

- Official Spigot `spigot-api-1.13.2-R0.1-20190423.030901-214.jar`.
- SHA-256 `9103f5e2c8789c7ac37c3b50b4cdfe2fca176f6400928c5228af5749138e23b2`.
- URL is pinned in `scripts/build-pitremake.py`; downloaded bytes and cached
  bytes must match before use. An explicit missing or wrong-hash file fails
  without a download.
- This jar only compiles Equipment's unchanged cross-version methods. The
  final plugin contains no Bukkit API classes. Actual compatibility tests run
  on the deployed Wind 1.8.8 core and Java 17, with Java 8 compilation.

`NpcEquipmentSnapshotCompatibility` exercises the packaged trait, actual native
item/NBT comparisons, exposed-array mutation and lifecycle behavior. Its
allocation check compares marked versus ordinary polling on the same fixture;
it does not claim a particular live-server tick-time improvement.
