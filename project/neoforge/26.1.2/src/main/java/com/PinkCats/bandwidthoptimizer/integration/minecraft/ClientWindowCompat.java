package com.PinkCats.bandwidthoptimizer.integration.minecraft;

import net.minecraft.client.Minecraft;

public final class ClientWindowCompat {
    private ClientWindowCompat() {
    }

    public static boolean isIconified(Minecraft minecraft) {
        return minecraft != null && minecraft.getWindow().isIconified();
    }
}
