package com.astra.revolution.perf;

import baritone.api.BaritoneAPI;
import baritone.api.IBaritone;
import baritone.api.Settings;

/**
 * Inteligentny regulator pamięci i wydajności Baritone.
 *
 * Zapobiega spadkom FPS i ścinkom u gracza (Bot 1) poprzez zachowanie pamięci podręcznej chunków,
 * stabilne czasy oczekiwania A* (bez pętli resetów co 350ms) oraz brak synchronicznego zapisu dyskowego w pętli gry.
 */
public final class BaritoneMemoryGuard {

    private static boolean settingsInitialized = false;

    private BaritoneMemoryGuard() {}

    public static void init(IBaritone baritone) {
        applySettings();
    }

    public static void init() {
        applySettings();
    }

    private static synchronized void applySettings() {
        if (settingsInitialized) return;
        settingsInitialized = true;

        Settings s = BaritoneAPI.getSettings();
        if (s == null) return;

        int botNum = 1;
        try {
            String p = System.getProperty("botNum", System.getProperty("astra.botNum", "1"));
            botNum = Integer.parseInt(p.trim());
        } catch (Throwable ignored) {}

        if (botNum > 1) {
            // Boty w tle (Bot 2+) - wyłączamy renderowanie 3D w oknie
            s.renderPath.value = false;
            s.renderPathAsLine.value = false;
            s.renderGoal.value = false;
            s.renderGoalXZBeacon.value = false;
            s.renderSelectionBoxes.value = false;

            s.chunkCaching.value = true; // Zawsze włączone dla płynnego pathfindingu
            s.pruneRegionsFromRAM.value = true;
            s.backfill.value = false;

            s.primaryTimeoutMS.value = 600L;
            s.failureTimeoutMS.value = 2500L;
            s.planAheadPrimaryTimeoutMS.value = 3000L;
            s.planAheadFailureTimeoutMS.value = 4000L;
            s.simplifyUnloadedYCoord.value = true;
            s.movementTimeoutTicks.value = 100;
        } else {
            // Główny Bot 1 (okno gracza) - 100% PŁYNNOŚCI, PEŁNA WIDOCZNOŚĆ
            s.renderPath.value = true;
            s.renderPathAsLine.value = true;
            s.renderGoal.value = true;
            s.renderGoalXZBeacon.value = true;
            s.renderSelectionBoxes.value = true;

            s.chunkCaching.value = true;
            s.pruneRegionsFromRAM.value = false; // Brak agresywnego czyszczenia = zero ścinek i dropów FPS
            s.backfill.value = false;

            s.primaryTimeoutMS.value = 1200L;
            s.failureTimeoutMS.value = 4000L;
            s.planAheadPrimaryTimeoutMS.value = 4000L;
            s.planAheadFailureTimeoutMS.value = 5000L;
            s.simplifyUnloadedYCoord.value = true;
            s.movementTimeoutTicks.value = 100;
        }
    }
}

