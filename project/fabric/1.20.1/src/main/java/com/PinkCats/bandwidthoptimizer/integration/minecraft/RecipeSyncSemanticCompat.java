package com.PinkCats.bandwidthoptimizer.integration.minecraft;

import com.PinkCats.bandwidthoptimizer.recipe.RecipeSyncDeltaCodec;
import com.PinkCats.bandwidthoptimizer.recipe.RecipeSyncStructuralView;
import io.netty.buffer.ByteBuf;
import io.netty.buffer.ByteBufUtil;
import io.netty.buffer.Unpooled;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.protocol.Packet;
import net.minecraft.network.protocol.game.ClientboundUpdateRecipesPacket;
import net.minecraft.world.item.crafting.Recipe;

import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HexFormat;

public final class RecipeSyncSemanticCompat {
    private RecipeSyncSemanticCompat() {}

    public static String semanticHash(Packet<?> packet, byte[] rawPacketBytes) {
        if (!(packet instanceof ClientboundUpdateRecipesPacket recipesPacket)) {
            return RecipeSyncDeltaCodec.sha256(rawPacketBytes);
        }
        MessageDigest digest = sha256();
        ArrayList<Recipe<?>> recipes = new ArrayList<>(recipesPacket.getRecipes());
        recipes.sort(Comparator.comparing(recipe -> recipe.getId().toString()));
        updateInt(digest, recipes.size());
        for (Recipe<?> recipe : recipes) {
            byte[] encoded = encodeRecipe(recipe);
            updateInt(digest, encoded.length);
            digest.update(encoded);
        }
        return HexFormat.of().formatHex(digest.digest());
    }

    public static RecipeSyncStructuralView structuralView(Packet<?> packet, byte[] rawPacketBytes) {
        if (!(packet instanceof ClientboundUpdateRecipesPacket recipesPacket)) {
            return null;
        }
        ArrayList<byte[]> records = new ArrayList<>(recipesPacket.getRecipes().size());
        for (Recipe<?> recipe : recipesPacket.getRecipes()) {
            records.add(encodeRecipe(recipe));
        }
        return RecipeSyncStructuralView.verified(rawPacketBytes, records);
    }

    private static byte[] encodeRecipe(Recipe<?> recipe) {
        ByteBuf buffer = Unpooled.buffer();
        try {
            ClientboundUpdateRecipesPacket.toNetwork(new FriendlyByteBuf(buffer), recipe);
            return ByteBufUtil.getBytes(buffer);
        } finally {
            buffer.release();
        }
    }

    private static MessageDigest sha256() {
        try {
            return MessageDigest.getInstance("SHA-256");
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException(exception);
        }
    }

    private static void updateInt(MessageDigest digest, int value) {
        digest.update((byte) (value >>> 24));
        digest.update((byte) (value >>> 16));
        digest.update((byte) (value >>> 8));
        digest.update((byte) value);
    }
}
