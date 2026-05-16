<p align="center">
  <img src="https://logistics.aisaveworld.tech/d/share/icon.png" width="180" height="180" alt="BandwidthOptimizer 图标">
</p>

<p align="center">
  <a href="README.md">English</a> | 简体中文
</p>

# BandwidthOptimizer

BandwidthOptimizer 是一个需要客户端与服务端同时安装的 Minecraft 网络带宽优化模组，主要面向大型整合包和公开服务器。它会减少重复、可压缩的 PLAY 阶段流量，同时尽量保证接收端在解码后看到的仍然是原本的 Minecraft 数据包流。

它适合登录同步、自定义网络包、机械网络、存储网络、反复进出区块等流量较重的场景。它不是 FPS 优化模组，也不能替代正常的服务端、代理端或网络线路调优。

<p align="center">
  <a href="https://discord.gg/qdMbM9Rq6B"><img src="https://img.shields.io/badge/Discord-反馈-5865F2?style=for-the-badge&logo=discord&logoColor=white" alt="Discord"></a>
  <a href="https://www.curseforge.com/minecraft/mc-mods/bandwidthoptimizer"><img src="https://img.shields.io/badge/CurseForge-下载-F16436?style=for-the-badge&logo=curseforge&logoColor=white" alt="CurseForge"></a>
  <a href="https://modrinth.com/mod/bandwidthoptimizer"><img src="https://img.shields.io/badge/Modrinth-下载-00AF5C?style=for-the-badge&logo=modrinth&logoColor=white" alt="Modrinth"></a>
</p>

![BandwidthOptimizer 预览](https://logistics.aisaveworld.tech/p/share/function.gif)

## 开发状态

BandwidthOptimizer 仍在积极开发中，在大型整合包、代理网络、重自定义包流量的环境里仍然可能存在 bug 或模组兼容性问题。

这个模组的目标是优化网络包，而不是修改世界数据或存档文件。当某个数据包、时序边界或模组交互看起来不安全时，BandwidthOptimizer 会尽量回退为直通或旁路，而不是强行优化。

如果你遇到问题，请尽量附上客户端日志、服务端日志、代理日志以及 BandwidthOptimizer 生成的报告文件。兼容性报告、建议和贡献都很欢迎。

## 主要功能

- 将已经由 Minecraft 或加载器编码完成的数据包封装为可逆的传输帧。
- 使用流式 Zstd 压缩适合压缩的数据包负载。
- 使用同步的字面量 / 模板映射减少重复数据包结构。
- 在安全的小窗口内批处理数据包，同时尊重协议阶段和 flush 边界。
- 通过 `full`、`ref`、`patch` 决策复用区块数据。
- 在重连和兼容的代理跨服切换后保留可复用的区块缓存。
- 对敏感包、协议切换和不安全时序边界进行旁路，而不是让所有包强行走同一条优化路径。
- 提供 HUD、服务端统计、来源报告、旁路报告和解码异常 dump，方便调试与复现实测数据。

## 工作原理

BandwidthOptimizer 工作在普通游戏逻辑之下。发送侧会在 Minecraft 或加载器把一个完整数据包编码为字节之后观察它，传输层随后可以选择封装、批处理、压缩或旁路这个已编码数据包。

接收侧会把传输帧还原为原始编码数据包字节，然后继续交给正常的数据包处理流程。设计目标是数据包流等价：还原后，接收端看到的数据包类型、内容和相对顺序应当与未安装 BandwidthOptimizer 时一致。

| 层级 | 作用 |
| --- | --- |
| 透明传输层 | 承载已经编码完成的数据包字节，不重写游戏逻辑。 |
| 流式 Zstd | 压缩适合字节级压缩的数据包内容。 |
| 字面量 / 模板映射 | 在发送端与接收端状态同步的前提下减少重复结构。 |
| 轻量批处理路径 | 在敏感 flush 路径中使用更便宜的批处理编码，避免完整模板工作过重。 |
| 区块传输 | 根据缓存状态和安全检查，在 `full`、`ref`、`patch`、`bypass` 之间选择。 |
| 边界控制 | 在协议切换、login/config/play 阶段、代理跨服和过期 epoch 附近强制 flush、旁路、预热或重置。 |

## 实测结果

带宽削减效果取决于整合包、玩家行为、代理结构以及主要流量来源。下面的数据来自真实测试中的 BandwidthOptimizer HUD 截图和报告文件，是可复现的样本，不是对所有服务器的保证。

这个模组最重要的指标是进入 BandwidthOptimizer 传输路径后的压缩比例。整服原始 / 实际流量也有参考价值，但其中会混入直通包、兼容旁路、缓存复用，以及一些不应该或无法压缩的模组流量。

| 环境 | 客户端优化流量 | 服务端原始到实际 | 服务端优化流量 | 直通流量 |
| --- | ---: | ---: | ---: | ---: |
| Create Delight Remake 1 服 | `23.21 MB -> 3.93 MB` (`16.9%`) | `305.35 GB -> 51.12 GB` (`16.7%`) | `40.28 GB` (`13.2%`) | `49.46 GB` (`16.2%`) |
| Create Delight Remake 2 服 | `24.51 MB -> 4.15 MB` (`16.9%`) | `516.70 GB -> 83.24 GB` (`16.1%`) | `57.57 GB` (`11.1%`) | `57.01 GB` (`11.0%`) |

高收益流量样本：

| 来源 | 环境 | 原始流量 | 实际传输 | 实际 / 原始 |
| --- | --- | ---: | ---: | ---: |
| `ClientboundLevelChunkWithLightPacket` | Create-focused core server | `9062.88 MiB` | `481.85 MiB` | `5.3%` |
| `ClientboundLevelChunkWithLightPacket` | Create-focused test server | `713.65 MiB` | `16.84 MiB` | `2.4%` |
| `ClientboundTabListPacket` | Create-focused core server | `6354.97 MiB` | `655.45 MiB` | `10.3%` |
| `lightmanscurrency:network` | Create-focused core server | `8206.80 MiB` | `593.69 MiB` | `7.2%` |
| `create:deployer` 方块实体数据 | Create Delight | `510.41 MiB` | `71.61 MiB` | `14.0%` |

低收益流量样本：

| 来源 | 环境 | 原始流量 | 实际传输 | 实际 / 原始 | 路径 |
| --- | --- | ---: | ---: | ---: | --- |
| `watut:main` | Create-focused core server | `4157.94 MiB` | `3974.80 MiB` | `95.6%` | `BATCH_DIRECT_FALLBACK` |
| `yes_steve_model:2_6_0` | Create-focused core server | `514.63 MiB` | `510.50 MiB` | `99.2%` | `BATCH_TRANSPORT_SHARE` |
| `watut:main` | Create-focused mirror server | `146.07 MiB` | `143.07 MiB` | `97.9%` | `BATCH_TRANSPORT_SHARE` |
| `watut:main` | Create Delight | `91.18 MiB` | `82.53 MiB` | `90.5%` | `BATCH_TRANSPORT_SHARE` |

WATUT / YSM 这类存在感、模型或类似数据流可能已经压缩、加密，或接近随机数据，二次压缩空间很小。遇到这种流量时，BandwidthOptimizer 会优先保证兼容性，并可能选择旁路或仅做最小封装。

常用报告路径：

```text
bandwidthoptimizer-native/transport-source-report/latest-source-report.md
bandwidthoptimizer-native/transport-bypass-report/latest-bypass-report.md
bandwidthoptimizer-native/decoder-exception-dump/
```

## 已测试整合包环境

BandwidthOptimizer 已在多个重型整合包环境中进行过兼容性测试，包括：

- 齿轮盛宴 / Create Delight
- 黄铜协奏曲 / Brass Concerto
- 航空学 / Aeronautics
- 重度机械症 / Mechanomania

这代表这些环境被用于兼容性测试，但不保证覆盖所有模组组合、代理拓扑或服务器配置。

## 支持版本

请下载与你的 Minecraft 版本和加载器匹配的构建。

| 加载器 | Minecraft | 说明 |
| --- | --- | --- |
| Forge | 1.19.2 | 面向 Forge 43.x。 |
| Forge | 1.20.1 | 面向 Forge 47.x。 |
| Fabric / Quilt | 1.20.1 | 需要 Fabric API，同时发布 Fabric 与 Quilt 加载器支持。 |
| Fabric / Quilt | 1.21.1 | 需要 Fabric API，同时发布 Fabric 与 Quilt 加载器支持。 |
| NeoForge | 1.21.1 | 面向 NeoForge 21.x。 |

## 安装

1. 下载与你的加载器和 Minecraft 版本匹配的 BandwidthOptimizer jar。
2. 将 jar 放入服务端 `mods` 文件夹。
3. 将同版本 BandwidthOptimizer 放入每个客户端的 `mods` 文件夹。
4. 使用 Fabric 或 Quilt 构建时安装 Fabric API。
5. 重启服务端和客户端。

如果客户端与服务端版本不一致，传输通道可能会被禁用，或无法正确协商。

## 配置

主要配置文件由加载器生成：

```text
config/bandwidthoptimizer-common.toml
config/bandwidthoptimizer-client.toml
```

多数服务器建议先使用默认配置，再通过对比修改前后的报告结果来调整选项。高级运行时覆盖项包括 `bandwidthoptimizer.transport.batchWindowMillis`、`bandwidthoptimizer.transport.mappingEnabled` 和 `bandwidthoptimizer.transport.zstdEnabled`。

## 指令

| 指令 | 说明 |
| --- | --- |
| `/bandwidthoptimizer` | 显示可用指令。 |
| `/bandwidthoptimizer hud` | 切换客户端 HUD。 |
| `/bandwidthoptimizer stats` | 显示服务端带宽统计。 |
| `/bandwidthoptimizer stats total` | 显示持久化的服务端总带宽统计。 |
| `/bandwidthoptimizer stats players [limit]` | 显示玩家 / 通道带宽排行。 |
| `/bandwidthoptimizer stats reset` | 重置持久化服务端带宽统计。 |
| `/bandwidthoptimizer test transportreport run [ticks]` | 启动一次短时间传输压缩报告，需要开启 debug analysis。 |
| `/bandwidthoptimizer test packetrank run [ticks]` | 启动一次数据包排行捕获，需要开启 debug analysis。 |

## 当前限制

- 客户端和服务端都必须安装该模组。
- 效果会随整合包、在线人数、流量来源和玩家行为变化。
- 已压缩、加密或接近随机的数据流可能几乎没有额外压缩收益。
- 部分数据包会被故意直通，因为优化它们的成本可能大于收益，或存在兼容风险。
- BandwidthOptimizer 优化的是网络流量，不直接优化 FPS、TPS、世界生成、数据库延迟或代理线路。

## 问题反馈

反馈问题时，请尽量附上 Minecraft 版本、加载器、BandwidthOptimizer 版本、代理结构、客户端日志、服务端日志、必要时的代理日志，以及 `bandwidthoptimizer-native` 下的报告文件。

## 许可证

本项目使用 GNU LGPL 2.1-only 许可证。
