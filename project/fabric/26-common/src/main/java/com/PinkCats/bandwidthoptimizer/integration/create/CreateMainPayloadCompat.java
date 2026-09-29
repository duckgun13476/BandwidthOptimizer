package com.PinkCats.bandwidthoptimizer.integration.create;

import net.minecraft.network.protocol.Packet;

public final class CreateMainPayloadCompat {
    private CreateMainPayloadCompat() {
    }

    public static String presentationOnlyKey(Packet<?> packet) {
        return null;
    }
}
