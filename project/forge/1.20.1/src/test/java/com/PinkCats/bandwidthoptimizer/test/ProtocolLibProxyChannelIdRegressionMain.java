package com.PinkCats.bandwidthoptimizer.test;

import com.PinkCats.bandwidthoptimizer.channel.ChannelIdentity;
import io.netty.channel.Channel;
import io.netty.util.AttributeKey;
import io.netty.util.DefaultAttributeMap;

import java.lang.reflect.Proxy;
import java.net.InetSocketAddress;

public final class ProtocolLibProxyChannelIdRegressionMain {

    private ProtocolLibProxyChannelIdRegressionMain() {}

    public static void main(String[] args) throws Exception {
        Channel proxyChannel = newBrokenIdChannelProxy();
        String firstId = ChannelIdentity.longText(proxyChannel);
        String secondId = ChannelIdentity.longText(proxyChannel);

        require(firstId.startsWith("fallback:"), "Broken proxy channel should use a fallback id");
        require(firstId.equals(secondId), "Fallback channel id should be stable for one connection");

        System.out.println("ProtocolLib proxy channel id regression matched");
    }

    private static Channel newBrokenIdChannelProxy() {
        DefaultAttributeMap attributes = new DefaultAttributeMap();
        return (Channel) Proxy.newProxyInstance(
                ProtocolLibProxyChannelIdRegressionMain.class.getClassLoader(),
                new Class<?>[]{Channel.class},
                (proxy, method, args) -> {
                    String methodName = method.getName();
                    if ("id".equals(methodName)) {
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
