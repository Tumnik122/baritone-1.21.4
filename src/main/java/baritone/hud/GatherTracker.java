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

import net.minecraft.core.BlockPos;
import net.minecraft.world.level.block.Block;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Tracks stats for blocks gathered and mined during Baritone automated tasks.
 */
public final class GatherTracker {

    public static final GatherTracker INSTANCE = new GatherTracker();

    private int totalMined = 0;
    private int sessionMined = 0;
    private long sessionStartTime = System.currentTimeMillis();
    private final Map<String, Integer> minedCounts = new ConcurrentHashMap<>();
    private String currentTask = "Idle";

    private GatherTracker() {}

    public synchronized void onBlockBroken(Block block, BlockPos pos) {
        if (block == null) {
            return;
        }
        totalMined++;
        sessionMined++;

        String blockName = block.getName().getString();
        minedCounts.merge(blockName, 1, Integer::sum);
    }

    public synchronized void resetSession() {
        sessionMined = 0;
        sessionStartTime = System.currentTimeMillis();
        minedCounts.clear();
    }

    public int getTotalMined() {
        return totalMined;
    }

    public int getSessionMined() {
        return sessionMined;
    }

    public Map<String, Integer> getMinedCounts() {
        // Return sorted by count descending
        Map<String, Integer> sorted = new LinkedHashMap<>();
        minedCounts.entrySet().stream()
                .sorted(Map.Entry.<String, Integer>comparingByValue().reversed())
                .forEachOrdered(e -> sorted.put(e.getKey(), e.getValue()));
        return sorted;
    }

    public long getSessionDurationSeconds() {
        return Math.max(1, (System.currentTimeMillis() - sessionStartTime) / 1000L);
    }

    public double getBlocksPerMinute() {
        long seconds = getSessionDurationSeconds();
        return (sessionMined * 60.0) / (double) seconds;
    }

    public String getCurrentTask() {
        return currentTask;
    }

    public void setCurrentTask(String currentTask) {
        this.currentTask = currentTask;
    }
}
