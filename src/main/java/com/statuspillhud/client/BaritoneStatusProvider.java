package com.statuspillhud.client;

import baritone.Baritone;
import baritone.api.BaritoneAPI;
import baritone.api.IBaritone;
import baritone.api.process.IBaritoneProcess;
import baritone.process.BuilderProcess;
import baritone.process.FarmProcess;
import baritone.process.TillProcess;
import net.minecraft.client.Minecraft;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Optional;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Dostarcza ustrukturyzowane informacje o aktywnym procesie Baritone dla Status Pill HUD.
 * Obsługuje procesy Farming (#farm), Tilling (#till / #zaorz), Clearing/Building (#cleararea / #build)
 * oraz ogólny stan pathingu/IDLE.
 */
public class BaritoneStatusProvider {

    private static final Logger LOGGER = LoggerFactory.getLogger(BaritoneStatusProvider.class);

    private final AtomicReference<ProcessInfo> lastKnownInfo = new AtomicReference<>(ProcessInfo.IDLE);

    // Śledzenie tempa zapełniania ekwipunku
    private long lastInvSampleTime = 0L;
    private int lastInvCount = -1;
    private double calculatedFillRate = 0.0;

    public ProcessInfo getCurrentProcess(Minecraft mc) {
        try {
            IBaritone baritone = BaritoneAPI.getProvider().getPrimaryBaritone();
            if (baritone == null) {
                lastKnownInfo.set(ProcessInfo.IDLE);
                return ProcessInfo.IDLE;
            }

            ProcessInfo info = analyzeBaritoneState(baritone, mc);
            lastKnownInfo.set(info);
            return info;
        } catch (Throwable e) {
            LOGGER.debug("[BaritoneProvider] Error accessing Baritone: {}", e.getMessage());
            lastKnownInfo.set(ProcessInfo.IDLE);
            return ProcessInfo.IDLE;
        }
    }

    private ProcessInfo analyzeBaritoneState(IBaritone baritone, Minecraft mc) {
        // 1. Sprawdź FarmProcess
        if (baritone instanceof Baritone bImpl) {
            FarmProcess farm = bImpl.getFarmProcess();
            if (farm != null && farm.isActive()) {
                return analyzeFarmProcess(farm, mc);
            }

            TillProcess till = bImpl.getTillProcess();
            if (till != null && till.isActive()) {
                return analyzeTillProcess(till);
            }

            BuilderProcess builder = bImpl.getBuilderProcess();
            if (builder != null && builder.isActive()) {
                return analyzeBuilderProcess(builder);
            }
        }

        // 2. Sprawdź czy jakikolwiek inny proces ma kontrolę
        if (baritone.getPathingControlManager() != null) {
            Optional<IBaritoneProcess> inControl = baritone.getPathingControlManager().mostRecentInControl();
            if (inControl.isPresent() && inControl.get().isActive()) {
                IBaritoneProcess proc = inControl.get();
                String name = proc.displayName();
                if (name == null || name.isEmpty()) {
                    name = proc.getClass().getSimpleName();
                }

                // Sprawdź czy to nie jest któryś ze specjalnych procesów po nazwie klasy
                if (name.contains("Farm")) {
                    return analyzeFarmProcess(null, mc);
                } else if (name.contains("Till")) {
                    return analyzeTillProcess(null);
                } else if (name.contains("Builder")) {
                    return analyzeBuilderProcess(null);
                }

                return new ProcessInfo(
                    ProcessType.OTHER,
                    name,
                    0, 0, 0, 0, 0, 0, 0.0, 0.0, true
                );
            }
        }

        // 3. Sprawdź PathingBehavior
        if (baritone.getPathingBehavior() != null && baritone.getPathingBehavior().isPathing()) {
            return new ProcessInfo(
                ProcessType.OTHER,
                "Pathing",
                0, 0, 0, 0, 0, 0, 0.0, 0.0, true
            );
        }

        return ProcessInfo.IDLE;
    }

    private ProcessInfo analyzeFarmProcess(FarmProcess farm, Minecraft mc) {
        int wheatHarvested = (int) FarmProcess.getTotalWheatHarvested();
        int wheatPerHour = (int) FarmProcess.getWheatPerHour();

        // Oblicz tempo zapełniania slotów (64 pszenicy na slot)
        double fillRate = 0.0;
        if (wheatPerHour > 0) {
            double slotsPerHour = wheatPerHour / 64.0;
            fillRate = slotsPerHour / 3600.0; // slotów na sekundę
        }

        // Próbkowanie ekwipunku co 5 sekund jako fallback/wsparcie
        long now = System.currentTimeMillis();
        if (lastInvSampleTime == 0L) {
            lastInvSampleTime = now;
            lastInvCount = countFilledSlots(mc);
        } else if (now - lastInvSampleTime >= 5000L) {
            int currentFilled = countFilledSlots(mc);
            if (lastInvCount >= 0 && currentFilled > lastInvCount) {
                double dt = (now - lastInvSampleTime) / 1000.0;
                calculatedFillRate = (currentFilled - lastInvCount) / dt;
            }
            lastInvSampleTime = now;
            lastInvCount = currentFilled;
        }

        if (fillRate <= 0.0001 && calculatedFillRate > 0.0001) {
            fillRate = calculatedFillRate;
        }

        return new ProcessInfo(
            ProcessType.FARMING,
            "Farming",
            wheatHarvested, wheatPerHour,
            0, 0, 0, 0, fillRate, 0.0, true
        );
    }

    private ProcessInfo analyzeTillProcess(TillProcess till) {
        int totalBlocks = till != null ? till.getTotalBlocks() : 0;
        int blocksTilled = till != null ? till.getBlocksTilled() : 0;
        int percentage = till != null ? till.getPercentage() : 0;
        long remaining = till != null ? till.getRemainingBlocks() : 0;
        double bps = till != null ? till.getBlocksPerSecond() : 0.5;
        if (bps < 0.1) bps = 0.5; // Domyślna bezpieczna wartość 0.5 bloku/s

        return new ProcessInfo(
            ProcessType.TILLING,
            "Tilling",
            0, 0, blocksTilled, totalBlocks, percentage,
            remaining, 0.0, bps, true
        );
    }

    private ProcessInfo analyzeBuilderProcess(BuilderProcess builder) {
        int percentage = builder != null ? builder.getPercentage() : 0;
        long remainingBlocks = builder != null ? builder.getRemainingBlocks() : 0;
        String buildName = builder != null ? builder.getBuildName() : "";
        boolean isClearing = buildName != null && (buildName.toLowerCase().contains("clear") || buildName.toLowerCase().contains("air"));

        ProcessType type = isClearing ? ProcessType.CLEARING : ProcessType.BUILDING;
        String displayName = isClearing ? "Clearing" : "Building";

        return new ProcessInfo(
            type,
            displayName,
            0, 0, 0, 0, percentage,
            remainingBlocks, 0.0, 0.5, true
        );
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

    public boolean isActive() {
        ProcessInfo info = lastKnownInfo.get();
        return info != null && info.isActive();
    }

    // === PROCESS INFO DATA CLASS ===
    public static class ProcessInfo {
        public static final ProcessInfo IDLE = new ProcessInfo(
            ProcessType.IDLE, "IDLE", 0, 0, 0, 0, 0, 0, 0.0, 0.0, false
        );

        private final ProcessType type;
        private final String displayName;
        private final int wheatHarvested;
        private final int wheatPerHour;
        private final int blocksTilled;
        private final int totalBlocks;
        private final int percentage;
        private final long remainingBlocks;
        private final double inventoryFillRate;
        private final double blocksPerSecond;
        private final boolean active;

        public ProcessInfo(ProcessType type, String displayName,
                          int wheatHarvested, int wheatPerHour,
                          int blocksTilled, int totalBlocks, int percentage,
                          long remainingBlocks, double inventoryFillRate,
                          double blocksPerSecond, boolean active) {
            this.type = type;
            this.displayName = displayName;
            this.wheatHarvested = wheatHarvested;
            this.wheatPerHour = wheatPerHour;
            this.blocksTilled = blocksTilled;
            this.totalBlocks = totalBlocks;
            this.percentage = percentage;
            this.remainingBlocks = remainingBlocks;
            this.inventoryFillRate = inventoryFillRate;
            this.blocksPerSecond = blocksPerSecond;
            this.active = active;
        }

        public ProcessType getType() { return type; }
        public String getDisplayName() { return displayName; }
        public int getWheatHarvested() { return wheatHarvested; }
        public int getWheatPerHour() { return wheatPerHour; }
        public int getBlocksTilled() { return blocksTilled; }
        public int getTotalBlocks() { return totalBlocks; }
        public int getPercentage() { return percentage; }
        public long getRemainingBlocks() { return remainingBlocks; }
        public double getInventoryFillRate() { return inventoryFillRate; }
        public double getBlocksPerSecond() { return blocksPerSecond; }
        public boolean isActive() { return active; }
    }

    public enum ProcessType {
        IDLE, FARMING, TILLING, CLEARING, BUILDING, OTHER
    }
}
