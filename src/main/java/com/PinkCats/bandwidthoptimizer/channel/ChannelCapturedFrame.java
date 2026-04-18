package com.PinkCats.bandwidthoptimizer.channel;

// 这个类用来保存一次“编码包帧快照”。
// 这里记录的是我们在理想主桩位置抓到的原始编码包字节，以及对应的方向、协议、包类和时间。
public record ChannelCapturedFrame(
        String direction,
        String protocolName,
        String packetClassName,
        int packetId,
        int byteLength,
        byte[] encodedBytes,
        long capturedAtMillis
) {
    // 这个函数返回 encodedBytes 的拷贝，避免外部代码直接改坏内部保存的字节。
    public byte[] copyEncodedBytes() {
        return this.encodedBytes.clone();
    }
}
