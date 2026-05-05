package com.PinkCats.bandwidthoptimizer.channel.capture;

//Packet Recorder
public record ChannelCapturedFrame(
        String channelId,
        String direction,
        String protocolName,
        String packetClassName,
        int packetId,
        int byteLength,
        byte[] encodedBytes,
        long capturedAtMillis
) {


    public ChannelCapturedFrame withPacketClassName(String newPacketClassName) {
        return new ChannelCapturedFrame(
                this.channelId,
                this.direction,
                this.protocolName,
                newPacketClassName,
                this.packetId,
                this.byteLength,
                copyEncodedBytes(),
                this.capturedAtMillis
        );
    }


    public byte[] copyEncodedBytes() {
        return this.encodedBytes.clone();
    }
}
