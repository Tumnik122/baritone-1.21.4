package com.statuspillhud.client;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class StatusPillHudClient {
    
    private static final Logger LOGGER = LoggerFactory.getLogger("StatusPillHUD");
    
    public static void init() {
        LOGGER.info("[StatusPillHUD] Initializing SDF-based capsule HUD...");
        StatusPillHud hud = StatusPillHud.getInstance();
        LOGGER.info("[StatusPillHUD] Registration complete. OpenGL pipeline: {}", 
            hud.isShaderAvailable() ? "ACTIVE (GL 3.3 Core SDF)" : "FALLBACK (vanilla)");
    }
    
    public static StatusPillHud getHud() {
        return StatusPillHud.getInstance();
    }
}
