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

import com.astra.revolution.perf.AstraPerformanceConfig;
import com.astra.revolution.gui.AstraSchematicScreen;
import com.astra.revolution.gui.AstraMainMenuScreen;
import com.astra.revolution.schematic.AstraSchematicManager;
import com.astra.revolution.schematic.AstraSchematicManager.BedwarsBridgeEngine;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.screens.TitleScreen;
import org.spongepowered.asm.mixin.injection.ModifyVariable;
import org.lwjgl.glfw.GLFW;

import java.io.FileOutputStream;
import java.io.OutputStream;
import java.io.PrintStream;
import java.io.PrintWriter;
import java.io.StringWriter;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardOpenOption;
import java.time.LocalDateTime;
import java.util.Map;
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
    private static boolean baritone$rshiftWasDown = false;

    @Unique
    private static boolean astra$wasKeyRightCtrlPressed = false;

    @Unique
    private long baritone$govTicks = 0L;

    @Inject(method = "tick", at = @At("HEAD"), cancellable = true)
    private void baritone$onTickHead(CallbackInfo ci) {
        Minecraft self = (Minecraft) (Object) this;
        AstraPerformanceConfig.syncWindowState(self);
        astra$pollSchematicHotkey(self);

        // (1) Tryb tła przez NATYWNE ustawienie (zawsze wykonane):
        boolean isSecondaryBot = AstraPerformanceConfig.getBotNum() > 1;
        if (isSecondaryBot || ((Baritone.settings().noPauseWhenPathing.value || Baritone.settings().runBackgroundWhenUnfocused.value)
                && Baritone.isPrimaryActive())) {
            if (this.baritone$savedPauseOnLostFocus == null) {
                this.baritone$savedPauseOnLostFocus = this.options.pauseOnLostFocus;
            }
            this.options.pauseOnLostFocus = false; // = F3+P „Pause on lost focus: OFF"
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
        if (AstraPerformanceConfig.getBotNum() > 1 || ((Baritone.settings().noPauseWhenPathing.value || Baritone.settings().runBackgroundWhenUnfocused.value)
                && Baritone.isPrimaryActive())) {
            cir.setReturnValue(false);
        }
    }

    @Unique
    private static volatile boolean astra$hooksInstalled = false;

    @Unique
    private static long astra$lastStopWarnMs = 0L;

    @Unique
    private static class AstraTeeOutputStream extends OutputStream {
        private final OutputStream primary;
        private final OutputStream secondary;

        public AstraTeeOutputStream(OutputStream primary, OutputStream secondary) {
            this.primary = primary;
            this.secondary = secondary;
        }

        @Override
        public void write(int b) throws java.io.IOException {
            primary.write(b);
            secondary.write(b);
        }

        @Override
        public void write(byte[] b, int off, int len) throws java.io.IOException {
            primary.write(b, off, len);
            secondary.write(b, off, len);
        }

        @Override
        public void flush() throws java.io.IOException {
            primary.flush();
            secondary.flush();
        }

        @Override
        public void close() throws java.io.IOException {
            try { primary.close(); } finally { secondary.close(); }
        }
    }

    @Inject(method = "<init>", at = @At("RETURN"))
    private void postInit(CallbackInfo ci) {
        BaritoneAPI.getProvider().getPrimaryBaritone();

        if (!astra$hooksInstalled) {
            astra$hooksInstalled = true;
            int botNum = AstraPerformanceConfig.getBotNum();
            Path logDir = Paths.get("logs");

            // 1. Przekierowanie stdout/stderr do astra-stdio-bot-X.log
            if (Boolean.parseBoolean(System.getProperty("astra.redirectStdStreams", "true"))) {
                try {
                    Files.createDirectories(logDir);
                    Path stdLog = logDir.resolve("astra-stdio-bot-" + botNum + ".log");
                    FileOutputStream fos = new FileOutputStream(stdLog.toFile(), true);
                    PrintStream fileStream = new PrintStream(fos, true, "UTF-8");
                    System.setOut(new PrintStream(new AstraTeeOutputStream(System.out, fileStream), true, "UTF-8"));
                    System.setErr(new PrintStream(new AstraTeeOutputStream(System.err, fileStream), true, "UTF-8"));
                    System.out.println("[Astra-Diagnostics] Aktywowano podwojny strumien logowania stdio -> " + stdLog);
                } catch (Throwable t) {
                    System.err.println("[Astra-Diagnostics] Nie udalo sie zainicjalizowac astra-stdio: " + t.getMessage());
                }
            }

            // 2. Globalny handler nieobsluzonych wyjatkow
            Thread.UncaughtExceptionHandler prevHandler = Thread.getDefaultUncaughtExceptionHandler();
            Thread.setDefaultUncaughtExceptionHandler((thread, throwable) -> {
                System.err.println("[Astra-CrashGuard] Przechwycono krytyczny wyjatek w watku [" + thread.getName() + "]: " + throwable.getMessage());
                throwable.printStackTrace();
                astra$writeEmergencyLog(logDir, botNum, "UNCAUGHT_EXCEPTION na watku [" + thread.getName() + "]", throwable);
                if (throwable instanceof OutOfMemoryError) {
                    System.gc();
                }
                if (prevHandler != null) {
                    prevHandler.uncaughtException(thread, throwable);
                }
            });

            // 3. ShutdownHook logujacy przyczyne zamkniecia JVM i zrzut watkow
            Runtime.getRuntime().addShutdownHook(new Thread(() -> {
                boolean allowStop = Boolean.getBoolean("astra.allowStop");
                StringBuilder sb = new StringBuilder();
                sb.append("\n=======================================================\n");
                sb.append("=== ASTRA CLIENT JVM SHUTDOWN DIAGNOSTICS ===\n");
                sb.append("Timestamp: ").append(LocalDateTime.now()).append("\n");
                sb.append("BotNum: ").append(botNum)
                  .append(" | isPrimary: ").append(AstraPerformanceConfig.isPrimaryClient())
                  .append(" | allowStop: ").append(allowStop).append("\n");
                sb.append("Active Threads: ").append(Thread.activeCount()).append("\n");

                for (Map.Entry<Thread, StackTraceElement[]> entry : Thread.getAllStackTraces().entrySet()) {
                    Thread t = entry.getKey();
                    if (t.getName().contains("Render") || t.getName().contains("Server") || t.getName().contains("Shutdown") || t.getName().contains("Main") || !t.isDaemon()) {
                        sb.append("\nWatek [").append(t.getName()).append("] stan=").append(t.getState()).append(":\n");
                        for (StackTraceElement el : entry.getValue()) {
                            sb.append("    at ").append(el).append("\n");
                        }
                    }
                }
                sb.append("=======================================================\n");
                org.slf4j.LoggerFactory.getLogger("AstraShutdown").warn(sb.toString());
                astra$writeEmergencyLog(logDir, botNum, sb.toString(), null);
            }, "Astra-Shutdown-Diagnostics"));
        }
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
        AstraSchematicManager.getInstance().onClientTick();
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

        if (this.screen == null && this.player != null) {
            long windowHandle = Minecraft.getInstance().getWindow().getWindow();
            boolean isDown = org.lwjgl.glfw.GLFW.glfwGetKey(
                    windowHandle,
                    com.astra.revolution.gui.BaritoneBypassScreen.OPEN_KEY
            ) == org.lwjgl.glfw.GLFW.GLFW_PRESS;
            if (isDown && !baritone$rshiftWasDown) {
                Minecraft.getInstance().setScreen(new com.astra.revolution.gui.BaritoneBypassScreen());
            }
            baritone$rshiftWasDown = isDown;
        } else {
            baritone$rshiftWasDown = false;
        }

        try {
            AstraSchematicManager.getInstance().onClientTick();
        } catch (Throwable ignored) {
        }

        astra$pollSchematicHotkey((Minecraft) (Object) this);
    }

    @Unique
    private void astra$pollSchematicHotkey(Minecraft self) {
        if (self.getWindow() == null || self.getWindow().getWindow() == 0L) {
            return;
        }

        long handle = self.getWindow().getWindow();
        boolean isRightCtrlDown = GLFW.glfwGetKey(handle, GLFW.GLFW_KEY_RIGHT_CONTROL) == GLFW.GLFW_PRESS;
        boolean isMDown = GLFW.glfwGetKey(handle, GLFW.GLFW_KEY_M) == GLFW.GLFW_PRESS;
        boolean isHotkeyTriggered = isRightCtrlDown || isMDown;

        if (isHotkeyTriggered && !astra$wasKeyRightCtrlPressed) {
            astra$wasKeyRightCtrlPressed = true;

            // 1. Otwieranie GUI wyłącznie podczas normalnej gry (gdy żaden inny ekran nie jest otwarty)
            if (this.player != null && this.screen == null) {
                self.setScreen(new AstraSchematicScreen());
            }
            // 2. Zamykanie GUI: Prawy Ctrl zamyka zawsze; klawisz M zamyka tylko wtedy, gdy nie piszemy w wyszukiwarce/polu nazwy
            else if (this.screen instanceof AstraSchematicScreen schematicScreen) {
                boolean isTypingInField = schematicScreen.isTypingInTextField()
                        || (this.screen.getFocused() instanceof EditBox editBox && editBox.isFocused());

                if (isRightCtrlDown || !isTypingInField) {
                    self.setScreen(null);
                }
            }
        } else if (!isHotkeyTriggered) {
            astra$wasKeyRightCtrlPressed = false;
        }

        // Bezpiecznik: zwolnij sneak mostkowania, jeśli gracz otworzył ekwipunek lub czat
        if (this.screen != null && this.player != null && BedwarsBridgeEngine.isCurrentlyBridging()) {
            BedwarsBridgeEngine.releaseBridgeSneak(this.player);
        }
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
        if (world != null && this.options != null && this.options.hideGui) {
            this.options.hideGui = false;
        }
        BaritoneAPI.getProvider().getPrimaryBaritone().getGameEventHandler()
                .onWorldEvent(new WorldEvent(world, EventState.POST));
    }

    // ═════════════════════════════════════════════════════════════════
    // Passthrough klawiszowania przy otwartym GUI (bezpieczne dla ESC)
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
        Screen current = instance.screen;
        if (current == null) {
            return null;
        }

        // Nigdy nie pomijaj PauseScreen (menu ESC), czatu, ekranu rozłączenia ani ekranów pauzy
        if (current instanceof net.minecraft.client.gui.screens.PauseScreen
                || current instanceof net.minecraft.client.gui.screens.ChatScreen
                || current instanceof net.minecraft.client.gui.screens.DisconnectedScreen
                || current.isPauseScreen()) {
            return current;
        }

        // Przepuszczaj zdarzenia klawiszy do gry TYLKO gdy Baritone faktycznie pathuje lub ma aktywny proces
        if (Baritone.settings().clicksWithScreenOpen.value && Baritone.isPrimaryActive() && instance.player != null) {
            IBaritone primary = BaritoneAPI.getProvider().getPrimaryBaritone();
            if (primary != null && (primary.getPathingBehavior().isPathing()
                    || primary.getPathingControlManager().mostRecentInControl().isPresent())) {
                return null;
            }
        }

        return current;
    }

    /**
     * AstraGuard: Wykrywa i loguje każde wywołanie Minecraft.stop() ze stosem wywołań.
     * Na botach pomocniczych (bot 2+) oraz bocie 1 uniemożliwia przypadkowe zamknięcie procesu przez sygnały OS / GLFW,
     * dopóki nie zezwoli na to użytkownik (np. komenda wyjścia lub parametr astra.allowStop=true).
     */
    @Inject(method = "stop", at = @At("HEAD"), cancellable = true)
    private void astra$guardBotUnexpectedStop(CallbackInfo ci) {
        boolean allowStop = Boolean.getBoolean("astra.allowStop");
        boolean guardPrimary = Boolean.parseBoolean(System.getProperty("astra.guardPrimaryStop", "true"));
        boolean isPrimary = AstraPerformanceConfig.isPrimaryClient();
        int botNum = AstraPerformanceConfig.getBotNum();

        org.slf4j.LoggerFactory.getLogger("AstraGuard").warn(
                "[AstraGuard] Wykryto wywołanie Minecraft.stop()! (isPrimary="
                        + isPrimary
                        + ", botNum=" + botNum
                        + ", allowStop=" + allowStop
                        + ", guardPrimary=" + guardPrimary + ")",
                new Throwable("Stacktrace wywołania Minecraft.stop()")
        );

        if (!allowStop && (!isPrimary || guardPrimary) && !astra$isCrashStop()) {
            long now = System.currentTimeMillis();
            if (now - astra$lastStopWarnMs > 2000L) {
                astra$lastStopWarnMs = now;
                org.slf4j.LoggerFactory.getLogger("AstraGuard").warn(
                        "[AstraGuard] ZABLOKOWANO przypadkowe zamknięcie bota #{}! Resetuję windowShouldClose.",
                        botNum
                );
            }
            Minecraft mc = (Minecraft) (Object) this;
            if (mc.getWindow() != null && mc.getWindow().getWindow() != 0L) {
                GLFW.glfwSetWindowShouldClose(mc.getWindow().getWindow(), false);
            }
            ci.cancel();
        }
    }

    @Unique
    private static boolean astra$isCrashStop() {
        for (StackTraceElement el : Thread.currentThread().getStackTrace()) {
            String cls = el.getClassName();
            String mth = el.getMethodName();
            if (cls.contains("CrashReport") || mth.contains("crash") || mth.contains("emergencySave")) {
                return true;
            }
        }
        return false;
    }

    @Unique
    private static void astra$writeEmergencyLog(Path logDir, int botNum, String header, Throwable t) {
        try {
            Files.createDirectories(logDir);
            Path crashLog = logDir.resolve("astra-shutdown-bot-" + botNum + ".log");
            StringBuilder out = new StringBuilder("\n[" + LocalDateTime.now() + "] " + header + "\n");
            if (t != null) {
                StringWriter sw = new StringWriter();
                t.printStackTrace(new PrintWriter(sw));
                out.append(sw);
            }
            Files.writeString(crashLog, out.toString(), StandardOpenOption.CREATE, StandardOpenOption.APPEND);
        } catch (Throwable ignored) {}
    }

    /**
     * Zastępuje domyślny TitleScreen nowym, animowanym menu głównym AstraMainMenuScreen.
     * Obsługuje zarówno bezpośrednie wywołania TitleScreen, jak i setScreen(null) poza światem.
     * Przytrzymanie lewego Shifta lub użycie przycisku Vanilla pozwala otworzyć vanillowe menu.
     */
    @ModifyVariable(
            method = "setScreen",
            at = @At("HEAD"),
            argsOnly = true
    )
    private Screen astra$replaceTitleScreen(Screen targetScreen) {
        if (AstraMainMenuScreen.bypassToVanillaOnce) {
            AstraMainMenuScreen.bypassToVanillaOnce = false;
            return targetScreen;
        }

        Minecraft mc = (Minecraft) (Object) this;
        if (mc.getWindow() != null && mc.getWindow().getWindow() != 0L) {
            if (GLFW.glfwGetKey(mc.getWindow().getWindow(), GLFW.GLFW_KEY_LEFT_SHIFT) == GLFW.GLFW_PRESS) {
                return targetScreen;
            }
        }

        if (targetScreen instanceof TitleScreen && !(targetScreen instanceof AstraMainMenuScreen)) {
            return new AstraMainMenuScreen();
        }

        if (targetScreen instanceof net.minecraft.client.gui.screens.worldselection.SelectWorldScreen && !(targetScreen instanceof com.astra.revolution.gui.AstraSingleplayerScreen)) {
            return new com.astra.revolution.gui.AstraSingleplayerScreen(this.screen);
        }

        if (targetScreen == null && this.level == null) {
            return new AstraMainMenuScreen();
        }

        return targetScreen;
    }
}
