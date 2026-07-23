package com.PinkCats.bandwidthoptimizer.gate.integration.create;

import net.minecraft.nbt.CompoundTag;

final class CreateGateSoundPolicy {

    private CreateGateSoundPolicy() {}

    static SoundState capture(String typeKey, CompoundTag tag) {
        String type = path(typeKey);
        CompoundTag safeTag = tag == null ? new CompoundTag() : tag;
        return new SoundState(
                type, safeTag.getString("Phase"), safeTag.getString("State"), safeTag.getInt("Ticks"),
                safeTag.getInt("CountDown"), safeTag.getInt("Pitch"), safeTag.getBoolean("Running"),
                safeTag.getBoolean("Fistbump"), safeTag.contains("Particle"),
                safeTag.contains("ParticleItems") && !safeTag.getList("ParticleItems", 10).isEmpty(),
                safeTag.contains("Animation") && !"NONE".equals(safeTag.getString("Animation")),
                itemFingerprint(safeTag));
    }

    static boolean shouldFlush(String typeKey, SoundState previous, SoundState current) {
        if (!CreateGateTypePolicy.isSoundClassified(typeKey) || current == null) {
            return false;
        }
        return switch (current.type()) {
            case "mechanical_press" -> current.hasParticleItems() || (current.running() && current.ticks() >= 120
                    && (previous == null || !previous.running() || previous.ticks() < 120 || previous.ticks() > current.ticks()));
            case "deployer" -> current.hasParticle() || (previous != null
                    && (previous.fistBump() != current.fistBump()
                    || (!previous.heldItem().equals(current.heldItem()) && !current.heldItem().isBlank())));
            case "mechanical_crafter" -> previous != null
                    && (("EXPORTING".equals(previous.phase()) && "WAITING".equals(current.phase()))
                    || ("CRAFTING".equals(current.phase()) && current.countDown() <= 1000 && previous.countDown() > 1000));
            case "mechanical_arm" -> previous != null && "SEARCH_OUTPUTS".equals(current.phase())
                    && !previous.heldItem().equals(current.heldItem()) && !current.heldItem().isBlank();
            case "cuckoo_clock" -> current.hasAnimation();
            case "steam_whistle" -> previous != null && previous.pitch() != current.pitch();
            default -> false;
        };
    }

    private static String itemFingerprint(CompoundTag tag) {
        if (tag == null || !tag.contains("HeldItem")) {
            return "";
        }
        CompoundTag itemTag = tag.getCompound("HeldItem");
        return itemTag.isEmpty() ? "" : itemTag.getString("id") + "#" + itemTag.getInt("count") + "#" + itemTag.getInt("Count");
    }

    private static String path(String typeKey) {
        if (typeKey == null) {
            return "";
        }
        int namespaceSeparator = typeKey.indexOf(':');
        return namespaceSeparator < 0 ? typeKey : typeKey.substring(namespaceSeparator + 1);
    }

    record SoundState(
            String type, String phase, String state, int ticks, int countDown, int pitch, boolean running,
            boolean fistBump, boolean hasParticle, boolean hasParticleItems, boolean hasAnimation, String heldItem
    ) {}
}
