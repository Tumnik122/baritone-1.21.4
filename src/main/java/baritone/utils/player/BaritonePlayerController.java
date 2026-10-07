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
        }

        syncHeldItem();
        return mc.gameMode.continueDestroyBlock(pos, side);
    }

    @Override
    public void resetBlockRemoving() {
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

    @Override
    public InteractionResult processRightClickBlock(LocalPlayer player, Level world, InteractionHand hand, BlockHitResult result) {
        if (result == null || player == null || mc.gameMode == null) {
            return InteractionResult.PASS;
        }

        // ═══════════════════════════════════════════════════════════════════════
        // GrimAC Protection: RotationPlace (pre-flying / post-flying / raycast desync)
        // Strategia: serverRotation (potwierdzona przez serwer w poprzednim ticku) jako PRIMARY.
        // Dopiero gdy serverRot nie jest dostępna — fallback na kliencki celownik.
        // ═══════════════════════════════════════════════════════════════════════
        BlockPos targetPos = result.getBlockPos();
        double reach = this.getBlockReachDistance();
        final boolean fastMode = Baritone.settings().farmFastMode.value;
        BlockHitResult candidateHit = null;

        IBaritone baritone = BaritoneAPI.getProvider().getBaritoneForPlayer(player);

        // 1. GRIMAC PRIMARY: serverRotation — rotacja potwierdzona przez serwer (z poprzedniego ticku)
        //    FarmProcess ustawia rotację przez LookBehavior → pakiet Rot leci w onPlayerUpdate PRE.
        //    W następnym ticku serverRotation jest aktualna i akcja jest legalna dla GrimAC.
        if (baritone != null) {
            Rotation serverRot = baritone.getLookBehavior().getServerRotation();
            if (serverRot != null) {
                HitResult serverHit = RayTraceUtils.rayTraceTowards(player, serverRot, reach + 0.2D, player.isCrouching());
                if (serverHit instanceof BlockHitResult sbhr && sbhr.getBlockPos().equals(targetPos)) {
                    // ✅ serverRotation trafia w blok — używamy Direction z prawdziwego raytrace
                    candidateHit = sbhr;
                } else if (fastMode) {
                    // fastMode: serverRotation nie trafia jeszcze w blok.
                    // Pakiet Rot zostanie wysłany przez LookBehavior w onPlayerUpdate PRE tego ticku.
                    // Serwer jeszcze nie przetwarza tej rotacji. Czekamy 1 tick → PASS.
                    // (Eliminuje RotationPlace pre-flying!)
                    return InteractionResult.PASS;
                }
            }
        }

        // 2. Kliencki fallback (normalny tryb lub brak Baritone)
        if (candidateHit == null) {
            if (mc.hitResult instanceof BlockHitResult currentHit && currentHit.getBlockPos().equals(targetPos)) {
                candidateHit = currentHit;
            } else {
                HitResult picked = player.pick(reach, 1.0F, false);
                if (picked instanceof BlockHitResult pickHit && pickHit.getBlockPos().equals(targetPos)) {
                    candidateHit = pickHit;
                } else {
                    HitResult pickedFluid = player.pick(reach, 1.0F, true);
                    if (pickedFluid instanceof BlockHitResult fluidHit && fluidHit.getBlockPos().equals(targetPos)) {
                        candidateHit = fluidHit;
                    }
                }
            }
        }

        // 3. Bez fastMode + antiCheatCompatibility: blokujemy jeśli serverRot nie trafia w blok
        if (candidateHit != null && baritone != null && Baritone.settings().antiCheatCompatibility.value && !fastMode) {
            Rotation serverRot = baritone.getLookBehavior().getServerRotation();
            if (serverRot != null) {
                HitResult serverHit = RayTraceUtils.rayTraceTowards(player, serverRot, reach, player.isCrouching());
                boolean serverMatches = serverHit instanceof BlockHitResult sbhr && sbhr.getBlockPos().equals(targetPos);
                if (!serverMatches) {
                    HitResult serverFluidHit = rayTraceTowardsFluid(player, serverRot, reach);
                    serverMatches = serverFluidHit instanceof BlockHitResult sfbhr && sfbhr.getBlockPos().equals(targetPos);
                }
                if (!serverMatches) {
                    return InteractionResult.PASS;
                }
            }
        }

        // 4. Fallback dla Farmland/SoulSand gdy brak candidateHit (np. stoimy bezpośrednio na grządce)
        //    Farmland ma tylko ścianę UP widoczną z góry — bezpieczny, Direction.UP jest zawsze prawidłowy.
        if (candidateHit == null) {
            BlockState bs = world.getBlockState(targetPos);
            if (bs.getBlock() instanceof FarmBlock || bs.is(Blocks.SOUL_SAND)) {
                candidateHit = new BlockHitResult(
                        new Vec3(targetPos.getX() + 0.5, targetPos.getY() + 0.9375, targetPos.getZ() + 0.5),
                        Direction.UP, targetPos, false);
            } else {
                return InteractionResult.PASS;
            }
        }

        // 5. Dla Farmland i Soul Sand — wymuszamy Direction.UP (jedyna legalna ściana dla nasion)
        BlockState bsFinal = world.getBlockState(targetPos);
        if (bsFinal.getBlock() instanceof FarmBlock || bsFinal.is(Blocks.SOUL_SAND)) {
            candidateHit = new BlockHitResult(candidateHit.getLocation(), Direction.UP, targetPos, false);
        }

        syncHeldItem();
        return mc.gameMode.useItemOn(player, hand, candidateHit);
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

        final boolean fastMode = Baritone.settings().farmFastMode.value;
        // GrimAC Protection: RotationBreak & PositionBreakA
        IBaritone baritone = BaritoneAPI.getProvider().getBaritoneForPlayer(mc.player);
        if (baritone != null && (Baritone.settings().antiCheatCompatibility.value || Baritone.settings().grimCompat.value || Baritone.settings().antiCheatCompat.value)) {
            Rotation serverRot = baritone.getLookBehavior().getServerRotation();
            if (serverRot != null) {
                HitResult serverHit = RayTraceUtils.rayTraceTowards(mc.player, serverRot, getBlockReachDistance(), mc.player.isCrouching());
                if (serverHit instanceof BlockHitResult sbhr && sbhr.getBlockPos().equals(loc)) {
                    face = sbhr.getDirection();
                } else if (!fastMode) {
                    return false;
                }
                // W fastMode: serverRotation nie trafia — FarmProcess.isServerRotationAimed() już to sprawdza
                // i nie wywołuje clickBlock jeśli nie trafia. Jeśli jednak tu dotarliśmy, kontynuuj.
            } else if (mc.hitResult instanceof BlockHitResult bhr && bhr.getBlockPos().equals(loc)) {
                face = bhr.getDirection();
            }

            face = getSafeBreakFace(mc.player, loc, face);
        }

        syncHeldItem();
        return mc.gameMode.startDestroyBlock(loc, face);
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
