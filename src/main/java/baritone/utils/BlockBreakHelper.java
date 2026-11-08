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

package baritone.utils;

import baritone.api.BaritoneAPI;
import baritone.api.utils.BetterBlockPos;
import baritone.api.utils.IPlayerContext;
import baritone.api.utils.RotationUtils;
import baritone.hud.AiActionLogger;
import baritone.utils.accessor.IPlayerControllerMP;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.level.ClipContext;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;

import java.util.Random;

/**
 * @author Brady
 * @since 8/25/2018
 *
 * GrimAC-safe block-breaking helper.
 *
 * Three invariants are enforced to produce zero FastBreak / GroundSpoof flags:
 *
 *  1. NO AIRBORNE MINING — we never send any attack packet while onGround=false.
 *     GrimAC applies a ÷5 speed penalty for airborne players; mining while
 *     airborne therefore causes "diff" FastBreak flags even for blocks that
 *     our local instamine formula considers safe.
 *
 *  2. INTER-BLOCK COOLDOWN — after a NON-instamine block breaks we enforce a
 *     hard 5-tick (250 ms) wait before starting the next block, regardless of
 *     whether the NEXT target is instamine.  GrimAC tracks the gap between
 *     FINISH_DIGGING(A) and START_DIGGING(B) and flags it when it is shorter
 *     than vanilla's destroyDelay.
 *
 *  3. INSTAMINE ONLY WHEN TRULY INSTAMINE — we zero destroyDelay / bypass
 *     breakDelayTimer exclusively for blocks where progress ≥ 1.0 per tick
 *     (FastBreakHelper.isInstaBreak).  Hard-coded "5-tick creative cooldown"
 *     vanilla sets between blocks is preserved for everything else.
 */
public final class BlockBreakHelper {
    // Minimum ticks between any two consecutive block-break sequences
    // (must match or exceed vanilla's destroyDelay = 5 so GrimAC is satisfied).
    private static final int GRIM_INTER_BLOCK_TICKS = 5;

    // Base per-block delay driven by blockBreakSpeed setting (kept for
    // humanised jitter on top of the GrimAC-mandated minimum).
    private static final int BASE_BREAK_DELAY = 1;

    private final IPlayerContext ctx;
    private final Random random = new Random();
    private boolean wasHitting;

    /** Ticks remaining in the current inter-block cooldown. */
    private int breakDelayTimer = 0;

    /**
     * True when the block that just broke was NON-instamine.
     * We need to sit out the full GRIM_INTER_BLOCK_TICKS before the next
     * START_DIGGING, even if the next target is itself instamine.
     */
    private boolean lastBreakWasNonInstamine = false;

    /**
     * Humanised reaction delay: ticks to wait before attacking a freshly-
     * targeted block.  Prevents 0-tick Aim/FastBreak flags on new targets.
     */
    private int reactionDelay = 0;
    private BlockPos lastTargetPos = null;
    private BlockState lastTargetBlockState = null;

    BlockBreakHelper(IPlayerContext ctx) {
        this.ctx = ctx;
    }

    public void stopBreakingBlock() {
        stopBreakingCleanly();
        lastBreakWasNonInstamine = false;
        ContinuousBreakController.reset();
    }

    public void stopBreakingCleanly() {
        if (wasHitting) {
            ctx.playerController().resetBlockRemoving();
            wasHitting = false;
            lastTargetPos = null;
            lastTargetBlockState = null;
        }
        reactionDelay = 0;
    }

    private HitResult getBlockHitResultDirectly() {
        if (ctx.player() == null || ctx.world() == null) return null;
        double reach = ctx.playerController().getBlockReachDistance();
        Vec3 eyePos = ctx.player().getEyePosition(1.0F);
        Vec3 lookVec = RotationUtils.calcLookDirectionFromRotation(ctx.playerRotations());
        Vec3 endPos = eyePos.add(lookVec.scale(reach));
        return ctx.world().clip(new ClipContext(eyePos, endPos, ClipContext.Block.OUTLINE, ClipContext.Fluid.NONE, ctx.player()));
    }

    public void tick(boolean isLeftClick) {
        HitResult trace = getBlockHitResultDirectly();
        if (trace == null || trace.getType() != HitResult.Type.BLOCK) {
            trace = ctx.objectMouseOver();
        }
        boolean isBlockTrace = trace != null && trace.getType() == HitResult.Type.BLOCK;
        BlockPos currentTarget = isBlockTrace ? ((BlockHitResult) trace).getBlockPos() : null;

        // ── FIX 4: Supporting floor block directly beneath feet ──
        // Instamining or breaking the floor block supporting the player in 0-ticks causes
        // a sudden vertical drop before the server acknowledges the block removal,
        // triggering GrimAC GroundSpoof, Simulation, and NoFall flags.
        // Therefore, if the target block is directly beneath the player's feet, we disable
        // 0-tick instamining and force standard multi-tick breaking, ensuring the server
        // acknowledges the destruction cleanly before the player starts falling.
        // We MUST NOT return early, as doing so deadlocks downward mining paths.
        boolean directlyAboveFeet = false;
        if (currentTarget != null && ctx.player() != null) {
            net.minecraft.world.phys.AABB playerBox = ctx.player().getBoundingBox();
            net.minecraft.world.phys.AABB blockBox = new net.minecraft.world.phys.AABB(currentTarget);
            boolean directlyAbove = Math.abs(playerBox.minY - (currentTarget.getY() + 1.0)) < 0.1;
            boolean horizontalOverlap = playerBox.minX < blockBox.maxX && playerBox.maxX > blockBox.minX
                    && playerBox.minZ < blockBox.maxZ && playerBox.maxZ > blockBox.minZ;
            directlyAboveFeet = directlyAbove && horizontalOverlap;
        }
        BlockState currentState = (currentTarget != null && ctx.world() != null)
                ? ctx.world().getBlockState(currentTarget) : null;

        // ── BEDROCK & UNBREAKABLE PROTECTION ──
        if (currentTarget != null && currentState != null) {
            if (currentState.is(Blocks.BEDROCK) || currentState.getDestroySpeed(ctx.world(), currentTarget) < 0) {
                stopBreakingBlock();
                return;
            }
        }

        // ── FIX 1: NEVER 0-tick instamine while airborne or in water ──
        // GrimAC applies ÷5 penalty to mining speed when airborne or in water without Aqua Affinity.
        // If we 0-tick instamine while !onGround or in water, GrimAC flags FastBreak.
        // Therefore, we disable 0-tick instamining and force standard multi-tick breaking.
        // We MUST NOT return early from tick(), as doing so deadlocks mining whenever the player
        // touches water, floats, or stands on uneven terrain.
        boolean grounded = ctx.player() != null && ctx.player().onGround() && !ctx.player().isInWater() && !ctx.player().isUnderWater();

        boolean isInstaMine = currentTarget != null
                && currentState != null
                && grounded
                && !directlyAboveFeet
                && FastBreakHelper.isInstaBreak(ctx, currentTarget, currentState);

        // ── FIX 2: INTER-BLOCK COOLDOWN ──────────────────────────────────────
        // After a non-instamine block breaks we enforce GRIM_INTER_BLOCK_TICKS
        // regardless of whether the NEW target is instamine.  We must not let
        // an isInstaMine=true target skip this wait (the previous "if (isInstaMine)
        // { breakDelayTimer=0; }" bug allowed exactly that).
        if (lastBreakWasNonInstamine && breakDelayTimer > 0) {
            breakDelayTimer--;
            if (breakDelayTimer > 0) {
                return; // still in mandatory inter-block cooldown
            }
            // Cooldown just expired — clear the flag and continue normally.
            lastBreakWasNonInstamine = false;
        } else if (!lastBreakWasNonInstamine) {
            if (isInstaMine) {
                // True instamine: no delay, zero destroyDelay right away.
                breakDelayTimer = 0;
                reactionDelay = 0;
                if (ctx.player() != null && ctx.minecraft().gameMode != null) {
                    ((IPlayerControllerMP) ctx.minecraft().gameMode).setDestroyDelay(0);
                }
            } else if (breakDelayTimer > 0) {
                breakDelayTimer--;
                return;
            }
        }

        if (isLeftClick && isBlockTrace) {
            // Zablokuj równoległe wywoływanie mc.gameMode.continueDestroyBlock przez Minecraft.handleKeybinds()
            if (ctx.minecraft().options.keyAttack.isDown()) {
                ctx.minecraft().options.keyAttack.setDown(false);
            }

            BlockPos targetPos = ((BlockHitResult) trace).getBlockPos();
            Direction side = ((BlockHitResult) trace).getDirection();

            BlockState state = ctx.world().getBlockState(targetPos);
            if (state.isAir() || state.is(Blocks.BEDROCK) || state.getDestroySpeed(ctx.world(), targetPos) < 0.0f) {
                stopBreakingCleanly();
                return;
            }

            ContinuousBreakController.notifyBreaking(ctx, new BetterBlockPos(targetPos));
            ctx.playerController().syncHeldItem();

            // 1. PIERWSZY TICK na danym bloku: wykonaj clickBlock (startDestroyBlock) DOKŁADNIE RAZ
            // Jeśli stan bloku uległ zmianie (np. spadł gravel lub sand w to samo miejsce), resetujemy sesję i wysyłamy nowy START_DESTROY_BLOCK
            boolean stateChanged = lastTargetBlockState != null && !state.equals(lastTargetBlockState);
            if (!wasHitting || !targetPos.equals(lastTargetPos) || stateChanged) {
                if (wasHitting && lastTargetPos != null && (!targetPos.equals(lastTargetPos) || stateChanged)) {
                    ctx.playerController().resetBlockRemoving();
                }
                ctx.playerController().syncHeldItem();
                ctx.playerController().clickBlock(targetPos, side);
                ctx.player().swing(InteractionHand.MAIN_HAND);

                wasHitting = !ctx.world().getBlockState(targetPos).isAir();
                lastTargetPos = wasHitting ? targetPos.immutable() : null;
                lastTargetBlockState = wasHitting ? state : null;
                return;
            }

            // 2. KOLEJNE TICKI na tym samym bloku: TYLKO onPlayerDamageBlock (continueDestroyBlock)
            if (ctx.playerController().onPlayerDamageBlock(targetPos, side)) {
                ctx.player().swing(InteractionHand.MAIN_HAND);
            }

            // Gdy blok został zniszczony, zresetuj stan pod kolejny blok
            if (ctx.world().getBlockState(targetPos).isAir()) {
                baritone.hud.GatherTracker.INSTANCE.onBlockBroken(state.getBlock(), targetPos);
                AiActionLogger.log("MINE", "Wykopano " + state.getBlock().getName().getString() + " na [" + targetPos.getX() + ", " + targetPos.getY() + ", " + targetPos.getZ() + "]");
                wasHitting = false;
                lastTargetPos = null;
                lastTargetBlockState = null;
                ctx.playerController().setHittingBlock(false);

                // OCHRONA GRIMAC INTER-BLOCK DELAY:
                // Po zniszczeniu bloku nie-instamine (np. deepslate, kamień), GrimAC ściśle wymaga zachowania
                // waniliowego odstępu destroyDelay (5 ticków = 250ms) przed wysłaniem START_DESTROY_BLOCK na kolejny blok.
                lastBreakWasNonInstamine = !isInstaMine;
                breakDelayTimer = lastBreakWasNonInstamine ? GRIM_INTER_BLOCK_TICKS : 0;
            }
        } else {
            stopBreakingCleanly();
        }
    }
}
