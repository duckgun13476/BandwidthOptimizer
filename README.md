<p align="center">
  <img src="https://logistics.aisaveworld.tech/d/share/icon.png" width="180" height="180" alt="BandwidthOptimizer icon">
</p>

<p align="center">
  English | <a href="README.zh-CN.md">简体中文</a>
</p>

# BandwidthOptimizer

BandwidthOptimizer is a client-and-server network optimization mod for heavily
modded Minecraft servers. It reduces repeated PLAY traffic while restoring the
original encoded packet stream before normal Minecraft packet handling.

It is intended for modpacks and public servers where login synchronization,
custom payloads, machine networks, storage systems, repeated chunk visits, and
idle clients generate substantial traffic. It is not an FPS or TPS optimizer.

<p align="center">
  <a href="https://discord.gg/qdMbM9Rq6B"><img src="https://img.shields.io/badge/Discord-Feedback-5865F2?style=for-the-badge&logo=discord&logoColor=white" alt="Discord"></a>
  <a href="https://www.curseforge.com/minecraft/mc-mods/bandwidthoptimizer"><img src="https://img.shields.io/badge/CurseForge-Download-F16436?style=for-the-badge&logo=curseforge&logoColor=white" alt="CurseForge"></a>
  <a href="https://modrinth.com/mod/bandwidthoptimizer"><img src="https://img.shields.io/badge/Modrinth-Download-00AF5C?style=for-the-badge&logo=modrinth&logoColor=white" alt="Modrinth"></a>
</p>

![BandwidthOptimizer preview](https://logistics.aisaveworld.tech/p/share/function.gif)

## Main Features

- Compresses suitable packet traffic with recoverable cross-frame streaming
  Zstd.
- Reduces repeated packet structures through synchronized literal and template
  mapping.
- Batches compatible small packets without changing their restored order.
- Reuses chunk data through verified `full`, `ref`, and `patch` transport paths.
- Keeps a bounded persistent client chunk cache for reuse after reconnecting or
  revisiting previously received terrain.
- Uses incremental cache writes, tiered retention, corruption checks, and
  background maintenance to limit disk and memory pressure.
- Reduces presentation-only traffic while a client is lightly or deeply idle,
  then restores current state when play resumes.
- Preserves protocol transitions and sensitive packet boundaries through flush,
  recovery, or compatibility paths.
- Provides an in-game HUD, uploadable web reports, per-player traffic history,
  packet-source attribution, and exact packet-class diagnostics.

## Transport Design

BandwidthOptimizer observes complete packet bytes after Minecraft or the loader
has encoded them. Suitable packets may be mapped, batched, compressed, or served
from a verified chunk reference. On the receiving side, BO restores the original
encoded bytes before normal packet decoding continues.

Cross-frame compression is protected by sequence and epoch validation. If a
stream boundary is missing or invalid, BO requests a bounded recovery payload and
resynchronizes the stream instead of continuing with unknown state.

Chunk reuse is hash-verified. Temporary in-memory reuse handles repeated traffic
during a session, while the persistent cache can reuse compatible terrain data
after reconnecting. Invalid, missing, or stale cache entries fall back to a full
packet.

Idle traffic reduction has two states. Light idle reduces quickly recoverable
presentation traffic while the game remains in the foreground. Deep idle applies
stronger reduction when the game is paused, minimized, or left in the background,
with recovery policies restoring current block, entity, HUD, and supported mod
state when the player returns.

## Performance

Results depend on the modpack, player activity, proxy topology, and dominant
packet sources. Older production measurements commonly reduced managed traffic
to roughly 16-24% of its original size in heavily modded environments.

Current measurements for the 5.10.30.99 architecture have reduced managed
traffic to about 3-11% of its original size. This is a substantial improvement
over the older baseline, but it is not a guaranteed result for every server; the
final ratio depends on packet composition and the amount of reusable traffic.

Already-compressed, encrypted, media-like, or near-random payloads may have little
additional compression potential. BO prioritizes packet correctness when further
processing would provide little benefit or create compatibility risk.

Some modpacks may contain mod conflicts that cause abnormal network packets. BO
cannot guarantee meaningful reduction of this abnormal traffic, but server
operators can run `/bandwidthoptimizer stats` to upload an analysis report and
locate its source.

## Supported Versions

Choose the build matching both the Minecraft version and loader.

| Loader | Minecraft | Status |
| --- | --- | --- |
| Forge | 1.19.2 | Release |
| Forge | 1.20.1 | Release |
| Fabric / Quilt | 1.20.1 | Release; requires Fabric API |
| Fabric / Quilt | 1.21.1 | Release; requires Fabric API |
| NeoForge | 1.21.1 | Release |
| NeoForge | 26.1.2 | Release |
| NeoForge | 26.2 | Beta |

## Installation

1. Download the jar matching your Minecraft version and loader.
2. Install it in the server `mods` directory.
3. Install the same BandwidthOptimizer version on every connecting client.
4. Install Fabric API when using a Fabric or Quilt build.
5. Restart the server and clients.

Client and server builds must use compatible BO network protocols. Keeping the
exact same BO version on both sides is strongly recommended.

## Commands

| Command | Purpose |
| --- | --- |
| `/bandwidthoptimizer hud` | Toggles the local client statistics HUD. |
| `/bandwidthoptimizer stats` | Uploads the current server report and returns a clickable BO Stats link. Requires OP permission. |
| `/bandwidthoptimizer debug` | Opens the on-demand diagnostic tools. Requires OP permission. |

## Web Reports

Run `/bandwidthoptimizer stats` as an operator. BO collects the current server
totals, player and time-period traffic history, transport and cache statistics,
idle-gate savings, bypass details, and packet-source attribution. The command
uploads the report and returns a clickable `bostats.torqueflux.com` viewer link.

The report page is the preferred way to share bandwidth evidence. Client, server,
and proxy logs are still required when investigating disconnects, decoder errors,
or timing-sensitive compatibility problems.

## Configuration

The default configuration is designed to be appropriate for most servers and
modpacks. Start with the defaults. Advanced administrators can tune the generated
client and common configuration files after comparing BO Stats reports before
and after a controlled test.

## Compatibility

BandwidthOptimizer has been tested with large Create-based modpacks, Aeronautics,
Mechanomania, Velocity proxy switching, Voxy, AutoFish, and other network-heavy
environments. These tests do not guarantee every possible mod combination.

When BO cannot safely preserve an optimization path, it uses a verified fallback
or recovery path. Compatibility reports should include the Minecraft version,
loader, BO version, relevant client/server/proxy logs, and a BO Stats report link
when available.

## License

This project is licensed under GNU LGPL 2.1 or any later version
(`LGPL-2.1-or-later`).
