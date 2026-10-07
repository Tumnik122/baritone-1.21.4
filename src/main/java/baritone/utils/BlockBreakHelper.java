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
import baritone.hud.AiActionLogger;
import baritone.utils.accessor.IPlayerControllerMP;
import net.minecraft.core.BlockPos;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.HitResult;

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

    BlockBreakHelper(IPlayerContext ctx) {
        this.ctx = ctx;
    }

    public void stopBreakingBlock() {
        if (ctx.player() != null && wasHitting) {
            ctx.playerController().setHittingBlock(false);
            ctx.playerController().resetBlockRemoving();
            wasHitting = false;
        }
        lastTargetPos = null;
        reactionDelay = 0;
        lastBreakWasNonInstamine = false;
        ContinuousBreakController.reset();
    }

    public void tick(boolean isLeftClick) {
        HitResult trace = ctx.objectMouseOver();
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
            ContinuousBreakController.notifyBreaking(ctx, new BetterBlockPos(currentTarget));

            // Humanised reaction delay — skip for true instamine (they're already
            // safe by definition, 1 tick = 50 ms which GrimAC allows).
            if (!isInstaMine && BaritoneAPI.getSettings().humanizedInteractDelay.value) {
                if (!currentTarget.equals(lastTargetPos)) {
                    lastTargetPos = currentTarget;
                    reactionDelay = 1 + random.nextInt(2); // 1-2 ticks
                }
                if (reactionDelay > 0) {
                    reactionDelay--;
                    return;
                }
            } else {
                lastTargetPos = currentTarget;
            }

            ctx.playerController().syncHeldItem();
            ctx.playerController().setHittingBlock(wasHitting);

            if (ctx.playerController().hasBrokenBlock()) {
                // ── Branch A: not currently mining → call startDestroyBlock ──
                BlockPos targetPos = ((BlockHitResult) trace).getBlockPos();
                net.minecraft.world.level.block.state.BlockState brokenState =
                        ctx.world().getBlockState(targetPos);
                ctx.playerController().clickBlock(targetPos, ((BlockHitResult) trace).getDirection());
                ctx.player().swing(InteractionHand.MAIN_HAND);

                if (isInstaMine) {
                    ((IPlayerControllerMP) ctx.minecraft().gameMode).setDestroyDelay(0);
                    breakDelayTimer = 0;
                    if (ctx.playerController().hasBrokenBlock()
                            || ctx.world().getBlockState(targetPos).isAir()) {
                        baritone.hud.GatherTracker.INSTANCE.onBlockBroken(
                                brokenState.getBlock(), targetPos);
                        AiActionLogger.log("MINE", "Wykopano "
                                + brokenState.getBlock().getName().getString()
                                + " na [" + targetPos.getX() + ", "
                                + targetPos.getY() + ", " + targetPos.getZ() + "]");
                    }
                }
            } else {
                // ── Branch B: currently mining → call continueDestroyBlock ──
                BlockPos targetPos = ((BlockHitResult) trace).getBlockPos();
                net.minecraft.world.level.block.state.BlockState brokenState =
                        ctx.world().getBlockState(targetPos);

                if (ctx.playerController().onPlayerDamageBlock(
                        targetPos, ((BlockHitResult) trace).getDirection())) {
                    ctx.player().swing(InteractionHand.MAIN_HAND);
                }

                if (ctx.playerController().hasBrokenBlock()) {
                    // Block just broke this tick.
                    baritone.hud.GatherTracker.INSTANCE.onBlockBroken(
                            brokenState.getBlock(), targetPos);
                    AiActionLogger.log("MINE", "Wykopano "
                            + brokenState.getBlock().getName().getString()
                            + " na [" + targetPos.getX() + ", "
                            + targetPos.getY() + ", " + targetPos.getZ() + "]");

                    if (isInstaMine) {
                        // ── FIX 3: instamine break — 0-tick inter-block gap ──
                        // Vanilla calls startDestroyBlock for the next block within
                        // the same continueDestroyBlock call, so the gap is already
                        // 0 ticks in vanilla.  GrimAC accepts this.
                        breakDelayTimer = 0;
                        lastBreakWasNonInstamine = false;
                        ((IPlayerControllerMP) ctx.minecraft().gameMode).setDestroyDelay(0);
                    } else {
                        // ── FIX 2 (continued): non-instamine broke ───────────
                        // Force GRIM_INTER_BLOCK_TICKS before the next block,
                        // regardless of what the next target is.
                        lastBreakWasNonInstamine = true;

                        int baseDelay = BaritoneAPI.getSettings().blockBreakSpeed.value
                                - BASE_BREAK_DELAY;

                        if (BaritoneAPI.getSettings().humanizedInteractDelay.value) {
                            int jitter = (int) Math.round(random.nextGaussian() * 0.5);
                            baseDelay += Math.max(0, Math.min(1, jitter));
                            if (baseDelay < 0) baseDelay = 0;
                        }

                        // Always at least GRIM_INTER_BLOCK_TICKS so GrimAC's
                        // inter-block delay check passes.
                        breakDelayTimer = Math.max(GRIM_INTER_BLOCK_TICKS, baseDelay);
                        // Restore vanilla destroyDelay so the server-side model
                        // also sees a proper cooldown.
                        ((IPlayerControllerMP) ctx.minecraft().gameMode)
                                .setDestroyDelay(GRIM_INTER_BLOCK_TICKS);
                        lastTargetPos = null;
                    }
                }
            }

            wasHitting = !ctx.playerController().hasBrokenBlock();
            ctx.playerController().setHittingBlock(false);
        } else {
            wasHitting = false;
            if (!isLeftClick) {
                lastTargetPos = null;
                reactionDelay = 0;
            }
        }
    }
}
