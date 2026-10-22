package com.statuspillhud.client;

import net.minecraft.client.gui.GuiGraphics;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class FallbackRenderer {
    
    private static final Logger LOGGER = LoggerFactory.getLogger(FallbackRenderer.class);
    
    public void renderCapsule(GuiGraphics guiGraphics, int x, int y, int width, int height) {
        // Renderowanie kapsuły przy użyciu metod vanilla GuiGraphics
        
        // 1. Tło z gradientem
        renderGradientBackground(guiGraphics, x, y, width, height);
        
        // 2. Ramka (border)
        renderBorder(guiGraphics, x, y, width, height);
        
        // 3. Efekt zaokrąglenia (symulacja)
        renderRoundedCorners(guiGraphics, x, y, width, height);
    }
    
    private void renderGradientBackground(GuiGraphics guiGraphics, int x, int y, 
                                           int width, int height) {
        // Główny gradient pionowy wewnątrz kapsuły
        guiGraphics.fillGradient(
            x + 2, y + 1, x + width - 2, y + height - 1,
            StatusPillHud.COLOR_BG_TOP,
            StatusPillHud.COLOR_BG_BOTTOM
        );
        // Boczne wypelnienia dla efektu zaokraglenia
        guiGraphics.fillGradient(
            x + 1, y + 2, x + 2, y + height - 2,
            StatusPillHud.COLOR_BG_TOP,
            StatusPillHud.COLOR_BG_BOTTOM
        );
        guiGraphics.fillGradient(
            x + width - 2, y + 2, x + width - 1, y + height - 2,
            StatusPillHud.COLOR_BG_TOP,
            StatusPillHud.COLOR_BG_BOTTOM
        );
    }
    
    private void renderBorder(GuiGraphics guiGraphics, int x, int y, 
                               int width, int height) {
        int borderColor = 0x809D00FF; // Półprzezroczysty neonowy fiolet
        
        // Górna linia
        guiGraphics.fill(x + 2, y, x + width - 2, y + 1, borderColor);
        // Dolna linia
        guiGraphics.fill(x + 2, y + height - 1, x + width - 2, y + height, borderColor);
        // Lewa linia
        guiGraphics.fill(x, y + 2, x + 1, y + height - 2, borderColor);
        // Prawa linia
        guiGraphics.fill(x + width - 1, y + 2, x + width, y + height - 2, borderColor);
        
        // Punkty łączące rogi
        guiGraphics.fill(x + 1, y + 1, x + 2, y + 2, borderColor);
        guiGraphics.fill(x + width - 2, y + 1, x + width - 1, y + 2, borderColor);
        guiGraphics.fill(x + 1, y + height - 2, x + 2, y + height - 1, borderColor);
        guiGraphics.fill(x + width - 2, y + height - 2, x + width - 1, y + height - 1, borderColor);
    }
    
    private void renderRoundedCorners(GuiGraphics guiGraphics, int x, int y, 
                                       int width, int height) {
        // Subtelne akcenty rogów
        int cornerAccent = 0x4000E5FF;
        guiGraphics.fill(x + 2, y + 1, x + 3, y + 2, cornerAccent);
        guiGraphics.fill(x + width - 3, y + 1, x + width - 2, y + 2, cornerAccent);
    }
}
