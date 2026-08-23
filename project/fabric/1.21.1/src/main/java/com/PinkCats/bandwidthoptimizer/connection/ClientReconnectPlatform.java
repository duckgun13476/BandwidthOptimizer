package com.PinkCats.bandwidthoptimizer.connection;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.ConnectScreen;
import net.minecraft.client.gui.screens.DisconnectedScreen;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.gui.screens.TitleScreen;
import net.minecraft.client.multiplayer.ServerData;
import net.minecraft.client.multiplayer.resolver.ServerAddress;

public enum ClientReconnectPlatform implements ClientReconnectCoordinator.Platform {
    INSTANCE;

    @Override
    public ClientReconnectCoordinator.Target currentTarget() {
        ServerData serverData = Minecraft.getInstance().getCurrentServer();
        return serverData == null
                ? null
                : new ClientReconnectCoordinator.Target(serverData.name, serverData.ip);
    }

    @Override
    public boolean hasActivePlayConnection() {
        Minecraft minecraft = Minecraft.getInstance();
        return minecraft.level != null && minecraft.getConnection() != null;
    }

    @Override
    public boolean isConnectInProgress() {
        return Minecraft.getInstance().screen instanceof ConnectScreen;
    }

    @Override
    public boolean isDisconnectedScreenReady() {
        return Minecraft.getInstance().screen instanceof DisconnectedScreen;
    }

    @Override
    public boolean startConnection(ClientReconnectCoordinator.Target target) {
        Minecraft minecraft = Minecraft.getInstance();
        Screen parent = minecraft.screen == null ? new TitleScreen() : minecraft.screen;
        ServerAddress address = ServerAddress.parseString(target.address());
        ServerData serverData = new ServerData(target.name(), target.address(), ServerData.Type.OTHER);
        ConnectScreen.startConnecting(parent, minecraft, address, serverData, false, null);
        return true;
    }
}
