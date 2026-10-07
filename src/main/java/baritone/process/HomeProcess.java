package baritone.process;

import baritone.Baritone;
import baritone.api.event.events.TickEvent;
import baritone.api.event.listener.AbstractGameEventListener;
import baritone.api.process.IBaritoneProcess;
import baritone.api.process.PathingCommand;
import baritone.api.process.PathingCommandType;
import baritone.api.utils.Rotation;
import baritone.api.utils.input.Input;
import baritone.bypass.BypassConfig;
import baritone.bypass.InventoryCleaner;
import baritone.bypass.RotationEngine;
import baritone.utils.BaritoneProcessHelper;
import net.minecraft.client.Minecraft;
import net.minecraft.client.Options;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.inventory.ClickType;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;

import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * Moduł HomeProcess – automatyczna obsługa /home, teleportacji, oddawania żelaza do skrzynki i powrotu.
 *
 * Przepływ pod #home 1:
 *  1. Wysyła /home -> klika w Kamień (Slot 19) w GUI "Twoje domki" (lub /home 1).
 *  2. Teleportuje gracza do Home 1 (baza ze skrzynkami).
 *  3. Otwiera skrzynkę bezpośrednio z celownika lub wyszukuje obok gracza (PPM bez ruszania myszką).
 *  4. Oddaje WSZYSTKIE rodzaje surowców (żelazo, diamenty, złoto itp.) za pomocą Shift-Click.
 *  5. Pozostawia w ekwipunku nienaruszone narzędzia (kilofy), jedzenie, wiadro itp.
 *  6. Zamyka skrzynkę po oddaniu surowców.
 *  7. Automatycznie wysyła /home -> klika w Zielone łóżko (Slot 20) i wraca do Home 2 (kopalnia)!
 *  8. Automatycznie wznawia poprzednie zadanie (np. #mine iron)!
 *
 * Działa w 100% nawet przy zminimalizowanej grze oraz z pełnym bezpieczeństwem na GrimAC.
 */
public class HomeProcess extends BaritoneProcessHelper implements IBaritoneProcess {

    public enum State {
        IDLE,
        STOPPING,
        // Prosty teleport (np. #home 2, #home 3..7 lub #home 1 stay)
        SENDING_COMMAND,
        WAITING_FOR_MENU,
        MENU_OPENED,
        CLICKED,
        // Pełny cykl oddawania pod #home 1:
        SENDING_HOME_1,
        WAITING_HOME_1_MENU,
        CLICK_HOME_1,
        TELEPORTING_TO_HOME_1,
        ROTATING_FOR_DROP,      // Obrót o 180° przed wyrzuceniem nasion (za siebie)
        DROPPING_SEEDS,
        ROTATING_BACK_AFTER_DROP, // Powrót do pierwotnego kąta po wyrzuceniu nasion
        OPENING_CHEST,
        WAITING_CHEST_MENU,
        DEPOSITING_ITEMS,
        CLOSING_CHEST,
        SENDING_HOME_2,
        WAITING_HOME_2_MENU,
        CLICK_HOME_2,
        TELEPORTING_TO_HOME_2,
        RESUMING_MINING,
        COOLDOWN
    }

    private static String savedMiningCommand = null;
    private static boolean autoHomeTriggered = false;

    private State state = State.IDLE;
    private int stateTicks = 0;
    private int targetHome = 1;
    private boolean autoDepositCycle = false;
    private boolean active = false;

    private int depositedStacksCount = 0;
    private int droppedSeedsCount = 0;
    private int lastTestedDepositSlot = -1;
    private int lastTestedDepositCount = -1;
    private int stuckDepositAttempts = 0;
    private int fullInvCheckTicks = 0;
    private int cannotPickupTicks = 0;
    private long lastHomeSortTime = 0L;
    /** Oryginalny kąt yaw gracza zapamiętany przed obrotem do wyrzucania nasion */
    private float dropYawOrigin = Float.NaN;

    public HomeProcess(Baritone baritone) {
        super(baritone);
        baritone.getGameEventHandler().registerEventListener(new AbstractGameEventListener() {
            @Override
            public void onTick(TickEvent event) {
                if (event.getType() == TickEvent.Type.IN) {
                    checkFullInventoryAutoHome();
                }
            }
        });
    }

    public static void setSavedMiningCommand(String command) {
        savedMiningCommand = command;
    }

    public static String getSavedMiningCommand() {
        return savedMiningCommand;
    }

    public static void clearSavedMiningCommand() {
        savedMiningCommand = null;
    }

    public static boolean isAutoHomeTriggered() {
        return autoHomeTriggered;
    }

    /**
     * Sprawdza stan ekwipunku podczas kopania.
     * W razie zapełnienia:
     * 1. Jeśli są śmieci: wyrzuca je i segreguje ekwipunek.
     * 2. Jeśli ekwipunek jest zapełniony urobkiem w ponad 70% (>= 26/36 slotów):
     *    zatrzymuje bota (#stop), wykonuje pełny cykl #home 1 (baza -> oddanie do skrzynki -> #home 2 kopalnia)
     *    i automatycznie wznawia zadanie kopania (#mine ...).
     * 3. Jeśli leży cenniejszy surowiec (np. diamenty 64/64), poświęca 1 slot niższego surowca (np. żelaza)
     *    i umieszcza 1 sztukę cenniejszego surowca, aby tam trafiały leżące diamenty.
     */
    private void checkFullInventoryAutoHome() {
        if (!Baritone.settings().autoHomeOnFull.value) {
            return;
        }
        if (ctx.player() == null || ctx.world() == null) {
            return;
        }
        if (isActive()) {
            return;
        }
        if (baritone.getAutoDropProcess().isCleaning()) {
            return;
        }

        boolean isMiningActive = baritone.getMineProcess().isActive()
                || baritone.getFarmProcess().isActive()
                || baritone.getBypassProcess().isActive()
                || (savedMiningCommand != null && (baritone.getPathingBehavior().isPathing() || baritone.getPathingBehavior().getGoal() != null));

        if (!isMiningActive) {
            return;
        }

        fullInvCheckTicks++;
        if (fullInvCheckTicks % 10 != 0) {
            return;
        }

        int freeSlots = InventoryCleaner.getFreeSlots(ctx.player());
        Set<String> targets = InventoryCleaner.collectResourceTargets(ctx.player(), baritone.getBypassProcess());
        boolean hasTrash = InventoryCleaner.hasTrash(ctx.player(), new BypassConfig(), targets);

        // 1. Jeśli ekwipunek zawiera śmieci i nie ma wolnych slotów – wyrzuć śmieci
        if (freeSlots == 0 && hasTrash) {
            logDirect("§e[AutoDrop] Ekwipunek jest pełny śmieci! Wyrzucam śmieci...");
            baritone.getAutoDropProcess().cleanNow();
            cannotPickupTicks = 0;
            return;
        }

        // 2. Warunek ponad 70% zapełnienia ekwipunku urobkiem (26+ PEŁNYCH stacków 64/64 z 36 slotów)
        int fullMinedSlots = countFullMinedResourceSlots(ctx.player());
        boolean reached70Percent = (fullMinedSlots >= 26);

        // 3. Sprawdzamy czy bot nie może podnieść wykopanego/zbieranego surowca leżącego pod nogami
        boolean unableToPickUp = isUnableToPickUpDroppedResource();
        if (unableToPickUp) {
            // Sprawdzamy czy możemy zwolnić 1 slot z niższego surowca (np. żelazo/redstone) dla cenniejszego (np. diamenty)
            var sacrificeTarget = AutoDropProcess.findSacrificeTarget(ctx.player(), ctx.world());
            if (sacrificeTarget != null && !baritone.getAutoDropProcess().isActive()) {
                baritone.getAutoDropProcess().sacrificeForTarget(sacrificeTarget);
                cannotPickupTicks = 0;
                return;
            }
            cannotPickupTicks += 10;
        } else {
            cannotPickupTicks = 0;
        }

        // 4. Sprawdzamy czy ekwipunek ma jeszcze miejsce na gromadzenie surowców
        boolean hasSpaceInStacks = hasSpaceForMoreResources(ctx.player());

        // Jeśli ekwipunek ma jeszcze miejsce w niepełnych stackach (np. sloty zablokowane po 1 sztuce)
        // i bot nie jest zablokowany przy leżącym dropie: kontynuuje pracę!
        if (hasSpaceInStacks && !reached70Percent && cannotPickupTicks < 40) {
            // Jeśli ekwipunek ma 0 wolnych slotów, ale są stacki TEGO SAMEGO surowca do połączenia, łączymy je (tylko gdy włączone autoSortInventory)
            if (freeSlots == 0 && Baritone.settings().autoSortInventory.value && InventoryCleaner.hasMergeableStacks(ctx.player()) && !baritone.getAutoDropProcess().isActive()) {
                if (System.currentTimeMillis() - lastHomeSortTime > 30000L) {
                    lastHomeSortTime = System.currentTimeMillis();
                    baritone.getAutoDropProcess().sortNow();
                }
            }
            return; // Bot nadal ma miejsce w niepełnych stackach (np. 1/64 po zablokowaniu slotów) – kontynuuje pracę!
        }

        // 5. Warunek oddania surowców do bazy (#home 1):
        // A) Ekwipunek zapełniony urobkiem w ponad 70% (>= 26/36 PEŁNYCH stacków 64/64)
        // LUB
        // B) Wszędzie są pełne stacki (wszędzie 64/64) i zero wolnych slotów (freeSlots == 0 && !hasSpaceInStacks && !hasTrash)
        // LUB
        // C) Bot stoi przy wykopanym/zebranym surowcu i przez 2 sekundy (40 ticków) nie może go podnieść (brak miejsca)!
        boolean fullOf64 = (freeSlots == 0 && !hasSpaceInStacks && !hasTrash);
        boolean cannotPickUpDrop = (cannotPickupTicks >= 40);

        if (reached70Percent || fullOf64 || cannotPickUpDrop) {
            cannotPickupTicks = 0;
            if (savedMiningCommand == null) {
                if (baritone.getBypassProcess().isActive()) {
                    savedMiningCommand = "bypass";
                } else if (baritone.getFarmProcess().isActive()) {
                    savedMiningCommand = "farm";
                } else if (baritone.getMineProcess().isActive()) {
                    savedMiningCommand = "mine ores";
                }
            }

            if (reached70Percent) {
                logDirect(String.format("§e[AutoHome] Ekwipunek zapełniony w ponad 70%% pełnymi stackami surowców (%d/36 pełnych stacków)! Wykonuję #stop i wracam do bazy (#home 1)...", fullMinedSlots));
            } else if (cannotPickUpDrop) {
                logDirect("§e[AutoHome] Bot nie może podnieść surowca (brak miejsca w EQ)! Wykonuję #stop i wracam do bazy (#home 1)...");
            } else {
                logDirect("§e[AutoHome] Ekwipunek jest w 100% pełny (wszędzie pełne stacki 64/64)! Wykonuję #stop i wracam do bazy (#home 1)...");
            }

            // Wymuszone zatrzymanie aktywnego procesu (#stop)
            autoHomeTriggered = true;
            try {
                baritone.getCommandManager().execute("stop");
            } finally {
                autoHomeTriggered = false;
            }

            // Rozpoczęcie cyklu oddania surowców do bazy i powrotu do kopalni / na farmę
            teleportToHome(1, true);
        }
    }

    /**
     * Liczy liczbę PEŁNYCH stacków surowców (stack.getCount() >= stack.getMaxStackSize()).
     * Sloty zablokowane po 1 sztuce (np. 1/64) NIE są liczone jako pełne.
     */
    public static int countFullMinedResourceSlots(LocalPlayer player) {
        if (player == null) return 0;
        int count = 0;
        var inv = player.getInventory();
        for (int i = 0; i < 36; i++) {
            ItemStack stack = inv.getItem(i);
            if (!stack.isEmpty() && isMinedResource(stack) && stack.getCount() >= stack.getMaxStackSize()) {
                count++;
            }
        }
        return count;
    }

    /**
     * Liczy liczbę slotów w ekwipunku zajętych przez wydobyte surowce (0..35).
     */
    public static int countMinedResourceSlots(LocalPlayer player) {
        if (player == null) return 0;
        int count = 0;
        var inv = player.getInventory();
        for (int i = 0; i < 36; i++) {
            ItemStack stack = inv.getItem(i);
            if (!stack.isEmpty() && isMinedResource(stack)) {
                count++;
            }
        }
        return count;
    }

    /**
     * Sprawdza czy gracz ma jeszcze miejsce na gromadzenie wydobywanych surowców.
     * Zwraca true, jeśli:
     * 1. Jest choć jeden wolny slot w ekwipunku (freeSlots > 0).
     * 2. LUB w którymkolwiek slocie z surowcem jest niepełny stack (count < maxStackSize, np. 1/64, 34/64).
     */
    public static boolean hasSpaceForMoreResources(LocalPlayer player) {
        if (player == null) return false;
        var inv = player.getInventory();
        for (int i = 0; i < 36; i++) {
            ItemStack stack = inv.getItem(i);
            if (stack.isEmpty()) {
                return true; // Jest przynajmniej jeden pusty slot
            }
            if (isMinedResource(stack) && stack.getCount() < stack.getMaxStackSize()) {
                return true; // Jest niepełny stack surowca (np. 1/64 lub 34/64) – bot może nadal zbierać!
            }
        }
        return false; // Wszędzie jest 64 (pełne stacki) i brak wolnych slotów!
    }

    public static int countIncompleteResourceStacks(LocalPlayer player) {
        if (player == null) return 0;
        int count = 0;
        var inv = player.getInventory();
        for (int i = 0; i < 36; i++) {
            ItemStack stack = inv.getItem(i);
            if (!stack.isEmpty() && isMinedResource(stack) && stack.getCount() < stack.getMaxStackSize()) {
                count++;
            }
        }
        return count;
    }

    /**
     * Sprawdza czy gracz stoi bezpośrednio przy wykopanym surowcu, ale nie może go podnieść (brak miejsca).
     */
    private boolean isUnableToPickUpDroppedResource() {
        if (ctx.world() == null || ctx.player() == null) return false;
        AABB pickupBox = ctx.player().getBoundingBox().inflate(1.8D);
        List<ItemEntity> nearby = ctx.world().getEntitiesOfClass(ItemEntity.class, pickupBox);
        for (ItemEntity itemEntity : nearby) {
            if (!itemEntity.isAlive()) continue;
            ItemStack item = itemEntity.getItem();
            if (isMinedResource(item)) {
                double dist = ctx.player().distanceTo(itemEntity);
                if (dist <= 2.2D) {
                    if (!canInventoryAcceptItem(ctx.player(), item)) {
                        return true;
                    }
                }
            }
        }
        return false;
    }

    public static boolean canInventoryAcceptItem(LocalPlayer player, ItemStack item) {
        if (player == null || item == null || item.isEmpty()) return true;
        var inv = player.getInventory();
        for (int i = 0; i < 36; i++) {
            ItemStack slot = inv.getItem(i);
            if (slot.isEmpty()) return true;
            if (ItemStack.isSameItemSameComponents(slot, item)) {
                if (slot.getCount() < slot.getMaxStackSize()) {
                    return true;
                }
            }
        }
        return false;
    }

    /**
     * Uruchamia procedurę teleportu do domku:
     * Dla home 1 domyślnie uruchamia pełny cykl: Home 1 -> oddanie żelaza do skrzynki -> Home 2!
     *
     * @param homeNumber numer domku (1..7)
     * @param autoDeposit czy dla Home 1 wykonać oddanie żelaza i powrót do Home 2
     */
    public void teleportToHome(int homeNumber, boolean autoDeposit) {
        this.targetHome = Math.max(1, Math.min(homeNumber, 7));
        this.autoDepositCycle = (this.targetHome == 1 && autoDeposit);
        this.state = State.STOPPING;
        this.stateTicks = 0;
        this.active = true;
        this.depositedStacksCount = 0;
        this.lastTestedDepositSlot = -1;
        this.lastTestedDepositCount = -1;
        this.stuckDepositAttempts = 0;
    }

    public void teleportToHome(int homeNumber) {
        // Domyślnie pod #home 1 wykonujemy pełny cykl ze skrzynką i powrotem do #home 2
        teleportToHome(homeNumber, true);
    }

    /**
     * Uruchamia procedurę testową na życzenie użytkownika (#test):
     * 1. Zatrzymuje aktywne zadanie (#stop)
     * 2. Zapisuje lub ustawia komendę do wznowienia (np. #mine iron)
     * 3. Uruchamia teleport do bazy (Home 1) -> oddanie surowców do skrzynki -> powrót (Home 2) -> wznowienie kopania
     */
    public void startTestCycle(String optionalResumeCommand) {
        if (optionalResumeCommand != null && !optionalResumeCommand.trim().isEmpty()) {
            savedMiningCommand = optionalResumeCommand.trim();
        } else if (savedMiningCommand == null) {
            savedMiningCommand = "mine iron";
        }

        logDirect("§a[Test] Uruchamiam natychmiastowy test cyklu bazy i skrzynki!");
        logDirect("§71. Zatrzymuję bota (#stop)...");
        logDirect("§72. Teleport do bazy /home 1 (slot 19 Kamień)");
        logDirect("§73. Otwarcie skrzynki w celowniku i oddanie wykopanych surowców");
        logDirect("§74. Zamknięcie skrzynki i powrót do kopalni /home 2 (slot 20 Zielone łóżko)");
        logDirect("§75. Wznowienie zadania: #" + savedMiningCommand);

        autoHomeTriggered = true;
        try {
            baritone.getCommandManager().execute("stop");
        } finally {
            autoHomeTriggered = false;
        }

        teleportToHome(1, true);
    }

    @Override
    public boolean isActive() {
        return active && state != State.IDLE;
    }

    @Override
    public PathingCommand onTick(boolean calcFailed, boolean isSafeToCancel) {
        if (ctx.player() == null || ctx.world() == null) {
            onLostControl();
            return new PathingCommand(null, PathingCommandType.REQUEST_PAUSE);
        }

        switch (state) {
            case STOPPING -> {
                enforceDeadStop();
                stateTicks++;
                var motion = ctx.player().getDeltaMovement();
                boolean movingHorizontally = Math.abs(motion.x) > 0.001 || Math.abs(motion.z) > 0.001;
                if (!movingHorizontally || stateTicks >= 15) {
                    if (autoDepositCycle) {
                        state = State.SENDING_HOME_1;
                    } else {
                        state = State.SENDING_COMMAND;
                    }
                    stateTicks = 0;
                }
                return new PathingCommand(null, PathingCommandType.REQUEST_PAUSE);
            }

            // =========================================================================
            // ŚCIEŻKA STANDARDOWA (np. #home 2..7 lub #home 1 stay)
            // =========================================================================
            case SENDING_COMMAND -> {
                enforceDeadStop();
                if (ctx.player().connection != null) {
                    logDirect(String.format("§b[Home] Wysyłam komendę /home (cel: Home %d)...", targetHome));
                    ctx.player().connection.sendCommand("home");
                }
                state = State.WAITING_FOR_MENU;
                stateTicks = 0;
                return new PathingCommand(null, PathingCommandType.REQUEST_PAUSE);
            }
            case WAITING_FOR_MENU -> {
                enforceDeadStop();
                stateTicks++;
                if (ctx.player().containerMenu != ctx.player().inventoryMenu) {
                    state = State.MENU_OPENED;
                    stateTicks = 0;
                    return new PathingCommand(null, PathingCommandType.REQUEST_PAUSE);
                }
                if (stateTicks >= 60) {
                    logDirect("§c[Home] Przekroczono czas oczekiwania na otwarcie menu \"Twoje domki\".");
                    onLostControl();
                }
                return new PathingCommand(null, PathingCommandType.REQUEST_PAUSE);
            }
            case MENU_OPENED -> {
                enforceDeadStop();
                stateTicks++;
                if (stateTicks >= 2) {
                    int slotToClick = resolveSlotForHome(targetHome);
                    int containerId = ctx.player().containerMenu.containerId;
                    ItemStack clickedStack = ItemStack.EMPTY;
                    if (slotToClick < ctx.player().containerMenu.slots.size()) {
                        clickedStack = ctx.player().containerMenu.getSlot(slotToClick).getItem();
                    }
                    String itemDesc = clickedStack.isEmpty()
                            ? "slot " + slotToClick
                            : BuiltInRegistries.ITEM.getKey(clickedStack.getItem()).getPath();

                    ctx.playerController().windowClick(containerId, slotToClick, 0, ClickType.PICKUP, ctx.player());
                    logDirect(String.format("§a[Home] Kliknięto w menu: Home %d (§e%s§a, slot %d). Trwa teleportacja...",
                            targetHome, itemDesc, slotToClick));

                    state = State.CLICKED;
                    stateTicks = 0;
                }
                return new PathingCommand(null, PathingCommandType.REQUEST_PAUSE);
            }
            case CLICKED -> {
                enforceDeadStop();
                stateTicks++;
                int delayTicks = Math.max(40, Baritone.settings().homeTeleportDelayTicks.value);
                if (stateTicks == 1) {
                    logDirect(String.format("§b[Home] Czekam %.1f s na zakończenie teleportu serwerowego...", delayTicks / 20.0F));
                }
                if (stateTicks >= delayTicks) {
                    state = State.COOLDOWN;
                    stateTicks = 0;
                }
                return new PathingCommand(null, PathingCommandType.REQUEST_PAUSE);
            }

            // =========================================================================
            // PEŁNY CYKL #home 1: Home 1 -> Skrzynka -> Oddanie żelaza -> Home 2
            // =========================================================================
            case SENDING_HOME_1 -> {
                enforceDeadStop();
                if (ctx.player().connection != null) {
                    logDirect("§b[Home] [1/5] Wysyłam /home (cel: Home 1 - baza i skrzynka)...");
                    ctx.player().connection.sendCommand("home");
                }
                state = State.WAITING_HOME_1_MENU;
                stateTicks = 0;
                return new PathingCommand(null, PathingCommandType.REQUEST_PAUSE);
            }
            case WAITING_HOME_1_MENU -> {
                enforceDeadStop();
                stateTicks++;
                if (ctx.player().containerMenu != ctx.player().inventoryMenu) {
                    state = State.CLICK_HOME_1;
                    stateTicks = 0;
                    return new PathingCommand(null, PathingCommandType.REQUEST_PAUSE);
                }
                // Fallback: jeśli po 25 tickach GUI się nie otworzyło, wyślij komendę "/home 1" bezpośrednio na czacie
                if (stateTicks == 25 && ctx.player().connection != null) {
                    logDirect("§7[Home] Brak GUI po /home - wysyłam bezpośrednio /home 1...");
                    ctx.player().connection.sendCommand("home 1");
                }
                if (stateTicks >= 60) {
                    logDirect("§c[Home] Przekroczono czas oczekiwania na menu \"Twoje domki\" / teleport.");
                    state = State.TELEPORTING_TO_HOME_1;
                    stateTicks = 0;
                    return new PathingCommand(null, PathingCommandType.REQUEST_PAUSE);
                }
                return new PathingCommand(null, PathingCommandType.REQUEST_PAUSE);
            }
            case CLICK_HOME_1 -> {
                enforceDeadStop();
                stateTicks++;
                if (stateTicks >= 2) {
                    int slotToClick = resolveSlotForHome(1);
                    int containerId = ctx.player().containerMenu.containerId;
                    ctx.playerController().windowClick(containerId, slotToClick, 0, ClickType.PICKUP, ctx.player());
                    logDirect("§a[Home] [2/5] Kliknięto Home 1 (Kamień). Rozpoczynam 7-sekundowy cooldown...");
                    state = State.TELEPORTING_TO_HOME_1;
                    stateTicks = 0;
                }
                return new PathingCommand(null, PathingCommandType.REQUEST_PAUSE);
            }
            case TELEPORTING_TO_HOME_1 -> {
                enforceDeadStop();
                stateTicks++;
                int delayTicks = Math.max(40, Baritone.settings().homeTeleportDelayTicks.value);
                if (stateTicks == 1) {
                    logDirect(String.format("§b[Home] Czekam %.1f s bez ruchu na teleportację serwera (warmup 5s) i załadowanie bazy...", delayTicks / 20.0F));
                }
                if (stateTicks >= delayTicks) {
                    // Zapamiętaj bieżący yaw przed obrotem o 180°
                    dropYawOrigin = ctx.player() != null ? ctx.player().getYRot() : 0.0f;
                    state = State.ROTATING_FOR_DROP;
                    stateTicks = 0;
                    droppedSeedsCount = 0;
                }
                return new PathingCommand(null, PathingCommandType.REQUEST_PAUSE);
            }
            case ROTATING_FOR_DROP -> {
                enforceDeadStop();
                stateTicks++;
                // Płynny obrót o 180° w 6 tickach (GrimAC-safe)
                if (ctx.player() != null) {
                    float targetYaw = dropYawOrigin + 180.0f;
                    float curYaw = ctx.player().getYRot();
                    float diff = net.minecraft.util.Mth.wrapDegrees(targetYaw - curYaw);
                    float step = Math.min(Math.abs(diff), 35.0f) * Math.signum(diff);
                    ctx.player().setYRot(curYaw + step);
                    ctx.player().setXRot(5.0f); // lekko w dół — wyrzucamy na ziemię za sobą
                    baritone.getLookBehavior().updateTarget(
                        new baritone.api.utils.Rotation(curYaw + step, 5.0f), true);
                    if (Math.abs(diff) < 4.0f || stateTicks >= 10) {
                        state = State.DROPPING_SEEDS;
                        stateTicks = 0;
                    }
                } else {
                    state = State.DROPPING_SEEDS;
                    stateTicks = 0;
                }
                return new PathingCommand(null, PathingCommandType.REQUEST_PAUSE);
            }
            case DROPPING_SEEDS -> {
                enforceDeadStop();
                stateTicks++;
                // Odstęp 2 ticków (100ms) między kolejnymi wyrzuceniami (zgodnie z #clean / GrimAC safe)
                if (stateTicks >= 2) {
                    stateTicks = 0;
                    var player = ctx.player();
                    if (player == null || player.containerMenu != player.inventoryMenu) {
                        state = State.OPENING_CHEST;
                        stateTicks = 0;
                        return new PathingCommand(null, PathingCommandType.REQUEST_PAUSE);
                    }

                    var inv = player.getInventory();
                    int targetMenuSlot = -1;

                    // 1. Sprawdzamy główny plecak (sloty 9-35)
                    for (int i = 9; i < 36; i++) {
                        ItemStack stack = inv.getItem(i);
                        if (isSeedItemToDrop(stack)) {
                            targetMenuSlot = i;
                            break;
                        }
                    }

                    // 2. Sprawdzamy hotbar (sloty 0-8), omijając trzymany slot
                    if (targetMenuSlot == -1) {
                        for (int i = 0; i < 9; i++) {
                            if (i == inv.selected) continue;
                            ItemStack stack = inv.getItem(i);
                            if (isSeedItemToDrop(stack)) {
                                targetMenuSlot = 36 + i;
                                break;
                            }
                        }
                    }

                    // 3. Jeśli został tylko w wybranym slocie hotbara, też wyrzuć
                    if (targetMenuSlot == -1) {
                        ItemStack stack = inv.getItem(inv.selected);
                        if (isSeedItemToDrop(stack)) {
                            targetMenuSlot = 36 + inv.selected;
                        }
                    }

                    if (targetMenuSlot != -1 && targetMenuSlot < player.inventoryMenu.slots.size()) {
                        // Wyrzucenie całego stacka nasion (ClickType.THROW z button=1)
                        ctx.playerController().windowClick(player.inventoryMenu.containerId, targetMenuSlot, 1, ClickType.THROW, player);
                        droppedSeedsCount++;
                    } else {
                        if (droppedSeedsCount > 0) {
                            logDirect(String.format("§a[Home] Wyrzucono %d stacków nasion za siebie (czyste EQ przed skrzynką)!", droppedSeedsCount));
                        }
                        // Wróć do oryginalnego kierunku zanim otworzymy skrzynkę
                        state = State.ROTATING_BACK_AFTER_DROP;
                        stateTicks = 0;
                    }
                }
                return new PathingCommand(null, PathingCommandType.REQUEST_PAUSE);
            }
            case ROTATING_BACK_AFTER_DROP -> {
                enforceDeadStop();
                stateTicks++;
                // Płynny powrót do oryginalnego kąta yaw (GrimAC-safe)
                if (ctx.player() != null && !Float.isNaN(dropYawOrigin)) {
                    float curYaw = ctx.player().getYRot();
                    float diff = net.minecraft.util.Mth.wrapDegrees(dropYawOrigin - curYaw);
                    float step = Math.min(Math.abs(diff), 35.0f) * Math.signum(diff);
                    ctx.player().setYRot(curYaw + step);
                    ctx.player().setXRot(20.0f); // normalny kąt do skrzynki
                    baritone.getLookBehavior().updateTarget(
                        new baritone.api.utils.Rotation(curYaw + step, 20.0f), true);
                    if (Math.abs(diff) < 4.0f || stateTicks >= 10) {
                        dropYawOrigin = Float.NaN;
                        state = State.OPENING_CHEST;
                        stateTicks = 0;
                    }
                } else {
                    dropYawOrigin = Float.NaN;
                    state = State.OPENING_CHEST;
                    stateTicks = 0;
                }
                return new PathingCommand(null, PathingCommandType.REQUEST_PAUSE);
            }
            case OPENING_CHEST -> {
                enforceDeadStop();

                // 1. Sprawdź, czy celownik gracza jest już bezpośrednio skierowany na skrzynkę
                HitResult pickHit = ctx.player().pick(4.5D, 0.0F, false);
                BlockPos chestPos = null;
                BlockHitResult bhrTarget = null;
                if (pickHit instanceof BlockHitResult bhr && pickHit.getType() == HitResult.Type.BLOCK) {
                    BlockState bs = ctx.world().getBlockState(bhr.getBlockPos());
                    if (isChestBlock(bs)) {
                        chestPos = bhr.getBlockPos();
                        bhrTarget = bhr;
                    }
                }

                // 2. Jeśli nie, wyszukaj skrzynkę obok bota
                if (chestPos == null) {
                    chestPos = findNearbyChest();
                }

                if (chestPos == null) {
                    logDirect("§c[Home] Nie znaleziono skrzynki obok bota (w promieniu 4.5 bloka). Przechodzę od razu do Home 2...");
                    state = State.SENDING_HOME_2;
                    stateTicks = 0;
                    return new PathingCommand(null, PathingCommandType.REQUEST_PAUSE);
                }

                if (bhrTarget != null) {
                    // Celownik jest już na skrzynce – otwieramy natychmiast bez obracania kamery
                    ctx.playerController().processRightClickBlock(ctx.player(), ctx.world(), InteractionHand.MAIN_HAND, bhrTarget);
                    ctx.player().swing(InteractionHand.MAIN_HAND);
                } else {
                    Vec3 eyePos = ctx.player().getEyePosition();
                    Vec3 hitVec = Vec3.atCenterOf(chestPos);
                    Direction hitDirection = Direction.UP;

                    BlockPos feet = ctx.playerFeet();
                    if (feet != null && (chestPos.equals(feet) || chestPos.equals(feet.below()))) {
                        hitVec = new Vec3(chestPos.getX() + 0.5D, chestPos.getY() + 0.875D, chestPos.getZ() + 0.5D);
                    }

                    // Płynny obrót celownika w stronę skrzynki (GrimAC-safe)
                    Rotation rot = RotationEngine.lookAt(eyePos, hitVec);
                    RotationEngine.apply(ctx.player(), rot, baritone.settings());

                    BlockHitResult hit = new BlockHitResult(hitVec, hitDirection, chestPos, false);
                    ctx.playerController().processRightClickBlock(ctx.player(), ctx.world(), InteractionHand.MAIN_HAND, hit);
                    ctx.player().swing(InteractionHand.MAIN_HAND);
                }

                logDirect(String.format("§b[Home] [3/5] Otwieram skrzynkę (PPM) na pozycji %s...", chestPos));
                state = State.WAITING_CHEST_MENU;
                stateTicks = 0;
                return new PathingCommand(null, PathingCommandType.REQUEST_PAUSE);
            }
            case WAITING_CHEST_MENU -> {
                enforceDeadStop();
                stateTicks++;
                if (ctx.player().containerMenu != ctx.player().inventoryMenu) {
                    logDirect("§a[Home] [4/5] Skrzynka otwarta! Rozpoczynam oddawanie wydobytych surowców...");
                    state = State.DEPOSITING_ITEMS;
                    stateTicks = 0;
                    depositedStacksCount = 0;
                    lastTestedDepositSlot = -1;
                    lastTestedDepositCount = -1;
                    stuckDepositAttempts = 0;
                    return new PathingCommand(null, PathingCommandType.REQUEST_PAUSE);
                }
                // Ponów kliknięcie PPM po 15 tickach w razie laga sieciowego
                if (stateTicks % 15 == 0 && stateTicks < 50) {
                    BlockPos chestPos = findNearbyChest();
                    if (chestPos != null) {
                        HitResult pickHit = ctx.player().pick(4.5D, 0.0F, false);
                        if (pickHit instanceof BlockHitResult bhr && isChestBlock(ctx.world().getBlockState(bhr.getBlockPos()))) {
                            ctx.playerController().processRightClickBlock(ctx.player(), ctx.world(), InteractionHand.MAIN_HAND, bhr);
                            ctx.player().swing(InteractionHand.MAIN_HAND);
                        } else {
                            Vec3 eyePos = ctx.player().getEyePosition();
                            Vec3 hitVec = (chestPos.equals(ctx.playerFeet()) || chestPos.equals(ctx.playerFeet().below()))
                                    ? new Vec3(chestPos.getX() + 0.5D, chestPos.getY() + 0.875D, chestPos.getZ() + 0.5D)
                                    : Vec3.atCenterOf(chestPos);
                            Rotation rot = RotationEngine.lookAt(eyePos, hitVec);
                            RotationEngine.apply(ctx.player(), rot, baritone.settings());
                            BlockHitResult hit = new BlockHitResult(hitVec, Direction.UP, chestPos, false);
                            ctx.playerController().processRightClickBlock(ctx.player(), ctx.world(), InteractionHand.MAIN_HAND, hit);
                            ctx.player().swing(InteractionHand.MAIN_HAND);
                        }
                    }
                }
                if (stateTicks >= 50) {
                    logDirect("§c[Home] Skrzynka nie otworzyła się w oczekiwanym czasie. Przechodzę do Home 2...");
                    state = State.SENDING_HOME_2;
                    stateTicks = 0;
                }
                return new PathingCommand(null, PathingCommandType.REQUEST_PAUSE);
            }
            case DEPOSITING_ITEMS -> {
                enforceDeadStop();
                stateTicks++;

                // Odstęp 2 ticków (100ms) między kliknięciami QuickMove (GrimAC safe)
                if (stateTicks >= 2) {
                    stateTicks = 0;
                    var menu = ctx.player().containerMenu;
                    int totalSlots = menu.slots.size();
                    int playerStartSlot = totalSlots - 36;

                    // Sprawdzamy czy poprzedni slot się powiódł (czy skrzynka nie jest pełna)
                    if (lastTestedDepositSlot != -1 && lastTestedDepositSlot < totalSlots) {
                        ItemStack currentStack = menu.getSlot(lastTestedDepositSlot).getItem();
                        if (isMinedResource(currentStack) && currentStack.getCount() == lastTestedDepositCount) {
                            stuckDepositAttempts++;
                            if (stuckDepositAttempts >= 2) {
                                logDirect("§e[Home] Skrzynka jest pełna! Nie można zmieścić więcej surowców. Zamykam...");
                                state = State.CLOSING_CHEST;
                                stateTicks = 0;
                                return new PathingCommand(null, PathingCommandType.REQUEST_PAUSE);
                            }
                        } else {
                            stuckDepositAttempts = 0;
                        }
                    }

                    // Szukamy kolejnego slotu z wydobytym surowcem w ekwipunku gracza
                    int targetDepositSlot = -1;
                    int countToMove = 0;
                    for (int s = playerStartSlot; s < totalSlots; s++) {
                        ItemStack stack = menu.getSlot(s).getItem();
                        if (isMinedResource(stack)) {
                            // Jeśli to nasiona lub sadzonki do sadzenia na farmie (#farm), zachowaj 1 stack do replantowania
                            if (isFarmPlantable(stack) && countPlantableStacks(menu, playerStartSlot, totalSlots, stack.getItem()) <= 1) {
                                continue;
                            }
                            targetDepositSlot = s;
                            countToMove = stack.getCount();
                            break;
                        }
                    }

                    if (targetDepositSlot != -1) {
                        lastTestedDepositSlot = targetDepositSlot;
                        lastTestedDepositCount = countToMove;
                        // Shift-click (Quick Move) przenosi cały stack do skrzynki
                        ctx.playerController().windowClick(menu.containerId, targetDepositSlot, 0, ClickType.QUICK_MOVE, ctx.player());
                        depositedStacksCount++;
                    } else {
                        // Brak kolejnych stacków w EQ – zakończono oddawanie!
                        logDirect(String.format("§a[Home] Oddano %d stacków wydobytych surowców do skrzynki! Zamykam skrzynkę...", depositedStacksCount));
                        state = State.CLOSING_CHEST;
                        stateTicks = 0;
                    }
                }
                return new PathingCommand(null, PathingCommandType.REQUEST_PAUSE);
            }
            case CLOSING_CHEST -> {
                enforceDeadStop();
                ctx.player().closeContainer();
                stateTicks++;
                if (stateTicks >= 6) {
                    state = State.SENDING_HOME_2;
                    stateTicks = 0;
                }
                return new PathingCommand(null, PathingCommandType.REQUEST_PAUSE);
            }
            case SENDING_HOME_2 -> {
                enforceDeadStop();
                if (ctx.player().connection != null) {
                    logDirect("§b[Home] [5/5] Wysyłam /home (cel: Home 2 - powrót do kopalni)...");
                    ctx.player().connection.sendCommand("home");
                }
                state = State.WAITING_HOME_2_MENU;
                stateTicks = 0;
                return new PathingCommand(null, PathingCommandType.REQUEST_PAUSE);
            }
            case WAITING_HOME_2_MENU -> {
                enforceDeadStop();
                stateTicks++;
                if (ctx.player().containerMenu != ctx.player().inventoryMenu) {
                    state = State.CLICK_HOME_2;
                    stateTicks = 0;
                    return new PathingCommand(null, PathingCommandType.REQUEST_PAUSE);
                }
                // Fallback: jeśli po 25 tickach GUI się nie otworzyło, wyślij komendę "/home 2" bezpośrednio na czacie
                if (stateTicks == 25 && ctx.player().connection != null) {
                    logDirect("§7[Home] Brak GUI po /home - wysyłam bezpośrednio /home 2...");
                    ctx.player().connection.sendCommand("home 2");
                }
                if (stateTicks >= 60) {
                    logDirect("§c[Home] Przekroczono czas oczekiwania na menu \"Twoje domki\" / teleport.");
                    state = State.TELEPORTING_TO_HOME_2;
                    stateTicks = 20;
                    return new PathingCommand(null, PathingCommandType.REQUEST_PAUSE);
                }
                return new PathingCommand(null, PathingCommandType.REQUEST_PAUSE);
            }
            case CLICK_HOME_2 -> {
                enforceDeadStop();
                stateTicks++;
                if (stateTicks >= 2) {
                    int slotToClick = resolveSlotForHome(2);
                    int containerId = ctx.player().containerMenu.containerId;
                    ctx.playerController().windowClick(containerId, slotToClick, 0, ClickType.PICKUP, ctx.player());
                    logDirect("§a[Home] Kliknięto Home 2 (Zielone łóżko). Teleportuję z powrotem do kopalni...");
                    state = State.TELEPORTING_TO_HOME_2;
                    stateTicks = 0;
                }
                return new PathingCommand(null, PathingCommandType.REQUEST_PAUSE);
            }
            case TELEPORTING_TO_HOME_2 -> {
                enforceDeadStop();
                stateTicks++;
                int delayTicks = Math.max(40, Baritone.settings().homeTeleportDelayTicks.value);
                if (stateTicks == 1) {
                    logDirect(String.format("§b[Home] Czekam %.1f s bez ruchu na teleportację serwera (warmup 5s) powrotu do kopalni...", delayTicks / 20.0F));
                }
                if (stateTicks >= delayTicks) {
                    state = State.RESUMING_MINING;
                    stateTicks = 0;
                }
                return new PathingCommand(null, PathingCommandType.REQUEST_PAUSE);
            }
            case RESUMING_MINING -> {
                enforceDeadStop();
                stateTicks++;
                if (stateTicks >= 5) {
                    String cmdToRun = savedMiningCommand;
                    savedMiningCommand = null;
                    onLostControl();
                    if (cmdToRun != null && !cmdToRun.trim().isEmpty()) {
                        logDirect(String.format("§a[Home] Powrót na miejsce (#home 2) zakończony! Wznawiam zadanie: #%s", cmdToRun));
                        baritone.getCommandManager().execute(cmdToRun);
                    } else {
                        logDirect("§a[Home] Powrót na miejsce (#home 2) zakończony sukcesem!");
                    }
                    return new PathingCommand(null, PathingCommandType.DEFER);
                }
                return new PathingCommand(null, PathingCommandType.REQUEST_PAUSE);
            }

            case COOLDOWN -> {
                enforceDeadStop();
                stateTicks++;
                if (stateTicks >= 6) {
                    onLostControl();
                }
                return new PathingCommand(null, PathingCommandType.REQUEST_PAUSE);
            }
            default -> {
                onLostControl();
                return new PathingCommand(null, PathingCommandType.REQUEST_PAUSE);
            }
        }
    }

    /**
     * Sprawdza, czy dany przedmiot jest surowcem/materiałem wydobytym w kopalni lub zebranym na farmie do oddania do skrzynki.
     * Uwzględnia:
     *  - Żelazo, złoto, miedź (surowe, sztabki, bloki, bryłki)
     *  - Diamenty, szmaragdy, węgiel, lapis lazuli, redstone, kwarc, ametyst
     *  - Netherite (starożytne zgliszcza / debris, odłamki, sztabki, bloki)
     *  - Wszelkie bloki rud (*_ore) oraz krzemień
     *  - Plony rolne z farmy (#farm): pszenica, marchew, ziemniaki, buraki, arbuzy, dynie, trzcina, bambus itp.
     *
     * Ściśle wyklucza i ZACHOWUJE w ekwipunku gracza:
     *  - Narzędzia (kilofy, siekiery, łopaty, motyki) i broń (miecze)
     *  - Zbroje i tarcze
     *  - Prowiant survivalowy gracza (pieczone mięso, złote marchewki itp.)
     *  - Wiadra (woda, lawa, puste)
     *  - Oświetlenie (pochodnie, latarnie)
     *  - Bloki budowlane (bruk, deepslate, ziemia itp. potrzebne do mostkowania)
     */
    public static boolean isMinedResource(ItemStack stack) {
        if (stack == null || stack.isEmpty()) return false;
        if (stack.isDamageableItem()) return false; // nigdy nie oddawaj narzędzi, broni ani zbroi

        Item item = stack.getItem();

        // Ochrona wiader i źródeł światła
        if (item == Items.WATER_BUCKET || item == Items.LAVA_BUCKET || item == Items.BUCKET || item == Items.MILK_BUCKET
                || item == Items.TORCH || item == Items.SOUL_TORCH
                || item == Items.LANTERN || item == Items.SOUL_LANTERN) {
            return false;
        }

        // Ochrona bloków budowlanych (potrzebnych do mostkowania/wspinaczki)
        if (item == Items.COBBLESTONE || item == Items.COBBLED_DEEPSLATE || item == Items.DIRT
                || item == Items.TUFF || item == Items.STONE || item == Items.DEEPSLATE
                || item == Items.GRAVEL || item == Items.SAND || item == Items.ANDESITE
                || item == Items.DIORITE || item == Items.GRANITE || item == Items.SCAFFOLDING) {
            return false;
        }

        // Płody rolne / plony z farmy (#farm) do oddania do skrzynki w bazie
        if (isFarmedCrop(stack)) {
            return true;
        }

        // Ochrona prowiantu gracza (pieczona wołowina, pieczona wieprzowina, pieczony kurczak, chleb itp.)
        if (stack.has(net.minecraft.core.component.DataComponents.FOOD)) return false;

        // Żelazo
        if (isIronItem(stack)) {
            return true;
        }

        // Diamenty, szmaragdy, węgiel, złoto, miedź, lapis, redstone, netherite, kwarc, ametyst, krzemień
        if (item == Items.DIAMOND || item == Items.DIAMOND_BLOCK
                || item == Items.RAW_GOLD || item == Items.GOLD_INGOT || item == Items.GOLD_BLOCK || item == Items.RAW_GOLD_BLOCK || item == Items.GOLD_NUGGET
                || item == Items.RAW_COPPER || item == Items.COPPER_INGOT || item == Items.COPPER_BLOCK || item == Items.RAW_COPPER_BLOCK
                || item == Items.COAL || item == Items.CHARCOAL || item == Items.COAL_BLOCK
                || item == Items.LAPIS_LAZULI || item == Items.LAPIS_BLOCK
                || item == Items.REDSTONE || item == Items.REDSTONE_BLOCK
                || item == Items.EMERALD || item == Items.EMERALD_BLOCK
                || item == Items.ANCIENT_DEBRIS || item == Items.NETHERITE_SCRAP || item == Items.NETHERITE_INGOT || item == Items.NETHERITE_BLOCK
                || item == Items.QUARTZ || item == Items.QUARTZ_BLOCK
                || item == Items.AMETHYST_SHARD || item == Items.AMETHYST_BLOCK
                || item == Items.FLINT) {
            return true;
        }

        // Wszelkie rudy z rejestru (np. głębinowe lub modowane)
        ResourceLocation loc = BuiltInRegistries.ITEM.getKey(item);
        if (loc != null) {
            String path = loc.getPath().toLowerCase(Locale.ROOT);
            if (path.endsWith("_ore") || path.startsWith("raw_") || path.endsWith("_raw_block")) {
                return true;
            }
        }

        // Drewno / pnie (jeśli zadaniem było ścinanie)
        if (isWoodOrLog(stack)) {
            return true;
        }

        // Wszystkie surowce rozpoznane przez InventoryCleaner
        if (InventoryCleaner.isResource(stack, null)) {
            return true;
        }

        return false;
    }

    public static boolean isWoodOrLog(ItemStack stack) {
        if (stack == null || stack.isEmpty()) return false;
        ResourceLocation loc = BuiltInRegistries.ITEM.getKey(stack.getItem());
        if (loc == null) return false;
        String path = loc.getPath().toLowerCase(Locale.ROOT);
        return path.endsWith("_log") || path.endsWith("_wood") || path.endsWith("_stem")
                || path.endsWith("_hyphae") || path.equals("bamboo_block") || path.equals("stripped_bamboo_block");
    }

    /**
     * Sprawdza, czy dany przedmiot jest nasionem do wyrzucenia na ziemię przed oddaniem surowców (#clean style).
     * UWAGA: Nigdy nie wyrzucamy pszenicy (Items.WHEAT), marchwi, ziemniaków ani innych cennych plonów!
     */
    public static boolean isSeedItemToDrop(ItemStack stack) {
        if (stack == null || stack.isEmpty()) return false;
        Item item = stack.getItem();
        return item == Items.WHEAT_SEEDS
                || item == Items.BEETROOT_SEEDS
                || item == Items.MELON_SEEDS
                || item == Items.PUMPKIN_SEEDS
                || item == Items.TORCHFLOWER_SEEDS
                || item == Items.PITCHER_POD;
    }

    /**
     * Sprawdza, czy dany przedmiot jest plonem rolnym z farmy (#farm) do oddania do skrzynki w bazie.
     * Nasiona zostały celowo wykluczone (są wyrzucane na ziemię przed oddaniem).
     */
    public static boolean isFarmedCrop(ItemStack stack) {
        if (stack == null || stack.isEmpty()) return false;
        Item item = stack.getItem();
        return item == Items.WHEAT
                || item == Items.CARROT
                || item == Items.POTATO
                || item == Items.POISONOUS_POTATO
                || item == Items.BEETROOT
                || item == Items.MELON_SLICE
                || item == Blocks.MELON.asItem()
                || item == Blocks.PUMPKIN.asItem()
                || item == Blocks.SUGAR_CANE.asItem()
                || item == Blocks.BAMBOO.asItem()
                || item == Blocks.CACTUS.asItem()
                || item == Items.COCOA_BEANS
                || item == Items.NETHER_WART
                || item == Items.SWEET_BERRIES
                || item == Items.GLOW_BERRIES
                || item == Items.BROWN_MUSHROOM
                || item == Items.RED_MUSHROOM;
    }

    /**
     * Nasiona i plony sadzone na farmie, z których należy zachować przynajmniej 1 stack w EQ na replantowanie.
     */
    public static boolean isFarmPlantable(ItemStack stack) {
        if (stack == null || stack.isEmpty()) return false;
        Item item = stack.getItem();
        return item == Items.WHEAT_SEEDS
                || item == Items.BEETROOT_SEEDS
                || item == Items.MELON_SEEDS
                || item == Items.PUMPKIN_SEEDS
                || item == Items.CARROT
                || item == Items.POTATO
                || item == Items.NETHER_WART
                || item == Items.COCOA_BEANS;
    }

    private static int countPlantableStacks(net.minecraft.world.inventory.AbstractContainerMenu menu, int start, int end, Item item) {
        int count = 0;
        for (int i = start; i < end; i++) {
            ItemStack s = menu.getSlot(i).getItem();
            if (!s.isEmpty() && s.getItem() == item) {
                count++;
            }
        }
        return count;
    }

    /**
     * Sprawdza, czy dany przedmiot jest surowcem/materiałem żelaznym do oddania do skrzynki.
     * Uwzględnia: surowe żelazo (raw_iron), sztabki (iron_ingot), bloki żelaza (iron_block, raw_iron_block) i bryłki (iron_nugget).
     * Wyklucza: kilofy, narzędzia, zbroje, broń itp.
     */
    public static boolean isIronItem(ItemStack stack) {
        if (stack == null || stack.isEmpty()) return false;
        if (stack.isDamageableItem()) return false; // nigdy nie oddawaj narzędzi ani zbroi

        Item item = stack.getItem();
        if (item == Items.RAW_IRON || item == Items.IRON_INGOT
                || item == Items.IRON_BLOCK || item == Items.RAW_IRON_BLOCK
                || item == Items.IRON_NUGGET) {
            return true;
        }

        ResourceLocation loc = BuiltInRegistries.ITEM.getKey(item);
        if (loc != null) {
            String path = loc.getPath().toLowerCase(Locale.ROOT);
            return path.equals("raw_iron") || path.equals("iron_ingot")
                    || path.equals("iron_block") || path.equals("raw_iron_block")
                    || path.equals("iron_nugget");
        }
        return false;
    }

    /**
     * Znajduje skrzynkę obok gracza w zasięgu interakcji:
     * 1. Sprawdza, czy gracz stoi bezpośrednio NA skrzynce (feet lub feet.below())
     * 2. Sprawdza celownik gracza
     * 3. Wyszukuje najbliższą skrzynkę w promieniu 4 bloków
     */
    private BlockPos findNearbyChest() {
        if (ctx.player() == null || ctx.world() == null) return null;
        BlockPos feet = ctx.playerFeet();
        if (feet == null) return null;

        // 1. Sprawdź, czy gracz stoi bezpośrednio NA skrzynce
        BlockState feetState = ctx.world().getBlockState(feet);
        if (isChestBlock(feetState)) {
            return feet;
        }

        BlockPos below = feet.below();
        BlockState belowState = ctx.world().getBlockState(below);
        if (isChestBlock(belowState)) {
            return below;
        }

        // 2. Sprawdź, czy gracz patrzy bezpośrednio na skrzynkę
        HitResult hit = ctx.player().pick(4.5D, 0.0F, false);
        if (hit instanceof BlockHitResult bhr && hit.getType() == HitResult.Type.BLOCK) {
            BlockState bs = ctx.world().getBlockState(bhr.getBlockPos());
            if (isChestBlock(bs)) {
                return bhr.getBlockPos();
            }
        }

        // 3. Wyszukaj najbliższą skrzynkę w promieniu 4 bloków
        BlockPos bestPos = null;
        double bestDist = Double.MAX_VALUE;

        for (int dx = -4; dx <= 4; dx++) {
            for (int dy = -2; dy <= 3; dy++) {
                for (int dz = -4; dz <= 4; dz++) {
                    BlockPos pos = feet.offset(dx, dy, dz);
                    BlockState state = ctx.world().getBlockState(pos);
                    if (isChestBlock(state)) {
                        double dist = feet.distSqr(pos);
                        if (dist < bestDist) {
                            bestDist = dist;
                            bestPos = pos;
                        }
                    }
                }
            }
        }
        return bestPos;
    }

    private boolean isChestBlock(BlockState state) {
        if (state == null) return false;
        return state.is(Blocks.CHEST) || state.is(Blocks.TRAPPED_CHEST) || state.is(Blocks.BARREL);
    }

    /**
     * Oblicza numer slotu w kontenerze dla wybranego numeru domku.
     * Na serwerze w menu "Twoje domki" (9x6):
     *  - Rząd 2 (sloty 18..26):
     *      Slot 19: Kamień (Home 1)
     *      Slot 20: Zielone łóżko (Home 2)
     *      Sloty 21..25: Czerwone łóżka (Home 3..7)
     */
    private int resolveSlotForHome(int homeNum) {
        if (ctx.player() == null || ctx.player().containerMenu == null) {
            return 18 + homeNum;
        }

        var menu = ctx.player().containerMenu;

        // Jeśli Home 1: sprawdź czy slot 19 zawiera kamień, lub poszukaj kamienia w rzędzie 2
        if (homeNum == 1) {
            if (menu.slots.size() > 19 && menu.getSlot(19).getItem().is(Items.STONE)) {
                return 19;
            }
            for (int s = 18; s <= 26 && s < menu.slots.size(); s++) {
                if (menu.getSlot(s).getItem().is(Items.STONE)) {
                    return s;
                }
            }
            return 19;
        }

        // Jeśli Home 2: sprawdź czy slot 20 zawiera zielone/jasnozielone łóżko
        if (homeNum == 2) {
            if (menu.slots.size() > 20 && isGreenBed(menu.getSlot(20).getItem())) {
                return 20;
            }
            for (int s = 18; s <= 26 && s < menu.slots.size(); s++) {
                if (isGreenBed(menu.getSlot(s).getItem())) {
                    return s;
                }
            }
            return 20;
        }

        // Home 3..7: slot = 18 + homeNum
        int calculated = 18 + homeNum;
        if (calculated < menu.slots.size()) {
            return calculated;
        }
        return 18 + homeNum;
    }

    private boolean isGreenBed(ItemStack stack) {
        if (stack.isEmpty()) return false;
        return stack.is(Items.LIME_BED) || stack.is(Items.GREEN_BED);
    }

    private PathingCommand enforceDeadStop() {
        baritone.getInputOverrideHandler().clearAllKeys();
        for (Input in : Input.values()) {
            baritone.getInputOverrideHandler().setInputForceState(in, false);
        }
        baritone.getInputOverrideHandler().getBlockBreakHelper().stopBreakingBlock();
        RotationEngine.reset();

        try {
            Options opts = Minecraft.getInstance().options;
            if (opts != null) {
                opts.keyUp.setDown(false);
                opts.keyDown.setDown(false);
                opts.keyLeft.setDown(false);
                opts.keyRight.setDown(false);
                opts.keyJump.setDown(false);
                opts.keySprint.setDown(false);
                opts.keyShift.setDown(false);
                opts.keyAttack.setDown(false);
                opts.keyUse.setDown(false);
            }
        } catch (Throwable ignored) {}

        if (ctx.player() != null && ctx.player().isSprinting()) {
            ctx.player().setSprinting(false);
        }

        baritone.getPathingBehavior().requestPause();
        return new PathingCommand(null, PathingCommandType.REQUEST_PAUSE);
    }

    @Override
    public void onLostControl() {
        state = State.IDLE;
        stateTicks = 0;
        active = false;
        autoDepositCycle = false;
        depositedStacksCount = 0;
        lastTestedDepositSlot = -1;
        lastTestedDepositCount = -1;
        stuckDepositAttempts = 0;
    }

    @Override
    public double priority() {
        return 6.5D;
    }

    @Override
    public boolean isTemporary() {
        return true;
    }

    @Override
    public String displayName0() {
        if (autoDepositCycle) {
            return "Home (cykl: Home 1 -> oddanie żelaza -> Home 2)";
        }
        return "Home (teleport do domku " + targetHome + ")";
    }

    public int getTargetHome() {
        return targetHome;
    }
}
