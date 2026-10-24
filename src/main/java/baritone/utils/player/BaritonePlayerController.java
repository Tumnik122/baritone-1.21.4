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

package baritone.utils.player;

import baritone.api.utils.IPlayerController;
import baritone.utils.accessor.IPlayerControllerMP;
import net.minecraft.client.Minecraft;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.inventory.ClickType;
import net.minecraft.world.level.GameType;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.BlockHitResult;
import baritone.Baritone;
import baritone.api.BaritoneAPI;
import baritone.utils.FastBreakHelper;
import baritone.api.IBaritone;
import baritone.api.utils.RayTraceUtils;
import baritone.api.utils.Rotation;
import baritone.api.utils.RotationUtils;
import net.minecraft.world.level.ClipContext;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.FarmBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;
import net.minecraft.world.phys.shapes.VoxelShape;


/**
 * Implementation of {@link IPlayerController} that chains to the primary player controller's methods
 *
 * @author Brady
 * @since 12/14/2018
 */
public final class BaritonePlayerController implements IPlayerController {

    private final Minecraft mc;

    public BaritonePlayerController(Minecraft mc) {
        this.mc = mc;
    }

    @Override
    public void syncHeldItem() {
        ((IPlayerControllerMP) mc.gameMode).callSyncCurrentPlayItem();
    }

    @Override
    public boolean hasBrokenBlock() {
        return !((IPlayerControllerMP) mc.gameMode).isHittingBlock();
    }

    private BlockPos currentBreakPos = null;
    private int breakStartTick = 0;

    @Override
    public boolean onPlayerDamageBlock(BlockPos pos, Direction side) {
        if (pos == null || side == null || mc.gameMode == null || mc.player == null) {
            return false;
        }

        IBaritone baritone = BaritoneAPI.getProvider().getBaritoneForPlayer(mc.player);
        if (baritone != null && (Baritone.settings().antiCheatCompatibility.value || Baritone.settings().grimCompat.value || Baritone.settings().antiCheatCompat.value)) {
            Rotation serverRot = baritone.getLookBehavior().getServerRotation();
            if (serverRot != null) {
                HitResult serverHit = RayTraceUtils.rayTraceTowards(mc.player, serverRot, getBlockReachDistance(), mc.player.isCrouching());
                if (serverHit instanceof BlockHitResult sbhr && sbhr.getBlockPos().equals(pos)) {
                    side = sbhr.getDirection();
                }
            } else if (mc.hitResult instanceof BlockHitResult bhr && bhr.getBlockPos().equals(pos)) {
                side = bhr.getDirection();
            }

            side = getSafeBreakFace(mc.player, pos, side);

            // GrimAC FastBreak pacing: do not call continueDestroyBlock earlier than server allows
            if (currentBreakPos != null && currentBreakPos.equals(pos) && mc.level != null) {
                BlockState st = mc.level.getBlockState(pos);
                double grimProgress = FastBreakHelper.getGrimDestroyProgress(baritone.getPlayerContext(), pos, st);
                int minGrimTicks = (grimProgress > 0) ? (int) Math.ceil(1.0 / grimProgress) : 1;
                int elapsed = mc.player.tickCount - breakStartTick;
                if (elapsed < minGrimTicks) {
                    return true;
                }
            } else {
                currentBreakPos = pos.immutable();
                breakStartTick = mc.player.tickCount;
            }
        }

        syncHeldItem();
        return mc.gameMode.continueDestroyBlock(pos, side);
    }

    @Override
    public void resetBlockRemoving() {
        currentBreakPos = null;
        breakStartTick = 0;
        mc.gameMode.stopDestroyBlock();
    }

    @Override
    public void windowClick(int windowId, int slotId, int mouseButton, ClickType type, Player player) {
        mc.gameMode.handleInventoryMouseClick(windowId, slotId, mouseButton, type, player);
    }

    @Override
    public GameType getGameType() {
        return mc.gameMode.getPlayerMode();
    }

    private long lastInteractionTick = -1L;
    private long lastInteractionTimeMs = 0L;
    private long lastRightClickTick = -1L;
    private long lastBreakTick = -1L;

    public boolean canInteractThisTick() {
        if (mc.player == null) return false;
        long currentTick = mc.player.tickCount;
        long now = System.currentTimeMillis();
        final boolean isGrim = Baritone.settings().antiCheatCompatibility.value
                || Baritone.settings().grimCompat.value
                || Baritone.settings().antiCheatCompat.value;

        long minTicks = isGrim ? 2L : 1L;
        long minMs = isGrim ? 110L : 45L;
        return (currentTick - lastInteractionTick) >= minTicks && (now - lastInteractionTimeMs) >= minMs;
    }

    public void markInteracted() {
        if (mc.player != null) {
            lastInteractionTick = mc.player.tickCount;
        }
        lastInteractionTimeMs = System.currentTimeMillis();
    }

    @Override
    public InteractionResult processRightClickBlock(LocalPlayer player, Level world, InteractionHand hand, BlockHitResult result) {
        if (result == null || player == null || mc.gameMode == null) {
            return InteractionResult.PASS;
        }

        final boolean isGrim = Baritone.settings().antiCheatCompatibility.value
                || Baritone.settings().grimCompat.value
                || Baritone.settings().antiCheatCompat.value;

        // Dla prawokliku (sadzenie nasion) pozwalamy na 1 interakcję na tick (50ms) zamiast 110ms spowalniającego throttla:
        if (isGrim && player.tickCount == lastRightClickTick) {
            return InteractionResult.PASS;
        } else if (!isGrim && !canInteractThisTick()) {
            return InteractionResult.PASS;
        }

        BlockPos targetPos = result.getBlockPos();
        double reach = Math.min(isGrim ? 2.95D : 4.0D, this.getBlockReachDistance());
        BlockHitResult candidateHit = null;

        IBaritone baritone = BaritoneAPI.getProvider().getBaritoneForPlayer(player);

        // 1. GRIMAC PRIMARY: effectiveRotation — raycast z perspektywy rotacji bieżącego ticku (zsynchronizowany obrót)
        if (baritone != null) {
            Rotation effectiveRot = baritone.getLookBehavior().getEffectiveRotation()
                    .orElseGet(() -> baritone.getLookBehavior().getServerRotation());
            if (effectiveRot != null) {
                HitResult serverHit = RayTraceUtils.rayTraceTowards(player, effectiveRot, reach, player.isCrouching());
                if (serverHit instanceof BlockHitResult sbhr && sbhr.getBlockPos().equals(targetPos)) {
                    // ✅ effectiveRotation trafia bezpośrednio w docelowy blok — używamy prawdziwego wektora i Direction
                    candidateHit = sbhr;
                }
                if (candidateHit == null && isGrim) {
                    // Pod GrimAC: ZAKAZ klikania przez ścianę/przeszkodę! Jeśli promień nie widzi bloku, blokujemy pakiet!
                    return InteractionResult.PASS;
                }
            }
        }

        // 2. Kliencki fallback (tylko non-Grim lub gdy brak serverRot)
        if (candidateHit == null) {
            if (isGrim) {
                return InteractionResult.PASS;
            }
            if (mc.hitResult instanceof BlockHitResult currentHit && currentHit.getBlockPos().equals(targetPos)) {
                candidateHit = currentHit;
            } else {
                HitResult picked = player.pick(reach, 1.0F, false);
                if (picked instanceof BlockHitResult pickHit && pickHit.getBlockPos().equals(targetPos)) {
                    candidateHit = pickHit;
                }
            }
        }

        if (candidateHit == null) {
            return InteractionResult.PASS;
        }

        // 3. Dla Farmland i Soul Sand — Direction.UP i blokada narzędzi
        BlockState bsFinal = world.getBlockState(targetPos);
        if (bsFinal.getBlock() instanceof FarmBlock || bsFinal.is(Blocks.SOUL_SAND)) {
            if (candidateHit.getDirection() != Direction.UP) {
                if (isGrim) {
                    return InteractionResult.PASS; // GrimAC wymaga uderzenia w ścianę UP
                }
                candidateHit = new BlockHitResult(candidateHit.getLocation(), Direction.UP, targetPos, false);
            }

            // ZABEZPIECZENIE ANTY-FLAG / ZERO-TOOL-ON-FARMLAND:
            net.minecraft.world.item.ItemStack held = player.getItemInHand(hand);
            if (!held.isEmpty() && (held.getItem() instanceof net.minecraft.world.item.DiggerItem || held.getItem() instanceof net.minecraft.world.item.SwordItem)) {
                return InteractionResult.PASS;
            }
        }

        syncHeldItem();
        InteractionResult res = mc.gameMode.useItemOn(player, hand, candidateHit);
        if (res.consumesAction()) {
            lastRightClickTick = player.tickCount;
            markInteracted();
        }
        return res;
    }

    private HitResult rayTraceTowardsFluid(LocalPlayer player, Rotation rotation, double reach) {
        Vec3 start = player.isCrouching()
                ? RayTraceUtils.inferSneakingEyePosition(player)
                : player.getEyePosition(1.0F);
        Vec3 direction = RotationUtils.calcLookDirectionFromRotation(rotation);
        Vec3 end = start.add(direction.x * reach, direction.y * reach, direction.z * reach);
        return player.level().clip(new ClipContext(start, end, ClipContext.Block.OUTLINE, ClipContext.Fluid.ANY, player));
    }

    @Override
    public InteractionResult processRightClick(LocalPlayer player, Level world, InteractionHand hand) {
        return mc.gameMode.useItem(player, hand);
    }

    @Override
    public boolean clickBlock(BlockPos loc, Direction face) {
        if (loc == null || face == null || mc.gameMode == null || mc.player == null) {
            return false;
        }

        final boolean isGrim = Baritone.settings().antiCheatCompatibility.value || Baritone.settings().grimCompat.value || Baritone.settings().antiCheatCompat.value;
        BlockState bs = mc.level != null ? mc.level.getBlockState(loc) : null;
        boolean isInstant = bs != null && bs.getDestroySpeed(mc.level, loc) == 0.0F;

        if (isInstant) {
            if (isGrim && mc.player.tickCount == lastBreakTick) {
                return false;
            } else if (!isGrim && !canInteractThisTick()) {
                return false;
            }
        } else {
            if (!canInteractThisTick()) {
                return false;
            }
        }

        // GrimAC Protection: RotationBreak & PositionBreakA
        IBaritone baritone = BaritoneAPI.getProvider().getBaritoneForPlayer(mc.player);
        if (baritone != null && isGrim) {
            Rotation effectiveRot = baritone.getLookBehavior().getEffectiveRotation()
                    .orElseGet(() -> baritone.getLookBehavior().getServerRotation());
            if (effectiveRot == null) {
                return false;
            }
            double reach = Math.min(3.0D, Math.min(Baritone.settings().grimSafeReach.value, getBlockReachDistance()));
            HitResult serverHit = RayTraceUtils.rayTraceTowards(mc.player, effectiveRot, reach, mc.player.isCrouching());
            if (serverHit instanceof BlockHitResult sbhr && sbhr.getBlockPos().equals(loc)) {
                face = sbhr.getDirection();
            } else {
                // effectiveRotation nie przecina jeszcze bloku na serwerze! Czekamy aż rotacja zostanie zsynchronizowana.
                return false;
            }
        }

        syncHeldItem();
        currentBreakPos = loc.immutable();
        breakStartTick = mc.player.tickCount;
        boolean breakResult = mc.gameMode.startDestroyBlock(loc, face);
        if (breakResult) {
            lastBreakTick = mc.player.tickCount;
            markInteracted();
        }
        return breakResult;
    }

    /**
     * Dobiera w 100% poprawną, bezpieczną ścianę bloku (Direction) do kliknięcia/kopania,
     * tak aby nigdy nie wywołać flagi GrimAC PositionBreakA ("Tried to break a block face from an impossible eye position").
     */
    public static Direction getSafeBreakFace(Player player, BlockPos pos, Direction requestedFace) {
        if (player == null || pos == null) {
            return requestedFace != null ? requestedFace : Direction.UP;
        }

        // 1. Jeśli żądana ściana nie jest niemożliwa wg GrimAC PositionBreakA, używamy jej
        if (requestedFace != null && !isFaceImpossible(player, pos, requestedFace)) {
            return requestedFace;
        }

        // 2. Sprawdzamy fizyczny celownik gracza w tej klatce
        HitResult hit = player.pick(4.5D, 1.0F, false);
        if (hit instanceof BlockHitResult bhr && bhr.getBlockPos().equals(pos)) {
            Direction dir = bhr.getDirection();
            if (!isFaceImpossible(player, pos, dir)) {
                return dir;
            }
        }

        // 3. Sprawdzamy rotację zarejestrowaną na serwerze (Baritone)
        if (player instanceof LocalPlayer lp) {
            IBaritone baritone = BaritoneAPI.getProvider().getBaritoneForPlayer(lp);
            if (baritone != null) {
                Rotation serverRot = baritone.getLookBehavior().getServerRotation();
                if (serverRot != null) {
                    HitResult serverHit = RayTraceUtils.rayTraceTowards(player, serverRot, 4.5D, player.isCrouching());
                    if (serverHit instanceof BlockHitResult sbhr && sbhr.getBlockPos().equals(pos)) {
                        Direction dir = sbhr.getDirection();
                        if (!isFaceImpossible(player, pos, dir)) {
                            return dir;
                        }
                    }
                }
            }
        }

        // 4. Geometryczny dobór ściany: szukamy ściany bloku najbardziej zwróconej ku oczom gracza
        Vec3 eye = player.getEyePosition();
        Vec3 center = Vec3.atCenterOf(pos);
        double dx = eye.x - center.x;
        double dy = eye.y - center.y;
        double dz = eye.z - center.z;

        Direction bestFace = null;
        double maxDot = -Double.MAX_VALUE;

        for (Direction d : Direction.values()) {
            if (!isFaceImpossible(player, pos, d)) {
                double dot = d.getStepX() * dx + d.getStepY() * dy + d.getStepZ() * dz;
                if (dot > maxDot) {
                    maxDot = dot;
                    bestFace = d;
                }
            }
        }

        if (bestFace != null) {
            return bestFace;
        }

        // 5. Fallback: ściana naprzeciwko kierunku patrzenia gracza
        Direction opposite = player.getDirection().getOpposite();
        if (!isFaceImpossible(player, pos, opposite)) {
            return opposite;
        }

        return Direction.NORTH;
    }

    /**
     * Sprawdza czy ściana jest niemożliwa do trafienia wg reguł GrimAC PositionBreakA.
     * W GrimAC:
     *   case UP    -> eyePositions.maxY < combined.maxY;
     *   case DOWN  -> eyePositions.minY > combined.minY;
     *   case NORTH -> eyePositions.minZ > combined.minZ;
     *   case SOUTH -> eyePositions.maxZ < combined.maxZ;
     *   case EAST  -> eyePositions.maxX < combined.maxX;
     *   case WEST  -> eyePositions.minX > combined.minX;
     */
    public static boolean isFaceImpossible(Player player, BlockPos pos, Direction face) {
        if (player == null || pos == null || face == null) return false;
        Vec3 eye = player.getEyePosition();
        double minX = pos.getX();
        double maxX = pos.getX() + 1.0;
        double minY = pos.getY();
        double maxY = pos.getY() + 1.0;
        double minZ = pos.getZ();
        double maxZ = pos.getZ() + 1.0;

        if (player.level() != null) {
            BlockState state = player.level().getBlockState(pos);
            VoxelShape shape = state.getShape(player.level(), pos);
            if (!shape.isEmpty()) {
                minX = pos.getX() + shape.min(Direction.Axis.X);
                maxX = pos.getX() + shape.max(Direction.Axis.X);
                minY = pos.getY() + shape.min(Direction.Axis.Y);
                maxY = pos.getY() + shape.max(Direction.Axis.Y);
                minZ = pos.getZ() + shape.min(Direction.Axis.Z);
                maxZ = pos.getZ() + shape.max(Direction.Axis.Z);
            }
        }

        switch (face) {
            case UP:
                return eye.y < maxY - 0.001;
            case DOWN:
                return eye.y > minY + 0.001;
            case NORTH:
                return eye.z > minZ + 0.001;
            case SOUTH:
                return eye.z < maxZ - 0.001;
            case WEST:
                return eye.x > minX + 0.001;
            case EAST:
                return eye.x < maxX - 0.001;
            default:
                return false;
        }
    }

    @Override
    public void setHittingBlock(boolean hittingBlock) {
        ((IPlayerControllerMP) mc.gameMode).setIsHittingBlock(hittingBlock);
    }

    @Override
    public void attack(Player player, net.minecraft.world.entity.Entity target) {
        syncHeldItem();
        mc.gameMode.attack(player, target);
        player.swing(InteractionHand.MAIN_HAND);
    }
}
