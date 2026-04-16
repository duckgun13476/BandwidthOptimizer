<img src="https://logistics.aisaveworld.tech/d/share/icon.png" width="180" height="180">
<br><br>

[English](README.md) | 简体中文

### BandwidthOptimizer

---
**<span style="color:#B96AD9;">用批量优化节省你的带宽。</span>**

[![Discord](https://img.shields.io/badge/Discord-Feedback-5865F2?style=for-the-badge&logo=discord&logoColor=white)](https://discord.gg/qdMbM9Rq6B)
[![GitHub](https://img.shields.io/badge/GitHub-Repository-24292e?style=for-the-badge&logo=github&logoColor=white)](https://github.com/duckgun13476/BandwidthOptimizer)

> **开发状态**
>
> BandwidthOptimizer 仍在积极开发中，因此仍然可能存在 bug 和模组兼容性问题。
>
> 当前设计采用严格的白名单策略。如果出现兼容性问题，通常只会表现为某些模组功能无法被正确优化，或暂时不能按预期工作；它的设计目标并不是破坏世界或损坏存档。
>
> 如果你遇到任何问题，请在 GitHub 上提交 issue。我会尽力调查并修复，也会认真、尊重地对待所有反馈与建议。
>
> 同时也欢迎任何形式的贡献。

概览
---
BandwidthOptimizer 通过一组高级优化技术，尽可能减少服务端与客户端之间不必要的网络流量。

在 Create 重型机械场景中，它最高可以实现约 20x 的带宽压缩；而从整体、真实服务器场景来看，更准确的表述是最高约 10x。


<br><br>

主要特性
---
1. 在 Create 重型机械场景中最高可达 20x，全局真实场景中最高约 10x
2. 针对 Create 与 AE2 实体提供专门优化
3. 减少复杂机械与存储网络中的冗余同步
4. 在自动化密集型整合包和服务器环境下显著改善性能
5. 完全兼容原版 / 正版验证环境

<br><br>

工作原理
---
<span style="color:#BFEDD2;">**BandwidthOptimizer 会通过分析并重构数据传输方式来降低网络负载：**</span>

**去重会消除重复数据包  
缓存会避免重复内容被再次发送  
模糊匹配会识别相似的数据包模式，并将其压缩为可复用模板  
这些机制协同工作，在不影响游戏语义正确性的前提下显著降低带宽占用。**

<br><br>

![Result](https://logistics.aisaveworld.tech/p/share/function.gif)

*示例：优化前后的带宽占用对比*

<br><br>


主要功能（算法）
---
1. 数据包去重
2. 数据包缓存
3. 数据包重放
4. 模糊模板匹配

<br><br>

兼容性
---
- Minecraft：1.20.1（示例）
- Loader：Forge / NeoForge
- 需要服务端与客户端同时安装
- 完全兼容 online-mode（正版认证）

<br><br>

代理支持
---
- 支持 Velocity
- 支持 BungeeCord / Waterfall
- 可在反向代理后正常工作（如 Nginx）
- 能在代理链路中保持数据包一致性

<br><br>

安装
---
1. 在服务端与客户端安装相同版本的 BandwidthOptimizer
2. 无需额外配置
3. 重启服务器

<span style="color:#BA372A;">*警告：版本不一致可能导致优化无法正常工作*</span>

<br><br>

安全性与稳定性
---
- 不修改游戏逻辑，也不改变服务端权威性
- 所有优化过程都是确定性的，并且可逆
- 设计目标是避免反同步以及客户端 / 服务端状态不一致
- 适合在多人环境中使用

<br><br>

适用场景
---
大型多人服务器  
自动化程度较高的模组服务器（尤其是 Create / AE2 场景）  
长期运行且受到网络瓶颈影响的服务器  
带宽有限或延迟较高的环境

<br><br>

当前限制
---
- 需要客户端与服务端同时安装该模组
- 最佳效果通常出现在高频、重复性强的 PLAY 阶段流量中。在极轻量或空闲场景下，重放封装本身的额外开销可能比原始负载还大。
- 在这种情况下，区块缓存复用仍可正常工作，但通用重放压缩的收益可能很小，甚至会带来轻微带宽开销。
- 这是当前设计中有意接受的取舍。若要继续压低超小流量场景下的开销，需要更复杂的传输路径，而通常只能额外节省很少的 KB/s。
