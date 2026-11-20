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

    @Shadow
    public abstract boolean destroyBlock(BlockPos pos);

    @Accessor("isDestroying")
    @Override
    public abstract void setIsHittingBlock(boolean isHittingBlock);

    @Accessor("isDestroying")
    @Override
    public abstract boolean isHittingBlock();

    @Accessor("destroyBlockPos")
    @Override
    public abstract BlockPos getCurrentBlock();

    @Accessor("destroyBlockPos")
    @Override
    public abstract void setCurrentBlock(BlockPos pos);

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

    @Invoker("startPrediction")
    @Override
    public abstract void callStartPrediction(net.minecraft.client.multiplayer.ClientLevel level, net.minecraft.client.multiplayer.prediction.PredictiveAction action);

    @org.spongepowered.asm.mixin.Unique
    private long argon$targetBlockPosLong = Long.MIN_VALUE;
    @org.spongepowered.asm.mixin.Unique
    private long argon$lastDestroyedPosLong = Long.MIN_VALUE;
    @org.spongepowered.asm.mixin.Unique
    private final it.unimi.dsi.fastutil.longs.Long2LongOpenHashMap argon$recentDestroyedBlocks = new it.unimi.dsi.fastutil.longs.Long2LongOpenHashMap();

    @org.spongepowered.asm.mixin.Unique
    private boolean argon$isRecentlyDestroyed(long posLong, long now) {
        this.argon$recentDestroyedBlocks.long2LongEntrySet().removeIf(e -> e.getLongValue() < now);
        return this.argon$recentDestroyedBlocks.containsKey(posLong);
    }

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

    @org.spongepowered.asm.mixin.Unique
    private static boolean argon$isBaritoneActive() {
        return baritone.Baritone.isPrimaryActive();
    }

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
        if (mc.level == null || mc.player == null) {
            this.setIsHittingBlock(false);
            cir.setReturnValue(false);
            return;
        }

        // Po #stop lub w trakcie manualnej rozgrywki przez dj: 100% waniliowe zachowanie klienta!
        // Żadnych blokad 3-sekundowych, żadnych modyfikacji destroyDelay czy fałszywych zwrotów false!
        if (!argon$isBaritoneActive()) {
            return;
        }

        net.minecraft.world.level.block.state.BlockState state = mc.level.getBlockState(pos);
        if (state.isAir() || (state.getBlock() instanceof net.minecraft.world.level.block.LiquidBlock) || state.getDestroySpeed(mc.level, pos) < 0.0f) {
            this.setIsHittingBlock(false);
            this.argon$diggingActiveOnServer = false;
            this.argon$targetBlockPosLong = Long.MIN_VALUE;
            cir.setReturnValue(false);
            return;
        }

        long now = System.currentTimeMillis();
        long posLong = pos.asLong();
        if (posLong == this.argon$lastDestroyedPosLong || argon$isRecentlyDestroyed(posLong, now)) {
            this.setIsHittingBlock(false);
            cir.setReturnValue(false);
            return;
        }

        // KRYTYCZNA OCHRONA GRIMAC (FastBreak delay=38..56ms, 91ms, 199ms):
        // Wymuszamy przestrzeganie odstępu 5 ticków (260ms) między kolejnymi blokami nie-instabreak!
        if (baritone.Baritone.isPrimaryActive()) {
            if (this.destroyDelay > 0 || !baritone.utils.GrimBreakValidator.canStartNewBreak(mc.player.tickCount)) {
                if (this.destroyDelay > 0) {
                    this.destroyDelay--;
                }
                this.setIsHittingBlock(false);
                cir.setReturnValue(false);
                return;
            }
        } else {
            if (this.destroyDelay > 0) {
                this.destroyDelay--;
                this.setIsHittingBlock(false);
                cir.setReturnValue(false);
                return;
            }
        }

        // BEZWZGLĘDNA OCHRONA BLOKU POD NOGAMI (Argon 4 & dj):
        // Nigdy nie pozwól na niszczenie bloku podłogi bezpośrednio pod stopami gracza, gdy bot tuneluje!
        if (pos.equals(mc.player.blockPosition().below())) {
            baritone.api.IBaritone primaryBaritone = baritone.api.BaritoneAPI.getProvider().getPrimaryBaritone();
            if (primaryBaritone != null && primaryBaritone.getCustomGoalProcess().isActive()) {
                this.setIsHittingBlock(false);
                this.argon$diggingActiveOnServer = false;
                this.argon$targetBlockPosLong = Long.MIN_VALUE;
                cir.setReturnValue(false);
                return;
            }
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
        // Jeśli kopiemy już ten sam blok na serwerze i w destroyBlockPos, pod żadnym pozorem NIE wysyłaj ponownego pakietu START_DESTROY_BLOCK!
        // Ponowny START_DESTROY_BLOCK resetuje stoper na serwerze GrimAC, powodując FastBreak.
        // ALE: Jeśli blok uległ zmianie (np. gracz spojrzał na niższy blok), startDestroyBlock MUSI wysłać pakiet!
        BlockPos currentHitting = this.getCurrentBlock();
        if (currentHitting != null && currentHitting.asLong() == posLong && this.isHittingBlock() && this.argon$targetBlockPosLong == posLong && this.argon$diggingActiveOnServer) {
            cir.setReturnValue(true);
            return;
        }

        this.argon$targetBlockPosLong = posLong;
        this.argon$targetBlockState = currentState;
        this.argon$miningTicks = 0;
        this.argon$diggingActiveOnServer = true;
        this.setDestroyProgress(0.0f);
        baritone.utils.GrimBreakValidator.onStartBreak(mc.player, mc.level, pos, direction, mc.player.tickCount);

        boolean isGrim = baritone.api.BaritoneAPI.getSettings().antiCheatCompatibility.value
                || baritone.api.BaritoneAPI.getSettings().grimCompat.value
                || baritone.api.BaritoneAPI.getSettings().antiCheatCompat.value;

        // KRYTYCZNA OBSŁUGA INSTAMINE / INSTABREAK (GrimAC 1.21.4):
        // W MC 1.21.4 i GrimAC, gdy blok ulega natychmiastowemu zniszczeniu (hardness == 0.0f LUB progress >= 1.0f
        // z wyłączeniem pni drzew LOGS), wysyłamy WYŁĄCZNIE pakiet START_DESTROY_BLOCK!
        // Serwer niszczy blok od razu w odpowiedzi na START_DESTROY_BLOCK. Wysłanie późniejszego
        // STOP_DESTROY_BLOCK z continueDestroyBlock trafia w blok powietrza i wywołuje flagę AirLiquidBreak FINISHED_DIGGING!
        boolean isTrueInsta = currentState != null && (currentState.getDestroySpeed(mc.level, pos) == 0.0f
                || (currentState.getDestroyProgress(mc.player, mc.level, pos) >= 1.0f && !currentState.is(net.minecraft.tags.BlockTags.LOGS)));

        if (isTrueInsta) {
            BlockPos cur = this.getCurrentBlock();
            if (cur != null && this.isHittingBlock() && !cur.equals(pos)) {
                if (mc.getConnection() != null) {
                    mc.getConnection().send(new net.minecraft.network.protocol.game.ServerboundPlayerActionPacket(
                            net.minecraft.network.protocol.game.ServerboundPlayerActionPacket.Action.ABORT_DESTROY_BLOCK,
                            cur,
                            direction
                    ));
                }
            }
            this.argon$targetBlockPosLong = posLong;
            this.argon$targetBlockState = currentState;
            this.argon$recentDestroyedBlocks.put(posLong, now + 3000L);
            this.argon$lastDestroyedPosLong = posLong;
            this.argon$diggingActiveOnServer = false;
            this.argon$miningTicks = 0;
            this.setIsHittingBlock(false);
            this.destroyDelay = 0;
            baritone.utils.GrimBreakValidator.onFinishBreak(pos, mc.player.tickCount, true);

            this.callStartPrediction(mc.level, seq -> {
                this.destroyBlock(pos);
                return new net.minecraft.network.protocol.game.ServerboundPlayerActionPacket(
                        net.minecraft.network.protocol.game.ServerboundPlayerActionPacket.Action.START_DESTROY_BLOCK,
                        pos,
                        direction,
                        seq
                );
            });
            cir.setReturnValue(true);
            return;
        }

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

        // Po #stop lub w trakcie manualnego kopania przez gracza: 100% waniliowe naliczanie i pakiety!
        if (!argon$isBaritoneActive()) {
            return;
        }

        net.minecraft.world.level.block.state.BlockState state = mc.level.getBlockState(pos);
        if (state.isAir() || (state.getBlock() instanceof net.minecraft.world.level.block.LiquidBlock)) {
            this.setIsHittingBlock(false);
            this.argon$diggingActiveOnServer = false;
            this.argon$targetBlockPosLong = Long.MIN_VALUE;
            baritone.utils.GrimBreakValidator.reset();
            cir.setReturnValue(false);
            return;
        }

        long now = System.currentTimeMillis();
        long posLong = pos.asLong();
        if (posLong == this.argon$lastDestroyedPosLong || argon$isRecentlyDestroyed(posLong, now)) {
            this.setIsHittingBlock(false);
            this.argon$diggingActiveOnServer = false;
            this.argon$targetBlockPosLong = Long.MIN_VALUE;
            baritone.utils.GrimBreakValidator.reset();
            cir.setReturnValue(false);
            return;
        }

        // BEZWZGLĘDNA OCHRONA BLOKU POD NOGAMI (Argon 4 & dj):
        // Nigdy nie pozwól na niszczenie bloku podłogi bezpośrednio pod stopami gracza, gdy bot tuneluje!
        if (pos.equals(mc.player.blockPosition().below())) {
            baritone.api.IBaritone primaryBaritone = baritone.api.BaritoneAPI.getProvider().getPrimaryBaritone();
            if (primaryBaritone != null && primaryBaritone.getCustomGoalProcess().isActive()) {
                this.setIsHittingBlock(false);
                this.argon$diggingActiveOnServer = false;
                this.argon$targetBlockPosLong = Long.MIN_VALUE;
                cir.setReturnValue(false);
                return;
            }
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
            // Po #stop lub gdy żaden proces Baritone nie jest aktywny, manualne kopanie gracza działa w 100% waniliowo!
            if (!baritone.Baritone.isPrimaryActive()) {
                return;
            }

            long currentPosLong = pos.asLong();
            net.minecraft.world.item.ItemStack currentTool = mc.player.getMainHandItem();
            boolean toolChanged = !net.minecraft.world.item.ItemStack.isSameItemSameComponents(this.argon$lastMiningTool, currentTool);

            // Jeśli celownik przesunął się na inny blok (np. niższy blok w korytarzu),
            // a destroyBlockPos wciąż wskazuje stary cel, pozwól waniliowemu continueDestroyBlock
            // wywołać startDestroyBlock() w celu wysłania legalnego pakietu START_DESTROY_BLOCK!
            BlockPos currentBlock = this.getCurrentBlock();
            if (currentBlock == null || currentBlock.asLong() != currentPosLong) {
                this.argon$targetBlockPosLong = Long.MIN_VALUE;
                this.argon$diggingActiveOnServer = false;
                this.argon$miningTicks = 0;
                return;
            }

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

            baritone.utils.GrimBreakValidator.onTickContinue(mc.player, mc.level, pos);

            state = mc.level.getBlockState(pos);
            int reqTicks = baritone.utils.GrimBreakValidator.getRequiredWaitTicks(
                    mc.player, mc.level, pos, state
            );

            // W Singleplayer nie blokujemy postępu kopania - waniliowe naliczanie działa w 100% naturalnie
            if (mc.hasSingleplayerServer()) {
                return;
            }

            // Na serwerach wieloosobowych z GrimAC:
            // Dopóki canFinishBreak nie zwróci true (kryteria tickowe, delta i czasowe),
            // BEZWZGLĘDNIE anulujemy continueDestroyBlock, aby nie wysłać przedwczesnego STOP_DESTROY_BLOCK!
            if (!baritone.utils.GrimBreakValidator.canFinishBreak(mc.player, mc.level, pos)) {
                float visualProgress = Math.min(0.95f, (float) this.argon$miningTicks / (float) Math.max(1, reqTicks));
                this.setDestroyProgress(visualProgress);
                mc.level.destroyBlockProgress(mc.player.getId(), pos, (int) (visualProgress * 10.0f));
                this.destroyDelay = 0;
                cir.setReturnValue(true);
                return;
            }

            // Gwarantujemy dokładnie 1 pakiet STOP_DESTROY_BLOCK i weryfikujemy stan bloku
            long finishNow = System.currentTimeMillis();
            if (mc.level.getBlockState(pos).isAir()
                    || this.argon$lastDestroyedPosLong == pos.asLong()
                    || argon$isRecentlyDestroyed(pos.asLong(), finishNow)
                    || !baritone.utils.GrimBreakValidator.consumeServerDigForFinish(pos)) {
                this.setIsHittingBlock(false);
                this.setDestroyProgress(0.0f);
                baritone.utils.GrimBreakValidator.reset();
                cir.setReturnValue(false);
                return;
            }

            // Wymagany czas i delta GrimAC minęły w 100%!
            baritone.utils.GrimBreakValidator.onFinishBreak(pos, currentTick, false);

            this.argon$recentDestroyedBlocks.put(pos.asLong(), finishNow + 3000L);
            this.argon$lastDestroyedPosLong = pos.asLong();
            this.argon$diggingActiveOnServer = false;
            this.argon$targetBlockPosLong = Long.MIN_VALUE;
            this.argon$targetBlockState = null;
            this.setIsHittingBlock(false);

            this.callStartPrediction(mc.level, seq -> {
                this.destroyBlock(pos);
                return new net.minecraft.network.protocol.game.ServerboundPlayerActionPacket(
                        net.minecraft.network.protocol.game.ServerboundPlayerActionPacket.Action.STOP_DESTROY_BLOCK,
                        pos,
                        direction,
                        seq
                );
            });

            this.setDestroyProgress(0.0f);
            this.destroyDelay = 5; // Waniliowe 5 ticków (250ms) dla bloków nie-instabreak!
            mc.level.destroyBlockProgress(mc.player.getId(), pos, -1);
            this.argon$miningTicks = 0;
            cir.setReturnValue(true);
            return;
        } else if (baritone.utils.FastBreakHelper.canInstaBreak(pos)) {
            this.destroyDelay = 0;
        }
    }

    @org.spongepowered.asm.mixin.injection.Inject(
            method = "destroyBlock",
            at = @org.spongepowered.asm.mixin.injection.At("RETURN"))
    private void onBlockDestroyed(BlockPos pos, org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable<Boolean> cir) {
        long pLong = pos.asLong();
        this.argon$recentDestroyedBlocks.put(pLong, System.currentTimeMillis() + 3000L);
        this.argon$lastDestroyedPosLong = pLong;
        this.argon$diggingActiveOnServer = false;
        this.argon$miningTicks = 0;
        this.argon$targetBlockPosLong = Long.MIN_VALUE;
        this.argon$targetBlockState = null;
        this.setIsHittingBlock(false);
    }

    @org.spongepowered.asm.mixin.injection.Inject(
            method = "stopDestroyBlock",
            at = @org.spongepowered.asm.mixin.injection.At("HEAD"),
            cancellable = true)
    private void onStopDestroyBlockHead(org.spongepowered.asm.mixin.injection.callback.CallbackInfo ci) {
        // Jeśli to Baritone jawnie wywołał resetBlockRemoving() (np. zmiana celu lub koniec zadania):
        if (baritone.utils.player.BaritonePlayerController.isResettingBlock) {
            this.argon$diggingActiveOnServer = false;
            this.argon$miningTicks = 0;
            this.argon$targetBlockPosLong = Long.MIN_VALUE;
            this.argon$targetBlockState = null;
            BlockPos current = this.getCurrentBlock();
            if (current != null) {
                this.setIsHittingBlock(false);
            }
            return;
        }

        // KRYTYCZNA OCHRONA (Argon 4 & dj):
        // Jeśli Baritone aktywnie kopie (CLICK_LEFT jest wymuszony przez maszynę bota),
        // zablokuj waniliowe stopDestroyBlock() wywoływane w każdym ticku przez continueAttack(false)
        // gdy gracz nie trzyma fizycznie lewego przycisku myszy!
        if (baritone.utils.player.BaritonePlayerController.isBaritoneDigging()) {
            ci.cancel();
            return;
        }

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
            if (direction != null && mc.level != null
                    && !baritone.utils.player.BaritonePlayerController.isFaceOccluded(mc.level, pos, direction)
                    && !baritone.utils.player.BaritonePlayerController.isFaceImpossible(mc.player, pos, direction)) {
                return direction;
            }
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
            if (direction != null && mc.level != null
                    && !baritone.utils.player.BaritonePlayerController.isFaceOccluded(mc.level, pos, direction)
                    && !baritone.utils.player.BaritonePlayerController.isFaceImpossible(mc.player, pos, direction)) {
                return direction;
            }
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
