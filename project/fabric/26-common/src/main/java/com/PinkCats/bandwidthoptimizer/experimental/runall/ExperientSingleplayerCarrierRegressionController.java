package com.PinkCats.bandwidthoptimizer.experimental.runall;

import com.PinkCats.bandwidthoptimizer.Config;
import com.PinkCats.bandwidthoptimizer.integration.minecraft.ClientWindowCompat;
import net.minecraft.client.Minecraft;
import net.minecraft.world.Difficulty;
import net.minecraft.world.level.GameType;
import net.minecraft.world.level.LevelSettings;
import net.minecraft.world.level.WorldDataConfiguration;
import net.minecraft.world.level.levelgen.WorldOptions;
import net.minecraft.world.level.levelgen.presets.WorldPresets;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;

public final class ExperientSingleplayerCarrierRegressionController {
    private static final int REQUIRED_STABLE_TICKS = 200;

    private static boolean started;
    private static boolean completed;
    private static int stableTicks;

    private ExperientSingleplayerCarrierRegressionController() {
    }

    public static void onClientTick(Minecraft minecraft) {
        String marker = System.getProperty(Config.RuntimeProperty.Experient.SINGLEPLAYER_CARRIER_MARKER, "").trim();
        if (!Boolean.getBoolean(Config.RuntimeProperty.Experient.SINGLEPLAYER_CARRIER_REGRESSION) || marker.isEmpty() || completed) {
            return;
        }

        if (!started) {
            if (minecraft.level != null || minecraft.player != null || ClientWindowCompat.hasOverlay(minecraft)) {
                return;
            }
            started = true;
            minecraft.createWorldOpenFlows().createFreshLevel(
                    "bo-carrier-regression-" + Long.toUnsignedString(System.nanoTime(), 36),
                    new LevelSettings("BO Carrier Regression", GameType.CREATIVE,
                            new LevelSettings.DifficultySettings(Difficulty.PEACEFUL, false, false),
                            true, WorldDataConfiguration.DEFAULT),
                    WorldOptions.defaultWithRandomSeed(),
                    WorldPresets::createNormalWorldDimensions,
                    ClientWindowCompat.screen(minecraft)
            );
            return;
        }

        if (!minecraft.isLocalServer() || minecraft.level == null || minecraft.player == null) {
            return;
        }
        if (++stableTicks < REQUIRED_STABLE_TICKS) {
            return;
        }

        completed = true;
        try {
            Files.writeString(Path.of(marker), "local-channel-carrier-regression=passed" + System.lineSeparator(),
                    StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING, StandardOpenOption.WRITE);
        } catch (IOException exception) {
            throw new IllegalStateException("Could not write singleplayer carrier regression marker", exception);
        } finally {
            minecraft.stop();
        }
    }
}
