package com.PinkCats.bandwidthoptimizer.test;

import com.PinkCats.bandwidthoptimizer.channel.ChannelIdentity;
import io.netty.channel.Channel;
import io.netty.util.AttributeKey;
import io.netty.util.DefaultAttributeMap;

import java.lang.reflect.Proxy;
import java.net.InetSocketAddress;
import java.util.concurrent.atomic.AtomicInteger;

public final class ProtocolLibProxyChannelIdRegressionMain {

    private ProtocolLibProxyChannelIdRegressionMain() {}

    public static void main(String[] args) throws Exception {
        AtomicInteger idCalls = new AtomicInteger();
        Channel proxyChannel = newBrokenIdChannelProxy(idCalls);
        String firstId = ChannelIdentity.longText(proxyChannel);
        for (int index = 0; index < 1_000; index++) {
            require(firstId.equals(ChannelIdentity.longText(proxyChannel)), "Fallback id should remain stable");
            require(firstId.equals(ChannelIdentity.shortText(proxyChannel)), "Fallback id should be shared by both renderings");
        }

        require(firstId.startsWith("fallback:"), "Broken proxy channel should use a fallback id");
        require(idCalls.get() == 1, "Broken proxy Channel.id() must be attempted only once");

        System.out.println("ProtocolLib proxy channel id regression matched");
    }

    private static Channel newBrokenIdChannelProxy(AtomicInteger idCalls) {
        DefaultAttributeMap attributes = new DefaultAttributeMap();
        return (Channel) Proxy.newProxyInstance(
                ProtocolLibProxyChannelIdRegressionMain.class.getClassLoader(),
                new Class<?>[]{Channel.class},
                (proxy, method, args) -> {
                    String methodName = method.getName();
                    if ("id".equals(methodName)) {
                        idCalls.incrementAndGet();
                        throw new AbstractMethodError("ProtocolLib proxy channel does not implement Channel.id()");
                    }
                    if ("attr".equals(methodName)) {
                        @SuppressWarnings("unchecked")
                        AttributeKey<Object> key = (AttributeKey<Object>) args[0];
                        return attributes.attr(key);
                    }
                    if ("remoteAddress".equals(methodName)) {
                        return new InetSocketAddress("127.0.0.1", 25565);
                    }
                    if ("toString".equals(methodName)) {
                        return "BrokenIdChannelProxy";
                    }
                    return null;
                }
        );
    }

    private static void require(boolean condition, String message) {
        if (!condition) {
            throw new IllegalStateException(message);
        }
    }
}
