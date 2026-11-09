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

    @Accessor("destroyProgress")
    @Override
    public abstract float getDestroyProgress();

    @Accessor("destroyProgress")
    @Override
    public abstract void setDestroyProgress(float destroyProgress);

    @org.spongepowered.asm.mixin.Unique
    private long argon$targetBlockPosLong = Long.MIN_VALUE;
    @org.spongepowered.asm.mixin.Unique
    private long argon$lastDestroyedPosLong = Long.MIN_VALUE;
    @org.spongepowered.asm.mixin.Unique
    private int argon$lastContinueTick = -1;
    @org.spongepowered.asm.mixin.Unique
    private int argon$miningTicks = 0;
    @org.spongepowered.asm.mixin.Unique
    private boolean argon$diggingActiveOnServer = false;
    @org.spongepowered.asm.mixin.Unique
    private net.minecraft.world.level.block.state.BlockState argon$targetBlockState = null;
    @org.spongepowered.asm.mixin.Unique
    private net.minecraft.world.item.ItemStack argon$lastMiningTool = net.minecraft.world.item.ItemStack.EMPTY;

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
            at = @org.spongepowered.asm.mixin.injection.At("HEAD"),
            cancellable = true)
    private void onStartDestroyBlockHead(
            BlockPos pos,
            net.minecraft.core.Direction direction,
            org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable<Boolean> cir) {
        net.minecraft.client.Minecraft mc = net.minecraft.client.Minecraft.getInstance();
        if (mc.level != null && mc.player != null) {
            net.minecraft.world.level.block.state.BlockState state = mc.level.getBlockState(pos);
            if (state.isAir() || !state.getFluidState().isEmpty() || state.getDestroySpeed(mc.level, pos) < 0.0f) {
                this.setIsHittingBlock(false);
                this.argon$diggingActiveOnServer = false;
                this.argon$targetBlockPosLong = Long.MIN_VALUE;
                cir.setReturnValue(false);
                return;
            }

            // KRYTYCZNA OCHRONA (Argon 4 & dj):
            // Nigdy nie wysyłaj pakietu kopania bloku wymagającego właściwego narzędzia (np. Deepslate/kamień)
            // złym narzędziem (np. łopatą w klatce przejściowej po gravelu)!
            if (state.requiresCorrectToolForDrops()) {
                net.minecraft.world.item.ItemStack held = mc.player.getMainHandItem();
                if (!held.isCorrectToolForDrops(state)) {
                    this.setDestroyProgress(0.0f);
                    this.argon$miningTicks = 0;
                    this.setIsHittingBlock(false);
                    this.argon$diggingActiveOnServer = false;
                    this.argon$targetBlockPosLong = Long.MIN_VALUE;
                    cir.setReturnValue(false);
                    return;
                }
            }
        }

        long posLong = pos.asLong();
        BlockPos current = this.getCurrentBlock();
        if (current != null && (current.asLong() == this.argon$lastDestroyedPosLong || !this.argon$diggingActiveOnServer)) {
            this.setIsHittingBlock(false);
        }

        net.minecraft.world.level.block.state.BlockState currentState = mc.level != null ? mc.level.getBlockState(pos) : null;
        if (this.argon$targetBlockState != null && currentState != null && !this.argon$targetBlockState.equals(currentState)) {
            // Blok uległ zmianie w tym samym pos (np. spadł gravel/sand) -> wymagany nowy START_DESTROY_BLOCK
            this.argon$diggingActiveOnServer = false;
            this.argon$miningTicks = 0;
            this.argon$targetBlockPosLong = Long.MIN_VALUE;
        }

        // KRYTYCZNE DLA GRIMAC (FastBreak 51ms/107ms):
        // Jeśli kopiemy już ten sam blok na serwerze, pod żadnym pozorem NIE wysyłaj ponownego pakietu START_DESTROY_BLOCK!
        // Ponowny START_DESTROY_BLOCK resetuje stoper na serwerze GrimAC, powodując FastBreak.
        if (this.isHittingBlock() && this.argon$targetBlockPosLong == posLong && this.argon$diggingActiveOnServer) {
            cir.setReturnValue(true);
            return;
        }

        if (this.argon$targetBlockPosLong != posLong || !this.argon$diggingActiveOnServer) {
            this.argon$targetBlockPosLong = posLong;
            this.argon$targetBlockState = currentState;
            this.argon$miningTicks = 0;
            this.argon$diggingActiveOnServer = true;
            this.setDestroyProgress(0.0f);
        }

        boolean isGrim = baritone.api.BaritoneAPI.getSettings().antiCheatCompatibility.value
                || baritone.api.BaritoneAPI.getSettings().grimCompat.value
                || baritone.api.BaritoneAPI.getSettings().antiCheatCompat.value;
        if (!isGrim && baritone.utils.FastBreakHelper.canInstaBreak(pos)) {
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
            at = @org.spongepowered.asm.mixin.injection.At("HEAD"),
            cancellable = true)
    private void onContinueDestroyBlockHead(
            BlockPos pos,
            net.minecraft.core.Direction direction,
            org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable<Boolean> cir) {
        net.minecraft.client.Minecraft mc = net.minecraft.client.Minecraft.getInstance();
        if (mc.player == null || mc.level == null) return;

        net.minecraft.world.level.block.state.BlockState state = mc.level.getBlockState(pos);
        if (state.isAir() || !state.getFluidState().isEmpty()) {
            this.setIsHittingBlock(false);
            this.argon$diggingActiveOnServer = false;
            this.argon$targetBlockPosLong = Long.MIN_VALUE;
            cir.setReturnValue(true);
            return;
        }

        // KRYTYCZNA OCHRONA (Argon 4 & dj):
        // Jeśli blok wymaga narzędzia, a gracz trzyma łopatę/rękę (np. po gravelu), natychmiast odetnij kopanie
        if (state.requiresCorrectToolForDrops()) {
            net.minecraft.world.item.ItemStack held = mc.player.getMainHandItem();
            if (!held.isCorrectToolForDrops(state)) {
                this.setDestroyProgress(0.0f);
                this.argon$miningTicks = 0;
                this.setIsHittingBlock(false);
                this.argon$diggingActiveOnServer = false;
                this.argon$targetBlockPosLong = Long.MIN_VALUE;
                cir.setReturnValue(false);
                return;
            }
        }

        boolean isGrim = baritone.api.BaritoneAPI.getSettings().antiCheatCompatibility.value
                || baritone.api.BaritoneAPI.getSettings().grimCompat.value
                || baritone.api.BaritoneAPI.getSettings().antiCheatCompat.value;

        if (isGrim) {
            long currentPosLong = pos.asLong();
            net.minecraft.world.item.ItemStack currentTool = mc.player.getMainHandItem();
            boolean toolChanged = !net.minecraft.world.item.ItemStack.isSameItemSameComponents(this.argon$lastMiningTool, currentTool);

            if (this.argon$targetBlockPosLong != currentPosLong
                    || (this.argon$targetBlockState != null && !this.argon$targetBlockState.equals(state))
                    || toolChanged) {
                this.argon$targetBlockPosLong = currentPosLong;
                this.argon$targetBlockState = state;
                this.argon$miningTicks = 0;
                this.argon$diggingActiveOnServer = true;
                this.argon$lastMiningTool = currentTool.copy();
                this.setDestroyProgress(0.0f);
            }

            int currentTick = mc.player.tickCount;
            if (currentTick == this.argon$lastContinueTick) {
                cir.setReturnValue(true);
                return;
            }
            this.argon$lastContinueTick = currentTick;
            this.argon$miningTicks++;

            state = mc.level.getBlockState(pos);
            int reqTicks = baritone.utils.GrimBreakValidator.getRequiredTicks(
                    mc.player, mc.level, pos, state, mc.player.onGround()
            );

            // W GrimAC, aby nie było 5-tickowego opóźnienia między kolejnymi krokami kopania tego samego bloku:
            this.destroyDelay = 0;

            // Dopóki nie minęła wymagana liczba ticków, trzymaj destroyProgress < 0.60f
            // ALE: jeśli destroyProgress >= 1.0f (BaritonePlayerController legalnie zezwolił na wykopanie), NIE cofaj postępu!
            if (this.argon$miningTicks < reqTicks && this.getDestroyProgress() < 1.0f) {
                float vanillaDelta = state.getDestroyProgress(mc.player, mc.level, pos);
                if (this.getDestroyProgress() + vanillaDelta >= 0.60f) {
                    this.setDestroyProgress(Math.max(0.0f, 0.50f - vanillaDelta));
                }
            } else {
                // KRYTYCZNA NAPRAWA (Argon 4 & dj):
                // Wymagana liczba ticków GrimAC minęła! Ustawiamy 1.0f, aby waniliowe continueDestroyBlock
                // w tym samym ticku natychmiast zniszczyło blok i legalnie wysłało pakiet STOP_DESTROY_BLOCK!
                // UWAGA: NIE czyścimy tutaj argon$targetBlockPosLong = Long.MIN_VALUE, ponieważ spowodowałoby to
                // natychmiastowy reset destroyProgress do 0.0f w następnej klatce przed wywołaniem destroyBlock!
                // Czyszczenie następuje poprawnie w onBlockDestroyed.
                this.setDestroyProgress(1.0f);
                this.argon$miningTicks = 0;
            }
        } else if (baritone.utils.FastBreakHelper.canInstaBreak(pos)) {
            this.destroyDelay = 0;
        }
    }

    @org.spongepowered.asm.mixin.injection.Inject(
            method = "destroyBlock",
            at = @org.spongepowered.asm.mixin.injection.At("RETURN"))
    private void onBlockDestroyed(BlockPos pos, org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable<Boolean> cir) {
        this.argon$lastDestroyedPosLong = pos.asLong();
        this.argon$diggingActiveOnServer = false;
        this.argon$miningTicks = 0;
        this.argon$targetBlockPosLong = Long.MIN_VALUE;
        this.argon$targetBlockState = null;
    }

    @org.spongepowered.asm.mixin.injection.Inject(
            method = "stopDestroyBlock",
            at = @org.spongepowered.asm.mixin.injection.At("HEAD"))
    private void onStopDestroyBlockHead(org.spongepowered.asm.mixin.injection.callback.CallbackInfo ci) {
        this.argon$diggingActiveOnServer = false;
        this.argon$miningTicks = 0;
        this.argon$targetBlockPosLong = Long.MIN_VALUE;
        this.argon$targetBlockState = null;
        BlockPos current = this.getCurrentBlock();
        if (current != null) {
            this.setIsHittingBlock(false);
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

    @org.spongepowered.asm.mixin.injection.Inject(
            method = "attack",
            at = @org.spongepowered.asm.mixin.injection.At("HEAD"),
            require = 0
    )
    private void astra$onAttackEntity(net.minecraft.world.entity.player.Player player, net.minecraft.world.entity.Entity targetEntity, org.spongepowered.asm.mixin.injection.callback.CallbackInfo ci) {
        if (targetEntity != null) {
            com.astra.revolution.visuals.AstraGodTierVisuals.triggerShockwave(
                    targetEntity.getX(),
                    targetEntity.getY() + targetEntity.getBbHeight() * 0.5,
                    targetEntity.getZ(),
                    0x00F0FF,
                    true
            );
        }
    }
}
