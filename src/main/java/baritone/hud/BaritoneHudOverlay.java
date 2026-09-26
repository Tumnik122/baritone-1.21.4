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
import baritone.behavior.PathingBehavior;
import baritone.optimization.FpsOptimizer;
import baritone.pathing.calc.AbstractNodeCostSearch;
import baritone.pathing.path.PathExecutor;
import baritone.process.AutoEatProcess;
import baritone.process.BuilderProcess;
import net.minecraft.client.DeltaTracker;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.world.phys.Vec3;

import java.util.Map;

/**
 * HUD "Stealth" v2 — ulepszony panel z:
 *  - BPS spark chart (mini wykres historii prędkości)
 *  - Koordynaty X/Y/Z z kolorowaniem strefy Y (lava/surface/void)
 *  - Wyświetlanie aktualnego celu pathfindingu
 *  - Animowane chroma dots w nagłówku wg stanu
 *  - Wymiar (OW/NETH/END) po prawej stronie koordynatów
 */
public final class BaritoneHudOverlay {

    private BaritoneHudOverlay() {}

    // ═══ layout ═══
    private static final int W = 190;
    private static final int LINE = 11;
    private static final int DIV = 8;        // 4 przerwy + 1px kreska + 3 przerwy
    private static final int TOP = 8;
    private static final int BOTTOM = 7;
    private static final int PAD = 8;
    private static final float BASE_SCALE = 0.9F;

    // ═══ paleta ═══
    private static final int ACCENT   = 0xFF00E5FF;   // cyjan
    private static final int ACCENT_G = 0xFF00FF88;   // zielony
    private static final int ACCENT_R = 0xFFFF3355;   // czerwony (danger)
    private static final int ACCENT_Y = 0xFFFFCC00;   // żółty (warning)
    private static final int DIVIDER  = 0x22FFFFFF;
    private static final int LEADER   = 0x28FFFFFF;

    // ═══ statystyki ═══
    private static long lastFrameMs = 0L;
    private static double prevX = 0, prevZ = 0;
    private static boolean posInit = false;
    private static double bps = 0;

    // ═══ BPS spark chart — kołowy bufor 10 próbek ═══
    private static final int SPARK_N = 10;
    private static final float[] sparkBps = new float[SPARK_N];
    private static int sparkIdx = 0;
    private static long lastSparkMs = 0L;
    private static float sparkMax = 1.0F;

    private static long lastNodesSampleMs = 0L;
    private static int lastNodesSnapshot = 0;
    private static double nodesPerSec = 0;

    private static float displayProgress = 0F;
    private static float displayBuildProgress = 0F;  // build bar smooth
    private static int dotTick = 0;
    private static long lastDotMs = 0L;
    private static int errorCount = 0;

    // ═══ detekcja zdarzeń → log ═══
    private static String lastTask = "";
    private static boolean lastCalculating = false;
    private static boolean lastAutoEat = false;
    private static int lastPathLen = -1;

    public static void render(GuiGraphics guiGraphics, DeltaTracker deltaTracker) {
        try {
            render0(guiGraphics, deltaTracker);
        } catch (Throwable t) {
            if (errorCount++ < 3) {
                System.err.println("[BaritoneHUD] błąd renderu #" + errorCount + ": " + t);
            }
        }
        try {
            baritone.bypass.BypassHudOverlay.render(guiGraphics, deltaTracker);
        } catch (Throwable ignored) {}
    }

    private static void render0(GuiGraphics g, DeltaTracker deltaTracker) {
        Minecraft mc = Minecraft.getInstance();
        if (!Baritone.settings().showHudOverlay.value || mc.options.hideGui || mc.player == null) {
            return;
        }
        IBaritone baritone = BaritoneAPI.getProvider().getPrimaryBaritone();
        if (baritone == null) {
            return;
        }

        long nowMs = System.currentTimeMillis();

        // ── BPS (prawdziwy dt klatki, filtr teleportów) ──
        double dtSec = lastFrameMs == 0L ? 0.05 : Math.max(0.001, (nowMs - lastFrameMs) / 1000.0);
        lastFrameMs = nowMs;
        Vec3 pos = mc.player.position();
        if (posInit) {
            double dx = pos.x - prevX, dz = pos.z - prevZ;
            double inst = Math.sqrt(dx * dx + dz * dz) / dtSec;
            if (inst < 30.0) {
                bps = bps * 0.80 + inst * 0.20;
            }
        } else {
            posInit = true;
        }
        prevX = pos.x;
        prevZ = pos.z;

        // ── BPS spark chart: próbka co 500ms ──
        if (nowMs - lastSparkMs >= 500L) {
            sparkBps[sparkIdx % SPARK_N] = (float) bps;
            sparkIdx++;
            lastSparkMs = nowMs;
            sparkMax = 0.1F;
            for (float v : sparkBps) sparkMax = Math.max(sparkMax, v);
        }

        if (nowMs - lastDotMs > 450) {
            dotTick = (dotTick + 1) % 4;
            lastDotMs = nowMs;
        }

        Font font = mc.font;
        GatherTracker tracker = GatherTracker.INSTANCE;

        // ── zadanie ──
        String taskName = "IDLE";
        boolean isActive = false;
        if (baritone.getPathingBehavior().isPathing()) {
            taskName = "PATHING";
            isActive = true;
        }
        if (baritone.getPathingControlManager() != null) {
            var inControl = baritone.getPathingControlManager().mostRecentInControl();
            if (inControl.isPresent() && inControl.get().isActive()) {
                taskName = cleanTaskName(inControl.get().displayName());
                isActive = true;
            }
        }
        tracker.setCurrentTask(taskName);

        // ── A* ──
        boolean isCalculating = false;
        int nodesExplored = 0;
        int nodesPerSecInt = 0;
        PathingBehavior pb = (PathingBehavior) baritone.getPathingBehavior();
        var inProgress = pb.getInProgress();
        if (inProgress.isPresent()) {
            AbstractNodeCostSearch finder = inProgress.get();
            nodesExplored = finder.nodesExplored;
            isCalculating = true;
            long elapsed = nowMs - lastNodesSampleMs;
            if (elapsed >= 180) {
                double raw = (nodesExplored - lastNodesSnapshot) / (elapsed / 1000.0);
                nodesPerSec = nodesPerSec * 0.6 + raw * 0.4;
                lastNodesSnapshot = nodesExplored;
                lastNodesSampleMs = nowMs;
            }
            nodesPerSecInt = (int) nodesPerSec;
        } else {
            lastNodesSnapshot = 0;
            nodesPerSec = 0;
            lastNodesSampleMs = nowMs;
        }

        // ── trasa ──
        PathExecutor current = pb.getCurrent();
        int pathPos = 0, pathLen = 0;
        if (current != null && current.getPath() != null) {
            pathPos = current.getPosition();
            pathLen = current.getPath().length();
        }
        float target = pathLen > 0 ? Math.min(1F, (float) pathPos / pathLen) : 0F;
        displayProgress += (target - displayProgress) * 0.25F;

        String etaStr = "--:--";
        if (isActive && pathLen > 0 && pathPos < pathLen) {
            double etaSec = (pathLen - pathPos) / Math.max(2.5, bps);
            etaStr = String.format("%02d:%02d", (long) (etaSec / 60), (long) (etaSec % 60));
        }

        // ── Cel pathfindingu ──
        String goalStr = null;
        var goal = baritone.getPathingBehavior().getGoal();
        if (goal != null && isActive) {
            if (goal instanceof baritone.api.utils.interfaces.IGoalRenderPos grp) {
                var gp = grp.getGoalPos();
                goalStr = gp.getX() + " §8, §f" + gp.getY() + " §8, §f" + gp.getZ();
            } else if (goal instanceof baritone.api.pathing.goals.GoalComposite gc && gc.goals().length > 0) {
                var first = gc.goals()[0];
                if (first instanceof baritone.api.utils.interfaces.IGoalRenderPos grp) {
                    var gp = grp.getGoalPos();
                    goalStr = gp.getX() + " §8, §f" + gp.getY() + " §8, §f" + gp.getZ() + (gc.goals().length > 1 ? " §8(+" + (gc.goals().length - 1) + ")" : "");
                } else {
                    goalStr = gc.goals().length + " targets";
                }
            } else if (goal instanceof baritone.api.pathing.goals.GoalXZ gxz) {
                goalStr = "X: " + gxz.getX() + " §8, §7Z: " + gxz.getZ();
            } else if (goal instanceof baritone.api.pathing.goals.GoalYLevel gyl) {
                goalStr = "Y: " + gyl.level;
            } else {
                String gs = goal.toString();
                if (gs.length() > 22) gs = gs.substring(0, 21) + "…";
                goalStr = gs;
            }
        }

        // ── AutoEat / vitals ──
        AutoEatProcess autoEatProc = ((Baritone) baritone).getAutoEatProcess();
        boolean autoEatActive = Baritone.settings().autoEat.value && autoEatProc != null
                && (autoEatProc.isEating() || autoEatProc.isWaitingForSwap());
        boolean showHealth = Baritone.settings().hudShowHealth.value;
        float hp = mc.player.getHealth();
        float maxHp = mc.player.getMaxHealth();
        int food = mc.player.getFoodData().getFoodLevel();

        // ── Koordynaty ──
        int px = (int) Math.floor(pos.x);
        int py = (int) Math.floor(pos.y);
        int pz = (int) Math.floor(pos.z);

        // ── log zdarzeń ──
        if (!taskName.equals(lastTask)) { HudLog.push("TASK ▸ " + taskName); lastTask = taskName; }
        if (isCalculating != lastCalculating) {
            HudLog.push(isCalculating ? "A* ▸ search started" : "A* ▸ solved @ " + lastNodesSnapshot);
            lastCalculating = isCalculating;
        }
        if (pathLen > 0 && pathLen != lastPathLen) { HudLog.push("ROUTE ▸ " + pathLen + " steps"); lastPathLen = pathLen; }
        if (autoEatActive != lastAutoEat) {
            HudLog.push(autoEatActive ? "AUTOEAT ▸ start" : "AUTOEAT ▸ done");
            lastAutoEat = autoEatActive;
        }
        int logLines = 0;
        int logFirst = Math.max(0, HudLog.size() - 3);
        for (int i = logFirst; i < HudLog.size(); i++) {
            if (HudLog.age(i) < 10000L) {
                logLines++;
            }
        }

        // ── przełączniki sekcji ──
        Map<String, Integer> breakdown = tracker.getMinedCounts();
        boolean showSpeed = Baritone.settings().hudShowSpeed.value;
        boolean showAStar = isCalculating && Baritone.settings().hudShowAStar.value;
        boolean showMined = Baritone.settings().hudShowMined.value;
        boolean showOpt = Baritone.settings().fpsBoostEnabled.value && Baritone.settings().hudShowFpsBoost.value;
        boolean showBar = pathLen > 0;
        boolean shadersOn = HudShaders.isUsable();
        boolean showRadar = Baritone.settings().hudShowRadar.value && shadersOn;
        boolean showCoords = true; // zawsze widoczne
        boolean showSpark = showSpeed && isActive; // spark tylko gdy aktywny
        boolean showGoal = goalStr != null;
        int breakdownItems = showMined ? Math.min(3, breakdown.size()) : 0;

        // ── builder progress ──
        BuilderProcess builder = null;
        int buildTotal = 0, buildDone = 0, buildGhosts = 0, buildMissing = 0;
        String buildName = "";
        boolean showBuild = false;
        try {
            Object rawBuilder = BaritoneAPI.getProvider().getPrimaryBaritone().getBuilderProcess();
            if (rawBuilder instanceof BuilderProcess bp && bp.isActive()) {
                builder = bp;
                buildTotal = bp.getTotalCount();
                buildDone  = bp.getCompletedCount();
                buildGhosts = bp.getScheduler().ghostCount();
                buildMissing = bp.getMissingBlocks().size();
                buildName = bp.getBuildName();
                if (buildName.length() > 16) buildName = buildName.substring(0, 15) + "…";
                float buildTarget = buildTotal > 0 ? Math.min(1F, (float) buildDone / buildTotal) : 0F;
                displayBuildProgress += (buildTarget - displayBuildProgress) * 0.15F;
                showBuild = true;
            }
        } catch (Throwable ignored) {}
        if (!showBuild) displayBuildProgress = 0F;

        // ── wysokość panelu ──
        int H = TOP + BOTTOM;
        H += LINE;                                                        // nagłówek
        H += DIV;
        H += LINE;                                                        // status
        if (showGoal) H += LINE;                                          // cel
        if (showCoords) { H += DIV; H += LINE; }                         // koordynaty
        if (showSpeed || showAStar) { H += DIV; H += LINE * ((showSpeed ? 1 : 0) + (showAStar ? 1 : 0)); }
        if (showSpark) { H += 14; }                                       // spark chart
        if (showBar) { H += DIV; H += 7; H += LINE; }
        if (showBuild) { H += DIV; H += 7; H += LINE; }                  // build bar
        if (showRadar) { H += DIV; H += 54; }
        if (showMined) { H += DIV; H += LINE; H += breakdownItems * LINE; }
        H += DIV;
        if (showHealth) {
            H += LINE;
            if (shadersOn) {
                H += 14;
            }
        }
        if (autoEatActive) H += LINE;
        H += LINE;                                                        // uptime
        if (showOpt) H += LINE;
        if (logLines > 0) { H += DIV; H += logLines * LINE; }

        // ── skala / pozycja ──
        float scale = (float) Math.max(0.25, Math.min(3.0,
                Baritone.settings().hudScale.value * BASE_SCALE));
        int screenW = (int) (mc.getWindow().getGuiScaledWidth() / scale);
        int screenH = (int) (mc.getWindow().getGuiScaledHeight() / scale);
        String posCfg = Baritone.settings().hudPosition.value.trim().toUpperCase();

        int startX = 6, startY = 6;
        switch (posCfg) {
            case "TOP_RIGHT"    -> startX = Math.max(6, screenW - W - 6);
            case "BOTTOM_LEFT"  -> startY = Math.max(6, screenH - H - 6);
            case "BOTTOM_RIGHT" -> { startX = Math.max(6, screenW - W - 6); startY = Math.max(6, screenH - H - 6); }
            default -> { }
        }

        g.pose().pushPose();
        g.pose().scale(scale, scale, 1.0F);
        try {
            boolean shaders = HudShaders.isUsable();
            float glow = isCalculating ? 1.0F : isActive ? 0.55F : 0.0F;

            // ═══ Glassmorphic Obsidian Card Panel ═══
            drawGlassCard(g, startX, startY, W, H, ACCENT, isActive);

            int tx = startX + PAD;
            int right = startX + W - PAD;
            int contentW = W - PAD * 2;
            int ty = startY + TOP;

            // ═══ nagłówek z animowanymi chroma dots ═══
            long tms = System.currentTimeMillis();
            String statusDot;
            if (isCalculating) {
                int phase = (int) ((tms / 120) % 3);
                statusDot = (phase == 0 ? "§e" : phase == 1 ? "§b" : "§d") + "◆";
            } else if (isActive) {
                statusDot = ((tms / 500) % 2 == 0 ? "§a" : "§2") + "●";
            } else {
                statusDot = "§8○";
            }
            g.drawString(font, "§b§lBARITONE §8· §3§lSTEALTH §r" + statusDot, tx, ty, 0xFFFFFFFF, false);
            int fps = mc.getFps();
            String fpsCol = fps >= 60 ? "§a" : fps >= 30 ? "§e" : "§c";
            String fpsTxt = fpsCol + fps + " §7fps";
            g.drawString(font, fpsTxt, right - font.width(fpsTxt), ty, 0xFFFFFFFF, false);
            ty += LINE;
            ty = divider(g, tx, contentW, ty);

            // ═══ status ═══
            String icon = isCalculating ? "§e⚡" : isActive ? "§a●" : "§7○";
            String tCol = isCalculating ? "§e" : isActive ? "§a" : "§7";
            String ell = isActive ? "...".substring(0, dotTick) : "";
            g.drawString(font, icon + " " + tCol + taskName + ell + "§8_", tx, ty, 0xFFFFFFFF, false);
            ty += LINE;

            // ═══ cel pathfindingu ═══
            if (showGoal) {
                g.drawString(font, "§8⇒ §7Goal §f" + goalStr, tx, ty, 0xFFFFFFFF, false);
                ty += LINE;
            }

            // ═══ koordynaty z kolorowaniem strefy Y + wymiar ═══
            if (showCoords) {
                ty = divider(g, tx, contentW, ty);
                // Kolor Y wg strefy zagrożenia
                String yCol;
                if      (py < 0)  yCol = "§d";          // Void — magenta
                else if (py < 13) yCol = "§c";          // Lava zone — czerwony
                else if (py < 40) yCol = "§6";          // Deep underground — pomarańczowy
                else if (py < 63) yCol = "§e";          // Cave/underwater — żółty
                else              yCol = "§a";          // Powierzchnia — zielony
                String coordTxt = "§8xyz §7" + px + " " + yCol + py + " §7" + pz;
                g.drawString(font, coordTxt, tx, ty, 0xFFFFFFFF, false);
                // Wymiar po prawej
                String dim = "§a§lOW";
                if (mc.level != null) {
                    String dimPath = mc.level.dimension().location().getPath();
                    if      (dimPath.contains("nether")) dim = "§c§lNETH";
                    else if (dimPath.contains("end"))    dim = "§5§lEND";
                }
                g.drawString(font, dim, right - font.width(dim), ty, 0xFFFFFFFF, false);
                ty += LINE;
            }

            // ═══ metryki prędkości / A* ═══
            if (showSpeed || showAStar) {
                ty = divider(g, tx, contentW, ty);
                if (showSpeed) {
                    String sCol = !isActive ? "§7" : bps >= 5.0 ? "§a" : bps >= 1.0 ? "§e" : "§c";
                    g.drawString(font, "§b» §7Speed " + sCol + String.format("%.1f", bps)
                            + "§7 bps §8│ §7ETA §f" + etaStr, tx, ty, 0xFFFFFFFF, false);
                    ty += LINE;
                }
                if (showAStar) {
                    String ns = nodesPerSecInt >= 1000
                            ? String.format("%.1fk", nodesPerSecInt / 1000.0)
                            : String.valueOf(nodesPerSecInt);
                    String nCol = nodesPerSecInt >= 25000 ? "§a" : nodesPerSecInt >= 10000 ? "§e" : "§c";
                    g.drawString(font, "§b✱ §7A* " + nCol + ns + "§7 n/s §8(§f" + nodesExplored + "§8)",
                            tx, ty, 0xFFFFFFFF, false);
                    ty += LINE;
                }
            }

            // ═══ BPS Spark chart — mini wykres historii prędkości bez kolizji z tekstem ═══
            if (showSpark) {
                String maxLabel = "§b" + String.format("%.1f", sparkMax) + " §7b/s";
                int labelW = font.width(maxLabel) + 6;
                int sparW = contentW - labelW;
                int sparH = 10;
                int barCount = SPARK_N;
                int barW2 = Math.max(2, (sparW - barCount) / barCount);
                // tło spark
                g.fill(tx, ty, tx + sparW, ty + sparH, 0x22000000);
                for (int i = 0; i < barCount; i++) {
                    int idx = (sparkIdx + i) % SPARK_N;
                    float v = sparkBps[idx] / Math.max(0.1F, sparkMax);
                    int bh = Math.max(1, (int) (v * (sparH - 1)));
                    int bx = tx + i * (barW2 + 1);
                    boolean isCurrent = (i == barCount - 1);
                    int col = isCurrent ? ACCENT : lerp(0xFF1E3A4C, ACCENT, v * 0.7F);
                    g.fill(bx, ty + sparH - bh, bx + barW2, ty + sparH, col);
                    if (isCurrent && bh > 1) {
                        g.fill(bx, ty + sparH - bh, bx + barW2, ty + sparH - bh + 1, 0xFFFFFFFF);
                    }
                }
                // etykieta max po prawej, poza słupkami!
                g.drawString(font, maxLabel, tx + sparW + 4, ty + 1, 0xFFFFFFFF, false);
                ty += sparH + 4;
            }

            // ═══ trasa: animowany pasek + etykieta ═══
            if (showBar) {
                ty = divider(g, tx, contentW, ty);
                int barW = contentW;
                int barH = 5;
                g.fill(tx, ty, tx + barW, ty + barH, 0x33000000);
                g.fill(tx, ty, tx + barW, ty + 1, 0x22FFFFFF);
                int filled = Math.min(barW, Math.max(0, (int) (barW * displayProgress)));
                if (filled > 0) {
                    for (int i = 0; i < filled; i++) {
                        float f = (float) i / (float) barW;
                        g.fill(tx + i, ty, tx + i + 1, ty + barH, lerp(ACCENT, ACCENT_G, f));
                    }
                    g.fill(tx + Math.max(0, filled - 2), ty, tx + filled, ty + barH, 0xFFFFFFFF);
                }
                ty += barH + 2;
                int pct = (int) (displayProgress * 100);
                g.drawString(font, "§b→ §7Route §f" + pathPos + "§8/§f" + pathLen
                        + " §8(§b" + pct + "%§8)", tx, ty, 0xFFFFFFFF, false);
                ty += LINE;
            }

            // ═══ Build progress bar ═══
            if (showBuild) {
                ty = divider(g, tx, contentW, ty);
                int barW = contentW;
                int barH = 5;
                int buildColor = buildMissing > 0 ? 0xFFFFC800 : ACCENT_G;
                g.fill(tx, ty, tx + barW, ty + barH, 0x33000000);
                g.fill(tx, ty, tx + barW, ty + 1, 0x22FFFFFF);
                int filled = Math.min(barW, Math.max(0, (int) (barW * displayBuildProgress)));
                if (filled > 0) {
                    for (int i = 0; i < filled; i++) {
                        float f = (float) i / (float) barW;
                        g.fill(tx + i, ty, tx + i + 1, ty + barH, lerp(ACCENT, buildColor, f));
                    }
                    g.fill(tx + Math.max(0, filled - 2), ty, tx + filled, ty + barH, 0xFFFFFFFF);
                }
                ty += barH + 2;
                int pct = (int) (displayBuildProgress * 100);
                String ghostInfo = buildGhosts > 0 ? " §8│ §c" + buildGhosts + " ghost" : "";
                String missInfo  = buildMissing > 0 ? " §8│ §6miss:§e" + buildMissing : "";
                g.drawString(font, "§a⬛ §7Build §f" + buildDone + "§8/§f" + buildTotal
                        + " §8(§a" + pct + "%§8)" + ghostInfo + missInfo
                        + " §8[" + buildName + "§8]", tx, ty, 0xFFFFFFFF, false);
                ty += LINE;
            }

            // ═══ Radar mini-mapa ═══
            if (showRadar) {
                ty = divider(g, tx, contentW, ty);
                float[] dots = new float[32];
                int dc = 0;
                float range = 48.0F;
                if (current != null && current.getPath() != null) {
                    var positions = current.getPath().positions();
                    int step = Math.max(1, positions.size() / 16);
                    for (int i = 0; i < positions.size() && dc < 16; i += step) {
                        var bp = positions.get(i);
                        float lx = (float) ((bp.x + 0.5 - pos.x) / range);
                        float lz = (float) ((bp.z + 0.5 - pos.z) / range);
                        dots[dc * 2] = clamp01(0.5F + lx * 0.5F);
                        dots[dc * 2 + 1] = clamp01(0.5F + lz * 0.5F);
                        dc++;
                    }
                }
                float gx = 0.5F, gz = 0.5F;
                boolean hasGoal = false;
                if (goal instanceof baritone.api.utils.interfaces.IGoalRenderPos grp) {
                    var gp = grp.getGoalPos();
                    gx = clamp01(0.5F + (float) ((gp.getX() + 0.5 - pos.x) / range) * 0.5F);
                    gz = clamp01(0.5F + (float) ((gp.getZ() + 0.5 - pos.z) / range) * 0.5F);
                    hasGoal = true;
                }
                HudShaders.drawRadar(g, tx + (contentW - 52) / 2F, ty, 52, 52, ACCENT, dots, dc, gx, gz, hasGoal);
                ty += 54;
            }

            // ═══ Mined stats ═══
            if (showMined) {
                ty = divider(g, tx, contentW, ty);
                double bpm = tracker.getBlocksPerMinute();
                String bCol = bpm > 40 ? "§a" : bpm > 15 ? "§e" : "§7";
                g.drawString(font, "§b⛏ §7Mined §a§l" + tracker.getSessionMined()
                        + " §8│ " + bCol + String.format("%.1f", bpm) + "§7/m §8│ Σ §f"
                        + tracker.getTotalMined(), tx, ty, 0xFFFFFFFF, false);
                ty += LINE;

                int count = 0;
                for (Map.Entry<String, Integer> e : breakdown.entrySet()) {
                    if (count++ >= 3) break;
                    String name = e.getKey();
                    if (name.length() > 15) name = name.substring(0, 14) + "…";
                    String nameTxt = "§8▪ §f" + name;
                    String valTxt = "§e§l" + e.getValue();
                    g.drawString(font, nameTxt, tx + 2, ty, 0xFFFFFFFF, false);
                    int valX = right - font.width(valTxt);
                    int nameEnd = tx + 2 + font.width(nameTxt) + 3;
                    if (valX - 3 > nameEnd) {
                        g.fill(nameEnd, ty + 4, valX - 3, ty + 5, LEADER);
                    }
                    g.drawString(font, valTxt, valX, ty, 0xFFFFFFFF, false);
                    ty += LINE;
                }
            }

            // ═══ blok dolny: zdrowie, uptime ═══
            ty = divider(g, tx, contentW, ty);
            if (showHealth) {
                float hpPct = hp / maxHp;
                String hc = hpPct > 0.75F ? "§a" : hpPct > 0.4F ? "§e" : "§c";
                String fc = food >= 18 ? "§a" : food >= 12 ? "§e" : "§c";
                g.drawString(font, "§c♥ §7HP " + hc + String.format("%.1f", hp / 2F)
                        + " §8│ §6♦ §7Food " + fc + food + "§8/§f20", tx, ty, 0xFFFFFFFF, false);
                ty += LINE;
                if (shaders) {
                    HudShaders.drawRounded(g, tx, ty, contentW, 4, 2.0F, 0xFF9FB6C8, 0.08F, HudShaders.MODE_PILL, 0F);
                    HudShaders.drawRounded(g, tx, ty, contentW, 4, 2.0F, 0xFFFF3355, 0.65F, HudShaders.MODE_VITAL, hpPct);
                    ty += 6;
                    float foodPct = food / 20.0F;
                    HudShaders.drawRounded(g, tx, ty, contentW, 4, 2.0F, 0xFF9FB6C8, 0.08F, HudShaders.MODE_PILL, 0F);
                    HudShaders.drawRounded(g, tx, ty, contentW, 4, 2.0F, 0xFFFFCC33, 0.55F, HudShaders.MODE_VITAL, foodPct);
                    ty += 8;
                }
            }
            if (autoEatActive) {
                String state = autoEatProc.isWaitingForSwap() ? "§fswapping slot…" : "§feat…";
                String pulse = (nowMs / 400) % 2 == 0 ? "§c" : "§6";
                g.drawString(font, pulse + "♥ §7AutoEat " + state, tx, ty, 0xFFFFFFFF, false);
                ty += LINE;
            }
            long sec = tracker.getSessionDurationSeconds();
            g.drawString(font, "§b⌛ §7Uptime §f" + String.format("%02d:%02d", sec / 60, sec % 60),
                    tx, ty, 0xFFFFFFFF, false);
            ty += LINE;
            if (showOpt) {
                g.drawString(font, "§b⚙ §7Opt §a3x §8│ §7cull §b"
                        + FpsOptimizer.culledEntitiesThisFrame + "§7e §8/ §e"
                        + FpsOptimizer.culledBlockEntitiesThisFrame + "§7b", tx, ty, 0xFFFFFFFF, false);
                ty += LINE;
            }

            // ═══ log zdarzeń ═══
            if (logLines > 0) {
                ty = divider(g, tx, contentW, ty);
                for (int i = logFirst; i < HudLog.size(); i++) {
                    long age = HudLog.age(i);
                    if (age >= 10000L) continue;
                    String txt = HudLog.text(i);
                    while (txt.length() > 3 && font.width("› " + txt + "…") > contentW) {
                        txt = txt.substring(0, txt.length() - 1);
                    }
                    if (font.width("› " + txt) > contentW) txt = txt + "…";
                    float fade = age <= 7000L ? 1F : 1F - (age - 7000F) / 3000F;
                    int alpha = (int) Math.max(70, Math.min(255, fade * 255F));
                    g.drawString(font, "› " + txt, tx, ty, (alpha << 24) | 0x00D9F2FF, false);
                    ty += LINE;
                }
            }
        } finally {
            g.pose().popPose();
        }
    }

    /** Kreska sekcji. Zwraca nowe y. */
    private static int divider(GuiGraphics g, int x, int w, int y) {
        y += 4;
        // Subtle gradient divider — no shader stall needed, pure fill
        int col0 = 0x00FFFFFF & ACCENT | 0x55000000;
        int col1 = 0x00FFFFFF & ACCENT | 0x00000000;
        int mid  = x + w / 2;
        for (int i = 0; i < w / 2; i++) {
            float t = (float) i / (w / 2f);
            int alpha = (int) (85 * t);
            int c = (alpha << 24) | (ACCENT & 0x00FFFFFF);
            g.fill(mid - i - 1, y, mid - i, y + 1, c);
            g.fill(mid + i,     y, mid + i + 1, y + 1, c);
        }
        return y + 4;
    }

    /**
     * Glassmorphic obsidian card — no glCopyTexSubImage2D stall, pure fill-stack.
     * Uses layered semi-transparent fills to create a dark glassy look:
     *  - deep obsidian base (alpha ~82%)
     *  - inner highlight rim (top edge)
     *  - accent glow border
     */
    private static void drawGlassCard(GuiGraphics g, int x, int y, int w, int h, int accent, boolean active) {
        // 1. Dark obsidian base
        g.fill(x, y, x + w, y + h, 0xD2080C12);
        // 2. Subtle inner glass highlight (top strip)
        g.fill(x + 1, y + 1, x + w - 1, y + 3, 0x14FFFFFF);
        // 3. Border — 1px, accent-coloured, dim
        int borderAlpha = active ? 0x90000000 : 0x50000000;
        int border = borderAlpha | (accent & 0x00FFFFFF);
        g.fill(x,         y,         x + w,     y + 1,     border); // top
        g.fill(x,         y + h - 1, x + w,     y + h,     border); // bottom
        g.fill(x,         y,         x + 1,     y + h,     border); // left
        g.fill(x + w - 1, y,         x + w,     y + h,     border); // right
        // 4. Active: extra glow tint along top border
        if (active) {
            int glow = 0x2800E5FF; // cyan tint
            g.fill(x + 1, y + 1, x + w - 1, y + 2, glow);
        }
    }

    /**
     * Formats raw Baritone task/process name into clean human-readable form.
     * Strips BlockOptionalMeta class dump, lowercases block names, capitalises action.
     */
    private static String cleanTaskName(String raw) {
        if (raw == null || raw.isEmpty()) return "Idle";
        // e.g. "Mine BlockOptionalMeta{block=Block{minecraft:oak_log}...}" → "Mine oak_log"
        java.util.regex.Matcher m = java.util.regex.Pattern
                .compile("minecraft:([a-z0-9_]+)").matcher(raw);
        if (m.find()) {
            // Extract the action word before the block name
            String action = raw.split(" ")[0];
            if (action.length() > 12) action = action.substring(0, 12);
            String block = m.group(1).replace('_', ' ');
            return action + ": " + block;
        }
        // fallback: strip after first '{'
        int brace = raw.indexOf('{');
        if (brace > 0) raw = raw.substring(0, brace).trim();
        // strip after first '['
        int bracket = raw.indexOf('[');
        if (bracket > 0) raw = raw.substring(0, bracket).trim();
        return raw.length() > 28 ? raw.substring(0, 28) : raw;
    }

    private static float clamp01(float v) {
        return Math.max(0.0F, Math.min(1.0F, v));
    }

    /** Interpolacja ARGB (fallback panelu). */
    private static int lerp(int c1, int c2, float t) {
        int r  = (int) ((c1 >> 16 & 0xFF) * (1 - t) + (c2 >> 16 & 0xFF) * t);
        int gg = (int) ((c1 >> 8  & 0xFF) * (1 - t) + (c2 >> 8  & 0xFF) * t);
        int b  = (int) ((c1       & 0xFF) * (1 - t) + (c2       & 0xFF) * t);
        return (0xFF << 24) | (r << 16) | (gg << 8) | b;
    }
}
