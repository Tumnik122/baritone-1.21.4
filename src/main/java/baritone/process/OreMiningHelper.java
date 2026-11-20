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

package baritone.process;

import baritone.Baritone;
import baritone.api.pathing.goals.GoalOreMining;
import baritone.api.utils.IPlayerContext;
import baritone.api.utils.Rotation;
import baritone.pathing.movement.MovementHelper;
import baritone.utils.BlockStateInterface;
import net.minecraft.core.BlockPos;
import net.minecraft.util.Mth;
import net.minecraft.world.level.ClipContext;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.Vec3;
import net.minecraft.world.phys.shapes.CollisionContext;

/**
 * Wspólny walidator dla GoalOreMining (wątek A*) i MineProcess (wątek klienta).
 * Zapewnia 100% symetrię między celem ścieżki a fizycznym kopaniem oraz kwantyzację GCD dla GrimAC.
 */
public final class OreMiningHelper {

    private OreMiningHelper() {}

    /**
     * Tworzy bezpieczną wątkowo wyrocznię dla GoalOreMining opartą wyłącznie na BlockStateInterface.
     */
    public static GoalOreMining.VoxelOracle createOracle(Baritone baritone) {
        final BlockStateInterface bsi = baritone.bsi;
        final Level world = baritone.getPlayerContext().world();

        return new GoalOreMining.VoxelOracle() {
            // Wątek A* jest jednowątkowy per wyszukiwanie; ThreadLocal chroni przed współdzieleniem
            private final ThreadLocal<BlockPos.MutableBlockPos> scratchPos =
                    ThreadLocal.withInitial(BlockPos.MutableBlockPos::new);

            @Override
            public boolean canStandAt(int x, int y, int z) {
                if (bsi == null) return false;
                BlockState floor = bsi.get0(x, y - 1, z);
                if (floor.isAir() || !floor.getFluidState().isEmpty()) {
                    return false;
                }
                // Sprawdź, czy nie stoimy w pełnym bloku lub cieczy
                BlockState feet = bsi.get0(x, y, z);
                BlockState head = bsi.get0(x, y + 1, z);
                return MovementHelper.canWalkOn(bsi, x, y - 1, z, floor)
                        && !MovementHelper.isLiquid(feet)
                        && !MovementHelper.isLiquid(head);
            }

            @Override
            public boolean isRayPassable(int x, int y, int z) {
                if (bsi == null) return true;
                BlockState state = bsi.get0(x, y, z);
                return state.isAir() || !state.getFluidState().isEmpty();
            }

            @Override
            public boolean canSafelyPierce(int bx, int by, int bz, int playerX, int playerY, int playerZ) {
                if (bsi == null || world == null) return false;

                // Ochrona przed podkopaniem dokładnie tego bloku, na którym stoi bot
                if (bx == playerX && by == playerY - 1 && bz == playerZ) {
                    BlockState belowFloor = bsi.get0(bx, by - 1, bz);
                    if (belowFloor.isAir() || MovementHelper.isLiquid(belowFloor)) {
                        return false;
                    }
                }

                BlockState state = bsi.get0(bx, by, bz);
                if (state.isAir() || !state.getFluidState().isEmpty()) {
                    return false;
                }

                BlockPos.MutableBlockPos mpos = scratchPos.get().set(bx, by, bz);
                if (state.getDestroySpeed(world, mpos) < 0.0F) {
                    return false; // Bedrock, Barrier, End Portal Frame itp.
                }

                return !MovementHelper.avoidBreaking(bsi, bx, by, bz, state);
            }
        };
    }

    /**
     * Kwantyzacja rotacji do siatki czułości myszy Minecraft (GCD) — wymagane przez GrimAC 1.21.4.
     */
    public static Rotation quantizeGcd(IPlayerContext ctx, Rotation target, Rotation current) {
        double sensitivity = ctx.minecraft().options.sensitivity().get();
        double f = sensitivity * 0.6D + 0.2D;
        double gcd = f * f * f * 8.0D * 0.15D;
        if (gcd <= 1.0E-6D) {
            return target;
        }

        float deltaYaw = Mth.wrapDegrees(target.getYaw() - current.getYaw());
        float deltaPitch = target.getPitch() - current.getPitch();

        float qDeltaYaw = (float) (Math.round(deltaYaw / gcd) * gcd);
        float qDeltaPitch = (float) (Math.round(deltaPitch / gcd) * gcd);

        float finalYaw = current.getYaw() + qDeltaYaw;
        float finalPitch = Mth.clamp(current.getPitch() + qDeltaPitch, -90.0F, 90.0F);
        return new Rotation(finalYaw, finalPitch);
    }

    /**
     * Dokładny raycast klienta zgodny z kątem po kwantyzacji GCD (używa CollisionContext.empty() - zero NPE).
     */
    public static BlockHitResult raycastWithRotation(IPlayerContext ctx, Vec3 eyes, Rotation quantizedRot, double reach) {
        float pitchRad = quantizedRot.getPitch() * Mth.DEG_TO_RAD;
        float yawRad = -quantizedRot.getYaw() * Mth.DEG_TO_RAD;

        float cosYaw = Mth.cos(yawRad);
        float sinYaw = Mth.sin(yawRad);
        float cosPitch = Mth.cos(pitchRad);
        float sinPitch = Mth.sin(pitchRad);

        Vec3 lookVec = new Vec3(sinYaw * cosPitch, -sinPitch, cosYaw * cosPitch);
        Vec3 end = eyes.add(lookVec.scale(reach));

        return ctx.world().clip(new ClipContext(
                eyes,
                end,
                ClipContext.Block.OUTLINE,
                ClipContext.Fluid.NONE,
                CollisionContext.empty()
        ));
    }

    /**
     * Sprawdza, czy dany blok jest bezpieczny do rozbicia w kontekście bieżącej pozycji gracza.
     */
    public static boolean isBreakableSafe(Baritone baritone, IPlayerContext ctx, BlockPos pos, BlockState state) {
        if (state.isAir() || !state.getFluidState().isEmpty()) {
            return false;
        }
        if (state.getDestroySpeed(ctx.world(), pos) < 0.0F) {
            return false;
        }
        BlockPos feet = ctx.playerFeet();
        if (pos.getX() == feet.getX() && pos.getY() == feet.getY() - 1 && pos.getZ() == feet.getZ()) {
            BlockState under = baritone.bsi.get0(pos.getX(), pos.getY() - 1, pos.getZ());
            if (under.isAir() || MovementHelper.isLiquid(under)) {
                return false;
            }
        }
        return !MovementHelper.avoidBreaking(baritone.bsi, pos.getX(), pos.getY(), pos.getZ(), state);
    }
}

