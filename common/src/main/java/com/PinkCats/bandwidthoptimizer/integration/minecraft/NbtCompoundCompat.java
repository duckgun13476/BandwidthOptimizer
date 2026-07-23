package com.PinkCats.bandwidthoptimizer.integration.minecraft;

import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;

import java.util.Set;

/** Loader bridge for the CompoundTag accessor changes. */
public final class NbtCompoundCompat {
    private NbtCompoundCompat() {
    }

    public static String string(CompoundTag tag, String key) {
        return tag.getString(key);
    }

    public static int intValue(CompoundTag tag, String key) {
        return tag.getInt(key);
    }

    public static boolean booleanValue(CompoundTag tag, String key) {
        return tag.getBoolean(key);
    }

    public static CompoundTag compoundOrEmpty(CompoundTag tag, String key) {
        return tag.getCompound(key);
    }

    public static boolean listIsEmpty(CompoundTag tag, String key, int elementType) {
        return tag.getList(key, elementType).isEmpty();
    }

    public static Set<String> keys(CompoundTag tag) {
        return tag.getAllKeys();
    }
}
