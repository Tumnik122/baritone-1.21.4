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

import baritone.Baritone;
import baritone.api.BaritoneAPI;
import baritone.api.IBaritone;
import baritone.api.event.events.PlayerUpdateEvent;
import baritone.api.event.events.TickEvent;
import baritone.api.event.events.WorldEvent;
import baritone.api.event.events.type.EventState;
import net.minecraft.client.Minecraft;
import net.minecraft.client.Options;
import net.minecraft.client.gui.screens.ReceivingLevelScreen;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.player.LocalPlayer;
import org.objectweb.asm.Opcodes;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.gen.Invoker;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.Redirect;
import org.spongepowered.asm.mixin.injection.Slice;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

import java.util.function.BiFunction;

@Mixin(Minecraft.class)
public abstract class MixinMinecraft {

    @Shadow public LocalPlayer player;
    @Shadow public ClientLevel level;
    @Shadow public Options options;
    @Shadow public Screen screen;

    @Unique
    private BiFunction<EventState, TickEvent.Type, TickEvent> tickProvider;

    @Invoker("handleKeybinds")
    public abstract void baritone$invokeHandleKeybinds();

    // ═════════════════════════════════════════════════════════════════
    // FIX TIMER / TIMERLIMIT (część 1)
    //
    // USUNIĘTE oba @Redirect na isWindowActive() (tick + run).
    // Dlaczego: przy nieaktywnym oknie wymuszały "aktywne" → vanilla
    // nie throttle'owała pętli → przy FPS bez limitu akumulator ticków
    // dryfował, a clamp 200 ms zamieniał dryf w bursty ticków →
    // >20 pakietów ruchu/s → Timer; burst ponad limit → TimerLimit.
    // Przy aktywnym oknie redirecty były pass-through, więc szkoda
    // narastała w tle i "wypływała" już po powrocie do okna.
    //
    // Zamiast kłamać pętli pacingu, używamy NATYWNEGO przełącznika
    // vanilla (odpowiednik F3+P): options.pauseOnLostFocus = false
    // na czas pracy bota. Vanilla sama utrzymuje wtedy dokładnie
    // 20 TPS bez fokusu, ze swoim własnym, sprawdzonym pacingiem —
    // Grim widzi strumień pakietów 1:1 z czystym klientem.
    // ═════════════════════════════════════════════════════════════════
    @Unique
    private Boolean baritone$savedPauseOnLostFocus = null;

    // ═════════════════════════════════════════════════════════════════
    // FIX TIMER / TIMERLIMIT (część 2) — GOVERNOR TICKÓW (gwarancja)
    //
    // Pakiety ruchu powstają wyłącznie 1:1 w Minecraft.tick()
    // → LocalPlayer.tick() → sendPosition(). Twardy limit ticków
    // względem zegara ściankowego = twardy limit 20 pakietów/s,
    // NIEZALEŻNIE od tego, co robi pętla frame'ów (FpsOptimizer,
    // vsync, redirecty innych modów itd.).
    //
    //  • allowed = elapsed/50ms + 2 — slack 2 przepuszcza legalny
    //    vanilla catch-up (do 4 ticków/frame po hitchu, maks. clamp
    //    vanilla = 200 ms), więc zachowanie vanilla nie jest dotykane;
    //  • offset +2 jest stały → długoterminowo dokładnie 20.0 TPS;
    //  • rebase epoki przy zaległości >2 s (np. ładowanie świata),
    //    aby governor nigdy nie „wygasał";
    //  • cancel ticka = dla gry to po prostu wolniejsza klatka.
    // ═════════════════════════════════════════════════════════════════
    @Unique
    private long baritone$govEpoch = -1L;

    @Unique
    private long baritone$govTicks = 0L;

    @Inject(method = "tick", at = @At("HEAD"), cancellable = true)
    private void baritone$onTickHead(CallbackInfo ci) {
        // (1) Tryb tła przez NATYWNE ustawienie (zawsze wykonane):
        if ((Baritone.settings().noPauseWhenPathing.value || Baritone.settings().runBackgroundWhenUnfocused.value)
                && Baritone.isPrimaryActive()) {
            if (this.baritone$savedPauseOnLostFocus == null) {
                this.baritone$savedPauseOnLostFocus = this.options.pauseOnLostFocus;
                this.options.pauseOnLostFocus = false; // = F3+P „Pause on lost focus: OFF"
            }
        } else if (this.baritone$savedPauseOnLostFocus != null) {
            // Restore oryginalnej wartości po zakończeniu pracy bota
            this.options.pauseOnLostFocus = this.baritone$savedPauseOnLostFocus;
            this.baritone$savedPauseOnLostFocus = null;
        }

        // (2) Governor — aktywny tylko gdy bot pracuje; idle = vanilla 1:1
        if (!Baritone.isPrimaryActive()) {
            this.baritone$govEpoch = -1L;
            this.baritone$govTicks = 0L;
            return;
        }
        long now = System.nanoTime();
        if (this.baritone$govEpoch < 0L) {
            this.baritone$govEpoch = now;
            this.baritone$govTicks = 0L;
            return; // pierwszy tick — przepuść i zainicjuj epokę
        }
        long allowed = (now - this.baritone$govEpoch) / 50_000_000L + 2L;
        if (this.baritone$govTicks > allowed) {
            // Powyżej zegarowego limitu 20 TPS — TEN tick nie odbywa
            // się w ogóle → żaden pakiet ruchu nie zostaje wysłany.
            ci.cancel();
            return;
        }
        this.baritone$govTicks++;
        // Rebase po długiej przerwie (world load / pauza) — bez tego
        // zaległy „kredyt" wyłączyłby governor na stałe.
        if (allowed - this.baritone$govTicks > 40L) {
            this.baritone$govEpoch = now - this.baritone$govTicks * 50_000_000L;
        }
    }

    // ESC nie pauzuje świata gdy Baritone aktywny — ZACHOWANE
    // (w multiplayerze isPaused() i tak jest false; działa tylko w SP)
    @Inject(method = "isPaused", at = @At("HEAD"), cancellable = true, require = 0)
    private void baritone$forceNotPaused(CallbackInfoReturnable<Boolean> cir) {
        if ((Baritone.settings().noPauseWhenPathing.value || Baritone.settings().runBackgroundWhenUnfocused.value)
                && Baritone.isPrimaryActive()) {
            cir.setReturnValue(false);
        }
    }

    @Inject(method = "<init>", at = @At("RETURN"))
    private void postInit(CallbackInfo ci) {
        BaritoneAPI.getProvider().getPrimaryBaritone();
    }

    // ══════ poniżej: oryginalne @Inject — BEZ ZMIAN ══════

    @Inject(
            method = "tick",
            at = @At(
                    value = "FIELD",
                    opcode = Opcodes.GETFIELD,
                    target = "net/minecraft/client/Minecraft.screen:Lnet/minecraft/client/gui/screens/Screen;",
                    ordinal = 0,
                    shift = At.Shift.BEFORE
            ),
            slice = @Slice(
                    from = @At(
                            value = "FIELD",
                            opcode = Opcodes.PUTFIELD,
                            target = "net/minecraft/client/Minecraft.missTime:I"
                    )
            )
    )
    private void runTick(CallbackInfo ci) {
        this.tickProvider = TickEvent.createNextProvider();
        for (IBaritone baritone : BaritoneAPI.getProvider().getAllBaritones()) {
            TickEvent.Type type = baritone.getPlayerContext().player() != null && baritone.getPlayerContext().world() != null
                    ? TickEvent.Type.IN : TickEvent.Type.OUT;
            baritone.getGameEventHandler().onTick(this.tickProvider.apply(EventState.PRE, type));
        }
    }

    @Inject(method = "tick", at = @At("RETURN"))
    private void postRunTick(CallbackInfo ci) {
        if (this.tickProvider == null) return;
        for (IBaritone baritone : BaritoneAPI.getProvider().getAllBaritones()) {
            TickEvent.Type type = baritone.getPlayerContext().player() != null && baritone.getPlayerContext().world() != null
                    ? TickEvent.Type.IN : TickEvent.Type.OUT;
            baritone.getGameEventHandler().onPostTick(this.tickProvider.apply(EventState.POST, type));
        }
        this.tickProvider = null;
    }

    @Inject(
            method = "tick",
            at = @At(
                    value = "INVOKE",
                    target = "net/minecraft/client/multiplayer/ClientLevel.tickEntities()V",
                    shift = At.Shift.AFTER
            )
    )
    private void postUpdateEntities(CallbackInfo ci) {
        IBaritone baritone = BaritoneAPI.getProvider().getBaritoneForPlayer(this.player);
        if (baritone != null) {
            baritone.getGameEventHandler().onPlayerUpdate(new PlayerUpdateEvent(EventState.POST));
        }
    }

    @Inject(method = "setLevel", at = @At("HEAD"))
    private void preLoadWorld(ClientLevel world, ReceivingLevelScreen.Reason arg2, CallbackInfo ci) {
        if (this.level == null && world == null) return;
        BaritoneAPI.getProvider().getPrimaryBaritone().getGameEventHandler()
                .onWorldEvent(new WorldEvent(world, EventState.PRE));
    }

    @Inject(method = "setLevel", at = @At("RETURN"))
    private void postLoadWorld(ClientLevel world, ReceivingLevelScreen.Reason arg2, CallbackInfo ci) {
        BaritoneAPI.getProvider().getPrimaryBaritone().getGameEventHandler()
                .onWorldEvent(new WorldEvent(world, EventState.POST));
    }

    // ═════════════════════════════════════════════════════════════════
    // Passthrough klawiszowania przy otwartym GUI — ZACHOWANE.
    // NOTA: to NIE jest źródło Timer — handleKeybinds() wysyła
    // wyłącznie pakiety use/attack/hotbar (inna rodzina niż movement,
    // których liczy Timer). Governor i tak tnie wszystko u źródła.
    // ═════════════════════════════════════════════════════════════════
    @Redirect(
            method = "tick",
            at = @At(
                    value = "FIELD",
                    opcode = Opcodes.GETFIELD,
                    target = "Lnet/minecraft/client/Minecraft;screen:Lnet/minecraft/client/gui/screens/Screen;"
            ),
            slice = @Slice(
                    from = @At(value = "INVOKE", target = "Lnet/minecraft/client/gui/components/DebugScreenOverlay;showDebugScreen()Z"),
                    to = @At(value = "CONSTANT", args = "stringValue=Keybindings")
            )
    )
    private Screen passEvents(Minecraft instance) {
        if (Baritone.settings().clicksWithScreenOpen.value && Baritone.isPrimaryActive() && player != null) {
            return null;
        }
        return instance.screen;
    }
}
