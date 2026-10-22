package com.statuspillhud.client;

import baritone.Baritone;
import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.vertex.PoseStack;
import net.minecraft.client.DeltaTracker;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.util.Mth;
import org.joml.Matrix4f;
import org.lwjgl.opengl.GL33;
import org.lwjgl.system.MemoryUtil;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.FloatBuffer;

public class StatusPillHud {
    
    private static final Logger LOGGER = LoggerFactory.getLogger(StatusPillHud.class);
    
    private static StatusPillHud instance;
    
    // === KONFIGURACJA STYLU ===
    private static final float PILL_HEIGHT = 22.0f;
    private static final float PILL_CORNER_RADIUS = 11.0f;
    private static final float GLOW_INTENSITY = 0.35f;
    private static final float SEPARATOR_ALPHA = 0.3f;
    private static final float PULSE_SPEED = 2.0f;
    
    // Kolory (ARGB) - publiczne dla FallbackRenderer
    public static final int COLOR_BG_TOP = 0xE6120817;      // Głęboki indygo z alpha
    public static final int COLOR_BG_BOTTOM = 0xE61A0A2E;   // Ciemny fiolet
    public static final int COLOR_ACCENT = 0xFF9D00FF;       // Neonowy fiolet
    public static final int COLOR_ACCENT_CYAN = 0xFF00E5FF;  // Cyjan
    public static final int COLOR_TEXT_PRIMARY = 0xFFE0E0E0;
    public static final int COLOR_TEXT_DIM = 0xFF808090;
    public static final int COLOR_SEPARATOR = 0x50FFFFFF;
    
    // Stan animacji
    private float currentWidth = 0.0f;
    private float targetWidth = 0.0f;
    private long sessionStartTime = 0L;
    
    // Bufory tekstowe (pre-alokowane, zero GC)
    private final StringBuilder statusText = new StringBuilder(128);
    private final StringBuilder invText = new StringBuilder(32);
    private final StringBuilder etaText = new StringBuilder(32);
    
    // Komponenty
    private final PillShaderProgram shaderProgram;
    private final BaritoneStatusProvider baritoneProvider;
    private final FallbackRenderer fallbackRenderer;
    
    // Stan GL do zapisu/przywracania
    private final GLStateSnapshot glState = new GLStateSnapshot();
    
    // VAO/VBO dla renderingu kapsuły
    private int vaoId = -1;
    private int vboId = -1;
    private static final int VERTEX_SIZE = 4; // x, y, u, v
    private static final int MAX_VERTICES = 6; // 2 trójkąty dla quad
    
    // Pre-alokowane bufory
    private final FloatBuffer matrixBuffer = MemoryUtil.memAllocFloat(16);
    private final FloatBuffer vertexBuffer = MemoryUtil.memAllocFloat(MAX_VERTICES * VERTEX_SIZE);
    
    public static synchronized StatusPillHud getInstance() {
        if (instance == null) {
            instance = new StatusPillHud();
        }
        return instance;
    }
    
    public StatusPillHud() {
        this.shaderProgram = new PillShaderProgram();
        this.baritoneProvider = new BaritoneStatusProvider();
        this.fallbackRenderer = new FallbackRenderer();
        
        initializeGeometry();
        sessionStartTime = System.currentTimeMillis();
        
        LOGGER.info("[StatusPillHUD] Geometry initialized. VAO: {}, VBO: {}", vaoId, vboId);
    }
    
    private void initializeGeometry() {
        vaoId = GL33.glGenVertexArrays();
        vboId = GL33.glGenBuffers();
        
        GL33.glBindVertexArray(vaoId);
        GL33.glBindBuffer(GL33.GL_ARRAY_BUFFER, vboId);
        
        // Dane wierzchołków: pozycja [-1, 1] i koordynaty tekstury [0, 1]
        float[] vertices = {
            // Pozycja    UV
            -1.0f, -1.0f, 0.0f, 0.0f,  // Lewy dolny
             1.0f, -1.0f, 1.0f, 0.0f,  // Prawy dolny
             1.0f,  1.0f, 1.0f, 1.0f,  // Prawy górny
            
            -1.0f, -1.0f, 0.0f, 0.0f,  // Lewy dolny
             1.0f,  1.0f, 1.0f, 1.0f,  // Prawy górny
            -1.0f,  1.0f, 0.0f, 1.0f   // Lewy górny
        };
        
        vertexBuffer.clear();
        vertexBuffer.put(vertices);
        vertexBuffer.flip();
        
        GL33.glBufferData(GL33.GL_ARRAY_BUFFER, vertexBuffer, GL33.GL_STATIC_DRAW);
        
        int stride = VERTEX_SIZE * Float.BYTES;
        
        // aPosition (location 0)
        GL33.glEnableVertexAttribArray(0);
        GL33.glVertexAttribPointer(0, 2, GL33.GL_FLOAT, false, stride, 0);
        
        // aUV (location 1)  
        GL33.glEnableVertexAttribArray(1);
        GL33.glVertexAttribPointer(1, 2, GL33.GL_FLOAT, false, stride, 2 * Float.BYTES);
        
        GL33.glBindVertexArray(0);
        GL33.glBindBuffer(GL33.GL_ARRAY_BUFFER, 0);
    }
    
    public boolean isShaderAvailable() {
        return shaderProgram.isCompiled();
    }
    
    public void render(GuiGraphics guiGraphics, DeltaTracker deltaTracker) {
        Minecraft mc = Minecraft.getInstance();
        
        if (mc.player == null || mc.level == null || mc.options.hideGui) {
            return;
        }
        
        if (com.astra.revolution.gui.BaritoneBypassScreen.wildPillHud) {
            return;
        }
        
        if (!Baritone.settings().showStatusPillHud.value) {
            return;
        }
        
        Font font = mc.font;
        if (font == null) {
            return;
        }
        
        // Aktualizacja danych
        updateBaritoneData(mc);
        updateInventoryData(mc);
        updateEtaData(mc);
        
        // Obliczenie docelowej szerokości
        computeTargetWidth(font);
        
        // Interpolacja LERP szerokości
        if (currentWidth <= 1.0f) {
            currentWidth = targetWidth;
        } else {
            currentWidth = Mth.lerp(0.18f, currentWidth, targetWidth);
            if (Math.abs(currentWidth - targetWidth) < 0.5f) {
                currentWidth = targetWidth;
            }
        }
        
        // Pozycja paska (prawy górny róg)
        int screenWidth = guiGraphics.guiWidth();
        float pillX = screenWidth - currentWidth - 10.0f;
        float pillY = 8.0f;
        
        // Renderowanie tła
        if (shaderProgram.isCompiled()) {
            renderWithShader(guiGraphics, pillX, pillY, currentWidth, PILL_HEIGHT, mc);
        } else {
            renderFallback(guiGraphics, (int) pillX, (int) pillY, 
                (int) currentWidth, (int) PILL_HEIGHT);
        }
        
        // Renderowanie tekstu
        renderTextContent(guiGraphics, font, pillX, pillY, currentWidth, mc);
    }
    
    private void renderWithShader(GuiGraphics guiGraphics, float x, float y, 
                                  float width, float height, Minecraft mc) {
        glState.saveState();
        
        try {
            GL33.glEnable(GL33.GL_BLEND);
            GL33.glBlendFunc(GL33.GL_SRC_ALPHA, GL33.GL_ONE_MINUS_SRC_ALPHA);
            GL33.glDisable(GL33.GL_DEPTH_TEST);
            GL33.glDisable(GL33.GL_CULL_FACE);
            
            GL33.glUseProgram(shaderProgram.getProgramId());
            
            PoseStack poseStack = guiGraphics.pose();
            Matrix4f modelViewMatrix = poseStack.last().pose();
            float guiW = (float) Math.max(1, guiGraphics.guiWidth());
            float guiH = (float) Math.max(1, guiGraphics.guiHeight());
            Matrix4f projectionMatrix = new Matrix4f().setOrtho(0.0f, guiW, guiH, 0.0f, -1000.0f, 3000.0f);
            
            matrixBuffer.clear();
            modelViewMatrix.get(matrixBuffer);
            matrixBuffer.flip();
            GL33.glUniformMatrix4fv(
                shaderProgram.getUniformLocation("uModelView"),
                false, matrixBuffer
            );
            
            matrixBuffer.clear();
            projectionMatrix.get(matrixBuffer);
            matrixBuffer.flip();
            GL33.glUniformMatrix4fv(
                shaderProgram.getUniformLocation("uProjection"),
                false, matrixBuffer
            );
            
            long currentTime = System.currentTimeMillis();
            float time = (currentTime % 100000L) / 1000.0f;
            
            GL33.glUniform2f(shaderProgram.getUniformLocation("uPosition"), x, y);
            GL33.glUniform2f(shaderProgram.getUniformLocation("uSize"), width, height);
            GL33.glUniform1f(shaderProgram.getUniformLocation("uCornerRadius"), PILL_CORNER_RADIUS);
            GL33.glUniform1f(shaderProgram.getUniformLocation("uTime"), time);
            GL33.glUniform1f(shaderProgram.getUniformLocation("uGlowIntensity"), GLOW_INTENSITY);
            GL33.glUniform1f(shaderProgram.getUniformLocation("uGuiScale"), 
                (float) mc.getWindow().getGuiScale());
            
            setUniformColor("uColorTop", COLOR_BG_TOP);
            setUniformColor("uColorBottom", COLOR_BG_BOTTOM);
            setUniformColor("uAccentColor", COLOR_ACCENT);
            setUniformColor("uAccentCyan", COLOR_ACCENT_CYAN);
            
            GL33.glBindVertexArray(vaoId);
            GL33.glDrawArrays(GL33.GL_TRIANGLES, 0, 6);
            GL33.glBindVertexArray(0);
            
        } catch (Throwable t) {
            LOGGER.debug("[StatusPillHUD] Render with shader error: {}", t.getMessage());
        } finally {
            glState.restoreState();
        }
    }
    
    private void setUniformColor(String name, int argb) {
        int location = shaderProgram.getUniformLocation(name);
        if (location < 0) return;
        float r = ((argb >> 16) & 0xFF) / 255.0f;
        float g = ((argb >> 8) & 0xFF) / 255.0f;
        float b = (argb & 0xFF) / 255.0f;
        float a = ((argb >> 24) & 0xFF) / 255.0f;
        GL33.glUniform4f(location, r, g, b, a);
    }
    
    private void renderFallback(GuiGraphics guiGraphics, int x, int y, int width, int height) {
        fallbackRenderer.renderCapsule(guiGraphics, x, y, width, height);
    }
    
    private void renderTextContent(GuiGraphics guiGraphics, Font font, 
                                   float pillX, float pillY, float pillWidth,
                                   Minecraft mc) {
        float textY = pillY + (PILL_HEIGHT - font.lineHeight) / 2.0f + 0.5f;
        float currentX = pillX + 9.0f;
        
        // Sekcja 1: Pulsująca kropka statusu
        long currentTime = System.currentTimeMillis();
        float pulseAlpha = 0.5f + 0.5f * 
            (float) Math.sin(currentTime * 0.005 * PULSE_SPEED * Math.PI);
        
        int dotColor = baritoneProvider.isActive() 
            ? applyAlpha(COLOR_ACCENT, (int) (pulseAlpha * 255))
            : applyAlpha(COLOR_TEXT_DIM, 140);
        
        guiGraphics.fill(
            (int) currentX, (int) (textY + font.lineHeight / 2 - 2),
            (int) (currentX + 3), (int) (textY + font.lineHeight / 2 + 2),
            dotColor
        );
        currentX += 8.0f;
        
        // Sekcja 2: Status procesu
        String statusStr = statusText.toString();
        if (!statusStr.isEmpty()) {
            int textWidth = font.width(statusStr);
            guiGraphics.drawString(font, statusStr, 
                (int) currentX, (int) textY, COLOR_TEXT_PRIMARY, false);
            currentX += textWidth + 7.0f;
        }
        
        // Separator 1
        renderSeparator(guiGraphics, (int) currentX, (int) pillY, (int) PILL_HEIGHT);
        currentX += 8.0f;
        
        // Sekcja 3: Ekwipunek
        String invStr = invText.toString();
        if (!invStr.isEmpty()) {
            int invColor = getInventoryColor(mc);
            guiGraphics.drawString(font, invStr, 
                (int) currentX, (int) textY, invColor, false);
            currentX += font.width(invStr) + 7.0f;
        }
        
        // Separator 2
        renderSeparator(guiGraphics, (int) currentX, (int) pillY, (int) PILL_HEIGHT);
        currentX += 8.0f;
        
        // Sekcja 4: ETA
        String etaStr = etaText.toString();
        if (!etaStr.isEmpty()) {
            guiGraphics.drawString(font, etaStr, 
                (int) currentX, (int) textY, COLOR_ACCENT_CYAN, false);
        }
    }
    
    private void renderSeparator(GuiGraphics guiGraphics, int x, int pillY, int height) {
        int separatorColor = applyAlpha(COLOR_SEPARATOR, (int) (SEPARATOR_ALPHA * 255));
        int y1 = pillY + 4;
        int y2 = pillY + height - 4;
        guiGraphics.fill(x, y1, x + 1, y2, separatorColor);
    }
    
    private int getInventoryColor(Minecraft mc) {
        int filledSlots = countFilledSlots(mc);
        
        if (filledSlots >= 34) {
            long time = System.currentTimeMillis();
            float pulse = 0.5f + 0.5f * (float) Math.sin(time * 0.01);
            int alpha = (int) (160 + 95 * pulse);
            return applyAlpha(0xFFFF4444, alpha);
        } else if (filledSlots >= 28) {
            return 0xFFFFAA00; // Żółty/Pomarańczowy
        } else {
            return 0xFF55FF55; // Zielony
        }
    }
    
    private int countFilledSlots(Minecraft mc) {
        if (mc.player == null || mc.player.getInventory() == null) {
            return 0;
        }
        int count = 0;
        for (int i = 0; i < 36; i++) {
            if (!mc.player.getInventory().getItem(i).isEmpty()) {
                count++;
            }
        }
        return count;
    }
    
    private int applyAlpha(int color, int alpha) {
        return (alpha << 24) | (color & 0x00FFFFFF);
    }
    
    private void updateBaritoneData(Minecraft mc) {
        statusText.setLength(0);
        
        BaritoneStatusProvider.ProcessInfo info = baritoneProvider.getCurrentProcess(mc);
        
        if (info == null || !info.isActive()) {
            statusText.append("◈ IDLE");
            return;
        }
        
        statusText.append("◈ ");
        
        switch (info.getType()) {
            case FARMING -> {
                statusText.append("[Baritone] Farming: ")
                    .append(String.format("%,d", info.getWheatHarvested()))
                    .append(" wheat / ")
                    .append(info.getWheatPerHour())
                    .append("/h");
            }
            case TILLING -> {
                statusText.append("[Baritone] Tilling: ")
                    .append(info.getBlocksTilled())
                    .append("/")
                    .append(info.getTotalBlocks())
                    .append(" (")
                    .append(info.getPercentage())
                    .append("%)");
            }
            case CLEARING -> {
                statusText.append("[Baritone] Clearing: ")
                    .append(info.getPercentage())
                    .append("%");
            }
            case BUILDING -> {
                statusText.append("[Baritone] Building: ")
                    .append(info.getPercentage())
                    .append("%");
            }
            default -> {
                statusText.append("[Baritone] ")
                    .append(info.getDisplayName());
            }
        }
    }
    
    private void updateInventoryData(Minecraft mc) {
        invText.setLength(0);
        int filled = countFilledSlots(mc);
        invText.append("Inv: ").append(filled).append("/36");
    }
    
    private void updateEtaData(Minecraft mc) {
        etaText.setLength(0);
        
        BaritoneStatusProvider.ProcessInfo info = baritoneProvider.getCurrentProcess(mc);
        
        if (info == null || !info.isActive()) {
            long uptime = (System.currentTimeMillis() - sessionStartTime) / 1000;
            etaText.append("Up ");
            formatTime(etaText, uptime);
            return;
        }
        
        switch (info.getType()) {
            case FARMING -> {
                double fillRate = info.getInventoryFillRate();
                int emptySlots = 36 - countFilledSlots(mc);
                if (emptySlots <= 0) {
                    etaText.append("FULL");
                } else if (fillRate > 0.0001) {
                    long secondsToFull = (long) (emptySlots / fillRate);
                    if (secondsToFull > 0 && secondsToFull < 86400) {
                        etaText.append("ETA ");
                        formatTime(etaText, secondsToFull);
                    } else {
                        etaText.append("ETA --:--");
                    }
                } else {
                    etaText.append("ETA --:--");
                }
            }
            case TILLING, CLEARING, BUILDING -> {
                double blocksPerSecond = info.getBlocksPerSecond();
                long remainingBlocks = info.getRemainingBlocks();
                
                if (remainingBlocks <= 0) {
                    etaText.append("DONE");
                } else if (blocksPerSecond > 0.01) {
                    long secondsRemaining = (long) (remainingBlocks / blocksPerSecond);
                    etaText.append("ETA ");
                    formatTime(etaText, secondsRemaining);
                } else {
                    etaText.append("ETA --:--");
                }
            }
            default -> {
                long uptime = (System.currentTimeMillis() - sessionStartTime) / 1000;
                etaText.append("Up ");
                formatTime(etaText, uptime);
            }
        }
    }
    
    private void formatTime(StringBuilder sb, long totalSeconds) {
        if (totalSeconds < 0) {
            sb.append("--:--");
            return;
        }
        
        long minutes = totalSeconds / 60;
        long seconds = totalSeconds % 60;
        
        if (minutes > 99) {
            sb.append("99:59");
            return;
        }
        
        if (minutes < 10) {
            sb.append('0');
        }
        sb.append(minutes).append(':');
        
        if (seconds < 10) {
            sb.append('0');
        }
        sb.append(seconds);
    }
    
    private void computeTargetWidth(Font font) {
        int statusWidth = font.width(statusText.toString());
        int invWidth = font.width(invText.toString());
        int etaWidth = font.width(etaText.toString());
        
        // 9px margin + 8px dot + 7px spacing + status + 8px sep + 8px spacing + inv + 8px sep + 8px spacing + eta + 10px margin
        targetWidth = 17.0f + statusWidth + 16.0f + invWidth + 16.0f + etaWidth + 10.0f;
        targetWidth = Math.max(targetWidth, 120.0f);
    }
    
    // === KLASA WEWNĘTRZNA: Zapis/przywracanie stanu GL ===
    private static class GLStateSnapshot {
        private int prevProgram;
        private int prevVAO;
        private int prevTextureBinding;
        private int prevActiveTexture;
        private boolean prevBlendEnabled;
        private boolean prevDepthTestEnabled;
        private boolean prevCullFaceEnabled;
        private int prevBlendSrcRgb = GL33.GL_SRC_ALPHA;
        private int prevBlendDstRgb = GL33.GL_ONE_MINUS_SRC_ALPHA;
        private int prevBlendSrcAlpha = GL33.GL_SRC_ALPHA;
        private int prevBlendDstAlpha = GL33.GL_ONE_MINUS_SRC_ALPHA;
        
        void saveState() {
            prevProgram = GL33.glGetInteger(GL33.GL_CURRENT_PROGRAM);
            prevVAO = GL33.glGetInteger(GL33.GL_VERTEX_ARRAY_BINDING);
            prevTextureBinding = GL33.glGetInteger(GL33.GL_TEXTURE_BINDING_2D);
            prevActiveTexture = GL33.glGetInteger(GL33.GL_ACTIVE_TEXTURE);
            prevBlendEnabled = GL33.glGetBoolean(GL33.GL_BLEND);
            prevDepthTestEnabled = GL33.glGetBoolean(GL33.GL_DEPTH_TEST);
            prevCullFaceEnabled = GL33.glGetBoolean(GL33.GL_CULL_FACE);
            
            prevBlendSrcRgb = GL33.glGetInteger(GL33.GL_BLEND_SRC_RGB);
            prevBlendDstRgb = GL33.glGetInteger(GL33.GL_BLEND_DST_RGB);
            prevBlendSrcAlpha = GL33.glGetInteger(GL33.GL_BLEND_SRC_ALPHA);
            prevBlendDstAlpha = GL33.glGetInteger(GL33.GL_BLEND_DST_ALPHA);
        }
        
        void restoreState() {
            GL33.glUseProgram(prevProgram);
            GL33.glBindVertexArray(prevVAO);
            GL33.glActiveTexture(prevActiveTexture);
            GL33.glBindTexture(GL33.GL_TEXTURE_2D, prevTextureBinding);
            
            if (prevBlendEnabled) {
                GL33.glEnable(GL33.GL_BLEND);
            } else {
                GL33.glDisable(GL33.GL_BLEND);
            }
            
            if (prevDepthTestEnabled) {
                GL33.glEnable(GL33.GL_DEPTH_TEST);
            } else {
                GL33.glDisable(GL33.GL_DEPTH_TEST);
            }
            
            if (prevCullFaceEnabled) {
                GL33.glEnable(GL33.GL_CULL_FACE);
            } else {
                GL33.glDisable(GL33.GL_CULL_FACE);
            }
            
            if (prevBlendSrcRgb != 0 && prevBlendDstRgb != 0) {
                GL33.glBlendFuncSeparate(
                    prevBlendSrcRgb, prevBlendDstRgb,
                    prevBlendSrcAlpha, prevBlendDstAlpha
                );
            }
        }
    }
}
