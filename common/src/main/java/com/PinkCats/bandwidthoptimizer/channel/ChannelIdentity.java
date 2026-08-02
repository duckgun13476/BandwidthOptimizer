package com.PinkCats.bandwidthoptimizer.channel;

import com.PinkCats.bandwidthoptimizer.debug.CompatibilityIssueReporter;
import io.netty.channel.Channel;
import io.netty.channel.ChannelId;
import io.netty.util.AttributeKey;

public final class ChannelIdentity {

    private static final AttributeKey<String> SAFE_LONG_ID_KEY =
            AttributeKey.valueOf("bandwidthoptimizer:safe_channel_long_id");

    private ChannelIdentity() {}

    public static String longText(Channel channel) {
        if (channel == null) {
            return "<unknown-channel>";
        }
        try {
            ChannelId channelId = channel.id();
            return channelId == null ? fallbackLongText(channel) : channelId.asLongText();
        } catch (LinkageError error) {
            CompatibilityIssueReporter.report(channelIdIssueKey(channel), error, "ChannelIdentity.longText");
            return fallbackLongText(channel);
        }
    }

    public static String shortText(Channel channel) {
        if (channel == null) {
            return "<unknown-channel>";
        }
        try {
            ChannelId channelId = channel.id();
            return channelId == null ? fallbackLongText(channel) : channelId.asShortText();
        } catch (LinkageError error) {
            CompatibilityIssueReporter.report(channelIdIssueKey(channel), error, "ChannelIdentity.shortText");
            return fallbackLongText(channel);
        }
    }

    private static String channelIdIssueKey(Channel channel) {
        return "channel_identity.id_linkage:" + channel.getClass().getName();
    }

    private static String fallbackLongText(Channel channel) {
        try {
            String existingId = channel.attr(SAFE_LONG_ID_KEY).get();
            if (existingId != null && !existingId.isBlank()) {
                return existingId;
            }
            String generatedId = generateFallbackLongText(channel);
            String racedId = channel.attr(SAFE_LONG_ID_KEY).setIfAbsent(generatedId);
            return racedId == null ? generatedId : racedId;
        } catch (RuntimeException | LinkageError error) {
            return generateFallbackLongText(channel);
        }
    }

    private static String generateFallbackLongText(Channel channel) {
        return "fallback:" + channel.getClass().getName()
                + ':' + Integer.toHexString(System.identityHashCode(channel))
                + ':' + safeRemoteAddress(channel);
    }

    private static String safeRemoteAddress(Channel channel) {
        try {
            return String.valueOf(channel.remoteAddress());
        } catch (RuntimeException | LinkageError error) {
            return "<unknown-remote>";
        }
    }
}
