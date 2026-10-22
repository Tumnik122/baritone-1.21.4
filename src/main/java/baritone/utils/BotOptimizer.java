package baritone.utils;

import baritone.Baritone;
import baritone.api.event.events.TickEvent;
import baritone.api.event.listener.AbstractGameEventListener;
import net.minecraft.client.CloudStatus;
import net.minecraft.client.GraphicsStatus;
import net.minecraft.client.Minecraft;
import net.minecraft.client.Options;

/**
 * Inteligentny moduł optymalizacji klienta.
 *
 * Zapewnia płynne 165+ FPS dla głównego bota (Bot 1) bez mikro-zacięć (stutteringu)
 * oraz ultra-niskie zużycie RAM/CPU dla botów pracujących w tle (Bot 2+).
 */
public final class BotOptimizer {

    private static boolean active = false;
    private static boolean registered = false;

    private BotOptimizer() {}

    public static int getBotNumber() {
        try {
            String p = System.getProperty("botNum", System.getProperty("astra.botNum", "1"));
            return Integer.parseInt(p.trim());
        } catch (Throwable ignored) {
            return 1;
        }
    }

    public static void init(Baritone baritone) {
        if (registered) return;
        registered = true;

        // Dla bota gracza (Bot 1) domyślnie NIE włączamy agresywnego botOptimizera,
        // chyba że użytkownik wyraźnie wpisze #botopt on lub włączy go w ustawieniach
        if (getBotNumber() > 1 || Baritone.settings().botOptimizer.value) {
            setActive(true);
        }

        baritone.getGameEventHandler().registerEventListener(new AbstractGameEventListener() {
            @Override
            public void onTick(TickEvent event) {
                if (event.getType() == TickEvent.Type.IN) {
                    if (Baritone.settings().botOptimizer.value != active) {
                        setActive(Baritone.settings().botOptimizer.value);
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

    public static void applyOptimizations() {
        try {
            Minecraft mc = Minecraft.getInstance();
            Options opts = mc.options;
            if (opts == null) return;

            int botNum = getBotNumber();

            if (botNum > 1) {
                // Tylko dla botów w tle (Bot 2+)
                opts.renderDistance().set(2);
                opts.simulationDistance().set(2);
                opts.framerateLimit().set(30);
                opts.graphicsMode().set(GraphicsStatus.FAST);
                opts.ambientOcclusion().set(false);
                opts.cloudStatus().set(CloudStatus.OFF);
                opts.entityShadows().set(false);
                opts.biomeBlendRadius().set(0);
            } else {
                // Główny Bot 1 (gracz) - bez dławienia klatek i bez wymuszania 2 chunków!
                if (opts.framerateLimit().get() < 120) {
                    opts.framerateLimit().set(165);
                }
                // Nigdy nie zmieniamy mipmapLevels w trakcie gry (reload atlasu tekstur powoduje freeze i biały ekran!)
            }

            Baritone.settings().maxCachedWorldScanCount.value = 10;
        } catch (Throwable ignored) {}
    }
}

