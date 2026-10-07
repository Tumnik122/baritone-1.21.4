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

package baritone.launch.mixins;

import baritone.utils.accessor.IPlayerControllerMP;
import net.minecraft.client.multiplayer.MultiPlayerGameMode;
import net.minecraft.core.BlockPos;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.gen.Accessor;
import org.spongepowered.asm.mixin.gen.Invoker;

@Mixin(MultiPlayerGameMode.class)
public abstract class MixinPlayerController implements IPlayerControllerMP {

    @Shadow
    private int destroyDelay;

    @Accessor("isDestroying")
    @Override
    public abstract void setIsHittingBlock(boolean isHittingBlock);

    @Accessor("isDestroying")
    @Override
    public abstract boolean isHittingBlock();

    @Accessor("destroyBlockPos")
    @Override
    public abstract BlockPos getCurrentBlock();

    @Invoker("ensureHasSentCarriedItem")
    @Override
    public abstract void callSyncCurrentPlayItem();

    @Accessor("destroyDelay")
    @Override
    public abstract void setDestroyDelay(int destroyDelay);

    /**
     * Zeroes destroyDelay at the HEAD of startDestroyBlock so that the
     * vanilla check (destroyDelay > 0 → skip mining) does NOT fire for
     * blocks where we have already confirmed instamine locally.
     *
     * NOTE: we do NOT touch destroyDelay in a RETURN inject on destroyBlock,
     * because after destruction the block position contains AIR (hardness 0),
     * which would make canInstaBreak() return true for *every* position and
     * remove the inter-block cooldown GrimAC requires.  Inter-block timing is
     * handled by BlockBreakHelper instead.
     */
    @org.spongepowered.asm.mixin.injection.Inject(
            method = "startDestroyBlock",
            at = @org.spongepowered.asm.mixin.injection.At("HEAD"))
    private void onStartDestroyBlockHead(
            BlockPos pos,
            net.minecraft.core.Direction direction,
            org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable<Boolean> cir) {
        if (baritone.utils.FastBreakHelper.canInstaBreak(pos)) {
            this.destroyDelay = 0;
        }
    }


    /**
     * Same zero-out at the HEAD of continueDestroyBlock.
     * This allows mining progress to be computed on every tick without the
     * 5-tick vanilla countdown stalling instamine blocks.
     */
    @org.spongepowered.asm.mixin.injection.Inject(
            method = "continueDestroyBlock",
            at = @org.spongepowered.asm.mixin.injection.At("HEAD"))
    private void onContinueDestroyBlockHead(
            BlockPos pos,
            net.minecraft.core.Direction direction,
            org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable<Boolean> cir) {
        if (baritone.utils.FastBreakHelper.canInstaBreak(pos)) {
            this.destroyDelay = 0;
        }
    }

    @org.spongepowered.asm.mixin.injection.ModifyVariable(
            method = "startDestroyBlock",
            at = @org.spongepowered.asm.mixin.injection.At("HEAD"),
            argsOnly = true)
    private net.minecraft.core.Direction fixStartDestroyDirection(
            net.minecraft.core.Direction direction,
            BlockPos pos) {
        net.minecraft.client.Minecraft mc = net.minecraft.client.Minecraft.getInstance();
        if (mc.player != null && (baritone.api.BaritoneAPI.getSettings().antiCheatCompatibility.value || baritone.api.BaritoneAPI.getSettings().grimCompat.value || baritone.api.BaritoneAPI.getSettings().antiCheatCompat.value)) {
            return baritone.utils.player.BaritonePlayerController.getSafeBreakFace(mc.player, pos, direction);
        }
        return direction;
    }

    @org.spongepowered.asm.mixin.injection.ModifyVariable(
            method = "continueDestroyBlock",
            at = @org.spongepowered.asm.mixin.injection.At("HEAD"),
            argsOnly = true)
    private net.minecraft.core.Direction fixContinueDestroyDirection(
            net.minecraft.core.Direction direction,
            BlockPos pos) {
        net.minecraft.client.Minecraft mc = net.minecraft.client.Minecraft.getInstance();
        if (mc.player != null && (baritone.api.BaritoneAPI.getSettings().antiCheatCompatibility.value || baritone.api.BaritoneAPI.getSettings().grimCompat.value || baritone.api.BaritoneAPI.getSettings().antiCheatCompat.value)) {
            return baritone.utils.player.BaritonePlayerController.getSafeBreakFace(mc.player, pos, direction);
        }
        return direction;
    }
}
