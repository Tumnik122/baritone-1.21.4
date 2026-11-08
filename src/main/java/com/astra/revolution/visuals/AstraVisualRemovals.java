package com.astra.revolution.visuals;

/**
 * ZARZĄDZANIE CZYSTOŚCIĄ EKRANU (Inspirowane MoonDLC InGameOverlayRendererMixin & InGameHudMixin).
 *
 * Eliminuje agresywne, zasłaniające ekran efekty waniliowego Minecrafta:
 * 1. Low Fire / No Fire: Usuwa lub obniża płomienie, gdy gracz się pali, zapewniając 100% widoczności w PvP.
 * 2. No Suffocation Overlay: Blokuje brzydką teksturę wewnątrz bloków (kamień/ziemia) przy zablokowaniu głowy.
 * 3. Clear Water: Likwiduje ciemną, mętną nakładkę podwodną dla przejrzystej widoczności.
 * 4. No Portal / Confusion Nausea: Zapobiega uciążliwym drganiom i falowaniu kamery.
 */
public final class AstraVisualRemovals {

    private static boolean lowFireEnabled = true;
    private static boolean noSuffocationEnabled = true;
    private static boolean clearWaterEnabled = true;
    private static boolean noPortalNauseaEnabled = true;

    private AstraVisualRemovals() {}

    public static boolean isLowFireEnabled() {
        return lowFireEnabled;
    }

    public static void setLowFireEnabled(boolean enabled) {
        lowFireEnabled = enabled;
    }

    public static boolean isNoSuffocationEnabled() {
        return noSuffocationEnabled;
    }

    public static void setNoSuffocationEnabled(boolean enabled) {
        noSuffocationEnabled = enabled;
    }

    public static boolean isClearWaterEnabled() {
        return clearWaterEnabled;
    }

    public static void setClearWaterEnabled(boolean enabled) {
        clearWaterEnabled = enabled;
    }

    public static boolean isNoPortalNauseaEnabled() {
        return noPortalNauseaEnabled;
    }

    public static void setNoPortalNauseaEnabled(boolean enabled) {
        noPortalNauseaEnabled = enabled;
    }
}
