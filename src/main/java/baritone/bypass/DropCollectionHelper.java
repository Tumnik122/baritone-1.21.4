package baritone.bypass;

import net.minecraft.client.player.LocalPlayer;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.tags.FluidTags;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.level.ClipContext;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.BaseFireBlock;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;

/**
 * Pomocnik bezpiecznego zbierania dropów surowców pod GrimAC.
 * Weryfikuje fizyczną dostępność itemu, wykrywa bloki blokujące podejście
 * i zapobiega utknięciu w ścianach lub wnękach.
 */
public final class DropCollectionHelper {

    private DropCollectionHelper() {}

    /**
     * Sprawdza, czy drop jest niebezpieczny (leży w lawie, ogniu, nad jeziorem lawy lub w pobliżu otwartej lawy).
     * Zapobiega samobójczym próbom podnoszenia itemów z lawy.
     */
    public static boolean isDropUnsafe(Level level, ItemEntity drop) {
        if (level == null || drop == null || !drop.isAlive()) return true;

        BlockPos pos = drop.blockPosition();
        BlockState stateAt = level.getBlockState(pos);

        // 1. Sam item leży bezpośrednio w lawie lub płomieniach
        if (stateAt.getFluidState().is(FluidTags.LAVA) || stateAt.getBlock() instanceof BaseFireBlock) {
            return true;
        }
        BlockState above = level.getBlockState(pos.above());
        if (above.getFluidState().is(FluidTags.LAVA)) {
            return true;
        }

        // 2. Pod itemem jest lawa lub dół z lawą
        BlockPos below = pos.below();
        BlockState stateBelow = level.getBlockState(below);
        if (stateBelow.getFluidState().is(FluidTags.LAVA) || stateBelow.getBlock() instanceof BaseFireBlock || stateBelow.getBlock().equals(Blocks.MAGMA_BLOCK)) {
            return true;
        }
        if (LiquidDetector.isLavaPitBelow(level, below, 6)) {
            return true;
        }

        // 3. Sprawdź sąsiadów poziomo (1 kratka wokół dropu pod kątem otwartej lawy)
        for (Direction dir : Direction.Plane.HORIZONTAL) {
            BlockPos adj = pos.relative(dir);
            if (level.getFluidState(adj).is(FluidTags.LAVA) || level.getFluidState(adj.below()).is(FluidTags.LAVA)) {
                return true;
            }
        }

        return false;
    }

    /**
     * Sprawdza, czy gracz może podejść do dropu w linii prostej bez kolizji z blokami i bez wpadania w lawę/dziury.
     */
    public static boolean canWalkDirectlyToDrop(LocalPlayer player, Level level, ItemEntity drop) {
        if (player == null || level == null || drop == null || !drop.isAlive()) return false;

        Vec3 playerPos = player.position();
        Vec3 dropPos = drop.position();

        // Różnica wysokości: dopuszczamy drop w rowku 1 blok w dół (-1.4m) lub na półce do +1.0m
        double dy = dropPos.y - playerPos.y;
        if (dy < -1.4 || dy > 1.0) {
            return false;
        }

        Vec3 delta = new Vec3(dropPos.x - playerPos.x, 0.0, dropPos.z - playerPos.z);
        double dist = delta.length();
        if (dist < 0.25) return true;

        // Gracz podnosi item przy kontakcie hitboxów (zasięg podnoszenia to ~1.0m od środka gracza)
        double checkDist = Math.max(0.2, dist - 0.5);
        Vec3 step = delta.normalize().scale(0.25);
        int steps = (int) Math.ceil(checkDist / 0.25);

        AABB currentBox = player.getBoundingBox().deflate(0.04);
        for (int i = 1; i <= steps; i++) {
            AABB testBox = currentBox.move(step.scale(i));
            if (!level.noCollision(player, testBox)) {
                return false; // Na drodze stoi solidny blok (np. deepslate we wnęce)
            }
            // Sprawdź, czy po drodze pod stopami jest podłoże i nie ma płynów (lawa/woda)
            BlockPos below = BlockPos.containing(testBox.getCenter().x, testBox.minY - 0.5, testBox.getCenter().z);
            if (!level.getFluidState(below).isEmpty()) {
                return false;
            }
            // Jeśli podłoże jest powietrzem, dopuszczamy maksymalnie 1 blok uskoku w dół
            if (level.getBlockState(below).isAir()) {
                BlockPos twoBelow = below.below();
                if (!level.getFluidState(twoBelow).isEmpty() || level.getBlockState(twoBelow).isAir()) {
                    return false; // Głęboka dziura lub lawa pod spodem
                }
            }
        }
        return true;
    }

    /**
     * Zwraca pierwszy blok zasłaniający dostęp do dropu w zasięgu kopania gracza (do rozkopania wnęki).
     */
    public static BlockPos findObstructingBlock(LocalPlayer player, Level level, ItemEntity drop) {
        if (player == null || level == null || drop == null) return null;

        Vec3 eyePos = player.getEyePosition();
        Vec3 dropCenter = drop.getBoundingBox().getCenter();

        // 1. Raycast od oczu gracza do środka dropu
        BlockHitResult hit = level.clip(new ClipContext(
                eyePos, dropCenter, ClipContext.Block.COLLIDER, ClipContext.Fluid.NONE, player
        ));
        if (hit.getType() == HitResult.Type.BLOCK) {
            BlockPos hitPos = hit.getBlockPos();
            if (isMineableObstruction(level, hitPos, eyePos)) {
                return hitPos;
            }
        }

        // 2. Jeśli oczy widzą drop w szczelinie 1x1, ale ciało gracza nie może wejść:
        // sprawdź blok nad dropem oraz blok na wysokości nóg/głowy w kierunku dropu
        BlockPos dropBlockPos = drop.blockPosition();
        BlockPos aboveDrop = dropBlockPos.above();
        if (isMineableObstruction(level, aboveDrop, eyePos)) {
            return aboveDrop;
        }

        Vec3 dirToDrop = new Vec3(drop.getX() - player.getX(), 0, drop.getZ() - player.getZ());
        if (dirToDrop.lengthSqr() > 1.0E-4) {
            dirToDrop = dirToDrop.normalize();
            BlockPos frontFeet = BlockPos.containing(player.getX() + dirToDrop.x * 0.8, player.getY() + 0.2, player.getZ() + dirToDrop.z * 0.8);
            BlockPos frontHead = frontFeet.above();
            if (isMineableObstruction(level, frontHead, eyePos)) {
                return frontHead;
            }
            if (isMineableObstruction(level, frontFeet, eyePos)) {
                return frontFeet;
            }
        }

        return null;
    }

    private static boolean isMineableObstruction(Level level, BlockPos pos, Vec3 eyePos) {
        if (eyePos.distanceToSqr(Vec3.atCenterOf(pos)) > 4.2 * 4.2) return false;
        BlockState state = level.getBlockState(pos);
        if (state.isAir() || !state.getFluidState().isEmpty()) return false;
        float hardness = state.getDestroySpeed(level, pos);
        return hardness >= 0.0f && !state.getCollisionShape(level, pos).isEmpty();
    }
}

