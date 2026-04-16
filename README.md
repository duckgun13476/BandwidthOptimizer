
<img src="https://logistics.aisaveworld.tech/d/share/icon.png" width="180" height="180">
<br><br>

English | [简体中文](README.zh-CN.md)

### BandwidthOptimizer

---
**<span style="color:#B96AD9;">Save your bandwidth with big quantities.</span>**

[![Discord](https://img.shields.io/badge/Discord-Feedback-5865F2?style=for-the-badge&logo=discord&logoColor=white)](https://discord.gg/qdMbM9Rq6B)
[![GitHub](https://img.shields.io/badge/GitHub-Repository-24292e?style=for-the-badge&logo=github&logoColor=white)](https://github.com/duckgun13476/BandwidthOptimizer)

> **Development Status**
>
> BandwidthOptimizer is still under active development, so bugs and mod compatibility issues are still possible.
>
> The current design uses a strict whitelist-based strategy. If a compatibility problem happens, it should only result in certain mod features not being optimized correctly or temporarily not working as expected. It is not intended to damage worlds or corrupt saves.
>
> If you encounter any issue, please open an issue on GitHub. I will do my best to investigate, fix it, and treat all reports and suggestions seriously and respectfully.
>
> Contributions of any kind are also welcome.

Overview
---
BandwidthOptimizer introduces a set of advanced optimization techniques to minimize unnecessary network traffic between server and client.

In Create-heavy machinery setups, it can achieve up to a 20x reduction in bandwidth usage, while overall real-world server-wide reduction is more accurately described as up to 10x.


<br><br>

Key Features
---
1. Up to 20x reduction for Create-heavy machinery, and up to 10x reduction in overall real-world scenarios
2. Specialized optimization for Create and AE2 entities
3. Reduces redundant synchronization of complex machinery and storage networks
4. Greatly improves performance in automation-heavy setups
5. Fully compatible with vanilla / official gameplay environments

<br><br>

How It Works
---
<span style="color:#BFEDD2;">**BandwidthOptimizer reduces network load by analyzing and restructuring how data is transmitted:**</span>

**Deduplication eliminates repeated packets  
Caching prevents redundant data from being resent  
Fuzzy matching identifies similar packet patterns and compresses them into reusable templates  
These mechanisms work together to significantly lower bandwidth consumption without affecting gameplay correctness.**

<br><br>

![Result](https://logistics.aisaveworld.tech/p/share/function.gif)

*Example: Bandwidth usage comparison before and after optimization*

<br><br>


Main function (algorithms)
---
1. Packet deduplication
2. Packet caching
3. Packet replay
4. Fuzzy template matching

<br><br>

Compatibility
---
- Minecraft: 1.20.1 (example)
- Loader: Forge / NeoForge
- Requires installation on both server and client
- Fully compatible with online-mode (official authentication)

<br><br>

Proxy Support
---
- Supports Velocity
- Supports BungeeCord / Waterfall
- Works correctly behind reverse proxies (e.g., Nginx)
- Maintains packet consistency across proxy layers

<br><br>

Installation
---
1. Install the same version of BandwidthOptimizer on both server and client
2. No additional configuration required
3. Restart the server

<span style="color:#BA372A;">*Warning: Version mismatch might prevent proper optimization*</span>

<br><br>

Security & Stability
---
- Does not modify gameplay logic or server authority
- All optimizations are deterministic and reversible
- Designed to avoid desync and client/server inconsistency
- Safe to use in multiplayer environments

<br><br>

Use Cases
---
Large-scale multiplayer servers  
Modded servers with heavy automation (especially Create / AE2 setups)  
Long-running servers suffering from network bottlenecks  
Environments with limited bandwidth or high latency

<br><br>

Limitations
---
- Requires both client and server to have the mod installed
- Best results come from heavy, repetitive PLAY traffic. In ultra-light or idle scenarios, replay wrapper overhead can be larger than the payload itself.
- In those cases, chunk cache reuse still works normally, but generic replay compression may show little benefit or slight bandwidth overhead.
- This is an intentional tradeoff in the current design. Further reducing overhead for extremely small packet flows would require a much more complex transport path while usually saving only a few KB/s.
