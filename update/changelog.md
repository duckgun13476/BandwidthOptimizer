

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
