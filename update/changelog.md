#### v5.10.30.109-release

1. Lowered the Fabric 1.21.1 Fabric API requirement while retaining the typed networking API used by BO.
2. Fixed Fabric transport carrier re-entry in integrated worlds and corrected Fabric runtime dependency packaging.
3. Fixed the embedded Windows Zstd runtime loader and cleaned obsolete native fallback directories.
4. Reused bounded direct Zstd workspaces and recovery sessions to prevent direct-memory allocation churn during large synchronization bursts.
5. Optimized Create contraption reference resolution and preserved external connection-close causes in diagnostics.
6. Added exact command-tree de-duplication and timed clientbound burst diagnostics.
7. Added compatibility for Potato proxy protocol messages.

#### v5.10.30.104-release

1. Fixed streaming epoch transitions that could disconnect clients while preserving recovery for genuinely missing frames.

#### v5.10.30.103-release

1. Fixed oversized fragmented transport frames that could cause decoder errors and disconnect players (#24).
2. Added automatic recovery from supported transient connection losses.
3. Reduced server memory pressure by sharing immutable chunk snapshot data across cache access updates.
4. Prevented stale client payload tasks from running after protocol changes or disconnects.
5. Rejected non-Minecraft HTTP probes before packet decoding.
6. Compacted hourly traffic reports into daily files to reduce filesystem clutter.
7. Bounded diagnostic output to prevent repeated failures from flooding logs.

#### v5.10.30.99-release

1. Fixed streaming recovery after late acknowledgements and preserved pending recovery data across timeouts and opposite-direction resets.
2. Fixed re-entrant batch flushing from closing a second streaming epoch during recovery.
3. Added tiered persistent cache retention and bounded maintenance, with recovery for interrupted migrations, crashes, and reconnects.
4. Preserved chunk packet order while persistent cache entries are prepared and fixed transient Voxy holes during renderer handoff.
5. Reduced persistent cache CPU and disk overhead by reusing hot indexes and writing only changed cache data.
6. Improved BO Stats with interactive charts, per-player history, manual uploads, clickable links, and clearer memory reporting.
7. Improved packet-source attribution with content namespaces and added exact runtime packet-class tracing for compatibility diagnostics.
8. Added NeoForge 26.2 beta support.
9. Added resilient Discord release notifications and prevented routine streaming epoch diagnostics from flooding logs.

#### v5.10.30.88-beta

1. Added recoverable cross-frame streaming Zstd compression with sequence-gap detection, independent recovery batches, and silent epoch resynchronization.
2. Fixed streaming epoch boundaries during login, configuration, reconnects, and large synchronization bursts to prevent bounded-queue disconnects.
3. Rebuilt the persistent chunk cache for on-demand offline reuse, incremental disk writes, bounded recovery, and lifecycle-safe reference cleanup.
4. Added rate-limited warnings when client cache I/O remains blocked for more than 1.5 seconds.
5. Improved oversized transport fragmentation and reassembly compatibility.
6. Fixed ProtocolLib proxy channel identity handling and added concise reporting for repeated compatibility failures.
7. Moved Create contraption proximity scans off Netty threads to prevent concurrent entity-list access and player disconnects.
8. Extended light-idle traffic reduction to off-screen block and entity updates while preserving recovery when play resumes.
9. Preserved fishing and AutoFish updates during deep idle and streaming epoch transitions.
10. Batched foreground state synchronization packets and reduced server HUD snapshot overhead.
11. Added uploadable bandwidth reports with per-player and time-period traffic history.
12. Added runtime packet-class tracing and simplified commands into HUD, stats, and debug groups.
13. Added complete NeoForge 26.1.2 runtime, statistics, HUD, command, transport, and publishing support.
14. Restored HUD status colors and the idle indicator color on NeoForge 26.1.2.
15. Improved cross-version compatibility for chunk coordinates, player display names, loader APIs, and transport regressions.
16. Silenced known public protocol probes without hiding actionable decoder failures.

#### v3.9.26.72-release
1. Added adaptive AFK traffic controls for background clients.
2. Reduced verified presentation-only Minecraft and mod traffic.
3. Restored tracked state when returning from AFK.

#### v2.9.18.55-release
1. Fixed persistent chunk cache I/O pressure during fast exploration.
2. Added on-demand diagnostics and server BO log export.
3. Reduced transport diagnostic overhead and added hot-path cost tracing.
4. Fixed zstd transport session cleanup.

#### v2.8.16.40-release

1. Added Brazilian Portuguese localization.
2. Fixed Voxy rendering issues with stairs and chunk holes.

#### v2.8.16.38-release hotfix

1. Fixed Valkyrien startup crash.
2. Fixed Create interactive control delay.

#### v2.8.16.36-release hotfix

1. Fixed oversized transport batch flushes that could disconnect players after TPA or teleport bursts (#13).

#### v2.8.16.35-release

1. Gated all Create block entity updates with lightweight pre-encode size accounting.
2. Added Create transport raw and actual traffic to the server HUD.
3. Fixed server HUD raw-flow totals to include pre-encode Create gate savings.

#### v2.8.15.32-release

Important fixes:
1. Fixed runtime transport failures disabling optimization globally.
2. Preserved the real carrier write failure reason when transport carrier creation or payload limits fail (protect method but useless).
3. Restored Create dynamic-structure block entity updates to the merge gate for moving entity (8 point instead of 1 center).

Other fixes:
1. Added Forge 1.20.1 client chunk-gap diagnostics for missing chunk.

#### v2.8.14.29-release

Important fixes:
1. Fixed Valkyrien server-side inject crash mistake.
2. Added WATUT dynamic GUI compatibility throttling to reduce noisy GUI status traffic.
3. Fixed stateful transport carrier commit handling and added adaptive pre-wrap bypass for oversized or risky payloads.

Other fixes:
1. Fixed Fabric refmap publishing for release jars.
2. Added default low-overhead Netty spike diagnostics for overloaded network paths.
3. Added custom runAll test ports for cleaner regression validation.

#### v2.8.11.29-release

Important fixes:
1. Fixed proxy server-switch reset windows so stale transport frames and PLAY payloads no longer cross into the new backend login or chunk stream.
2. Fixed player floating after TP triggered by reset-style mods.
3. Fixed chunk restore stalls during fast map traversal.
4. Reduced player TP chunk-load latency; the BO path can now reach a usable state faster than vanilla loading in tested cases.

Other fixes:
1. Added sender-side limits for batch and single transport carriers to prevent frames that the receiver would reject.
2. Fixed streaming carrier frame smuggling and bad-carrier state poisoning.
3. Improved persistent manifest and chunk cache synchronization, refresh, and restore stability.
4. Improved Create dynamic-structure block entity bypassing and TP critical-path protection.
5. Fixed server HUD session bandwidth accounting so live stats no longer include persisted historical totals.

#### v2.7.8.18-beta
1. Improved Sable dynamic structure chunk sync compatibility.
2. Added Valkyrien Skies dynamic structure payload boundary handling.
3. Fixed Create update visibility checks on moving dynamic structures.

#### v2.7.7.17-beta
1. Trim server shadow chunk cache(This may cause high memory usage)
2. Validate persistent manifest reuse(Robust improve)

#### v2.7.7.15-beta
1. Fix Voxy chunk bound compat.
2. Fix problem if full chunk packet is missing.


#### v2.7.7.14-beta
1. Added Create block entity update gating to reduce repeated off-screen sync traffic.
2. Added sound-aware safety handling for Create block entity updates.
3. Added HUD stats for Create gate savings and real-time client/server wire rates.
4. Added Minecraft-compression-based saving estimates with an explicit high-cost toggle.
5. Added persistent server HUD stats for compression estimates and Create gate results.
6. Added a 2-minute server global compression ratio window.

#### v2.7.6.11-beta
1. Improved Velocity server-switch transport stability.
2. Fixed TrueUUID login compatibility issues.
3. Reduced Netty IO cost during sensitive batch flushes.
4. Improved transport routing for delayed carrier packets.

#### v2.7.6.6-beta
1. Added Forge 1.19.2, Fabric 1.20.1, and Fabric 1.21.1 support.
2. Marked Fabric builds as Quilt-compatible on publishing platforms.
3. Fixed chunk cache reuse after returning to the multiplayer screen and reconnecting.
4. Fixed client disconnects caused by direct chunk transport envelopes being decoded as vanilla packets.
5. Fix velocity chunk border potential problem.

#### v2.6.6.3-beta
1. Improved Sable compatibility.
2. Fixed persistent server bandwidth stats.
3. Added source traffic reports for finding high-traffic mods.
4. Moved bypass report writing to a background thread.

#### v2.6.5.2-beta
1. Add decode error analyzer.

#### v2.6.5.1-beta
1. Added offline chunk cache reuse.
2. Split cache by server.
3. Added compressed local cache backup.
4. Improved server/client HUD stats.

#### v2.6.4.1-release
1. Fixed severe memory growth in template dictionary and chunk snapshot caches.
2. Improved zstd native driver loading, fallback, and cleanup.
3. Fixed HUD bypass stats display and aligned server-side bypass reporting.
4. Improved NeoForge 1.21.1 and Forge 1.20.1 release stability.
5. fix command duplicate.

#### v2.6.4
1. Add strict channel check.
2. Fix the bypass range too big.
3. Fix version label mistake.
4. Fix unstable reflection.

#### v2.6.3
1. Fixed `Flow` being reported as `NONE` in the transport bypass report.
2. Added old-path archiving and unified the working output path.
3. Fixed a memory leak in the chunk global cache cleanup path.

#### v2.6.2
1. Fixed chunk cache state not being closed correctly after Velocity / proxy server switching.
2. Fixed a server crash when the channel client was missing.
3. Added silent bypass recording and bypass rank reporting to identify packets that did not enter the batch / zstd / template dictionary pipeline.

#### v2.6.1
1. Fixed the NeoForge 1.21.1 semantic migration.

#### v2.6
1. Added the NeoForge 1.21.1 migration.
2. Reworked the project structure for multi-version support.

#### v2.5-beta
1. Fixed chunk cache reuse after repeated Nether portal travel, reducing repeated full chunk sends.
2. Added server total bandwidth stats and per-player bandwidth stats with HUD, commands, and world persistence.
3. Improved HUD refresh, display text, i18n, and optimized-flow percentage display.
4. Fixed tiny-packet negative compression cases by bypassing clearly unprofitable carrier/batch output.
5. Hardened public-server safety around unexpected server-bound carriers, oversized frames, and invalid mapping data.

#### v2.4-beta
BandwidthOptimizer 2.4 beta is the largest update since 1.3. This version rewrites almost the entire codebase and replaces the old packet replay/batch architecture with a new layered transport framework focused on stability, compatibility, and real bandwidth reduction.

##### Major Features
1. Completely rewritten the low-level network optimization framework.
2. Added a new general-purpose transport layer that works after vanilla packets have already been encoded.
3. Added safer packet aggregation, compression, packet-id mapping, and connection-level state reuse.
4. Added automatic bypass, flush, barrier, and fallback behavior for timing-sensitive situations.
5. Replaced the old replay-centered design with a packet-stream equivalent design that is closer to vanilla behavior.

##### New Chunk Hotspot Optimization
1. Added a new chunk hotspot transport system for high-traffic chunk data.
2. Added `Full`, `Ref`, and `Patch` transport decisions.
3. `Full` sends a complete chunk baseline when the client does not have one.
4. `Ref` sends only a reference when the client already has the same chunk data.
5. `Patch` sends only the difference when the client has an older matching chunk baseline.
6. Added content fingerprinting for chunk packets.
7. Added smarter reuse for repeated movement, view-distance boundaries, chunk reloads, small block changes, and portal/dimension return paths.
8. Added specialized handling for full chunks, light updates, section block updates, block updates, and block entity updates.

##### Portal, Respawn, and Dimension Switching
1. Fixed repeated full chunk traffic after fast Nether portal travel.
2. Fixed chunk cache reuse failure after dimension changes.
3. Fixed repeated large chunk traffic after respawn.
4. Fixed chunk state invalidation caused by teleport, respawn, and dimension boundaries.
5. Returning to a previously visited area can now reuse confirmed matching chunk data when it is safe.

##### Stability and Boundary Handling
1. Added safer handling for login, game join, respawn, dimension change, teleport, player position sync, chunk view center/radius changes, chunk unload, forget chunk, bundle boundaries, and listener-sensitive packets.
2. Improved ordering protection between optimized packets and direct vanilla packets.
3. Improved behavior around watch boundaries so small changes do not always fall back to full chunk transmission.
4. Added ACK/NACK/INVALIDATE feedback for chunk baseline state.
5. The server can now recover when the client is missing a required chunk baseline.

##### Compatibility Improvements
1. Fixed joining servers through Velocity.
2. Fixed Velocity adaptive behavior.
3. Improved compatibility with proxy environments.
4. Improved anti-cheat compatibility by keeping optimized traffic closer to vanilla packet-stream semantics.
5. Fixed mapping problems in mixed server environments.
6. Fixed mapping problems in release builds.
7. Improved coexistence with custom payload-heavy mods, minimap mods, anti-cheat mods, and model-related mods.
8. Automatically bypasses packets that are not safe or not worth optimizing.

##### Performance and Bandwidth
1. Greatly reduces repeated full chunk traffic in reuse-heavy situations.
2. Improves bandwidth usage when players revisit the same area.
3. Improves bandwidth usage around view-distance boundaries.
4. Improves bandwidth usage after portal travel and dimension switching.
5. Compresses many small or structurally similar packets more effectively.
6. Uses specialized algorithms for chunk traffic instead of applying one generic strategy to every packet.
7. Automatically bypasses low-benefit paths to avoid optimization overhead.

##### Fixes
1. Fixed ref reuse not working correctly.
2. Fixed chunk hop reuse failure in the new logic.
3. Fixed dimension chunk reuse.
4. Fixed duplicated full sends caused by patch timing anomalies.
5. Fixed chunk border reuse sequence problems.
6. Fixed watch-boundary refresh and patch reuse issues.
7. Fixed session failure.
8. Fixed fragile packet flow handling.
9. Fixed channel safe-close mistakes.
10. Fixed bandwidth optimization auto-close behavior.
11. Fixed `ClientboundMoveEntityPacket$Pos` compare behavior.
12. Fixed light packet issues in specific cases.
13. Fixed recipe listener packet timing by routing them through immediate transport.
14. Fixed client HUD display issues.

##### Removed and Replaced
1. Removed the old replay-based main architecture.
2. Removed the old packet batch implementation.
3. Removed the old chunk cache message structure.
4. Removed the old network statistics and telemetry pipeline.
5. Removed early experimental optimization paths.
6. Replaced the old implementation with the new channel transport, chunk hotspot transport, and full/ref/patch systems.

##### Upgrade Notes
1. This is a major beta rewrite. Testing on a staging server is recommended before production deployment.
2. The first load of a new area still needs full chunk baselines; most savings appear during revisits, return paths, boundary reuse, dimension travel, and small updates.
3. Some packets may still bypass optimization by design. This means they were considered unsafe or not beneficial enough to optimize.
4. Servers using Velocity, anti-cheat, or large modpacks should observe behavior before rolling out widely.


#### v1.3-beta
1. Rework the chunk cache algorithm and improve return-path reuse.
2. Add off-screen delta sync for cached chunks.
3. Compress chunk cache traffic through the internal batch/zstd pipeline.
4. Fix chunk cache HUD and telemetry bandwidth display.
5. Improve safety across reset, respawn, and dimension change.
6. Add compatibility bypass for OPAC, Xaero, and Yes Steve Model.
7. Bump the internal network protocol version.

#### v1.2-release
1. Add four new whitelist classes to the algorithm.
2. Fix #2 (Chunk cache use problem).
3. Fix the debug problem (Many debugs are for test algorithm that shouldn't show in the release version).
4. Full analysis for Create packets.

#### v1.1-release
1. Fix monitor problem.
2. Fix the whitelist mistake (improve 5% additional bandwidth save).

#### v1.0-beta
1. Initial Mod and publish beta
