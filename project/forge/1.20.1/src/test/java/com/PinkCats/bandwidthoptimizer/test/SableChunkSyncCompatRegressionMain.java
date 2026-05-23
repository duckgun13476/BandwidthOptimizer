package com.PinkCats.bandwidthoptimizer.test;

import com.PinkCats.bandwidthoptimizer.chunk.classify.packet.ChunkPacketCoordinate;

import java.lang.reflect.Constructor;
import java.lang.reflect.Method;
import java.util.Set;

public final class SableChunkSyncCompatRegressionMain {

    private SableChunkSyncCompatRegressionMain() {}

    public static void main(String[] args) throws Exception {
        Object state = newState();
        Method beginInitialSync = method("beginInitialSync", Long.class);
        Method finishInitialSync = method("finishInitialSync", Long.class);
        Method rememberInitialSyncChunk = method("rememberInitialSyncChunk", ChunkPacketCoordinate.class);
        Method removeUnknownTrackedChunks = method("removeUnknownTrackedChunks");
        Method removeTrackedPlot = method("removeTrackedPlot", long.class);
        Method isInitialSyncActive = method("isInitialSyncActive");

        beginInitialSync.invoke(state, new Object[]{null});
        require((Boolean) isInitialSyncActive.invoke(state), "unknown initial sync did not become active");
        rememberInitialSyncChunk.invoke(state, ChunkPacketCoordinate.ofChunk(4, 9));
        rememberInitialSyncChunk.invoke(state, ChunkPacketCoordinate.ofChunk(5, 9));
        require((Integer) finishInitialSync.invoke(state, new Object[]{null}) == 2, "unknown finalize lost tracked chunks");
        require(!(Boolean) isInitialSyncActive.invoke(state), "unknown finalize did not leave initial sync");
        require(((Set<?>) removeUnknownTrackedChunks.invoke(state)).size() == 2, "unknown stop did not invalidate tracked chunks");
        require(((Set<?>) removeUnknownTrackedChunks.invoke(state)).isEmpty(), "unknown tracked chunks leaked after stop");

        long plotCoordinate = plotCoordinate(12, -3);
        beginInitialSync.invoke(state, plotCoordinate);
        rememberInitialSyncChunk.invoke(state, ChunkPacketCoordinate.ofChunk(12, -3));
        rememberInitialSyncChunk.invoke(state, ChunkPacketCoordinate.ofChunk(13, -3));
        require((Integer) finishInitialSync.invoke(state, plotCoordinate) == 2, "known plot finalize lost tracked chunks");
        require(((Set<?>) removeTrackedPlot.invoke(state, plotCoordinate)).size() == 2, "known plot stop did not invalidate tracked chunks");
        require(((Set<?>) removeTrackedPlot.invoke(state, plotCoordinate)).isEmpty(), "known plot chunks leaked after stop");

        beginInitialSync.invoke(state, new Object[]{null});
        for (int index = 0; index < 2100; index++) {
            rememberInitialSyncChunk.invoke(state, ChunkPacketCoordinate.ofChunk(index, 42));
        }
        finishInitialSync.invoke(state, new Object[]{null});
        require(((Set<?>) removeUnknownTrackedChunks.invoke(state)).size() == 2048, "unknown chunk cap changed unexpectedly");

        System.out.println("SableChunkSyncCompat regression matched");
    }

    private static Object newState() throws Exception {
        Class<?> stateClass = Class.forName("com.PinkCats.bandwidthoptimizer.compat.sable.SableChunkSyncCompat$SableSyncState");
        Constructor<?> constructor = stateClass.getDeclaredConstructor();
        constructor.setAccessible(true);
        return constructor.newInstance();
    }

    private static Method method(String name, Class<?>... parameterTypes) throws Exception {
        Class<?> stateClass = Class.forName("com.PinkCats.bandwidthoptimizer.compat.sable.SableChunkSyncCompat$SableSyncState");
        Method method = stateClass.getDeclaredMethod(name, parameterTypes);
        method.setAccessible(true);
        return method;
    }

    private static long plotCoordinate(int chunkX, int chunkZ) {
        return ((long) chunkZ << 32) | (chunkX & 0xffffffffL);
    }

    private static void require(boolean condition, String message) {
        if (!condition) {
            throw new IllegalStateException(message);
        }
    }
}
