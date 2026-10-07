package baritone.control;

import baritone.Baritone;
import baritone.api.utils.Helper;
import baritone.api.utils.IPlayerContext;
import baritone.api.utils.Rotation;
import baritone.api.utils.input.Input;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.screens.ConfirmScreen;
import net.minecraft.client.gui.screens.ConnectScreen;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.gui.screens.TitleScreen;
import net.minecraft.client.multiplayer.ServerData;
import net.minecraft.client.multiplayer.resolver.ServerAddress;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.inventory.ClickType;
import net.minecraft.world.inventory.Slot;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;

import java.lang.reflect.Field;
import java.util.Locale;

/**
 * AnarchiaLoginAutomation
 *
 * Automatyczna sekwencja logowania i dołączania do trybu:
 * 1. Jeśli bot jest w menu -> łączy z serwerem (IP z Direct Connection / anarchia.gg).
 * 2. Czeka na wejście do świata gry / lobby.
 * 3. Wysyła na czacie /login Tumnik@123.
 * 4. Anty-Bot: Przez 5 sekund trzyma klawisz 'W' (chodzi do przodu) i płynnie rusza myszką lewo-prawo.
 * 5. Po 5 sekundach puszcza W, najeżdża na kompas w hotbarze i klika PPM (otwiera menu wyboru trybu).
 * 6. Czeka na menu 'Wybierz tryb' i klika w serce (czerwony barwnik).
 */
public class AnarchiaLoginAutomation {

    public enum State {
        IDLE,
        CONNECTING,
        WAITING_JOIN,
        WAITING_RESOURCE_PACK, // 3 sekundy (60 ticków): oczekiwanie na załadowanie paczki zasobów
        SENDING_LOGIN,
        ANTIBOT_WALK_AND_LOOK, // 5 sekund (100 ticków): chodzenie (W) + ruszanie myszką lewo-prawo
        EQUIPPING_COMPASS,
        WAITING_COMPASS_MENU,
        CLICKING_HEART,
        VERIFYING_JOIN,        // Weryfikacja po kliknięciu serca
        RETRYING,              // Oczekiwanie na ponowienie próby
        COMPLETED
    }

    public static final java.util.Set<String> BLACKLISTED_ACCOUNTS = java.util.Set.of("mroz_53947", "kielbasa_1911");


    private final Baritone baritone;
    private final IPlayerContext ctx;
    private State state = State.IDLE;
    private int timerTicks = 0;
    private int menuWaitTicks = 0;
    private String loginPassword = "Tumnik@123";
    private String targetServerIp = "anarchia.gg";
    private float baseYaw = 0f;
    private float basePitch = 0f;
    private int retryCount = 0;
    private int compassRetries = 0;
    private int lobbyCycle = 1;
    private static final int MAX_RETRIES = 50;

    // Flagi gwarantujące wykonanie każdej z akcji TYLKO RAZ w danej sesji połączenia
    private boolean passwordSent = false;
    private boolean antiBotDone = false;
    private boolean compassUsed = false;

    public AnarchiaLoginAutomation(Baritone baritone) {
        this.baritone = baritone;
        this.ctx = baritone != null ? baritone.getPlayerContext() : null;
    }

    public synchronized void resetSessionFlags() {
        this.passwordSent = false;
        this.antiBotDone = false;
        this.compassUsed = false;
    }

    public boolean isPasswordSent() {
        return passwordSent;
    }

    public boolean isAntiBotDone() {
        return antiBotDone;
    }

    public boolean isCompassUsed() {
        return compassUsed;
    }

    public synchronized void start(String password, String serverIp) {
        if (password != null && !password.isBlank()) {
            this.loginPassword = password.trim();
        } else {
            this.loginPassword = "Tumnik@123";
        }
        if (serverIp != null && !serverIp.isBlank()) {
            this.targetServerIp = serverIp.trim();
        }

        this.retryCount = 0;
        this.compassRetries = 0;

        Minecraft mc = Minecraft.getInstance();
        if (mc.player != null) {
            String currentName = mc.player.getGameProfile().getName().toLowerCase(Locale.ROOT);
            if (BLACKLISTED_ACCOUNTS.contains(currentName)) {
                log("§c[AutoLogin] BLOKADA: KONTO " + mc.player.getGameProfile().getName() + " JEST NA CZARNEJ LIŚCIE! Blokuję logowanie botem.");
                stop();
                return;
            }
        }

        if (state == State.COMPLETED) {
            log("§a[AutoLogin] Bot jest już w pełni zalogowany na serwerze trybu (COMPLETED). Pomijam ponowne uruchomienie.");
            return;
        }

        this.lobbyCycle = 1;
        this.retryCount = 0;
        this.compassRetries = 0;

        boolean inGame = mc.player != null && mc.level != null;

        log("§a[AutoLogin] Rozpoczynam procedurę Anarchia (Hasło: " + loginPassword + ")...");

        if (inGame) {
            if (passwordSent && antiBotDone && compassUsed) {
                log("§a[AutoLogin] Hasło, anty-bot i kompas zostały już wykonane w tej sesji (tylko 1 raz). Weryfikuję dołączenie do trybu...");
                this.state = State.VERIFYING_JOIN;
                this.timerTicks = 40;
                return;
            }

            if (!passwordSent) {
                log("§e[AutoLogin] Bot jest na serwerze. Przechodzę do wysłania /login (tylko 1 raz)...");
                this.state = State.SENDING_LOGIN;
                this.timerTicks = 20; // 1s przed /login dla stabilności
            } else if (!antiBotDone) {
                log("§e[AutoLogin] Hasło zostało już wysłane. Rozpoczynam Anty-Bot (tylko 1 raz)...");
                this.baseYaw = mc.player.getYRot();
                this.basePitch = mc.player.getXRot();
                this.state = State.ANTIBOT_WALK_AND_LOOK;
                this.timerTicks = 100;
            } else if (!compassUsed) {
                log("§e[AutoLogin] Anty-Bot już wykonany. Używam kompasu (tylko 1 raz)...");
                this.state = State.EQUIPPING_COMPASS;
                this.timerTicks = 5;
            } else {
                this.state = State.VERIFYING_JOIN;
                this.timerTicks = 40;
            }
        } else {
            resetSessionFlags();
            String ip = (mc.options != null && mc.options.lastMpIp != null && !mc.options.lastMpIp.isBlank())
                    ? mc.options.lastMpIp.trim()
                    : this.targetServerIp;
            this.targetServerIp = ip;
            log("§b[AutoLogin] Łączę z serwerem: " + ip + "...");
            this.state = State.CONNECTING;
            this.timerTicks = 0;

            mc.execute(() -> {
                try {
                    ServerAddress address = ServerAddress.parseString(ip);
                    ServerData serverData = new ServerData("Anarchia", ip, ServerData.Type.OTHER);
                    ConnectScreen.startConnecting(new TitleScreen(), mc, address, serverData, false, null);
                } catch (Throwable t) {
                    triggerRetry("Błąd łączenia z serwerem: " + t.getMessage(), 60);
                }
            });
        }
    }

    public void triggerRetry(String reason, int delayTicks) {
        if (state == State.COMPLETED) return;
        Minecraft mc = Minecraft.getInstance();
        boolean inGame = mc != null && mc.player != null && mc.level != null;
        if (!inGame) {
            resetSessionFlags();
        }

        retryCount++;
        if (retryCount > MAX_RETRIES) {
            log("§c[AutoLogin] Przekroczono limit prób (" + MAX_RETRIES + "). Zatrzymuję procedurę.");
            stop();
            return;
        }

        // Zwolnij ewentualnie trzymany klawisz W
        try {
            if (baritone != null && baritone.getInputOverrideHandler() != null) {
                baritone.getInputOverrideHandler().setInputForceState(Input.MOVE_FORWARD, false);
            }
            if (mc != null && mc.options != null && mc.options.keyUp != null) {
                mc.options.keyUp.setDown(false);
            }
        } catch (Throwable ignored) {}

        log(String.format(Locale.ROOT, "§e[AutoLogin] %s! Ponawiam próbę (#%d/%d) za %.1fs...", reason, retryCount, MAX_RETRIES, delayTicks / 20.0f));
        this.state = State.RETRYING;
        this.timerTicks = delayTicks;
    }

    public synchronized void stop() {
        if (state != State.IDLE && state != State.COMPLETED) {
            log("§c[AutoLogin] Procedura automatycznego logowania zatrzymana.");
        }
        try {
            if (baritone != null && baritone.getInputOverrideHandler() != null) {
                baritone.getInputOverrideHandler().setInputForceState(Input.MOVE_FORWARD, false);
            }
            Minecraft mc = Minecraft.getInstance();
            if (mc != null && mc.options != null && mc.options.keyUp != null) {
                mc.options.keyUp.setDown(false);
            }
        } catch (Throwable ignored) {}
        this.state = State.IDLE;
        this.timerTicks = 0;
        this.menuWaitTicks = 0;
        this.retryCount = 0;
        this.compassRetries = 0;
    }

    public boolean isActive() {
        return state != State.IDLE && state != State.COMPLETED;
    }

    public State getState() {
        return state;
    }

    public int getRetryCount() {
        return retryCount;
    }

    public String getStatusDescription() {
        return switch (state) {
            case IDLE -> "Bezczynny";
            case CONNECTING -> "Łączenie z serwerem " + targetServerIp + (retryCount > 0 ? (" (próba #" + retryCount + ")") : "");
            case WAITING_JOIN -> "Oczekiwanie na dołączenie do świata";
            case WAITING_RESOURCE_PACK -> String.format(Locale.ROOT, "Ładowanie paczki zasobów serwera (%.1fs)", timerTicks / 20.0f);
            case SENDING_LOGIN -> "Wysyłanie /login " + loginPassword + " (tylko 1 raz)";
            case ANTIBOT_WALK_AND_LOOK -> String.format(Locale.ROOT, "Anty-Bot: W + Myszka lewo/prawo (tylko 1 raz, %.1fs)", timerTicks / 20.0f);
            case EQUIPPING_COMPASS -> "Używanie kompasu (tylko 1 raz)";
            case WAITING_COMPASS_MENU -> "Oczekiwanie na menu wyboru trybu";
            case CLICKING_HEART -> "Klikanie serca (czerwony barwnik)";
            case VERIFYING_JOIN -> String.format(Locale.ROOT, "Weryfikacja wejścia na tryb (%.1fs)", timerTicks / 20.0f);
            case RETRYING -> String.format(Locale.ROOT, "Ponawianie próby za %.1fs (próba #%d)", timerTicks / 20.0f, retryCount);
            case COMPLETED -> "Zalogowano i wybrano tryb";
        };
    }

    public void onTick() {
        if (!isActive()) return;

        Minecraft mc = Minecraft.getInstance();
        String screenName = mc.screen != null ? mc.screen.getClass().getSimpleName() : "";
        boolean isDisconnectScreen = mc.screen != null && (screenName.contains("Disconnect") || screenName.contains("Alert") || screenName.contains("Error"));

        // Automatyczne akceptowanie paczki zasobów serwera (ConfirmScreen / PackConfirmScreen) + 3s oczekiwania
        if (mc.screen instanceof ConfirmScreen || (mc.screen != null && mc.screen.getClass().getSimpleName().contains("PackConfirmScreen"))) {
            if (state != State.WAITING_RESOURCE_PACK) {
                log("§a[AutoLogin] Wykryto ekran potwierdzenia paczki zasobów serwera (" + mc.screen.getClass().getSimpleName() + "). Automatycznie akceptuję...");
                try {
                    for (var child : mc.screen.children()) {
                        if (child instanceof Button btn) {
                            btn.onPress();
                            break;
                        }
                    }
                } catch (Throwable t) {
                    log("§c[AutoLogin] Błąd akceptacji paczki: " + t.getMessage());
                }
                log("§e[AutoLogin] Paczka zasobów zaakceptowana. Czekam 3 sekundy na jej załadowanie...");
                state = State.WAITING_RESOURCE_PACK;
                timerTicks = 60; // 3 sekundy (60 ticków) na załadowanie
                return;
            }
        }

        if (state != State.CONNECTING && state != State.RETRYING && state != State.WAITING_RESOURCE_PACK) {
            if (isDisconnectScreen) {
                resetSessionFlags();
                triggerRetry("Wykryto ekran rozłączenia (" + screenName + ")", 60);
                return;
            }
            if (mc.player == null || mc.level == null) {
                resetSessionFlags();
                triggerRetry("Bot został rozłączony z serwerem", 60);
                return;
            }
        }

        switch (state) {
            case CONNECTING -> {
                timerTicks++;
                if (isDisconnectScreen) {
                    resetSessionFlags();
                    triggerRetry("Błąd połączenia z serwerem (" + screenName + ")", 60);
                    return;
                }
                if (mc.player != null && mc.level != null && mc.player.connection != null) {
                    log("§a[AutoLogin] Połączono z serwerem! Czekam 1.25s na zsynchronizowanie świata...");
                    state = State.WAITING_JOIN;
                    timerTicks = 25; // 1.25s
                } else if (timerTicks > 500) { // 25s timeout
                    triggerRetry("Przekroczono limit czasu oczekiwania na połączenie z serwerem", 60);
                }
            }

            case WAITING_JOIN -> {
                if (mc.player == null || mc.level == null) {
                    state = State.CONNECTING;
                    timerTicks = 0;
                    return;
                }
                timerTicks--;
                if (timerTicks <= 0) {
                    if (!passwordSent) {
                        state = State.SENDING_LOGIN;
                        timerTicks = 0;
                    } else if (!antiBotDone) {
                        baseYaw = mc.player.getYRot();
                        basePitch = mc.player.getXRot();
                        state = State.ANTIBOT_WALK_AND_LOOK;
                        timerTicks = 100;
                    } else if (!compassUsed) {
                        state = State.EQUIPPING_COMPASS;
                        timerTicks = 5;
                    } else {
                        state = State.VERIFYING_JOIN;
                        timerTicks = 40;
                    }
                }
            }

            case WAITING_RESOURCE_PACK -> {
                timerTicks--;
                if (timerTicks <= 0) {
                    log("§a[AutoLogin] Paczka zasobów załadowana!");
                    if (!passwordSent) {
                        log("§a[AutoLogin] Przechodzę do wysłania /login (tylko 1 raz)...");
                        state = State.SENDING_LOGIN;
                        timerTicks = 10;
                    } else if (!antiBotDone) {
                        baseYaw = mc.player != null ? mc.player.getYRot() : 0f;
                        basePitch = mc.player != null ? mc.player.getXRot() : 0f;
                        state = State.ANTIBOT_WALK_AND_LOOK;
                        timerTicks = 100;
                    } else if (!compassUsed) {
                        state = State.EQUIPPING_COMPASS;
                        timerTicks = 5;
                    } else {
                        state = State.VERIFYING_JOIN;
                        timerTicks = 40;
                    }
                }
            }

            case SENDING_LOGIN -> {
                LocalPlayer player = mc.player;
                if (player == null || player.connection == null) {
                    state = State.CONNECTING;
                    timerTicks = 0;
                    return;
                }
                if (passwordSent) {
                    log("§a[AutoLogin] [1/4] Hasło zostało już wysłane wcześniej (tylko 1 raz). Pomijam.");
                    if (!antiBotDone) {
                        baseYaw = player.getYRot();
                        basePitch = player.getXRot();
                        state = State.ANTIBOT_WALK_AND_LOOK;
                        timerTicks = 100;
                    } else if (!compassUsed) {
                        state = State.EQUIPPING_COMPASS;
                        timerTicks = 5;
                    } else {
                        state = State.VERIFYING_JOIN;
                        timerTicks = 40;
                    }
                    return;
                }
                if (timerTicks > 0) {
                    timerTicks--;
                    return;
                }
                log("§e[AutoLogin] [1/4] Wysyłam na czacie: /login " + loginPassword + " (tylko 1 raz)");
                try {
                    player.connection.sendCommand("login " + loginPassword);
                } catch (Throwable t) {
                    try {
                        player.connection.sendChat("/login " + loginPassword);
                    } catch (Throwable t2) {
                        log("§c[AutoLogin] Błąd wysyłania komendy: " + t2.getMessage());
                    }
                }
                passwordSent = true;
                log("§a[AutoLogin] [1/4] Wysłano /login (tylko 1 raz)!");

                if (!antiBotDone) {
                    log("§a[AutoLogin] [2/4] Rozpoczynam procedurę Anty-Bot (trzymanie W + myszka lewo/prawo przez 5s, tylko 1 raz)...");
                    baseYaw = player.getYRot();
                    basePitch = player.getXRot();
                    state = State.ANTIBOT_WALK_AND_LOOK;
                    timerTicks = 100; // 5 sekund = 100 ticków
                } else if (!compassUsed) {
                    state = State.EQUIPPING_COMPASS;
                    timerTicks = 5;
                } else {
                    state = State.VERIFYING_JOIN;
                    timerTicks = 40;
                }
            }

            case ANTIBOT_WALK_AND_LOOK -> {
                LocalPlayer player = mc.player;
                if (player == null) {
                    stop();
                    return;
                }
                if (antiBotDone) {
                    log("§a[AutoLogin] [2/4] Anty-Bot został już wykonany w tej sesji (tylko 1 raz). Pomijam.");
                    try {
                        if (baritone != null && baritone.getInputOverrideHandler() != null) {
                            baritone.getInputOverrideHandler().setInputForceState(Input.MOVE_FORWARD, false);
                        }
                        if (mc.options != null && mc.options.keyUp != null) {
                            mc.options.keyUp.setDown(false);
                        }
                    } catch (Throwable ignored) {}
                    if (!compassUsed) {
                        state = State.EQUIPPING_COMPASS;
                        timerTicks = 5;
                    } else {
                        state = State.VERIFYING_JOIN;
                        timerTicks = 40;
                    }
                    return;
                }

                // Trzymaj klawisz 'W' (chodzenie do przodu)
                if (baritone != null && baritone.getInputOverrideHandler() != null) {
                    baritone.getInputOverrideHandler().setInputForceState(Input.MOVE_FORWARD, true);
                }
                if (mc.options != null && mc.options.keyUp != null) {
                    mc.options.keyUp.setDown(true);
                }

                // Płynne ruszanie myszką lewo-prawo (sinusoida +/- 45 stopni)
                int elapsed = 100 - timerTicks;
                double rad = elapsed * 0.16;
                float deltaYaw = (float) (Math.sin(rad) * 45.0f);
                float deltaPitch = (float) (Math.sin(rad * 0.5) * 4.0f);
                float currentYaw = baseYaw + deltaYaw;
                float currentPitch = Math.max(-80f, Math.min(80f, basePitch + deltaPitch));

                player.setYRot(currentYaw);
                player.setXRot(currentPitch);
                player.yRotO = currentYaw;
                player.xRotO = currentPitch;
                try {
                    baritone.getLookBehavior().updateTarget(new Rotation(currentYaw, currentPitch), true);
                } catch (Throwable ignored) {}

                timerTicks--;

                if (timerTicks % 20 == 0 && timerTicks > 0) {
                    log(String.format(Locale.ROOT, "§e[AutoLogin] [2/4] Anty-Bot: Idę w przód i ruszam myszką... (%.1fs)", timerTicks / 20.0f));
                }

                if (timerTicks <= 0) {
                    // Po 5 sekundach puść klawisz 'W'
                    antiBotDone = true;
                    try {
                        if (baritone != null && baritone.getInputOverrideHandler() != null) {
                            baritone.getInputOverrideHandler().setInputForceState(Input.MOVE_FORWARD, false);
                        }
                        if (mc.options != null && mc.options.keyUp != null) {
                            mc.options.keyUp.setDown(false);
                        }
                    } catch (Throwable ignored) {}
                    log("§a[AutoLogin] [2/4] Weryfikacja anty-bot ukończona (tylko 1 raz)! Najeżdżam na kompas w hotbarze...");
                    if (!compassUsed) {
                        state = State.EQUIPPING_COMPASS;
                        timerTicks = 5; // 0.25s pauzy przed kliknięciem
                    } else {
                        state = State.VERIFYING_JOIN;
                        timerTicks = 40;
                    }
                }
            }

            case EQUIPPING_COMPASS -> {
                timerTicks--;
                if (timerTicks <= 0) {
                    LocalPlayer player = mc.player;
                    if (player == null) {
                        stop();
                        return;
                    }

                    if (compassUsed) {
                        log("§a[AutoLogin] [3/4] Kompas został już użyty (tylko 1 raz). Pomijam ponowne klikanie.");
                        if (player.containerMenu != player.inventoryMenu) {
                            state = State.CLICKING_HEART;
                            timerTicks = 8;
                        } else {
                            state = State.VERIFYING_JOIN;
                            timerTicks = 40;
                        }
                        return;
                    }

                    // Jeśli menu jest już otwarte
                    if (player.containerMenu != player.inventoryMenu) {
                        log("§a[AutoLogin] [3/4] Menu trybów jest już otwarte! Klikam serce...");
                        compassUsed = true;
                        state = State.CLICKING_HEART;
                        timerTicks = 8;
                        return;
                    }

                    // Szukamy kompasu w hotbarze (sloty 0-8)
                    int compassHotbarSlot = -1;
                    for (int i = 0; i < 9; i++) {
                        ItemStack st = player.getInventory().getItem(i);
                        if (!st.isEmpty() && (st.is(Items.COMPASS) || st.getItem() == Items.COMPASS)) {
                            compassHotbarSlot = i;
                            break;
                        }
                    }

                    // Jeśli kompas jest w dalszych slotach (9-35), przerzucamy do bieżącego hotbaru
                    if (compassHotbarSlot == -1) {
                        for (int i = 9; i < 36; i++) {
                            ItemStack st = player.getInventory().getItem(i);
                            if (!st.isEmpty() && (st.is(Items.COMPASS) || st.getItem() == Items.COMPASS)) {
                                int selected = player.getInventory().selected;
                                ctx.playerController().windowClick(player.inventoryMenu.containerId, i, selected, ClickType.SWAP, player);
                                compassHotbarSlot = selected;
                                break;
                            }
                        }
                    }

                    if (compassHotbarSlot != -1) {
                        player.getInventory().selected = compassHotbarSlot;
                        ctx.playerController().syncHeldItem();
                        log("§b[AutoLogin] [3/4] Wybrano kompas w hotbarze (slot " + (compassHotbarSlot + 1) + "). Klikam PPM (tylko 1 raz)...");
                        ctx.playerController().processRightClick(player, ctx.world(), InteractionHand.MAIN_HAND);
                        player.swing(InteractionHand.MAIN_HAND);
                        compassUsed = true;

                        state = State.WAITING_COMPASS_MENU;
                        menuWaitTicks = 0;
                        timerTicks = 0;
                    } else {
                        log("§c[AutoLogin] Kompas nie znaleziony w eq! Próbuję kliknąć PPM trzymanym przedmiotem (tylko 1 raz)...");
                        ctx.playerController().processRightClick(player, ctx.world(), InteractionHand.MAIN_HAND);
                        player.swing(InteractionHand.MAIN_HAND);
                        compassUsed = true;
                        state = State.WAITING_COMPASS_MENU;
                        menuWaitTicks = 0;
                        timerTicks = 0;
                    }
                }
            }

            case WAITING_COMPASS_MENU -> {
                LocalPlayer player = mc.player;
                if (player == null) {
                    stop();
                    return;
                }

                menuWaitTicks++;

                // Sprawdzamy czy otworzyło się menu kontenera ('Wybierz tryb')
                if (player.containerMenu != player.inventoryMenu) {
                    log("§a[AutoLogin] [4/4] Menu 'Wybierz tryb' otwarte! Szukam serca (czerwony barwnik)...");
                    state = State.CLICKING_HEART;
                    timerTicks = 8; // Odczekaj 8 ticków (0.4s) na załadowanie przedmiotów w menu
                    return;
                }

                // Czekamy na otwarcie menu bez powtarzania klikania kompasem (kompas klikany tylko 1 raz!)
                if (menuWaitTicks > 120) { // 6s timeout
                    log("§e[AutoLogin] Menu kompasu nie otworzyło się (lub nastąpiło bezpośrednie przeniesienie). Przechodzę do weryfikacji...");
                    state = State.VERIFYING_JOIN;
                    timerTicks = 40;
                }
            }

            case CLICKING_HEART -> {
                timerTicks--;
                if (timerTicks <= 0) {
                    LocalPlayer player = mc.player;
                    if (player == null) {
                        stop();
                        return;
                    }
                    if (player.containerMenu == player.inventoryMenu) {
                        log("§a[AutoLogin] Menu zostało zamknięte. Oczekuję na dołączenie do trybu...");
                        state = State.VERIFYING_JOIN;
                        timerTicks = 60;
                        return;
                    }

                    var menu = player.containerMenu;
                    int containerSlotsCount = Math.max(0, menu.slots.size() - 36);
                    int heartSlot = -1;

                    // Szukamy czerwonego barwnika / serca w menu kontenera
                    int searchLimit = containerSlotsCount > 0 ? containerSlotsCount : menu.slots.size();
                    for (int i = 0; i < searchLimit; i++) {
                        Slot slot = menu.getSlot(i);
                        ItemStack st = slot.getItem();
                        if (st.isEmpty()) continue;

                        boolean isRedDye = st.is(Items.RED_DYE) || st.getItem() == Items.RED_DYE;
                        String name = st.getHoverName().getString().toLowerCase(Locale.ROOT);
                        String desc = st.getItem().getDescriptionId().toLowerCase(Locale.ROOT);

                        if (isRedDye || name.contains("serce") || name.contains("heart") || name.contains("anarchia") || name.contains("smp") || desc.contains("red_dye")) {
                            heartSlot = i;
                            log("§a[AutoLogin] Znaleziono serce / czerwony barwnik w slocie " + i + " (" + st.getHoverName().getString() + ")!");
                            break;
                        }
                    }

                    // Jeśli sloty są jeszcze puste z powodu laga sieciowego, poczekaj jeszcze chwilę
                    if (heartSlot == -1 && timerTicks > -20) {
                        return;
                    }

                    if (heartSlot != -1) {
                        ctx.playerController().windowClick(menu.containerId, heartSlot, 0, ClickType.PICKUP, player);
                        log("§a[AutoLogin] Kliknięto serce (czerwony barwnik)! Oczekuję na dołączenie do trybu...");
                        state = State.VERIFYING_JOIN;
                        timerTicks = 100; // 5s na przetransferowanie do trybu
                    } else {
                        // Fallback: kliknij pierwszy niepusty slot w menu jeśli barwnik miał inne ID
                        for (int i = 0; i < searchLimit; i++) {
                            if (!menu.getSlot(i).getItem().isEmpty()) {
                                heartSlot = i;
                                break;
                            }
                        }
                        if (heartSlot != -1) {
                            ctx.playerController().windowClick(menu.containerId, heartSlot, 0, ClickType.PICKUP, player);
                            log("§e[AutoLogin] Kliknięto pierwszy dostępny tryb w slocie " + heartSlot + ". Oczekuję na wejście...");
                            state = State.VERIFYING_JOIN;
                            timerTicks = 100;
                        } else {
                            log("§c[AutoLogin] Nie odnaleziono serca ani żadnego przedmiotu w menu. Przechodzę do weryfikacji...");
                            state = State.VERIFYING_JOIN;
                            timerTicks = 60;
                        }
                    }
                }
            }

            case VERIFYING_JOIN -> {
                timerTicks--;
                LocalPlayer player = mc.player;
                if (player == null || mc.level == null) {
                    resetSessionFlags();
                    triggerRetry("Rozłączono podczas przenoszenia na serwer trybu", 60);
                    return;
                }
                if (timerTicks <= 0) {
                    boolean stillInLobby = false;
                    if (player.containerMenu != player.inventoryMenu) {
                        stillInLobby = true;
                    } else {
                        for (int i = 0; i < 9; i++) {
                            ItemStack st = player.getInventory().getItem(i);
                            if (!st.isEmpty() && (st.is(Items.COMPASS) || st.getItem() == Items.COMPASS)) {
                                String name = st.getHoverName().getString().toLowerCase(Locale.ROOT);
                                if (name.contains("tryb") || name.contains("wybór") || name.contains("wybierz") || name.contains("menu") || name.contains("serwer") || name.contains("anarchia")) {
                                    stillInLobby = true;
                                    break;
                                }
                            }
                        }
                    }

                    if (stillInLobby) {
                        // Zgodnie z wytycznymi użytkownika: hasło, anty-bot i kompas TYLKO RAZ!
                        // Czekamy cierpliwie na przeniesienie przez proxy/kolejkę, bez ponawiania ruchu i kompasu.
                        lobbyCycle++;
                        if (lobbyCycle <= 15) {
                            if (lobbyCycle % 3 == 0) {
                                log(String.format(Locale.ROOT, "§e[AutoLogin] Oczekiwanie na dołączenie do trybu (kolejka / ładowanie świata)... (%d/15)", lobbyCycle));
                            }
                            timerTicks = 60; // czekaj kolejne 3 sekundy
                            return;
                        } else {
                            if (player.containerMenu != player.inventoryMenu) {
                                log("§e[AutoLogin] Menu trybów wciąż otwarte po kolejce. Ponawiam kliknięcie serca...");
                                state = State.CLICKING_HEART;
                                timerTicks = 10;
                                lobbyCycle = 1;
                                return;
                            }
                            log("§a[AutoLogin] Czas oczekiwania na transfer upłynął. Uznaję dołączenie za zakończone (COMPLETED).");
                        }
                    }

                    log("§a[AutoLogin] Cała procedura zakończona sukcesem, bot jest na serwerze! 🚀");
                    state = State.COMPLETED;
                    retryCount = 0;
                    compassRetries = 0;
                    lobbyCycle = 1;
                    try {
                        if (baritone != null && baritone.getInputOverrideHandler() != null) {
                            baritone.getInputOverrideHandler().setInputForceState(Input.MOVE_FORWARD, false);
                        }
                        if (mc.options != null && mc.options.keyUp != null) {
                            mc.options.keyUp.setDown(false);
                        }
                    } catch (Throwable ignored) {}
                }
            }

            case RETRYING -> {
                timerTicks--;
                if (timerTicks <= 0) {
                    Minecraft mcInstance = Minecraft.getInstance();
                    boolean inGame = mcInstance.player != null && mcInstance.level != null;
                    if (inGame) {
                        if (mcInstance.player.containerMenu != mcInstance.player.inventoryMenu) {
                            log("§e[AutoLogin] Menu wyboru trybu jest otwarte. Ponawiam kliknięcie serca...");
                            state = State.CLICKING_HEART;
                            timerTicks = 8;
                        } else if (!passwordSent) {
                            state = State.SENDING_LOGIN;
                            timerTicks = 10;
                        } else if (!antiBotDone) {
                            baseYaw = mcInstance.player.getYRot();
                            basePitch = mcInstance.player.getXRot();
                            state = State.ANTIBOT_WALK_AND_LOOK;
                            timerTicks = 100;
                        } else if (!compassUsed) {
                            state = State.EQUIPPING_COMPASS;
                            timerTicks = 5;
                        } else {
                            state = State.VERIFYING_JOIN;
                            timerTicks = 40;
                        }
                    } else {
                        resetSessionFlags();
                        log("§b[AutoLogin] Łączę ponownie z serwerem: " + targetServerIp + " (próba #" + retryCount + ")...");
                        state = State.CONNECTING;
                        timerTicks = 0;
                        compassRetries = 0;
                        mcInstance.execute(() -> {
                            try {
                                ServerAddress address = ServerAddress.parseString(targetServerIp);
                                ServerData serverData = new ServerData("Anarchia", targetServerIp, ServerData.Type.OTHER);
                                ConnectScreen.startConnecting(new TitleScreen(), mcInstance, address, serverData, false, null);
                            } catch (Throwable t) {
                                triggerRetry("Błąd łączenia: " + t.getMessage(), 60);
                            }
                        });
                    }
                }
            }

            case COMPLETED -> {
                try {
                    if (baritone != null && baritone.getInputOverrideHandler() != null) {
                        baritone.getInputOverrideHandler().setInputForceState(Input.MOVE_FORWARD, false);
                    }
                    if (mc.options != null && mc.options.keyUp != null) {
                        mc.options.keyUp.setDown(false);
                    }
                } catch (Throwable ignored) {}
            }
        }
    }

    private void log(String msg) {
        Helper.HELPER.logDirect(msg);
        if (baritone != null && baritone.getWindowsBotController() != null) {
            baritone.getWindowsBotController().addLogMessage(msg);
        }
    }
}
