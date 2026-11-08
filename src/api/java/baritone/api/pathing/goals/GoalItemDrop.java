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

package baritone.api.pathing.goals;

import baritone.api.utils.interfaces.IGoalRenderPos;
import net.minecraft.core.BlockPos;
import net.minecraft.util.Mth;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.level.BlockGetter;
import net.minecraft.world.level.block.state.BlockState;

/**
 * Cel A* zoptymalizowany pod rzeczywistą mechanikę podnoszenia przedmiotów w Minecraft 1.21.4
 * oraz restrykcje antycheatów (GrimAC / Vulcan / Polar).
 *
 * Eliminuje problem wbijania się w podłogę (kopania pod itemem) i skakania do sufitu:
 * 1. Zamiast GoalBlock(x, y, z), cel jest spełniony, gdy gracz wejdzie w bezpieczną strefę
 *    przecięcia AABB podnoszenia (promień XZ = 0.85, dy od -0.35 do 1.95).
 * 2. Dla wykopanych bloków w suficie/ścianie (anticipatedDrops) pozycja jest rzutowana
 *    grawitacyjnie w dół (resolveLandingY) na pierwszy stabilny blok z kolizją.
 * 3. Skwantowany hash (siatka 0.25 bloku) eliminuje niepotrzebne przebudowy ścieżki
 *    podczas oscylacji i obracania się encji przedmiotu w świecie.
 */
public class GoalItemDrop implements Goal, IGoalRenderPos {

    // Bezpieczny promień podnoszenia w poziomie (vanilla: 1.0 + 0.3 połowy szerokości gracza = 1.30;
    // zawężamy do 0.85 dla pewności ticku fizyki GrimAC PredictionEngine i braku overshootu).
    private static final double PICKUP_RADIUS_XZ = 0.85;
    private static final double PICKUP_MIN_DY = -0.35;
    private static final double PICKUP_MAX_DY = 1.95;

    private final double itemX;
    private final double itemY;
    private final double itemZ;
    private final int blockX;
    private final int blockY;
    private final int blockZ;
    private final int quantizedHash;

    public GoalItemDrop(ItemEntity entity) {
        this(entity.getX(), entity.getY(), entity.getZ(), null, false);
    }

    public GoalItemDrop(ItemEntity entity, BlockGetter level) {
        this(entity.getX(), entity.getY(), entity.getZ(), level, false);
    }

    public GoalItemDrop(BlockPos brokenOrePos, BlockGetter level) {
        this(brokenOrePos.getX() + 0.5, brokenOrePos.getY() + 0.125, brokenOrePos.getZ() + 0.5, level, true);
    }

    public GoalItemDrop(double rawX, double rawY, double rawZ, BlockGetter level, boolean projectGravity) {
        int bx = Mth.floor(rawX);
        int by = Mth.floor(rawY);
        int bz = Mth.floor(rawZ);

        if (projectGravity && level != null) {
            by = resolveLandingY(level, bx, by, bz);
            rawY = by + 0.125;
        }

        this.itemX = rawX;
        this.itemY = rawY;
        this.itemZ = rawZ;
        this.blockX = bx;
        this.blockY = by;
        this.blockZ = bz;

        int qx = (int) Math.floor(rawX * 4.0);
        int qy = (int) Math.floor(rawY * 2.0);
        int qz = (int) Math.floor(rawZ * 4.0);
        this.quantizedHash = (qx * 7340033) ^ (qy * 8388617) ^ qz;
    }

    /**
     * Sprowadza pozycję wykopanego bloku (np. z sufitu lub ściany) w dół do pierwszego bloku z kolizją.
     */
    public static int resolveLandingY(BlockGetter level, int x, int startY, int z) {
        int minY = level.getMinY();
        int limitY = Math.max(minY, startY - 16);
        for (int y = startY; y > limitY; y--) {
            BlockPos checkPos = new BlockPos(x, y - 1, z);
            BlockState below = level.getBlockState(checkPos);
            if (!below.getCollisionShape(level, checkPos).isEmpty()) {
                return y;
            }
        }
        return startY;
    }

    public double getItemX() {
        return itemX;
    }

    public double getItemY() {
        return itemY;
    }

    public double getItemZ() {
        return itemZ;
    }

    @Override
    public boolean isInGoal(int x, int y, int z) {
        double dx = Math.abs((x + 0.5) - this.itemX);
        double dz = Math.abs((z + 0.5) - this.itemZ);
        double dy = this.itemY - y;

        if (dx <= PICKUP_RADIUS_XZ && dz <= PICKUP_RADIUS_XZ && dy >= PICKUP_MIN_DY && dy <= PICKUP_MAX_DY) {
            return true;
        }
        // Fallback: jeśli gracz stoi w bloku itemu lub bloku sąsiednim w pionie
        return x == this.blockX && z == this.blockZ && (y == this.blockY || y == this.blockY - 1 || y == this.blockY + 1);
    }

    @Override
    public double heuristic(int x, int y, int z) {
        double dx = Math.max(0.0, Math.abs((x + 0.5) - this.itemX) - (PICKUP_RADIUS_XZ * 0.8));
        double dz = Math.max(0.0, Math.abs((z + 0.5) - this.itemZ) - (PICKUP_RADIUS_XZ * 0.8));
        int dyDiff = (this.itemY >= y + PICKUP_MIN_DY && this.itemY <= y + PICKUP_MAX_DY)
                ? 0
                : (y - this.blockY);

        return GoalXZ.calculate(dx, dz) + (dyDiff == 0 ? 0.0 : GoalYLevel.calculate(this.blockY, y));
    }

    @Override
    public double heuristic() {
        return 0.0;
    }

    @Override
    public BlockPos getGoalPos() {
        return new BlockPos(blockX, blockY, blockZ);
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (!(o instanceof GoalItemDrop other)) return false;
        return this.quantizedHash == other.quantizedHash
                && Math.abs(this.itemX - other.itemX) < 0.35
                && Math.abs(this.itemY - other.itemY) < 0.60
                && Math.abs(this.itemZ - other.itemZ) < 0.35;
    }

    @Override
    public int hashCode() {
        return this.quantizedHash;
    }

    @Override
    public String toString() {
        return String.format("GoalItemDrop{x=%.2f, y=%.2f, z=%.2f}", itemX, itemY, itemZ);
    }
}

