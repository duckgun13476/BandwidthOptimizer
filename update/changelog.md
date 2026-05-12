#### v2.7.6.6-beta
1. Added Forge 1.19.2, Fabric 1.20.1, and Fabric 1.21.1 support.
2. Fixed chunk cache reuse after returning to the multiplayer screen and reconnecting.
3. Fixed client disconnects caused by direct chunk transport envelopes being decoded as vanilla packets.

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
