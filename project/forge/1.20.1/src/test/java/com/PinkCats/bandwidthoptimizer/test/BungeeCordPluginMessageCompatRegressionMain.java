package com.PinkCats.bandwidthoptimizer.integration.bungeecord;

import java.nio.file.Files;
import java.nio.file.Path;

public final class BungeeCordPluginMessageCompatRegressionMain {

    private BungeeCordPluginMessageCompatRegressionMain() {}

    public static void main(String[] args) throws Exception {
        ProxyControlChannelRegistry.resetForTests();
        assertProxyControl("BungeeCord");
        assertProxyControl("bungeecord");
        assertProxyControl("bungeecord:main");
        assertProxyControl("BUNGEE:MAIN");
        assertProxyControl("potato:chat");
        assertProxyControl("POTATO:PCPBRIDGE");
        assertProxyControl("potato:tpa");
        assertProxyControl("yuntpa:main");
        assertNotProxyControl("minecraft:brand");
        assertNotProxyControl("modid:payload");
        assertNotProxyControl("potato:nametag");

        Path directory = Files.createTempDirectory("bo-proxy-channel-regression");
        Path config = directory.resolve("bandwidthoptimizer-common.toml");
        Files.writeString(config, "[proxy]\nadditional_control_channels = [\"example:one\", \"Example:Two\"]\n");
        assertReload(config, true, 2);
        assertProxyControl("example:one");
        assertProxyControl("EXAMPLE:TWO");

        Files.writeString(config, "[proxy]\nadditional_control_channels = [\"example:*\"]\n");
        assertReload(config, false, 2);
        assertProxyControl("example:one");

        Files.writeString(config, "[proxy]\nadditional_control_channels = []\n");
        assertReload(config, true, 0);
        assertNotProxyControl("example:one");
        assertProxyControl("yuntpa:main");
        System.out.println("BungeeCord plugin-message compatibility regression passed.");
    }

    private static void assertReload(Path config, boolean success, int count) {
        ProxyControlChannelRegistry.ReloadResult result = ProxyControlChannelRegistry.reload(config);
        if (result.success() != success || result.additionalChannelCount() != count) {
            throw new AssertionError("Unexpected reload result: " + result);
        }
    }

    private static void assertProxyControl(String payloadChannel) {
        if (!BungeeCordPluginMessageCompat.isProxyControlChannel(payloadChannel)) {
            throw new AssertionError("Expected BungeeCord proxy control channel: " + payloadChannel);
        }
    }

    private static void assertNotProxyControl(String payloadChannel) {
        if (BungeeCordPluginMessageCompat.isProxyControlChannel(payloadChannel)) {
            throw new AssertionError("Expected normal payload channel: " + payloadChannel);
        }
    }
}
