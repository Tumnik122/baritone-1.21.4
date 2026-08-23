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
import baritone.api.utils.IPlayerContext;
import baritone.utils.accessor.IPlayerControllerMP;
import net.minecraft.core.BlockPos;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.HitResult;

import java.util.Random;

/**
 * @author Brady
 * @since 8/25/2018
 */
public final class BlockBreakHelper {
    // base ticks between block breaks caused by tick logic
    private static final int BASE_BREAK_DELAY = 1;

    private final IPlayerContext ctx;
    private final Random random = new Random();
    private boolean wasHitting;
    private int breakDelayTimer = 0;

    /**
     * Humanized reaction delay: the number of ticks to wait before starting to
     * break the first block after crosshair lands on it. Mimics human ~50-100ms
     * visual reaction time to prevent 0-tick FastBreak/Aim flags.
     */
    private int reactionDelay = 0;
    private BlockPos lastTargetPos = null;

    BlockBreakHelper(IPlayerContext ctx) {
        this.ctx = ctx;
    }

    public void stopBreakingBlock() {
        // The player controller will never be null, but the player can be
        if (ctx.player() != null && wasHitting) {
            ctx.playerController().setHittingBlock(false);
            ctx.playerController().resetBlockRemoving();
            wasHitting = false;
        }
        lastTargetPos = null;
        reactionDelay = 0;
    }

    public void tick(boolean isLeftClick) {
        if (breakDelayTimer > 0) {
            breakDelayTimer--;
            return;
        }
        HitResult trace = ctx.objectMouseOver();
        boolean isBlockTrace = trace != null && trace.getType() == HitResult.Type.BLOCK;

        if (isLeftClick && isBlockTrace) {
            BlockPos currentTarget = ((BlockHitResult) trace).getBlockPos();

            // Humanized reaction delay: wait 1-2 ticks the first time we look at a new block
            if (BaritoneAPI.getSettings().humanizedInteractDelay.value) {
                if (!currentTarget.equals(lastTargetPos)) {
                    lastTargetPos = currentTarget;
                    reactionDelay = 1 + random.nextInt(2); // 1 or 2 ticks
                }
                if (reactionDelay > 0) {
                    reactionDelay--;
                    return;
                }
            } else {
                lastTargetPos = currentTarget;
            }

            ctx.playerController().setHittingBlock(wasHitting);
            if (ctx.playerController().hasBrokenBlock()) {
                ctx.playerController().syncHeldItem();
                ctx.playerController().clickBlock(((BlockHitResult) trace).getBlockPos(), ((BlockHitResult) trace).getDirection());
                ctx.player().swing(InteractionHand.MAIN_HAND);
            } else {
                BlockPos targetPos = ((BlockHitResult) trace).getBlockPos();
                net.minecraft.world.level.block.state.BlockState brokenState = ctx.world().getBlockState(targetPos);
                if (ctx.playerController().onPlayerDamageBlock(targetPos, ((BlockHitResult) trace).getDirection())) {
                    ctx.player().swing(InteractionHand.MAIN_HAND);
                }
                if (ctx.playerController().hasBrokenBlock()) { // block broken this tick
                    baritone.hud.GatherTracker.INSTANCE.onBlockBroken(brokenState.getBlock(), targetPos);
                    // break delay timer only applies for multi-tick block breaks like vanilla
                    int baseDelay = BaritoneAPI.getSettings().blockBreakSpeed.value - BASE_BREAK_DELAY;

                    // Add subtle Gaussian jitter and chain-mining cooldown gap
                    if (BaritoneAPI.getSettings().humanizedInteractDelay.value) {
                        int breakJitter = (int) Math.round(random.nextGaussian() * 0.8);
                        baseDelay += Math.max(-1, Math.min(1, breakJitter));
                        // Inter-block delay (chain mining gap) to avoid 0-tick gaps between consecutive blocks
                        int chainGap = random.nextInt(2) + 1; // 1-2 ticks
                        baseDelay += chainGap;
                        if (baseDelay < 0) {
                            baseDelay = 0;
                        }
                    }
                    breakDelayTimer = Math.max(0, baseDelay);
                    // must reset controller's destroy delay to prevent the client from delaying itself unnecessarily
                    ((IPlayerControllerMP) ctx.minecraft().gameMode).setDestroyDelay(0);
                    // Reset lastTargetPos so next block also gets a reaction delay
                    lastTargetPos = null;
                }
            }
            // if true, we're breaking a block. if false, we broke the block this tick
            wasHitting = !ctx.playerController().hasBrokenBlock();
            // this value will be reset by the MC client handling mouse keys
            // since we're not spoofing the click keybind to the client, the client will stop the break if isDestroyingBlock is true
            // we store and restore this value on the next tick to determine if we're breaking a block
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
