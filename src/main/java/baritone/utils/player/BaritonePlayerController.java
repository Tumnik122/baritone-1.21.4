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

    private static int lastSentSelectedSlot = -1;
    private static long lastSentSlotTick = -1L;

    public static void setCarriedItemSafe(LocalPlayer player, int targetSlot) {
        if (player == null || targetSlot < 0 || targetSlot > 8) {
            return;
        }
        Minecraft client = Minecraft.getInstance();
        long currentTick = player.tickCount;

        // Jeśli slot w ekwipunku i ostatnio wysłany pakiet to ten sam slot - nic nie rób
        if (player.getInventory().selected == targetSlot && lastSentSelectedSlot == targetSlot) {
            return;
        }

        // Zabezpieczenie przed GrimAC BadPacketsA:
        // GrimAC rzuca flagę, jeśli w TYM SAMYM TICKU gracz wyśle zmianę na slot A, a potem na slot B
        if (lastSentSlotTick == currentTick && lastSentSelectedSlot != -1 && lastSentSelectedSlot != targetSlot) {
            player.getInventory().selected = targetSlot;
            return;
        }

        player.getInventory().selected = targetSlot;
        lastSentSelectedSlot = targetSlot;
        lastSentSlotTick = currentTick;

        if (client.gameMode != null) {
            ((IPlayerControllerMP) client.gameMode).callSyncCurrentPlayItem();
        } else if (player.connection != null) {
            player.connection.send(new net.minecraft.network.protocol.game.ServerboundSetCarriedItemPacket(targetSlot));
        }
    }

    @Override
    public void syncHeldItem() {
        if (mc.player != null) {
            setCarriedItemSafe(mc.player, mc.player.getInventory().selected);
        } else if (mc.gameMode != null) {
            ((IPlayerControllerMP) mc.gameMode).callSyncCurrentPlayItem();
        }
    }

    @Override
    public boolean hasBrokenBlock() {
        return serverDiggingPos == null || mc.level == null || mc.level.getBlockState(serverDiggingPos).isAir();
    }

    private BlockPos serverDiggingPos = null;
    private BlockState lastServerDiggingState = null;
    private float serverDestroyProgress = 0.0f;
    private int currentBlockMiningTicks = 0;
    private int lastClientTick = -1;
    private boolean lastTickOnGround = true;

    @Override
    public boolean onPlayerDamageBlock(BlockPos pos, Direction side) {
        if (mc.gameMode == null || mc.level == null || mc.player == null) return false;

        BlockState state = mc.level.getBlockState(pos);
        if (state.isAir() || state.getDestroySpeed(mc.level, pos) < 0.0f) {
            resetBlockRemoving();
            return false;
        }

        if (serverDiggingPos == null || !serverDiggingPos.equals(pos) || (lastServerDiggingState != null && !lastServerDiggingState.equals(state))) {
            return clickBlock(pos, side);
        }

        // BLOKADA 1: Maksymalnie jedno naliczenie postępu na jeden tick klienta (mc.player.tickCount)
        if (mc.player.tickCount == lastClientTick) {
            return true;
        }
        lastClientTick = mc.player.tickCount;

        syncHeldItem();

        float grimDelta = baritone.utils.GrimBreakValidator.getGrimDestroyProgress(mc.player, mc.level, pos, state, lastTickOnGround);
        int requiredTicks = baritone.utils.GrimBreakValidator.getRequiredTicks(mc.player, mc.level, pos, state, lastTickOnGround);
        lastTickOnGround = mc.player.onGround();

        currentBlockMiningTicks++;
        serverDestroyProgress += grimDelta;

        float vanillaDelta = state.getDestroyProgress(mc.player, mc.level, pos);
        boolean readyToBreak = (serverDestroyProgress >= 1.0f && currentBlockMiningTicks >= requiredTicks);

        if (!readyToBreak) {
            // BLOKADA 2: Ustaw destroyProgress tak, aby PO dodaniu vanillaDelta wewnątrz continueDestroyBlock
            // wynik wyniósł maksymalnie 0.60f (poniżej waniliowego 1.0f ORAZ poniżej progów 0.7f z FastBreak).
            // Dzięki temu continueDestroyBlock wykona animację/dźwięk, ale NIE wyśle STOP_DESTROY_BLOCK i NIE zniszczy bloku!
            float safePreProgress = Math.max(0.0f, Math.min(serverDestroyProgress, 0.60f) - Math.max(0.0f, vanillaDelta));
            ((IPlayerControllerMP) mc.gameMode).setDestroyProgress(safePreProgress);
            mc.gameMode.continueDestroyBlock(pos, side);
            // Po wywołaniu ponownie przypilnuj bezpiecznej wartości
            ((IPlayerControllerMP) mc.gameMode).setDestroyProgress(Math.min(serverDestroyProgress, 0.60f));
            return true;
        }

        // Blok osiągnął wymaganą liczbę ticków oraz postęp >= 1.0f na serwerze:
        // Ustaw 1.0f, aby pojedyncze wywołanie continueDestroyBlock legalnie wysłało STOP_DESTROY_BLOCK
        ((IPlayerControllerMP) mc.gameMode).setDestroyProgress(1.0f);
        boolean result = mc.gameMode.continueDestroyBlock(pos, side);

        // Natychmiast wyczyść flagę kopania, aby zapobiec wysłaniu ABORT_DESTROY_BLOCK (WrongBreak: last=null)
        ((IPlayerControllerMP) mc.gameMode).setIsHittingBlock(false);
        ((IPlayerControllerMP) mc.gameMode).setDestroyProgress(0.0f);
        serverDiggingPos = null;
        lastServerDiggingState = null;
        serverDestroyProgress = 0.0f;
        currentBlockMiningTicks = 0;

        return result;
    }

    @Override
    public void resetBlockRemoving() {
        if (mc.gameMode == null) return;

        // KRYTYCZNE przeciw WrongBreak (action=CANCELLED_DIGGING, last=null):
        // Jeśli serwer nie ma aktywnego bloku (lub STOP_DESTROY_BLOCK już poszedł),
        // wyłącz isDestroying PRZED stopDestroyBlock(), aby klient nie wysłał nielegalnego pakietu ABORT_DESTROY_BLOCK.
        if (serverDiggingPos == null) {
            ((IPlayerControllerMP) mc.gameMode).setIsHittingBlock(false);
        }
        mc.gameMode.stopDestroyBlock();
        serverDiggingPos = null;
        lastServerDiggingState = null;
        serverDestroyProgress = 0.0f;
        currentBlockMiningTicks = 0;
        ((IPlayerControllerMP) mc.gameMode).setIsHittingBlock(false);
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
        if (mc.hasSingleplayerServer()) return true;
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

        // 1. GRIMAC PRIMARY: serverRotation — promień z rotacji, którą SERWER faktycznie zarejestrował (pre-flying)
        if (baritone != null && isGrim) {
            Rotation serverRot = baritone.getLookBehavior().getServerRotation();
            if (serverRot != null) {
                HitResult serverHit = RayTraceUtils.rayTraceTowards(player, serverRot, reach, player.isCrouching());
                if (serverHit instanceof BlockHitResult sbhr && sbhr.getBlockPos().equals(targetPos)) {
                    // serverRotation trafia bezpośrednio w docelowy blok — bezpieczne dla GrimAC
                    candidateHit = sbhr;
                }
            }
            if (candidateHit == null) {
                // Pod GrimAC: ZAKAZ klikania, jeśli serverRotation jeszcze nie patrzy na docelowy blok!
                // Odkładamy kliknięcie na kolejny tick, w którym sendPosition() wyśle właściwą rotację (0 dodatkowych pakietów flying).
                return InteractionResult.PASS;
            }
        } else if (baritone != null) {
            Rotation effectiveRot = baritone.getLookBehavior().getEffectiveRotation()
                    .orElseGet(() -> baritone.getLookBehavior().getServerRotation());
            if (effectiveRot != null) {
                HitResult serverHit = RayTraceUtils.rayTraceTowards(player, effectiveRot, reach, player.isCrouching());
                if (serverHit instanceof BlockHitResult sbhr && sbhr.getBlockPos().equals(targetPos)) {
                    candidateHit = sbhr;
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
        if (mc.gameMode == null || mc.level == null || mc.player == null) return false;

        BlockState state = mc.level.getBlockState(loc);
        if (state.isAir() || state.getDestroySpeed(mc.level, loc) < 0.0f) return false;

        // Nie wysyłaj duplikatu START_DESTROY_BLOCK, jeśli już kopiemy ten sam blok w tym samym stanie
        if (loc.equals(serverDiggingPos) && state.equals(lastServerDiggingState)) {
            return true;
        }

        // Jeśli zmieniamy blok w trakcie kopania poprzedniego, najpierw czysto anuluj poprzedni
        if (serverDiggingPos != null) {
            resetBlockRemoving();
        }

        syncHeldItem();
        lastTickOnGround = mc.player.onGround();
        float initialDelta = baritone.utils.GrimBreakValidator.getGrimDestroyProgress(mc.player, mc.level, loc, state, lastTickOnGround);

        lastClientTick = mc.player.tickCount;
        currentBlockMiningTicks = 0;
        serverDestroyProgress = initialDelta >= 1.0f ? 1.0f : 0.0f;
        serverDiggingPos = loc.immutable();
        lastServerDiggingState = state;

        // Wyzeruj stan przed startDestroyBlock, aby uniknąć przeniesienia postępu
        ((IPlayerControllerMP) mc.gameMode).setDestroyProgress(0.0f);

        boolean breakResult = mc.gameMode.startDestroyBlock(loc, face);
        if (breakResult) {
            lastBreakTick = mc.player.tickCount;
            markInteracted();
        }

        if (initialDelta >= 1.0f || mc.level.getBlockState(loc).isAir()) {
            // Instamine zakończony — zablokuj późniejszy ABORT_DESTROY_BLOCK
            ((IPlayerControllerMP) mc.gameMode).setIsHittingBlock(false);
            ((IPlayerControllerMP) mc.gameMode).setDestroyProgress(0.0f);
            serverDiggingPos = null;
            serverDestroyProgress = 0.0f;
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
