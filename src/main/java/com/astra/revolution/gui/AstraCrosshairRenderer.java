package com.astra.revolution.gui;

import com.mojang.blaze3d.systems.RenderSystem;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.util.Mth;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.phys.HitResult;

/**
 * GOD-TIER DYNAMIC CROSSHAIR & HITMARKER ENGINE (Astra Client 1.21.4).
 * Inspirowane MoonDLC InGameHudMixin (Crosshair suppression & custom high-precision HUD).
 *
 * Cechy:
 * 1. Zero-Allocation render w pętli klatki.
 * 2. Dynamiczny odstęp (Gap) reagujący płynnie na prędkość ruchu i stan walki.
 * 3. Hitmarker z zanikającym additive bloomem przy trafieniu w przeciwnika.
 * 4. Wizualny wskaźnik przeładowania broni (Attack Cooldown) w formie eleganckiego mikroskopijnego łuku.
 * 5. Celownik automatycznie barwi się na kolor celu (Lilac / Neon Red) przy najechaniu na wrogą encję.
 */
public final class AstraCrosshairRenderer {

    public static final AstraCrosshairRenderer INSTANCE = new AstraCrosshairRenderer();

    private boolean enabled = false;
    private boolean dynamicGap = true;
    private boolean hitmarkersEnabled = true;
    private boolean chromaEnabled = true;

    // Stan hitmarkera
    private long lastHitTimeMs = 0L;
    private static final long HITMARKER_DURATION_MS = 280L;

    // Płynny odstęp
    private float smoothedGap = 3.5f;

    // Paleta kolorów Astra
    private static final int COL_CORE_WHITE    = 0xFFFFFFFF;
    private static final int COL_OUTLINE_BLACK = 0xC0000000;
    private static final int COL_TARGET_ENEMY  = 0xFFFF3366;
    private static final int COL_CHROMA_CYAN   = 0xFF00F0FF;
    private static final int COL_CHROMA_LILAC  = 0xFFC77DFF;
    private static final int COL_HITMARKER     = 0xFFFFFFFF;

    private AstraCrosshairRenderer() {}

    public boolean isEnabled() {
        return enabled;
    }

    public void setEnabled(boolean enabled) {
        this.enabled = enabled;
    }

    /**
     * Wywoływane przy zadaniu obrażeń (np. z attack mixinu lub packet handlera).
     */
    public void triggerHitmarker() {
        this.lastHitTimeMs = System.currentTimeMillis();
    }

    /**
     * Główna metoda renderująca własny celownik zamiast vanilla.
     * Wywoływana z MixinGui.renderCrosshair.
     */
    public boolean render(GuiGraphics g, float partialTick) {
        if (!enabled) return false;

        Minecraft mc = Minecraft.getInstance();
        LocalPlayer player = mc.player;
        if (player == null || mc.level == null) return false;

        // Nie renderuj celownika jeśli gracz jest w trybie widza lub ukrył HUD (F1)
        if (mc.options.hideGui || player.isSpectator()) return false;

        int screenW = g.guiWidth();
        int screenH = g.guiHeight();
        float cx = screenW / 2.0f;
        float cy = screenH / 2.0f;

        // Oblicz prędkość gracza dla dynamicznego gapu
        double dx = player.getX() - player.xo;
        double dz = player.getZ() - player.zo;
        float speed = (float) Math.hypot(dx, dz) * 20.0f;

        float targetGap = 3.5f;
        if (dynamicGap) {
            targetGap += Math.min(5.5f, speed * 0.45f);
            if (!player.onGround()) {
                targetGap += 2.0f; // Rozszerz w powietrzu
            }
        }
        smoothedGap = Mth.lerp(0.25f, smoothedGap, targetGap);

        // Wykryj cel pod celownikiem
        boolean targetAcquired = false;
        if (mc.hitResult != null && mc.hitResult.getType() == HitResult.Type.ENTITY) {
            if (mc.crosshairPickEntity instanceof LivingEntity living && living.isAlive() && living != player) {
                targetAcquired = true;
            }
        }

        // Kolor bazowy celownika
        int color;
        if (targetAcquired) {
            color = COL_TARGET_ENEMY;
        } else if (chromaEnabled) {
            float hue = ((System.currentTimeMillis() % 4000L) / 4000.0f);
            int rgb = Mth.hsvToRgb(hue, 0.65f, 1.0f) & 0x00FFFFFF;
            color = 0xFF000000 | rgb;
        } else {
            color = COL_CORE_WHITE;
        }

        float gap = smoothedGap;
        float len = 4.5f;
        float thick = 1.0f;

        // 1. Czarny obrys (Drop-shadow outline dla czytelności na każdym tle)
        renderCrossTines(g, cx, cy, gap, len, thick + 1.0f, COL_OUTLINE_BLACK);

        // 2. Rdzeń celownika (Core tines)
        renderCrossTines(g, cx, cy, gap, len, thick, color);

        // 3. Centralna kropka (Center dot)
        g.fill((int) (cx - 1), (int) (cy - 1), (int) (cx + 1), (int) (cy + 1), COL_OUTLINE_BLACK);
        g.fill((int) cx, (int) cy, (int) (cx + 1), (int) (cy + 1), color);

        // 4. Attack Cooldown Indicator (Łuk/pasek pod celownikiem gdy broń nie jest w 100% naładowana)
        float attackProgress = player.getAttackStrengthScale(0.0f);
        if (attackProgress < 0.99f) {
            renderAttackIndicator(g, cx, cy + gap + len + 3.0f, attackProgress);
        }

        // 5. Hitmarker feedback
        if (hitmarkersEnabled) {
            long elapsed = System.currentTimeMillis() - lastHitTimeMs;
            if (elapsed < HITMARKER_DURATION_MS) {
                float alphaPct = 1.0f - (float) elapsed / HITMARKER_DURATION_MS;
                int alpha = (int) (alphaPct * 255.0f);
                int hmColor = (alpha << 24) | 0x00FFFFFF;
                renderHitmarker(g, cx, cy, alphaPct, hmColor);
            }
        }

        return true;
    }

    private void renderCrossTines(GuiGraphics g, float cx, float cy, float gap, float len, float t, int col) {
        int halfT = (int) Math.ceil(t / 2.0f);

        // Lewe ramię
        g.fill((int) (cx - gap - len), (int) (cy - halfT), (int) (cx - gap), (int) (cy + halfT), col);
        // Prawe ramię
        g.fill((int) (cx + gap), (int) (cy - halfT), (int) (cx + gap + len), (int) (cy + halfT), col);
        // Górne ramię
        g.fill((int) (cx - halfT), (int) (cy - gap - len), (int) (cx + halfT), (int) (cy - gap), col);
        // Dolne ramię
        g.fill((int) (cx - halfT), (int) (cy + gap), (int) (cx + halfT), (int) (cy + gap + len), col);
    }

    private void renderAttackIndicator(GuiGraphics g, float x, float y, float progress) {
        int barW = 14;
        int barH = 2;
        int startX = (int) (x - barW / 2.0f);
        int startY = (int) y;

        // Tło
        g.fill(startX - 1, startY - 1, startX + barW + 1, startY + barH + 1, 0xA0000000);

        // Wypełnienie
        int fillW = (int) (barW * progress);
        int barCol = progress >= 0.9f ? COL_CHROMA_CYAN : COL_CHROMA_LILAC;
        g.fill(startX, startY, startX + fillW, startY + barH, barCol);
    }

    private void renderHitmarker(GuiGraphics g, float cx, float cy, float alphaPct, int col) {
        float dist = 4.0f + (1.0f - alphaPct) * 3.0f; // Rozszerzanie hitmarkera
        float len = 3.5f;

        // Diagonalne linie X: Top-Left, Top-Right, Bottom-Left, Bottom-Right
        drawLine(g, cx - dist - len, cy - dist - len, cx - dist, cy - dist, col);
        drawLine(g, cx + dist, cy - dist, cx + dist + len, cy - dist - len, col);
        drawLine(g, cx - dist - len, cy + dist + len, cx - dist, cy + dist, col);
        drawLine(g, cx + dist, cy + dist, cx + dist + len, cy + dist + len, col);
    }

    private void drawLine(GuiGraphics g, float x1, float y1, float x2, float y2, int col) {
        int steps = Math.max(1, (int) Math.hypot(x2 - x1, y2 - y1));
        for (int i = 0; i <= steps; i++) {
            float t = (float) i / steps;
            int px = (int) Mth.lerp(t, x1, x2);
            int py = (int) Mth.lerp(t, y1, y2);
            g.fill(px, py, px + 1, py + 1, col);
        }
    }
}
