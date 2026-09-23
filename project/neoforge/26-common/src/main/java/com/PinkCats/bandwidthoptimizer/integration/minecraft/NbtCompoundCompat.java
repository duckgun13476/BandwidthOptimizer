package com.PinkCats.bandwidthoptimizer.integration.minecraft;

import net.minecraft.nbt.CompoundTag;

import java.util.Set;

public final class NbtCompoundCompat {
    private NbtCompoundCompat() {
    }

    public static String string(CompoundTag tag, String key) {
        return tag.getStringOr(key, "");
    }

    public static int intValue(CompoundTag tag, String key) {
        return tag.getIntOr(key, 0);
    }

    public static boolean booleanValue(CompoundTag tag, String key) {
        return tag.getBooleanOr(key, false);
    }

    public static CompoundTag compoundOrEmpty(CompoundTag tag, String key) {
        return tag.getCompoundOrEmpty(key);
    }

    public static boolean listIsEmpty(CompoundTag tag, String key, int elementType) {
        return tag.getListOrEmpty(key).isEmpty();
    }

    public static Set<String> keys(CompoundTag tag) {
        return tag.keySet();
    }
}
