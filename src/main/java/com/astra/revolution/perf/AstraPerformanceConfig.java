package com.astra.revolution.perf;

import com.mojang.blaze3d.systems.RenderSystem;
import net.minecraft.client.Minecraft;
import org.lwjgl.glfw.GLFW;

import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Pillar 6: Bulletproof Headless & Startup Lock Guard.
 * Guarantees the primary client window never stalls on a blank white DWM surface,
 * while hiding secondary headless bot windows cleanly at the GLFW level.
 */
public final class AstraPerformanceConfig {

    public static final AtomicBoolean HEADLESS_MODE = new AtomicBoolean(
            Boolean.parseBoolean(System.getProperty("astra.headless", "false"))
    );
    private static final AtomicBoolean WINDOW_HIDDEN_STATE = new AtomicBoolean(false);

    public static final AtomicBoolean AUTO_HEADLESS_UNFOCUSED = new AtomicBoolean(false);

    public static final boolean DISABLE_AUDIO = Boolean.parseBoolean(
            System.getProperty("astra.disableAudio", "false")
    );
    public static final boolean DISABLE_PARTICLES = Boolean.parseBoolean(
            System.getProperty("astra.disableParticles", "false")
    );

    public static final AtomicInteger HEADLESS_LOOP_SLEEP_MS = new AtomicInteger(20);

    private AstraPerformanceConfig() {}

    public static int getBotNum() {
        try {
            String raw = System.getProperty("astra.botNum", System.getProperty("botNum", "1"));
            return Integer.parseInt(raw.trim());
        } catch (Throwable e) {
            return 1;
        }
    }

    /**
     * Returns true if this JVM instance is the primary interactive player window (Bot #1).
     */
    public static boolean isPrimaryClient() {
        return getBotNum() <= 1 && !Boolean.parseBoolean(System.getProperty("astra.forceSecondaryBot", "false"));
    }

    /**
     * Primary client is strictly immune to headless frame-skipping so GLFW backbuffer swaps
     * and Windows DWM composition never freeze on a white screen during startup or runtime.
     */
    public static boolean isHeadless() {
        if (isPrimaryClient()) {
            return false;
        }
        return HEADLESS_MODE.get();
    }

    /**
     * Triggered by the HTTP control plane (/api/render?headless=false or true).
     * Schedules native GLFW window show/hide on the main render thread.
     */
    public static void setHeadlessMode(boolean headlessRequested) {
        if (isPrimaryClient()) {
            HEADLESS_MODE.set(false);
            ensureWindowVisibility(true);
            return;
        }
        HEADLESS_MODE.set(headlessRequested);
        ensureWindowVisibility(!headlessRequested);
    }

    /**
     * Synchronizes the native GLFW window visibility with the current headless state.
     * Must be invoked once per client tick or frame from MixinMinecraft.
     */
    public static void syncWindowState(Minecraft mc) {
        if (mc == null || mc.getWindow() == null) {
            return;
        }
        long windowHandle = mc.getWindow().getWindow();
        if (windowHandle == 0L) {
            return;
        }

        if (isPrimaryClient()) {
            if (WINDOW_HIDDEN_STATE.compareAndSet(true, false)) {
                GLFW.glfwShowWindow(windowHandle);
            }
            return;
        }

        boolean shouldHide = isHeadless();
        if (shouldHide && WINDOW_HIDDEN_STATE.compareAndSet(false, true)) {
            GLFW.glfwHideWindow(windowHandle);
        } else if (!shouldHide && WINDOW_HIDDEN_STATE.compareAndSet(true, false)) {
            GLFW.glfwShowWindow(windowHandle);
            GLFW.glfwFocusWindow(windowHandle);
        }
    }

    private static void ensureWindowVisibility(boolean visible) {
        Minecraft mc = Minecraft.getInstance();
        if (mc == null) {
            return;
        }
        RenderSystem.recordRenderCall(() -> {
            if (mc.getWindow() == null) {
                return;
            }
            long handle = mc.getWindow().getWindow();
            if (handle == 0L) {
                return;
            }
            if (visible) {
                GLFW.glfwShowWindow(handle);
                WINDOW_HIDDEN_STATE.set(false);
            } else if (!isPrimaryClient()) {
                GLFW.glfwHideWindow(handle);
                WINDOW_HIDDEN_STATE.set(true);
            }
        });
    }
}
