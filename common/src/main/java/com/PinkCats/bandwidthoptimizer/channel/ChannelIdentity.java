package com.PinkCats.bandwidthoptimizer.channel;

import com.PinkCats.bandwidthoptimizer.debug.CompatibilityIssueReporter;
import io.netty.channel.Channel;
import io.netty.channel.ChannelId;
import io.netty.util.AttributeKey;

import java.util.concurrent.atomic.AtomicBoolean;

public final class ChannelIdentity {

    private static final AttributeKey<String> SAFE_LONG_ID_KEY =
            AttributeKey.valueOf("bandwidthoptimizer:safe_channel_long_id");
    private static final String PROTOCOL_LIB_PROXY_CLASS =
            "com.comphenix.protocol.injector.netty.channel.NettyChannelProxy";
    private static final ClassValue<ChannelIdCapability> CHANNEL_ID_CAPABILITIES = new ClassValue<>() {
        @Override
        protected ChannelIdCapability computeValue(Class<?> type) {
            return new ChannelIdCapability(type != null && PROTOCOL_LIB_PROXY_CLASS.equals(type.getName()));
        }
    };

    private ChannelIdentity() {}

    public static String longText(Channel channel) {
        if (channel == null) {
            return "<unknown-channel>";
        }
        ChannelId channelId = resolveChannelId(channel, "ChannelIdentity.longText");
        return channelId == null ? fallbackLongText(channel) : channelId.asLongText();
    }

    public static String shortText(Channel channel) {
        if (channel == null) {
            return "<unknown-channel>";
        }
        ChannelId channelId = resolveChannelId(channel, "ChannelIdentity.shortText");
        return channelId == null ? fallbackLongText(channel) : channelId.asShortText();
    }

    private static ChannelId resolveChannelId(Channel channel, String context) {
        ChannelIdCapability capability = CHANNEL_ID_CAPABILITIES.get(channel.getClass());
        if (capability.isBroken()) {
            capability.reportKnownBroken(channel, context);
            return null;
        }
        try {
            return channel.id();
        } catch (LinkageError error) {
            if (capability.markBroken()) {
                CompatibilityIssueReporter.report(channelIdIssueKey(channel), error, context);
            }
            return null;
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

    private static final class ChannelIdCapability {
        private final boolean knownBroken;
        private final AtomicBoolean broken;
        private final AtomicBoolean knownBrokenReported = new AtomicBoolean();

        private ChannelIdCapability(boolean knownBroken) {
            this.knownBroken = knownBroken;
            this.broken = new AtomicBoolean(knownBroken);
        }

        private boolean isBroken() {
            return this.broken.get();
        }

        private boolean markBroken() {
            return this.broken.compareAndSet(false, true);
        }

        private void reportKnownBroken(Channel channel, String context) {
            if (!this.knownBroken || !this.knownBrokenReported.compareAndSet(false, true)) {
                return;
            }
            CompatibilityIssueReporter.report(
                    channelIdIssueKey(channel),
                    new UnsupportedOperationException("ProtocolLib proxy does not implement Channel.id()"),
                    context
            );
        }
    }
}
