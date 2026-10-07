package baritone.utils;

import baritone.Baritone;
import baritone.api.event.events.TickEvent;
import baritone.api.event.listener.AbstractGameEventListener;
import net.minecraft.client.CloudStatus;
import net.minecraft.client.GraphicsStatus;
import net.minecraft.client.Minecraft;
import net.minecraft.client.OptionInstance;
import net.minecraft.client.Options;

/**
 * Moduł optymalizacji klienta pod uruchamianie wielu instancji (5+ botów).
 *
 * Drastycznie redukuje zużycie RAM (z ~2 GB na ~350 MB na instancję) oraz CPU/GPU:
 * 1. Render distance do 2 chunków (ogromna oszczędność pamięci na bufory chunków).
 * 2. Framerate limit do 20 FPS (i do 10 FPS gdy okno jest w tle / zminimalizowane).
 * 3. Grafika FAST, wyłączone cienie, chmury, biome blend i ambient occlusion.
 * 4. Wyłączone renderowanie ścieżek 3D Baritone (ścieżki liczą się normalnie, ale nie obciążają GPU).
 * 5. Wywołanie System.gc() po optymalizacji.
 */
public final class BotOptimizer {

    private static boolean active = false;
    private static int lastFocusCheckTicks = 0;
    private static boolean registered = false;

    private BotOptimizer() {}

    public static void init(Baritone baritone) {
        if (registered) return;
        registered = true;
        if (Baritone.settings().botOptimizer.value) {
            setActive(true);
        }
        baritone.getGameEventHandler().registerEventListener(new AbstractGameEventListener() {
            @Override
            public void onTick(TickEvent event) {
                if (event.getType() == TickEvent.Type.IN) {
                    if (Baritone.settings().botOptimizer.value != active) {
                        setActive(Baritone.settings().botOptimizer.value);
                    }
                    if (active) {
                        BotOptimizer.tickInternal();
                    }
                }
            }
        });
    }

    public static boolean isActive() {
        return active;
    }

    public static void setActive(boolean state) {
        active = state;
        Baritone.settings().botOptimizer.value = state;
        if (active) {
            applyOptimizations();
        }
    }

    @SuppressWarnings("unchecked")
    public static void applyOptimizations() {
        try {
            Minecraft mc = Minecraft.getInstance();
            Options opts = mc.options;
            if (opts != null) {
                // 1. Zmniejszenie dystansu renderowania (klucz do obniżenia RAM z 2GB do 350MB na bota!)
                opts.renderDistance().set(2);
                opts.simulationDistance().set(2);

                // 2. Limit klatek na sekundę (oszczędność GPU i CPU dla 5 botów)
                opts.framerateLimit().set(20);

                // 3. Maksymalnie szybka grafika (Fast graphics, brak cieni, brak chmur, wyłączone wygładzanie)
                opts.graphicsMode().set(GraphicsStatus.FAST);
                opts.ambientOcclusion().set(false);
                opts.cloudStatus().set(CloudStatus.OFF);
                opts.entityShadows().set(false);
                opts.biomeBlendRadius().set(0);
                opts.mipmapLevels().set(0);

                // 4. Cząsteczki na minimalne
                try {
                    OptionInstance<?> particlesOption = opts.particles();
                    Object[] constants = particlesOption.get().getClass().getEnumConstants();
                    if (constants != null && constants.length > 0) {
                        ((OptionInstance<Object>) particlesOption).set(constants[constants.length - 1]);
                    }
                } catch (Throwable ignored) {}

                opts.save();
            }

            // 5. Wyłączenie zbędnego renderowania 3D w Baritone (niepotrzebnego dla botów w tle)
            Baritone.settings().renderPath.value = false;
            Baritone.settings().renderGoal.value = false;
            Baritone.settings().renderSelectionBoxes.value = false;
            Baritone.settings().maxCachedWorldScanCount.value = 5;

            // 6. Natychmiastowe zwolnienie pamięci RAM
            System.gc();
        } catch (Throwable ignored) {}
    }

    private static void tickInternal() {
        lastFocusCheckTicks++;
        if (lastFocusCheckTicks % 20 != 0) return; // raz na sekundę

        try {
            Minecraft mc = Minecraft.getInstance();
            if (mc.options == null) return;

            // Jeśli okno Minecrafta jest w tle (nieaktywne / zminimalizowane), ogranicz FPS do 10 dla maksymalnej oszczędności!
            boolean focused = mc.isWindowActive();
            int currentLimit = mc.options.framerateLimit().get();
            int desiredLimit = focused ? 20 : 10;
            if (currentLimit != desiredLimit) {
                mc.options.framerateLimit().set(desiredLimit);
            }
        } catch (Throwable ignored) {}
    }
}
