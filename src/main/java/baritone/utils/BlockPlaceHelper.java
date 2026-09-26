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

import baritone.Baritone;
import baritone.api.BaritoneAPI;
import baritone.api.utils.IPlayerContext;
import baritone.api.utils.input.Input;
import net.minecraft.core.BlockPos;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.HitResult;

import java.util.Random;

import baritone.utils.builder.BlockStateResolver;
import net.minecraft.world.level.block.state.BlockState;

public class BlockPlaceHelper {
    // base ticks between places caused by tick logic
    private static final int BASE_PLACE_DELAY = 1;

    private final IPlayerContext ctx;
    private final Random random = new Random();
    private int rightClickTimer;

    /**
     * Humanized reaction delay: wait 1-2 ticks before placing on a brand-new block face
     * to mimic human visual recognition latency (~50-100ms).
     */
    private int placeReactionDelay = 0;
    private BlockPos lastPlaceTarget = null;
    private int targetMissTicks = 0;

    BlockPlaceHelper(IPlayerContext playerContext) {
        this.ctx = playerContext;
    }

    public void tick(boolean rightClickRequested) {
        if (rightClickTimer > 0) {
            rightClickTimer--;
            return;
        }
        HitResult mouseOver = ctx.objectMouseOver();
        if (!rightClickRequested || ctx.player().isHandsBusy() || mouseOver == null || mouseOver.getType() != HitResult.Type.BLOCK) {
            targetMissTicks++;
            if (targetMissTicks > 3) {
                lastPlaceTarget = null;
                placeReactionDelay = 0;
            }
            return;
        }
        targetMissTicks = 0;

        // GrimAC guard: Reach check (max 4.0 blocks to avoid Reach / FarPlace flags)
        double maxReach = Baritone.settings().antiCheatCompatibility.value ? 4.0D : Baritone.settings().blockReachDistance.value;
        if (ctx.player().getEyePosition().distanceTo(mouseOver.getLocation()) > maxReach) {
            return;
        }

        BlockPos currentTarget = ((BlockHitResult) mouseOver).getBlockPos();
        BlockState targetState = ctx.world().getBlockState(currentTarget);

        // GrimAC guard: If placing against an interactive block (chest, furnace, crafting table),
        // we must be crouching, otherwise the click will open the container GUI on server!
        if (BlockStateResolver.requiresSneakToPlaceAgainst(targetState) && !ctx.player().isCrouching()) {
            return;
        }

        // GrimAC guard: No sprint while placing
        if (Baritone.settings().antiCheatCompatibility.value && ctx.player().isSprinting()) {
            ctx.player().setSprinting(false);
            var b = BaritoneAPI.getProvider().getBaritoneForPlayer(ctx.player());
            if (b != null) {
                b.getInputOverrideHandler().setInputForceState(Input.SPRINT, false);
            }
        }

        // Humanized reaction delay: 1 tick before placing on a brand new block face
        if (Baritone.settings().humanizedInteractDelay.value) {
            if (!currentTarget.equals(lastPlaceTarget)) {
                lastPlaceTarget = currentTarget;
                placeReactionDelay = 1;
            }
            if (placeReactionDelay > 0) {
                placeReactionDelay--;
                return;
            }
        } else {
            lastPlaceTarget = currentTarget;
        }

        // Compute jittered delay: vanilla is 4 ticks, we add natural Gaussian variance
        int baseSpeed = Baritone.settings().rightClickSpeed.value;
        int jitter = 0;
        if (Baritone.settings().humanizedInteractDelay.value) {
            double g = Math.abs(random.nextGaussian());
            jitter = (int) Math.round(g * 1.2); // 0, 1, 2, occasionally 3
        }
        rightClickTimer = Math.max(0, baseSpeed + jitter - BASE_PLACE_DELAY);

        for (InteractionHand hand : InteractionHand.values()) {
            if (ctx.playerController().processRightClickBlock(ctx.player(), ctx.world(), hand, (BlockHitResult) mouseOver) == InteractionResult.SUCCESS) {
                ctx.player().swing(hand);
                lastPlaceTarget = null; // reset so next placement also gets delay
                return;
            }
            if (!ctx.player().getItemInHand(hand).isEmpty() && ctx.playerController().processRightClick(ctx.player(), ctx.world(), hand) == InteractionResult.SUCCESS) {
                return;
            }
        }
    }
}
