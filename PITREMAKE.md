# PitRemake Citizens 1.8.8 fork

This fork starts from upstream CitizensDev/Citizens2 commit
`5254f65945fa8e8dd2494e807d8a14992a30d319`, the source revision nearest
the installed Citizens 2.0.30 build 2803. The complete upstream source and
Open Software License 3.0 are retained.

WindSpigot 2.1.4 can update viewers of one NPC's tracker entry concurrently.
The original `PlayerlistTrackerEntry.lastUpdatedPlayer` field was shared between
those calls. The NPC's data watcher calls back into `updateLastPlayer()` while
the spawn packet is built; concurrent calls could bind its temporary tab
profile to the wrong viewer. This fork keeps that viewer in a thread-local slot
for the duration of `updatePlayer()` and clears it in `finally`.

`scripts/build-pitremake.py` compiles the changed upstream source against the
SHA-256-pinned official build 2803 JAR and the 1.8.8 server API. It replaces
the 1.8.8 tracker and human NPC controller classes and marks the plugin version
`2.0.30-PitRemake.9`. The full historical
Maven reactor is retained for source review; the narrow build avoids reliance
on abandoned dependency repositories and gives CI a reproducible base check.

The fork also leaves PitRemake combat bots out of Citizens' fake scoreboard
teams. PitRemake already assigns their visible name and tab team; the two
owners formerly competed for the same entry and forced a packet every display
refresh. Other Citizens NPCs retain their ordinary team behavior.

The first revision fixed the shared-viewer race. PitRemake's version gate
already skips its periodic visibility sweep with this fork. Continue validating
WindSpigot with multiple real viewers, range exits/reentries and skin respawns;
the local smoke test alone cannot prove every async tracker race is gone.

Revision 8 supports explicitly skinless combat bots (`pitsim-skinless`): no
texture lookup, no delayed skin profile, ordered ADD/native SPAWN/REMOVE in one
tracking pass. Ordinary NPCs and Keeper retain their skin policy. Thread-local
scope is restored even when sending a packet fails; no pending viewer/entity
references are created for skinless profiles.

Revision 9 serializes managed NPC movement tracking with teleport correction.
`synchronizePosition` resets Wind's quantized position/rotation baseline and
queues an absolute body correction for existing viewers. Pit queues the
packet-only nameplate afterward on the same connection queue. The correction
does not publish a profile, recreate the entity or change native physics. Native
regressions verify quantized coordinates and absence of delayed profile work.

The second fork revision keeps marked Pit combat bots out of the visible tab
list. Their tracker sends a temporary player profile, then removes it after
two ticks so the 1.8 client can cache its skin on the first render. Removing
it in the same tracking pass produced default skins in the local client. The
generic Citizens NPC timing is unchanged. Pit combat bots also skip Citizens' delayed
skin refresh and repeated viewer refreshes: their skin is already applied at
construction, and those refreshes re-added a visible bot to tab roughly a
second after spawn. This is scoped to the `pitsim-combat-bot` marker.

The third revision applies the same tab behavior to the existing PitRemake
Keeper NPC, identified by its persisted `pitsim-lobby-role: keeper` marker.
Other Citizens player NPCs retain their normal skin refresh. The local client
confirmed combat bot tab flashes stopped with revision 2; the Keeper was the
remaining visible flash. The same-pass removal also prevented both bots and
Keeper from loading skins, so revision 3 uses the short profile lifetime above.
The real-client check on revision 3 confirmed both skins eventually loaded
after joining and neither type kept flashing while the viewer stayed nearby.
Minecraft 1.8.9 uses the same player-info entry for skins and the tab overlay;
a brief entry on an initial spawn is possible for a textured player NPC
without changing the client. The repeated one-second refresh is eliminated.

Revision 4 retains the no-op NPC `queuePacket` override and its compiled-artifact
gate, preventing WindSpigot's unflushed NPC connections from retaining packets.
Revision 5 adds managed-bot optimizations in `EntityHumanNPC`: Pit explicitly
opts into staggered item scans every four ticks; all other NPCs still scan each
tick. Equipment snapshots include all five slots, clone NBT, and send only
changed slots to native trackers. Initial/range-entry equipment remains owned
by the native tracker. Native physics, movement and knockback are unchanged.
The offline Wind tests exercise packet retention, ordinary-player delivery,
scan bounds/staggering, hand/helmet updates, in-place NBT edits and removal.

Revision 6 queues managed profile ADD/REMOVE packets in the same Wind connection
queue as native spawns. The protected native spawn hook publishes profiles only
for actual spawns; vanilla retains its DataWatcher hook. One shared service
coalesces removals by viewer connection and profile UUID, fences old entities
across respawns, batches up to the configured packet entry limit, and clears
pending references on expiry/quit/disable. Profiles remain available for at
least 20 ticks for the first client render (managed metadata
`pitsim-skin-profile-ticks`, bounded 20–100). This replaces revision 2/3's
two-tick lifetime. A temporary initial tab entry is possible with 1.8 clients.

Managed bots and Keeper are omitted from discarded skin refresh scans. Ordinary
NPC candidates are shared within one tick, viewer requests coalesce, distance
tests avoid square roots, and quit/reset/despawn clear pending work. Transient
Mojang failures and 429s retry with backoff and fresh one-shot handlers; empty
texture results are failures. Same-name in-flight fetches coalesce. Completed
profile history and weak skin cache are bounded at 2048 (active requests stay
pinned). Late skin callbacks cannot respawn a replacement NPC entity.

Revision 4's exact no-op NPC queue artifact gate and revision 5's scan/equipment
optimizations remain. New native regressions cover packet order, range re-entry,
respawn, reconnect cleanup, concurrent viewers and batched expiry. Supply
`--libraries <Citizens/lib>` to `scripts/test-packet-queue.py` for CPU/skin-fetch
checks with Citizens' cached relocated runtime libraries, and optionally
`--baseline-artifact <revision5.jar>` for work comparisons. No network fetches
are needed by those fixtures. See `docs/VISIBILITY-CPU-20261003.md`.
