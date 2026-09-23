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
`2.0.30-PitRemake.3`. The full historical
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
