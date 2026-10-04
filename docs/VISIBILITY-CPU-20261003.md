# Citizens visibility, skins and CPU work — October 3, 2026

October4 follow-up: revision9 supersedes the combat-bot grace policy below when `pitsim-skinless` is set. Skinless bots skip texture fetching and delayed profiles, queue ADD/SPAWN/REMOVE in one pass, and retain no pending profile viewers. Keeper and ordinary NPC skin policy is unchanged. Managed movement tracking and teleport correction synchronize on the tracker; correction rebases quantized position/rotation/onGround and queues one absolute body packet without profile replay or entity recreation. Gameplay queues its nameplate afterward on the same connection. Native visibility/concurrent32, teleport baseline, no-op NPC queue, skin cache/retry/cleanup and item/equipment regressions pass. Publishing revision9 to main does not deploy production; signed-in visual verification remains outstanding.

Implemented in revision `2.0.30-PitRemake.6`, tested and installed on localhost only. Production and Git remotes were not changed. Release evidence lives at workspace `releases/citizens-visibility-cpu-local-20261003`.

Follow-up revision `2.0.30-PitRemake.7` reduces combat-bot skin grace to two ticks (default/metadata minimum), retaining Keeper's twenty ticks and all packet-order, identity, cleanup, cache/retry and CPU fixes below. Despawn cannot extend a pending deadline. The user chose skin preservation with minimized tab flashes, after revoking an immediate-remove preference. A two-tick window does not guarantee texture completion on a slow client or zero flashes. Updated native lifecycle tests cover two-tick expiry, Keeper policy, reconnects, replacement fences and despawn timing. Evidence and protocol investigation: workspace `releases/exe-tab-local-20261003/README.md`.

## Findings and fixes

The historical packet captures in the workspace established that WindSpigot queues native spawns while Citizens sends profile packets directly. Revision 5 retained that mixed delivery and independent delayed removals. Its managed profiles lasted only two ticks; gameplay also removed profiles immediately on living-bot replay. Those are concrete failure paths for an unknown-profile spawn or a default skin. This audit did not capture a new user-reported invisible occurrence in a signed-in client.

Revision 6 publishes the managed ADD through the same native connection queue before the native spawn, and queues REMOVE through that queue too. Wind's protected native spawn hook avoids sending profiles on unrelated DataWatcher reads. Vanilla's private spawn method keeps the existing DataWatcher hook. The previous ThreadLocal viewer context still clears in `finally`; managed admissions serialize per NPC to protect Wind's non-concurrent viewer map. Different NPCs retain parallel tracking.

A shared profile service keys removals by exact viewer connection and profile UUID. A later ADD replaces an older deadline; a delayed old-entity despawn cannot touch a replacement's pending profile. Profiles remain through at least 20 ticks of initial rendering, with managed metadata `pitsim-skin-profile-ticks` bounded to 20–100. One expiry scheduler batches removals, skips scanning until the next deadline, and releases references at expiry, quit and plugin disable. A brief initial tab entry can occur with Minecraft 1.8 clients. This grace period does not guarantee an external texture download will succeed.

Citizens also cached FAILED lookups as terminal and left its `fetching` latch set. Rate-limit retries could immediately deliver the previous failure to the new handler, instead of registering that handler for the next result. Failures and 429s now restart a pending request with fresh handlers and bounded exponential backoff, respecting the configured retry count. In-flight forced lookups join rather than discarding existing handlers. Empty texture responses count as failures. Backoff stops when no matching live NPC entity is waiting; late callbacks cannot respawn a replacement or removed entity.

## CPU and memory changes

- Managed combat bots and Keeper skip the generic skin scan/field-of-view/navigation refresh path, whose final packet operation was already disabled for them.
- Ordinary NPC candidates are shared within a tick; squared distances avoid square roots.
- Pending requests coalesce per viewer connection, preserving a requested reset. Quit/despawn/reset discard pending tasks and queued refresh references.
- Profile expiry uses one scheduler and batches up to the configured packet entry limit; no task is created per managed bot/viewer ADD.
- Skin cache uses weak values and a 2048-entry bound. Completed profile history is capped at 2048; active requests remain pinned until completion. Profile network batches are capped at 50 names per run.
- The builder publishes atomically from the destination directory, avoiding private Windows TEMP ACLs that otherwise make a JAR unreadable by the server account. The candidate still passes bytecode/replacement gates before replacing the output.

Revision 4's no-op `EmptyNetHandler.queuePacket(Packet)` remains packaged and checked by exact bytecode, not by version text. Revision 5's staggered item scans and deep five-slot equipment snapshots remain. Movement, knockback and combat timing are retained. Gameplay's five-minute arrow snapshot expiry and bot destruction/cleanup fixes remain and passed the full gameplay suite.

## Evidence

Offline fixtures executed the packaged fork against real WindSpigot classes:

- 200,000 NPC queue attempts retained zero packets; 100,000 ordinary queued packets and 100,000 ordinary direct packets were preserved.
- Join, range re-entry, same-UUID entity replacement, reconnect cleanup, metadata reads, 20-tick grace and 32 concurrent viewer admissions passed; profile ADD precedes native SPAWN, and old removal cannot erase the new profile.
- 120 profile removals generated eight packets at the configured 15-entry limit, with no expired profiles retained.
- In a 200-bot/10-ordinary-NPC/20-viewer burst of 100 requests per viewer, delayed refresh tasks fell from 2000 to 20. Managed entity reads fell from 400,000 to zero; ordinary entity reads from 20,000 to 10 for the shared tick snapshot. Ordinary NPCs still received the one coalesced refresh per viewer. These are work counts for this fixture, not a total-server CPU percentage.
- Failure-to-success recovery, same-name request deduplication, fresh callbacks, backoff, orphan retry termination and both cache limits passed with cached Citizens runtime libraries, without network requests.
- Nine artifact gate regressions passed. Gameplay: 3434 passed, 39 optional skips; actual Wind gameplay boundary: 99 passed. Zero failures/errors.

Thirty-second local JFR samples are retained in the release. The baseline contains only two Citizens stacks among 168 execution samples, and the before/after activity differs. They are insufficient for a reliable total CPU reduction claim. Client visibility, texture completion and long-session memory stability still need a signed-in play test.

The installed revision 6 runtime audit found 52 live NPC connections with 52 patched tracker entries, zero retained NPC packets and 53 cached skins. Startup/storage/network checks passed with zero runtime error lines. The after sample has seven Citizens stacks among 135 execution samples; it does not provide comparable activity or enough samples to infer a CPU percentage change.
