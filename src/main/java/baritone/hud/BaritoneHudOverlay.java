/*
 * This file is part of Baritone.
 *
 * Baritone is free software: you can redistribute it and/or modify
 * it under the terms of the GNU Lesser General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * Baritone is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU Lesser General Public License for more details.
 *
 * You should have received a copy of the GNU Lesser General Public License
 * along with Baritone.  If not, see <https://www.gnu.org/licenses/>.
 */

package baritone.hud;

import baritone.Baritone;
import baritone.api.BaritoneAPI;
import baritone.api.IBaritone;
import baritone.api.process.IBaritoneProcess;
import baritone.behavior.PathingBehavior;
import baritone.pathing.calc.AbstractNodeCostSearch;
import baritone.pathing.path.PathExecutor;
import baritone.optimization.FpsOptimizer;
import baritone.process.AutoEatProcess;
import net.minecraft.client.DeltaTracker;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.world.phys.Vec3;

import java.awt.Color;
import java.util.Map;
import java.util.Optional;

/**
 * Ultra-Modern Cyberpunk / Glassmorphism HUD overlay for Baritone displaying:
 * - Animated RGB Chroma Accent Header
 * - Real-time Live Task Status & Animated Pulse Badges
 * - Dynamic BPS (Blocks Per Second) Speedometer
 * - A* "Thinking Speed" (nodes/sec) meter
 * - Dual-Tone Glowing Path Progress Bar with ETA
 * - Gathered / Mined Block Counters & Rate Statistics
 * - Live Block Breakdown with Sleek Bullet Tags
 */
public final class BaritoneHudOverlay {

    private BaritoneHudOverlay() {}

    // Rolling nodes/s tracker
    private static long lastNodesSampleTime = 0;
    private static int lastNodesSnapshot = 0;
    private static double smoothedNodesPerSec = 0;
    private static int dotAnimTick = 0;
    private static long lastTickTime = 0;

    // Motion tracking for BPS (Blocks Per Second)
    private static double prevPlayerX = 0;
    private static double prevPlayerZ = 0;
    private static double currentBps = 0;

    public static void render(GuiGraphics guiGraphics, DeltaTracker deltaTracker) {
        if (!Baritone.settings().showHudOverlay.value) {
            return;
        }

        Minecraft mc = Minecraft.getInstance();
        if (mc.options.hideGui || mc.player == null) {
            return;
        }

        IBaritone baritone = BaritoneAPI.getProvider().getPrimaryBaritone();
        if (baritone == null) {
            return;
        }

        long nowMs = System.currentTimeMillis();

        // Speed calculation (BPS)
        Vec3 playerPos = mc.player.position();
        double dx = playerPos.x - prevPlayerX;
        double dz = playerPos.z - prevPlayerZ;
        double instantBps = Math.sqrt(dx * dx + dz * dz) * 20.0;
        currentBps = currentBps * 0.85 + instantBps * 0.15; // Smooth EMA
        prevPlayerX = playerPos.x;
        prevPlayerZ = playerPos.z;

        // Advance animation tick every ~450ms
        if (nowMs - lastTickTime > 450) {
            dotAnimTick = (dotAnimTick + 1) % 4;
            lastTickTime = nowMs;
        }

        Font font = mc.font;
        GatherTracker tracker = GatherTracker.INSTANCE;

        // ---- Detect current process ----
        String taskName = "IDLE";
        boolean isActive = false;
        if (baritone.getPathingBehavior().isPathing()) {
            taskName = "PATHING";
            isActive = true;
        }
        if (baritone.getPathingControlManager() != null) {
            Optional<IBaritoneProcess> inControl = baritone.getPathingControlManager().mostRecentInControl();
            if (inControl.isPresent() && inControl.get().isActive()) {
                taskName = inControl.get().displayName().toUpperCase();
                isActive = true;
            }
        }
        tracker.setCurrentTask(taskName);

        // ---- A*/s "thinking speed" ----
        boolean isCalculating = false;
        int currentNodesExplored = 0;
        int currentNodesPerSec = 0;
        PathingBehavior pb = (PathingBehavior) baritone.getPathingBehavior();
        Optional<AbstractNodeCostSearch> inProgress = pb.getInProgress();
        if (inProgress.isPresent()) {
            AbstractNodeCostSearch finder = inProgress.get();
            currentNodesExplored = finder.nodesExplored;
            isCalculating = true;

            long elapsed = nowMs - lastNodesSampleTime;
            if (elapsed >= 180) { // update every 180ms
                int delta = currentNodesExplored - lastNodesSnapshot;
                double rawRate = (delta / (elapsed / 1000.0));
                smoothedNodesPerSec = smoothedNodesPerSec * 0.6 + rawRate * 0.4; // EMA smoothing
                lastNodesSnapshot = currentNodesExplored;
                lastNodesSampleTime = nowMs;
                currentNodesPerSec = (int) smoothedNodesPerSec;
            } else {
                currentNodesPerSec = (int) smoothedNodesPerSec;
            }
        } else {
            lastNodesSnapshot = 0;
            smoothedNodesPerSec = 0;
            lastNodesSampleTime = nowMs;
        }

        // ---- Path progress ----
        PathExecutor current = pb.getCurrent();
        int pathPos = 0;
        int pathLen = 0;
        if (current != null && current.getPath() != null) {
            pathPos = current.getPosition();
            pathLen = current.getPath().length();
        }
        float pathProgress = (pathLen > 0) ? (float) pathPos / pathLen : 0f;

        // ---- ETA estimation ----
        String etaStr = "--:--";
        if (isActive && pathLen > 0 && pathPos < pathLen) {
            int remaining = pathLen - pathPos;
            double effectiveBps = Math.max(3.0, currentBps);
            double etaSec = remaining / effectiveBps;
            long etaMin = (long) (etaSec / 60);
            long etaSecRem = (long) (etaSec % 60);
            etaStr = String.format("%02d:%02d", etaMin, etaSecRem);
        }

        // ---- AutoEat status ----
        AutoEatProcess autoEatProc = ((baritone.Baritone) baritone).getAutoEatProcess();
        boolean autoEatActive   = Baritone.settings().autoEat.value && autoEatProc != null && (autoEatProc.isEating() || autoEatProc.isWaitingForSwap());

        // ---- Health & hunger ----
        boolean showHealth = Baritone.settings().hudShowHealth.value;
        float playerHealth = mc.player.getHealth();
        float playerMaxHealth = mc.player.getMaxHealth();
        int playerFood = mc.player.getFoodData().getFoodLevel();

        // ---- Layout dimensions ----
        Map<String, Integer> breakdown = tracker.getMinedCounts();
        boolean showSpeed = Baritone.settings().hudShowSpeed.value;
        boolean showAStar = isCalculating && Baritone.settings().hudShowAStar.value;
        boolean showMined = Baritone.settings().hudShowMined.value;
        boolean showOpt = Baritone.settings().fpsBoostEnabled.value && Baritone.settings().hudShowFpsBoost.value;
        int breakdownItems = showMined ? Math.min(4, breakdown.size()) : 0;

        int lineH = 10;
        int extraLines = breakdownItems > 0 ? breakdownItems : 0;
        int baseLines = 2; // Header + Task status
        if (showSpeed) baseLines++;
        if (showAStar) baseLines++;
        if (pathLen > 0) baseLines++; // Progress text
        if (showMined) baseLines++; // Mined rate
        baseLines++; // Session time
        if (showOpt) baseLines++;
        if (showHealth) baseLines++; // HP + hunger
        if (autoEatActive) baseLines++; // AutoEat row

        int width = 214;
        int height = 8 + baseLines * lineH + (pathLen > 0 ? 7 : 0) + (extraLines > 0 ? extraLines * lineH + 4 : 0) + 6;

        float hudScale = (float) Math.max(0.25, Math.min(3.0, Baritone.settings().hudScale.value));
        int screenW = (int) (mc.getWindow().getGuiScaledWidth() / hudScale);
        int screenH = (int) (mc.getWindow().getGuiScaledHeight() / hudScale);
        String posConfig = Baritone.settings().hudPosition.value.trim().toUpperCase();

        int startX = 8;
        int startY = 8;
        switch (posConfig) {
            case "TOP_RIGHT":
                startX = Math.max(8, screenW - width - 8);
                startY = 8;
                break;
            case "BOTTOM_LEFT":
                startX = 8;
                startY = Math.max(8, screenH - height - 8);
                break;
            case "BOTTOM_RIGHT":
                startX = Math.max(8, screenW - width - 8);
                startY = Math.max(8, screenH - height - 8);
                break;
            case "TOP_LEFT":
            default:
                startX = 8;
                startY = 8;
                break;
        }

        guiGraphics.pose().pushPose();
        if (hudScale != 1.0F) {
            guiGraphics.pose().scale(hudScale, hudScale, 1.0F);
        }

        try {
            // ---- Glassmorphic Background layers ----
            // Ambient soft outer glow
            guiGraphics.fill(startX - 2, startY - 2, startX + width + 2, startY + height + 2, 0x1400E5FF);
            // Dark metallic slate background (semi-transparent blur look)
            guiGraphics.fill(startX, startY, startX + width, startY + height, 0xD8080C14);
            // Inner subtle specular highlight
            guiGraphics.fill(startX + 1, startY + 1, startX + width - 1, startY + 2, 0x22FFFFFF);

            // ---- Animated RGB Chroma Top Gradient Bar ----
            float timeRatio = (float) ((nowMs % 4000L) / 4000.0);
            int barSegments = 24;
            int segW = width / barSegments;
            for (int i = 0; i < barSegments; i++) {
                float hue = (timeRatio + (float) i / barSegments) % 1.0F;
                int chromaColor = Color.HSBtoRGB(hue, 0.85F, 1.0F);
                int segX1 = startX + i * segW;
                int segX2 = (i == barSegments - 1) ? (startX + width) : (segX1 + segW);
                guiGraphics.fill(segX1, startY, segX2, startY + 2, chromaColor);
            }

            // Dynamic side accent border
            int accentLeft = isActive ? 0xFF00E5FF : 0xFF2A4356;
            if (isCalculating) {
                float pulse = (float) (0.6 + 0.4 * Math.sin(nowMs / 200.0));
                int a = (int) (255 * pulse);
                accentLeft = (a << 24) | 0x00E5FF;
            }
            guiGraphics.fill(startX, startY + 2, startX + 2, startY + height, accentLeft);
            guiGraphics.fill(startX, startY + height - 1, startX + width, startY + height, 0x28FFFFFF);
            guiGraphics.fill(startX + width - 1, startY + 2, startX + width, startY + height, 0x28FFFFFF);

            int textX = startX + 7;
            int textY = startY + 6;

            // ---- Header Title & Stealth Badge ----
            guiGraphics.drawString(font, "§b§lBARITONE §8┃ §3§lNEO-STEALTH", textX, textY, 0xFFFFFFFF, false);
            // Right-aligned mini FPS indicator
            int fps = mc.getFps();
            String fpsStr = "§8FPS: §f" + fps;
            guiGraphics.drawString(font, fpsStr, startX + width - font.width(fpsStr) - 6, textY, 0xFFFFFFFF, false);
            textY += lineH + 1;

            // ---- Status Pill & Activity indicator ----
            String statusDot = isActive ? (isCalculating ? "§e⚡" : "§a●") : "§7○";
            String statusColor = isActive ? (isCalculating ? "§e" : "§a") : "§7";
            String animatedEllipsis = isActive ? "...".substring(0, dotAnimTick) : "";
            guiGraphics.drawString(font, "§8[" + statusDot + "§8] §7Task: " + statusColor + taskName + animatedEllipsis, textX, textY, 0xFFFFFFFF, false);
            textY += lineH;

            // ---- Speedometer & Motion Metric ----
            if (showSpeed) {
                String speedColor = currentBps > 6.5 ? "§a" : (currentBps > 3.5 ? "§b" : (currentBps > 0.2 ? "§e" : "§7"));
                String speedStr = String.format("%.1f", currentBps);
                guiGraphics.drawString(font, "§7Speed: " + speedColor + speedStr + " §7bps §8│ §7ETA: §f" + etaStr, textX, textY, 0xFFFFFFFF, false);
                textY += lineH;
            }

            // ---- A* Thinking speed (when active) ----
            if (showAStar) {
                String nodesStr = (currentNodesPerSec >= 1000)
                        ? String.format("%.1fk", currentNodesPerSec / 1000.0)
                        : String.valueOf(currentNodesPerSec);
                String nodesColor = currentNodesPerSec > 40000 ? "§a" : (currentNodesPerSec > 15000 ? "§e" : "§c");
                guiGraphics.drawString(font, "§7A* Speed: " + nodesColor + nodesStr + " §7nodes/s §8(§f" + currentNodesExplored + "§8)", textX, textY, 0xFFFFFFFF, false);
                textY += lineH;
            }

            // ---- Path Progress Bar ----
            if (pathLen > 0) {
                int barW = width - 14;
                int barH = 4;
                int barX = textX;
                int barY = textY;

                // Background trough
                guiGraphics.fill(barX, barY, barX + barW, barY + barH, 0x44FFFFFF);

                // Filled segment
                int filled = (int) (barW * pathProgress);
                if (filled > 0) {
                    // Gradient progress fill
                    int fillCol1 = 0xFF00E5FF; // Cyan
                    int fillCol2 = (pathProgress > 0.85f) ? 0xFF00FF88 : 0xFFBF00FF; // Green or Magenta
                    guiGraphics.fill(barX, barY, barX + filled, barY + barH, fillCol1);
                    // Glowing tip
                    guiGraphics.fill(barX + Math.max(0, filled - 2), barY, barX + filled, barY + barH, fillCol2);
                }
                textY += barH + 3;

                int pct = (int) (pathProgress * 100);
                guiGraphics.drawString(font, "§7Progress: §f" + pathPos + "§8/§f" + pathLen + " §8(§b" + pct + "%§8)", textX, textY, 0xFFFFFFFF, false);
                textY += lineH;
            }

            // ---- Mined Blocks Counter & Rate ----
            if (showMined) {
                int session = tracker.getSessionMined();
                int total = tracker.getTotalMined();
                double bpm = tracker.getBlocksPerMinute();
                String bpmColor = bpm > 40 ? "§a" : (bpm > 15 ? "§e" : "§7");
                String bpmStr = String.format("%.1f", bpm);
                guiGraphics.drawString(font, "§7Mined: §a§l" + session + " §8│ §7Rate: " + bpmColor + bpmStr + "§7/m §8│ §7Tot: §f" + total, textX, textY, 0xFFFFFFFF, false);
                textY += lineH;
            }

            // ---- Session Timer ----
            long sec = tracker.getSessionDurationSeconds();
            String timeStr = String.format("%02d:%02d", sec / 60, sec % 60);
            guiGraphics.drawString(font, "§7Session Time: §f" + timeStr, textX, textY, 0xFFFFFFFF, false);
            textY += lineH;

            // ---- Health & Hunger ----
            if (showHealth) {
                float hpPct = playerHealth / playerMaxHealth;
                String hpColor = hpPct > 0.75f ? "§a" : (hpPct > 0.4f ? "§e" : "§c");
                String foodColor = playerFood >= 18 ? "§a" : (playerFood >= 12 ? "§e" : "§c");
                String hpStr = String.format("%.1f", playerHealth / 2f); // hearts
                guiGraphics.drawString(font,
                        "§7HP: " + hpColor + "♥ " + hpStr + " §8│ §7Food: " + foodColor + "\uD83C\uDF56 " + playerFood + "§8/§720",
                        textX, textY, 0xFFFFFFFF, false);
                textY += lineH;
            }

            // ---- AutoEat Status Row ----
            if (autoEatActive) {
                if (autoEatProc.isWaitingForSwap()) {
                    guiGraphics.drawString(font, "§b⇄ §eAutoEat: §fSwapping slot...", textX, textY, 0xFF00E5FF, false);
                } else {
                    long eatPulse = (nowMs / 500) % 2 == 0 ? 0xFFFF5555 : 0xFFFFAA00;
                    guiGraphics.drawString(font, "§c♥ §eAutoEat: §fEating...", textX, textY, (int) eatPulse, false);
                }
                textY += lineH;
            }

            // ---- FPS Optimization Stats ----
            if (showOpt) {
                int culledEnt = FpsOptimizer.culledEntitiesThisFrame;
                int culledBlk = FpsOptimizer.culledBlockEntitiesThisFrame;
                guiGraphics.drawString(font, "§7Opt: §a3x Boost §8│ §7Culled: §b" + culledEnt + "§7e §8/ §e" + culledBlk + "§7b", textX, textY, 0xFFFFFFFF, false);
                textY += lineH;
            }

            // ---- Block Breakdown (if any blocks mined) ----
            if (breakdownItems > 0) {
                guiGraphics.fill(textX, textY, textX + width - 14, textY + 1, 0x2AFFFFFF);
                textY += 3;
                int count = 0;
                for (Map.Entry<String, Integer> entry : breakdown.entrySet()) {
                    if (count++ >= 4) break;
                    String blockName = entry.getKey();
                    if (blockName.length() > 17) {
                        blockName = blockName.substring(0, 15) + "..";
                    }
                    guiGraphics.drawString(font, "§3▪ §f" + blockName + " §8» §e§l" + entry.getValue(), textX + 2, textY, 0xFFFFFFFF, false);
                    textY += lineH;
                }
            }
        } finally {
            guiGraphics.pose().popPose();
        }
    }
}
