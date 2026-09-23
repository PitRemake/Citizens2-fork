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
`2.0.30-PitRemake.1`. The full historical
Maven reactor is retained for source review; the narrow build avoids reliance
on abandoned dependency repositories and gives CI a reproducible base check.

The fork also leaves PitRemake combat bots out of Citizens' fake scoreboard
teams. PitRemake already assigns their visible name and tab team; the two
owners formerly competed for the same entry and forced a packet every display
refresh. Other Citizens NPCs retain their ordinary team behavior.

This fixes the shared-viewer race. It does not claim that every async tracking
or skin packet ordering fault is resolved. Validate on WindSpigot with multiple
real viewers, range exits/reentries, NPC skin respawns and packet traces before
removing PitRemake's bounded visibility sweep.
