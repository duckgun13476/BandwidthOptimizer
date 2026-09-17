package com.PinkCats.bandwidthoptimizer.integration.minecraft;

import com.PinkCats.bandwidthoptimizer.recipe.RecipeSyncDeltaCodec;
import com.PinkCats.bandwidthoptimizer.recipe.RecipeSyncStructuralView;
import io.netty.buffer.ByteBuf;
import io.netty.buffer.ByteBufUtil;
import io.netty.buffer.Unpooled;
import net.minecraft.core.RegistryAccess;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.protocol.Packet;
import net.minecraft.network.protocol.game.ClientboundUpdateRecipesPacket;
import net.minecraft.world.item.crafting.RecipeHolder;

import java.util.ArrayList;

public final class RecipeSyncSemanticCompat {
    private RecipeSyncSemanticCompat() {}

    public static String semanticHash(Packet<?> packet, byte[] rawPacketBytes) {
        return RecipeSyncDeltaCodec.sha256(rawPacketBytes);
    }

    public static RecipeSyncStructuralView structuralView(Packet<?> packet, byte[] rawPacketBytes) {
        if (!(packet instanceof ClientboundUpdateRecipesPacket recipesPacket)) {
            return null;
        }
        ArrayList<byte[]> records = new ArrayList<>(recipesPacket.getRecipes().size());
        for (RecipeHolder<?> recipe : recipesPacket.getRecipes()) {
            ByteBuf buffer = Unpooled.buffer();
            try {
                RegistryFriendlyByteBuf registryBuffer = new RegistryFriendlyByteBuf(
                        buffer, RegistryAccess.fromRegistryOfRegistries(BuiltInRegistries.REGISTRY));
                RecipeHolder.STREAM_CODEC.encode(registryBuffer, recipe);
                records.add(ByteBufUtil.getBytes(buffer));
            } finally {
                buffer.release();
            }
        }
        return RecipeSyncStructuralView.verified(rawPacketBytes, records);
    }
}
