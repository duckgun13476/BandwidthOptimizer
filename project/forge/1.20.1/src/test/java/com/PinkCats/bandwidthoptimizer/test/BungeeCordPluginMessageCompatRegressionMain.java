package com.PinkCats.bandwidthoptimizer.test;

import com.PinkCats.bandwidthoptimizer.integration.bungeecord.BungeeCordPluginMessageCompat;

public final class BungeeCordPluginMessageCompatRegressionMain {

    private BungeeCordPluginMessageCompatRegressionMain() {}

    public static void main(String[] args) {
        assertProxyControl("BungeeCord");
        assertProxyControl("bungeecord");
        assertProxyControl("bungeecord:main");
        assertProxyControl("BUNGEE:MAIN");
        assertProxyControl("potato:chat");
        assertProxyControl("POTATO:PCPBRIDGE");
        assertProxyControl("potato:tpa");
        assertNotProxyControl("minecraft:brand");
        assertNotProxyControl("modid:payload");
        assertNotProxyControl("potato:nametag");
        System.out.println("BungeeCord plugin-message compatibility regression passed.");
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
