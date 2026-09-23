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
import net.minecraft.resources.ResourceKey;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.crafting.RecipePropertySet;
import net.minecraft.world.item.crafting.SelectableRecipe;
import net.minecraft.world.item.crafting.StonecutterRecipe;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HexFormat;
import java.util.Map;

public final class RecipeSyncSemanticCompat {
    private RecipeSyncSemanticCompat() {}

    public static String semanticHash(Packet<?> packet, byte[] rawPacketBytes) {
        if (!(packet instanceof ClientboundUpdateRecipesPacket recipesPacket)) {
            return RecipeSyncDeltaCodec.sha256(rawPacketBytes);
        }
        MessageDigest digest = sha256();
        ArrayList<Map.Entry<?, RecipePropertySet>> itemSets = new ArrayList<>(recipesPacket.itemSets().entrySet());
        itemSets.sort(Comparator.comparing(entry -> entry.getKey().toString()));
        updateInt(digest, itemSets.size());
        for (Map.Entry<?, RecipePropertySet> entry : itemSets) {
            updateText(digest, entry.getKey().toString());
            ArrayList<String> itemIds = new ArrayList<>();
            BuiltInRegistries.ITEM.entrySet().forEach(itemEntry -> {
                if (entry.getValue().test(new ItemStack(itemEntry.getValue()))) {
                    itemIds.add(itemEntry.getKey().identifier().toString());
                }
            });
            itemIds.sort(String::compareTo);
            updateInt(digest, itemIds.size());
            itemIds.forEach(itemId -> updateText(digest, itemId));
        }
        ByteBuf buffer = Unpooled.buffer();
        try {
            RegistryFriendlyByteBuf registryBuffer = new RegistryFriendlyByteBuf(
                    buffer, RegistryAccess.fromRegistryOfRegistries(BuiltInRegistries.REGISTRY));
            SelectableRecipe.SingleInputSet.<StonecutterRecipe>noRecipeCodec()
                    .encode(registryBuffer, recipesPacket.stonecutterRecipes());
            byte[] stonecutterBytes = ByteBufUtil.getBytes(buffer);
            updateInt(digest, stonecutterBytes.length);
            digest.update(stonecutterBytes);
        } finally {
            buffer.release();
        }
        return HexFormat.of().formatHex(digest.digest());
    }

    public static RecipeSyncStructuralView structuralView(Packet<?> packet, byte[] rawPacketBytes) {
        if (!(packet instanceof ClientboundUpdateRecipesPacket recipesPacket)) {
            return null;
        }
        ArrayList<byte[]> records = new ArrayList<>(recipesPacket.itemSets().size() + 1);
        for (Map.Entry<ResourceKey<RecipePropertySet>, RecipePropertySet> entry
                : recipesPacket.itemSets().entrySet()) {
            ByteBuf entryBuffer = Unpooled.buffer();
            try {
                RegistryFriendlyByteBuf registryBuffer = new RegistryFriendlyByteBuf(
                        entryBuffer, RegistryAccess.fromRegistryOfRegistries(BuiltInRegistries.REGISTRY));
                ResourceKey.streamCodec(RecipePropertySet.TYPE_KEY).encode(registryBuffer, entry.getKey());
                RecipePropertySet.STREAM_CODEC.encode(registryBuffer, entry.getValue());
                records.add(ByteBufUtil.getBytes(entryBuffer));
            } finally {
                entryBuffer.release();
            }
        }
        ByteBuf stonecutterBuffer = Unpooled.buffer();
        try {
            RegistryFriendlyByteBuf registryBuffer = new RegistryFriendlyByteBuf(
                    stonecutterBuffer, RegistryAccess.fromRegistryOfRegistries(BuiltInRegistries.REGISTRY));
            SelectableRecipe.SingleInputSet.<StonecutterRecipe>noRecipeCodec()
                    .encode(registryBuffer, recipesPacket.stonecutterRecipes());
            records.add(ByteBufUtil.getBytes(stonecutterBuffer));
        } finally {
            stonecutterBuffer.release();
        }
        return RecipeSyncStructuralView.verified(rawPacketBytes, records);
    }

    private static MessageDigest sha256() {
        try {
            return MessageDigest.getInstance("SHA-256");
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException(exception);
        }
    }

    private static void updateText(MessageDigest digest, String value) {
        byte[] bytes = value.getBytes(StandardCharsets.UTF_8);
        updateInt(digest, bytes.length);
        digest.update(bytes);
    }

    private static void updateInt(MessageDigest digest, int value) {
        digest.update((byte) (value >>> 24));
        digest.update((byte) (value >>> 16));
        digest.update((byte) (value >>> 8));
        digest.update((byte) value);
    }
}
