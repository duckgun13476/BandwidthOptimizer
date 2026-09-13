package com.PinkCats.bandwidthoptimizer.gate;

import java.lang.reflect.Field;
import java.lang.reflect.Modifier;

public final class IdleGateClientPublicationRegressionMain {

    private IdleGateClientPublicationRegressionMain() {}

    public static void main(String[] args) throws Exception {
        Field currentMode = IdleGateClientController.class.getDeclaredField("currentMode");
        require(Modifier.isVolatile(currentMode.getModifiers()), "Idle mode is not safely published to Netty");

        currentMode.setAccessible(true);
        currentMode.set(null, IdleGateMode.BACKGROUND_IDLE);
        require(IdleGateClientController.currentMode() == IdleGateMode.BACKGROUND_IDLE,
                "Published idle mode was not observable");

        IdleGateClientController.onDisconnected();
        require(IdleGateClientController.currentMode() == IdleGateMode.ACTIVE,
                "Disconnect did not publish the active mode");
        System.out.println("Idle gate client publication regression passed");
    }

    private static void require(boolean condition, String message) {
        if (!condition) {
            throw new AssertionError(message);
        }
    }
}
