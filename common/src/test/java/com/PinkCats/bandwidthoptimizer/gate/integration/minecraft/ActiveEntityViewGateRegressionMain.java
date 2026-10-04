package com.PinkCats.bandwidthoptimizer.gate.integration.minecraft;

import net.minecraft.network.protocol.game.ClientboundMoveEntityPacket;

import java.util.List;

public final class ActiveEntityViewGateRegressionMain {
    private ActiveEntityViewGateRegressionMain() {}

    public static void main(String[] args) {
        verifyHalfRateAndExactDelta();
        verifyRotationAndGroundState();
        verifyOverflowFailsOpen();
        verifyOneTickExpiryAndRemoval();
        verifyBoundedRetention();
        verifyPassThroughTransition();
        System.out.println("Active entity interpolation gate regression passed.");
    }

    private static void verifyPassThroughTransition() {
        ActiveEntityViewGate.PlayerState state = new ActiveEntityViewGate.PlayerState();
        ClientboundMoveEntityPacket packet = pos(17, 123, 45, -67);
        require(state.accept(17, packet, 11L).captured(), "transition fixture must have one held packet");
        require(state.take(17) == packet, "Sable transition must release the exact original packet");
        require(state.take(17) == null, "original packet must be released only once");
        require(ActiveEntityViewGate.snapshot().pendingEntities() == 0,
                "Sable transition must release its global slot");
        require(state.drainExpired(12L).isEmpty(), "released packet must not replay on next tick");
    }

    private static void verifyHalfRateAndExactDelta() {
        ActiveEntityViewGate.PlayerState state = new ActiveEntityViewGate.PlayerState();
        ClientboundMoveEntityPacket first = pos(7, 100, 0, 0);
        ClientboundMoveEntityPacket second = pos(7, 200, 5, -4);
        require(state.accept(7, first, 10L).replacement() == null,
                "even tick should send without delay");
        require(state.accept(7, first, 11L).captured(),
                "odd tick should hold one movement update");
        var result = state.accept(7, second, 12L);
        require(result.replacement() != null && !result.captured(), "next update must combine");
        require(result.replacement().getXa() == 300
                        && result.replacement().getYa() == 5
                        && result.replacement().getZa() == -4,
                "combined delta must exactly reconstruct the server position");
        require(ActiveEntityViewGate.snapshot().pendingEntities() == 0,
                "paired update must release its global slot");
    }

    private static void verifyRotationAndGroundState() {
        ActiveEntityViewGate.PlayerState state = new ActiveEntityViewGate.PlayerState();
        var first = new ClientboundMoveEntityPacket.PosRot(
                8, (short) 10, (short) 0, (short) 20, (byte) 15, (byte) 16, true);
        var second = new ClientboundMoveEntityPacket.Rot(8, (byte) 25, (byte) 26, false);
        var merged = ActiveEntityViewGate.combine(8, first, second, (byte) 25, (byte) 26);
        require(merged instanceof ClientboundMoveEntityPacket.PosRot,
                "position and latest rotation must share one packet");
        require(merged.getXa() == 10 && merged.getZa() == 20,
                "rotation-only update must preserve pending position");
        require(rotationByte(merged, "yRot") == 25 && rotationByte(merged, "xRot") == 26
                        && !merged.isOnGround(),
                "latest rotation and ground state must win");
        require(ActiveEntityViewGate.payloadBytes(8, first)
                        + ActiveEntityViewGate.payloadBytes(8, second)
                        > ActiveEntityViewGate.payloadBytes(8, merged),
                "one merged packet must use fewer payload bytes");
    }

    private static void verifyOverflowFailsOpen() {
        ActiveEntityViewGate.PlayerState state = new ActiveEntityViewGate.PlayerState();
        var first = pos(9, 32760, 0, 0);
        var second = pos(9, 100, 0, 0);
        require(state.accept(9, first, 31L).captured(), "first update must be held");
        var result = state.accept(9, second, 32L);
        require(result.overflow() == first && result.replacement() == null,
                "unrepresentable sum must send both original packets in order");
        require(ActiveEntityViewGate.snapshot().pendingEntities() == 0,
                "overflow must release its global slot");
    }

    private static void verifyOneTickExpiryAndRemoval() {
        ActiveEntityViewGate.PlayerState state = new ActiveEntityViewGate.PlayerState();
        var first = pos(10, 20, 0, 0);
        require(state.accept(10, first, 41L).captured(), "first update must be held");
        require(state.drainExpired(41L).isEmpty(), "same tick must not flush");
        List<ClientboundMoveEntityPacket> expired = state.drainExpired(42L);
        require(expired.size() == 1 && expired.get(0) == first,
                "next tick must flush if no partner arrived");
        require(state.accept(11, pos(11, 20, 0, 0), 43L).captured(),
                "new entity must be held");
        require(state.remove(11), "removal/teleport must invalidate pending delta");
        require(state.drainExpired(44L).isEmpty(), "invalidated delta must never replay");
    }

    private static void verifyBoundedRetention() {
        ActiveEntityViewGate.PlayerState state = new ActiveEntityViewGate.PlayerState();
        for (int id = 1; id <= ActiveEntityViewGate.MAX_PENDING_ENTITIES_PER_PLAYER; id++) {
            require(state.accept(id, pos(id, 1, 0, 0), 51L).captured(),
                    "bounded entities must be admitted");
        }
        require(!state.accept(999, pos(999, 1, 0, 0), 51L).captured(),
                "per-player overflow must pass through");
        require(ActiveEntityViewGate.snapshot().pendingEntities()
                        == ActiveEntityViewGate.MAX_PENDING_ENTITIES_PER_PLAYER,
                "global pending count must match retained entries");
        state.clear();
        require(ActiveEntityViewGate.snapshot().pendingEntities() == 0,
                "clear must release all global slots");
    }

    private static ClientboundMoveEntityPacket.Pos pos(int id, int x, int y, int z) {
        return new ClientboundMoveEntityPacket.Pos(
                id, (short) x, (short) y, (short) z, true);
    }

    private static byte rotationByte(ClientboundMoveEntityPacket packet, String field) {
        try {
            var member = ClientboundMoveEntityPacket.class.getDeclaredField(field);
            member.setAccessible(true);
            return member.getByte(packet);
        } catch (ReflectiveOperationException exception) {
            throw new AssertionError("Unable to read test packet rotation", exception);
        }
    }

    private static void require(boolean condition, String message) {
        if (!condition) {
            throw new AssertionError(message);
        }
    }
}
