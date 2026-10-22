package com.astra.revolution.gui;

import com.astra.revolution.gui.BaritoneBypassScreen.VectorIcon;

/**
 * KATEGORIE BARITONE BYPASS GUI
 */
public enum Category {
    BYPASS("GrimAC Bypass", "Main", VectorIcon.SHIELD),
    MOVEMENT("Path & Movement", "Main", VectorIcon.MOVEMENT),
    MINING("Legit Mining", "Main", VectorIcon.MINING),
    RENDER("Visuals", "Main", VectorIcon.RENDER),
    PRESETS("AntiCheat Presets", "Other", VectorIcon.SLIDERS);

    public final String label;
    public final String section;
    public final VectorIcon icon;

    Category(String label, String section, VectorIcon icon) {
        this.label = label;
        this.section = section;
        this.icon = icon;
    }
}
