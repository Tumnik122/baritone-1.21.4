package baritone.bypass;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.components.BossHealthOverlay;
import net.minecraft.client.gui.components.LerpingBossEvent;

import java.lang.reflect.Field;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;

/**
 * Wykrywacz stanu walki (Anti-CombatLog) oparty na analizie BossBaru (jak w Exosware / HVD).
 *
 * Zapobiega samobójczemu rozłączeniu bota (disconnect), gdy na serwerze aktywny jest plugin
 * typu CombatLogX / AntiRelog / PvPManager. Wyrzucenie z serwera podczas walki skutkuje
 * natychmiastową śmiercią i utratą całego ekwipunku.
 */
public final class CombatLogDetector {

    private static Field eventsField = null;
    private static boolean reflectionInitialized = false;

    private CombatLogDetector() {}

    private static final String[] CANDIDATE_FIELD_NAMES = {"events", "bossBars", "map"};

    private static void initReflection() {
        if (reflectionInitialized) return;
        reflectionInitialized = true;
        try {
            // 1. Spróbuj dopasować znane nazwy pól z MojMap / Yarn
            for (String candidate : CANDIDATE_FIELD_NAMES) {
                try {
                    Field f = BossHealthOverlay.class.getDeclaredField(candidate);
                    if (Map.class.isAssignableFrom(f.getType())) {
                        f.setAccessible(true);
                        eventsField = f;
                        return;
                    }
                } catch (NoSuchFieldException ignored) {}
            }
            // 2. Fallback: iteruj po wszystkich polach typu Map
            for (Field f : BossHealthOverlay.class.getDeclaredFields()) {
                if (Map.class.isAssignableFrom(f.getType())) {
                    f.setAccessible(true);
                    eventsField = f;
                    break;
                }
            }
        } catch (Throwable t) {
            baritone.api.utils.Helper.HELPER.logDebug("CombatLogDetector: nie udało się zainicjalizować refleksji BossHealthOverlay: " + t.getMessage());
        }
    }

    /**
     * Sprawdza czy gracz znajduje się w trybie walki (Combat Tag).
     * Skanuje paski BossBaru w poszukiwaniu fraz: "pvp", "walka", "combat", "antirelog".
     */
    public static boolean isPlayerInCombat() {
        Minecraft mc = Minecraft.getInstance();
        if (mc == null || mc.gui == null) return false;

        BossHealthOverlay overlay = mc.gui.getBossOverlay();
        if (overlay == null) return false;

        initReflection();
        if (eventsField == null) return false;

        try {
            @SuppressWarnings("unchecked")
            Map<UUID, LerpingBossEvent> map = (Map<UUID, LerpingBossEvent>) eventsField.get(overlay);
            if (map != null && !map.isEmpty()) {
                for (LerpingBossEvent event : map.values()) {
                    if (event != null && event.getName() != null) {
                        String name = event.getName().getString().toLowerCase(Locale.ROOT);
                        if (name.contains("pvp") || name.contains("walka") || name.contains("combat")
                                || name.contains("antirelog") || name.contains("anty-relog")
                                || name.contains("antypvp") || name.contains("combatlog") || name.contains("fight")) {
                            return true;
                        }
                    }
                }
            }
        } catch (Throwable t) {
            baritone.api.utils.Helper.HELPER.logDebug("CombatLogDetector: błąd sprawdzania pasków bossów: " + t.getMessage());
        }

        return false;
    }
}
