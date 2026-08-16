<p align="center">
  <img src="https://logistics.aisaveworld.tech/d/share/icon.png" width="180" height="180" alt="BandwidthOptimizer 图标">
</p>

<p align="center">
  <a href="README.md">English</a> | 简体中文
</p>

# BandwidthOptimizer

BandwidthOptimizer 是一个需要客户端与服务端同时安装的 Minecraft 网络优化模组，主要面向大型整合包和公开服务器。它会减少重复的 PLAY 阶段流量，并在正常 Minecraft 数据包处理前还原原始编码数据流。

它适合登录同步、自定义网络包、机械与存储网络、反复访问区块以及挂机客户端带来大量流量的场景。它不是 FPS 或 TPS 优化模组。

<p align="center">
  <a href="https://discord.gg/qdMbM9Rq6B"><img src="https://img.shields.io/badge/Discord-反馈-5865F2?style=for-the-badge&logo=discord&logoColor=white" alt="Discord"></a>
  <a href="https://www.curseforge.com/minecraft/mc-mods/bandwidthoptimizer"><img src="https://img.shields.io/badge/CurseForge-下载-F16436?style=for-the-badge&logo=curseforge&logoColor=white" alt="CurseForge"></a>
  <a href="https://modrinth.com/mod/bandwidthoptimizer"><img src="https://img.shields.io/badge/Modrinth-下载-00AF5C?style=for-the-badge&logo=modrinth&logoColor=white" alt="Modrinth"></a>
</p>

![BandwidthOptimizer 预览](https://logistics.aisaveworld.tech/p/share/function.gif)

## 主要功能

- 使用可恢复的跨帧流式 Zstd 压缩适合压缩的数据包流量。
- 通过同步的字面量与模板映射减少重复的数据包结构。
- 在不改变还原顺序的前提下批处理兼容的小数据包。
- 通过经过校验的 `full`、`ref` 与 `patch` 路径复用区块数据。
- 使用有界的客户端永久区块缓存，在重连或再次访问已接收地形时复用数据。
- 通过增量写入、分层淘汰、损坏校验和后台维护控制磁盘与内存压力。
- 在客户端轻度或深度挂机时减少仅影响显示的流量，并在恢复游玩时同步当前状态。
- 在协议切换和敏感数据包边界使用 flush、恢复或兼容路径保护原始语义。
- 提供游戏内 HUD、可上传网页报告、玩家流量历史、数据包来源归属和精确 packet-class 诊断。

## 传输设计

BandwidthOptimizer 在 Minecraft 或加载器完成数据包编码后观察完整字节。适合处理的数据包可以经过映射、批处理、压缩，或从已经验证的区块引用中复用。接收侧会在正常数据包解码前还原原始编码字节。

跨帧压缩受 sequence 和 epoch 校验保护。如果流边界缺失或无效，BO 会请求有界恢复数据并重新同步压缩流，而不是继续使用未知状态。

区块复用使用哈希校验。内存临时缓存负责单次会话内的重复流量，永久缓存则可以在重连后复用兼容的地形数据。缓存条目缺失、过期或损坏时会回退为完整数据包。

挂机流量控制分为两级。轻度挂机会在游戏仍处于前台时减少可以快速恢复的显示流量；深度挂机会在暂停、最小化或切到后台时进一步削减流量。玩家返回后，恢复策略会重新同步当前方块、实体、HUD 和已支持模组的状态。

## 性能表现

实际效果取决于整合包、玩家行为、代理结构以及主要数据包来源。旧版生产环境实测中，重型整合包的受管流量通常可以降低到原始大小的约 16-24%。

当前 5.10.30.99 架构的现有实测结果已经达到原始受管流量的约 3-11%。这比旧版基线有明显提升，但仍不代表所有服务器都能获得相同结果；最终比例取决于实际数据包构成和可复用流量占比。

已经压缩、加密、接近媒体数据或近似随机的数据可能几乎没有二次压缩空间。当继续处理收益很低或可能产生兼容风险时，BO 会优先保证数据包正确性。

部分整合包可能存在模组冲突并导致网络层异常发包。BO 无法保证削减这类异常流量，但服务器管理员可以执行 `/bandwidthoptimizer stats` 上传分析报告并定位异常来源。

## 支持版本

请下载与你的 Minecraft 版本和加载器同时匹配的构建。

| 加载器 | Minecraft | 状态 |
| --- | --- | --- |
| Forge | 1.19.2 | Release |
| Forge | 1.20.1 | Release |
| Fabric / Quilt | 1.20.1 | Release；需要 Fabric API |
| Fabric / Quilt | 1.21.1 | Release；需要 Fabric API |
| NeoForge | 1.21.1 | Release |
| NeoForge | 26.1.2 | Release |
| NeoForge | 26.2 | Beta |

## 安装

1. 下载与你的 Minecraft 版本和加载器匹配的 jar。
2. 将它放入服务端 `mods` 目录。
3. 在每个连接客户端安装相同版本的 BandwidthOptimizer。
4. 使用 Fabric 或 Quilt 构建时安装 Fabric API。
5. 重启服务端和客户端。

客户端与服务端必须使用兼容的 BO 网络协议，强烈建议两端始终安装完全相同的 BO 版本。

## 命令

| 命令 | 用途 |
| --- | --- |
| `/bandwidthoptimizer hud` | 开关本地客户端统计 HUD。 |
| `/bandwidthoptimizer stats` | 上传当前服务端报告并返回可点击的 BO Stats 链接，需要 OP 权限。 |
| `/bandwidthoptimizer debug` | 打开按需诊断工具，需要 OP 权限。 |

## 网页报告

使用 OP 身份执行 `/bandwidthoptimizer stats`。BO 会收集当前服务端总量、玩家与时间段流量历史、传输和缓存统计、挂机门控节省量、旁路明细以及数据包来源归属，然后返回一个可点击的 `bostats.torqueflux.com` 报告链接。

网页报告是分享带宽证据的首选方式。调查断连、解码错误或时序敏感的兼容问题时，仍然需要同时提供客户端、服务端和代理端日志。

## 配置

默认配置已经适合大多数服务器和整合包，建议先直接使用默认值。需要进阶调整的管理员，可以在完成前后对照测试后再修改自动生成的客户端和通用配置文件，并使用 BO Stats 比较结果。

## 兼容性

BandwidthOptimizer 已在大型机械动力整合包、航空学、重度机械症、Velocity 跨服、Voxy、AutoFish 等网络负载较高的环境中进行测试，但这不代表覆盖了所有可能的模组组合。

当某条优化路径无法安全维持原始语义时，BO 会使用经过校验的回退或恢复路径。提交兼容性报告时，请附上 Minecraft 版本、加载器、BO 版本、相关客户端/服务端/代理日志，以及可用的 BO Stats 报告链接。

## 许可证

本项目使用 GNU LGPL 2.1 或任何更高版本（`LGPL-2.1-or-later`）许可证。
