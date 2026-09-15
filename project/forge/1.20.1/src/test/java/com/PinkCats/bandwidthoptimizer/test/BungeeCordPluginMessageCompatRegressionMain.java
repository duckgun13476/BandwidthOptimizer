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

        Files.writeString(config, "# keep me\n[general]\nvalue = 7\n\n[proxy]\nadditional_control_channels = [\"example:one\"] # preserve me\n\n[after]\nvalue = 9\n");
        assertMutation(ProxyControlChannelRegistry.add(config, "Example:Three"), true, true, 2);
        assertProxyControl("example:three");
        String persisted = Files.readString(config);
        check(persisted.contains("# keep me") && persisted.contains("# preserve me") && persisted.contains("[after]\nvalue = 9"),
                "channel update must preserve unrelated TOML content");
        check(persisted.contains("\"example:one\"") && persisted.contains("\"example:three\""),
                "added channel must be persisted");
        assertMutation(ProxyControlChannelRegistry.add(config, "example:three"), true, false, 2);
        assertMutation(ProxyControlChannelRegistry.add(config, "example:*"), false, false, 2);
        assertMutation(ProxyControlChannelRegistry.remove(config, "yuntpa:main"), true, false, 2);
        assertProxyControl("yuntpa:main");
        assertMutation(ProxyControlChannelRegistry.remove(config, "example:one"), true, true, 1);
        assertNotProxyControl("example:one");
        check(!Files.readString(config).contains("\"example:one\""), "removed channel must be deleted from TOML");
        assertReload(config, true, 1);
        assertProxyControl("example:three");

        Files.writeString(config, "[proxy]\nadditional_control_channels = [\"example:*\"]\n");
        assertReload(config, false, 1);
        assertMutation(ProxyControlChannelRegistry.add(config, "example:four"), false, false, 1);
        assertProxyControl("example:three");
        assertNotProxyControl("example:four");

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

    private static void assertMutation(ProxyControlChannelRegistry.MutationResult result,
                                       boolean success,
                                       boolean changed,
                                       int count) {
        if (result.success() != success || result.changed() != changed || result.additionalChannelCount() != count) {
            throw new AssertionError("Unexpected mutation result: " + result);
        }
    }

    private static void check(boolean condition, String message) {
        if (!condition) {
            throw new AssertionError(message);
        }
    }

    private static void assertNotProxyControl(String payloadChannel) {
        if (BungeeCordPluginMessageCompat.isProxyControlChannel(payloadChannel)) {
            throw new AssertionError("Expected normal payload channel: " + payloadChannel);
        }
    }
}
