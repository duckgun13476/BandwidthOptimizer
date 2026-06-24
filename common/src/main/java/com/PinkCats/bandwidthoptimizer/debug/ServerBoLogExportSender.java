package com.PinkCats.bandwidthoptimizer.debug;

import net.minecraft.server.level.ServerPlayer;

public interface ServerBoLogExportSender {
    boolean send(ServerPlayer player, ServerBoLogExport export);
}
