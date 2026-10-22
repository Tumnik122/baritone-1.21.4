package com.astra.revolution.compat;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.Screen;

import java.lang.reflect.Constructor;

/**
 * 🌌 ASTRA // ARGON 4 - IN-GAME ACCOUNT SWITCHER (IAS) BRIDGE
 * Provides seamless integration with In-Game Account Switcher mod across
 * all Argon 4 glassmorphic screens (Main Menu, Multiplayer, Singleplayer).
 */
public final class IasCompat {

    private static Boolean available = null;

    private IasCompat() {}

    /**
     * Checks if the In-Game Account Switcher mod is present on the runtime classpath.
     */
    public static boolean isAvailable() {
        if (available == null) {
            try {
                Class.forName("ru.vidtu.ias.screen.AccountScreen");
                available = true;
            } catch (Throwable t) {
                available = false;
            }
        }
        return available;
    }

    /**
     * Opens the IAS AccountScreen with the given screen as its parent.
     * Returns true if successfully opened, false otherwise.
     */
    public static boolean openIasScreen(Screen parent) {
        try {
            Class<?> clazz = Class.forName("ru.vidtu.ias.screen.AccountScreen");
            // Check all declared constructors accepting a single parameter (Screen parent)
            for (Constructor<?> ctor : clazz.getDeclaredConstructors()) {
                if (ctor.getParameterCount() == 1) {
                    ctor.setAccessible(true);
                    Object screenObj = ctor.newInstance(parent);
                    if (screenObj instanceof Screen screen) {
                        Minecraft.getInstance().setScreen(screen);
                        return true;
                    }
                }
            }
        } catch (Throwable t) {
            t.printStackTrace();
        }
        return false;
    }
}
