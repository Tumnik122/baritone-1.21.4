package com.astra.revolution.process;

import baritone.api.IBaritone;
import baritone.api.pathing.goals.GoalBlock;
import baritone.api.pathing.goals.GoalNear;
import baritone.api.process.IBaritoneProcess;
import baritone.api.process.PathingCommand;
import baritone.api.process.PathingCommandType;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.component.DataComponents;
import net.minecraft.util.Mth;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.inventory.ClickType;
import net.minecraft.world.inventory.Slot;
import net.minecraft.world.item.HoeItem;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.Vec3;

import java.util.ArrayDeque;
import java.util.Deque;
import java.util.Set;
import java.util.concurrent.ThreadLocalRandom;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Autonomiczny proces sprzedaży plonów (#sell / #sell all) oraz uzupełniania zapasów (#fill).
 * Zaprojektowany jako deterministyczny automat stanów odporny na lagi pakietowe i rozłączenia.
 */
public final class SellProcess implements IBaritoneProcess {

    public enum CycleState {
        IDLE("FARMING"),
        SAVING_CHECKPOINT("CHECKPOINT"),
        SELL_COMMAND_DISPATCH("SELLING"),
        SELL_WAITING_CONFIRMATION("SELLING"),
        NAVIGATING_TO_SELL_POINT("SELLING"),
        INTERACTING_SELL_CONTAINER("SELLING"),
        VERIFYING_SUPPLIES("REFILLING"),
        NAVIGATING_TO_SUPPLY_STATION("REFILLING"),
        INTERACTING_SUPPLY_CONTAINER("REFILLING"),
        RETURNING_TO_FIELD("RETURNING"),
        ALIGNING_TO_ROW("RETURNING");

        private final String hudLabel;

        CycleState(String hudLabel) {
            this.hudLabel = hudLabel;
        }

        public String getHudLabel() {
            return hudLabel;
        }
    }

    public enum SellStrategy {
        SERVER_COMMAND_SELL_ALL, // Komenda /sell all lub /skup
        PHYSICAL_CHEST_OR_NPC    // Fizyczne podejście / teleport do skrzyni skupu lub NPC
    }

    // Zbiory przedmiotów klasyfikowanych jako plony i nasiona
    private static final Set<Item> HARVEST_CROPS = Set.of(
            Items.WHEAT, Items.CARROT, Items.POTATO, Items.BEETROOT,
            Items.NETHER_WART, Items.MELON_SLICE, Items.PUMPKIN, Items.SUGAR_CANE
    );

    private static final Set<Item> PLANTABLE_SEEDS = Set.of(
            Items.WHEAT_SEEDS, Items.CARROT, Items.POTATO,
            Items.BEETROOT_SEEDS, Items.NETHER_WART
    );

    // Wyrażenie regularne przechwytujące kwoty sprzedaży z czatu (PL / EN)
    private static final Pattern ECONOMY_SALE_PATTERN = Pattern.compile(
            "(?i)(?:sprzedano|sprzedałeś|otrzymałeś|zarobiłeś|sold|received|earned).*?(?:za|for|:)?\\s*[$€£]?\\s*([0-9]{1,3}(?:[,\\s][0-9]{3})*(?:[.,][0-9]{1,2})?|[0-9]+(?:[.,][0-9]{1,2})?)\\s*(?:\\$|zł|pln|monet|coins)?"
    );

    private final IBaritone baritone;
    private final Minecraft mc;

    // Konfiguracja operacyjna
    private SellStrategy sellStrategy = SellStrategy.SERVER_COMMAND_SELL_ALL;
    private String sellCommand = "sell all";
    private String homeSellCommand = "home sell";
    private String homeFarmCommand = "home farm";
    private boolean useHomeTeleportForReturn = false;

    private BlockPos sellStationPos = null;
    private BlockPos supplyChestPos = null;

    // Progi buforowe dla #fill
    private static final int MIN_FREE_SLOTS_TRIGGER = 1;
    private static final int TARGET_SEED_BUFFER_COUNT = 192; // 3 pełne stacki nasion
    private static final int MIN_SEED_TRIGGER_COUNT = 32;
    private static final int MIN_HOE_DURABILITY = 28;
    private static final int TARGET_FOOD_COUNT = 32;

    // Stan wewnętrzny automatu i Checkpointu
    private CycleState state = CycleState.IDLE;
    private BlockPos lastFarmPos = null;
    private float lastFarmYaw = 0.0F;
    private int savedWaypointIndex = 0;

    private long stateEnteredAtMs = 0L;
    private long nextActionAllowedAtMs = 0L;
    private int cropCountBeforeSell = 0;
    private boolean homeTeleportSent = false;

    // Telemetria i statystyki dla ModernCapsuleHud oraz BaritoneBypassScreen
    private int completedSellCycles = 0;
    private double totalSessionEarnings = 0.0;
    private final Deque<TimestampedEvent> harvestEvents = new ArrayDeque<>();
    private final Deque<TimestampedEvent> earningEvents = new ArrayDeque<>();

    private record TimestampedEvent(long timestampMs, double amount) {}

    public SellProcess(IBaritone baritone) {
        this.baritone = baritone;
        this.mc = Minecraft.getInstance();
    }

    /**
     * Sprawdza, czy ekwipunek gracza osiągnął próg zapełnienia wymagający uruchomienia #sell.
     */
    public boolean shouldTriggerAutonomousCycle(LocalPlayer player) {
        if (player == null || state != CycleState.IDLE) {
            return false;
        }
        Inventory inv = player.getInventory();
        int freeSlots = 0;
        int cropStacks = 0;

        for (int i = 0; i < inv.items.size(); i++) {
            ItemStack stack = inv.items.get(i);
            if (stack.isEmpty()) {
                freeSlots++;
            } else if (HARVEST_CROPS.contains(stack.getItem()) && stack.getCount() >= stack.getMaxStackSize() - 4) {
                cropStacks++;
            }
        }
        return freeSlots <= MIN_FREE_SLOTS_TRIGGER && cropStacks >= 1;
    }

    /**
     * Inicjuje cykl #sell -> #fill -> powrót z zapisaniem dokładnego checkpointu na polu.
     */
    public void beginAutonomousCycle(BlockPos checkpointPos, float checkpointYaw, int currentRouteIndex) {
        this.lastFarmPos = checkpointPos.immutable();
        this.lastFarmYaw = checkpointYaw;
        this.savedWaypointIndex = currentRouteIndex;
        this.homeTeleportSent = false;
        transitionTo(CycleState.SAVING_CHECKPOINT);
    }

    /**
     * Pozwala na ręczne wywołanie #fill (uzupełnienie nasion, motyki i jedzenia).
     */
    public void triggerManualFill(BlockPos currentPos, float currentYaw, int routeIdx) {
        this.lastFarmPos = currentPos.immutable();
        this.lastFarmYaw = currentYaw;
        this.savedWaypointIndex = routeIdx;
        this.homeTeleportSent = false;
        transitionTo(CycleState.VERIFYING_SUPPLIES);
    }

    @Override
    public boolean isActive() {
        return state != CycleState.IDLE && mc.player != null && mc.level != null && mc.getConnection() != null;
    }

    @Override
    public PathingCommand onTick(boolean calcFailed, boolean isSafeToCancel) {
        LocalPlayer player = mc.player;
        ClientLevel level = mc.level;

        if (player == null || level == null || mc.getConnection() == null) {
            return new PathingCommand(null, PathingCommandType.REQUEST_PAUSE);
        }

        long now = System.currentTimeMillis();
        if (now < nextActionAllowedAtMs) {
            return new PathingCommand(null, PathingCommandType.REQUEST_PAUSE);
        }

        return switch (state) {
            case IDLE -> new PathingCommand(null, PathingCommandType.DEFER);

            case SAVING_CHECKPOINT -> {
                baritone.getInputOverrideHandler().clearAllKeys();
                cropCountBeforeSell = countTotalCrops(player.getInventory());
                scheduleHumanDelay(180, 310);

                if (sellStrategy == SellStrategy.SERVER_COMMAND_SELL_ALL) {
                    transitionTo(CycleState.SELL_COMMAND_DISPATCH);
                } else {
                    transitionTo(CycleState.NAVIGATING_TO_SELL_POINT);
                }
                yield new PathingCommand(null, PathingCommandType.REQUEST_PAUSE);
            }

            case SELL_COMMAND_DISPATCH -> {
                sendServerCommand(sellCommand);
                scheduleHumanDelay(450, 750);
                transitionTo(CycleState.SELL_WAITING_CONFIRMATION);
                yield new PathingCommand(null, PathingCommandType.REQUEST_PAUSE);
            }

            case SELL_WAITING_CONFIRMATION -> {
                int currentCrops = countTotalCrops(player.getInventory());
                boolean timedOut = (now - stateEnteredAtMs) > 3500L;

                if (currentCrops < cropCountBeforeSell || timedOut) {
                    completedSellCycles++;
                    transitionTo(CycleState.VERIFYING_SUPPLIES);
                }
                yield new PathingCommand(null, PathingCommandType.REQUEST_PAUSE);
            }

            case NAVIGATING_TO_SELL_POINT -> {
                if (sellStationPos == null) {
                    // Fallback do komendy serwerowej, jeśli nie skonfigurowano koordynatów skrzyni skupu
                    transitionTo(CycleState.SELL_COMMAND_DISPATCH);
                    yield new PathingCommand(null, PathingCommandType.REQUEST_PAUSE);
                }

                if (!homeTeleportSent && homeSellCommand != null && !homeSellCommand.isBlank()
                        && player.blockPosition().distSqr(sellStationPos) > 900.0) {
                    sendServerCommand(homeSellCommand);
                    homeTeleportSent = true;
                    scheduleHumanDelay(1200, 1800);
                    yield new PathingCommand(null, PathingCommandType.REQUEST_PAUSE);
                }

                if (player.blockPosition().closerThan(sellStationPos, 3.4)) {
                    baritone.getInputOverrideHandler().clearAllKeys();
                    openContainerAt(player, sellStationPos);
                    scheduleHumanDelay(350, 550);
                    transitionTo(CycleState.INTERACTING_SELL_CONTAINER);
                    yield new PathingCommand(null, PathingCommandType.REQUEST_PAUSE);
                }

                yield new PathingCommand(new GoalNear(sellStationPos, 2), PathingCommandType.SET_GOAL_AND_PATH);
            }

            case INTERACTING_SELL_CONTAINER -> {
                if (mc.screen instanceof AbstractContainerScreen<?> containerScreen) {
                    boolean movedItem = transferOneCropStackToContainer(player, containerScreen.getMenu());
                    if (movedItem) {
                        // GrimAC Inventory Click Delay (120-185 ms między kliknięciami)
                        scheduleHumanDelay(120, 185);
                        yield new PathingCommand(null, PathingCommandType.REQUEST_PAUSE);
                    } else {
                        player.closeContainer();
                        completedSellCycles++;
                        scheduleHumanDelay(220, 360);
                        transitionTo(CycleState.VERIFYING_SUPPLIES);
                    }
                } else if ((now - stateEnteredAtMs) > 3000L) {
                    transitionTo(CycleState.VERIFYING_SUPPLIES);
                }
                yield new PathingCommand(null, PathingCommandType.REQUEST_PAUSE);
            }

            case VERIFYING_SUPPLIES -> {
                boolean needsRefill = requiresSupplyRefill(player.getInventory());
                if (needsRefill && supplyChestPos != null) {
                    transitionTo(CycleState.NAVIGATING_TO_SUPPLY_STATION);
                } else {
                    homeTeleportSent = false;
                    transitionTo(CycleState.RETURNING_TO_FIELD);
                }
                yield new PathingCommand(null, PathingCommandType.REQUEST_PAUSE);
            }

            case NAVIGATING_TO_SUPPLY_STATION -> {
                if (supplyChestPos == null) {
                    transitionTo(CycleState.RETURNING_TO_FIELD);
                    yield new PathingCommand(null, PathingCommandType.REQUEST_PAUSE);
                }

                if (player.blockPosition().closerThan(supplyChestPos, 3.4)) {
                    baritone.getInputOverrideHandler().clearAllKeys();
                    openContainerAt(player, supplyChestPos);
                    scheduleHumanDelay(350, 550);
                    transitionTo(CycleState.INTERACTING_SUPPLY_CONTAINER);
                    yield new PathingCommand(null, PathingCommandType.REQUEST_PAUSE);
                }
                yield new PathingCommand(new GoalNear(supplyChestPos, 2), PathingCommandType.SET_GOAL_AND_PATH);
            }

            case INTERACTING_SUPPLY_CONTAINER -> {
                if (mc.screen instanceof AbstractContainerScreen<?> containerScreen) {
                    boolean extracted = extractNeededSupplyFromContainer(player, containerScreen.getMenu());
                    if (extracted) {
                        scheduleHumanDelay(130, 195);
                        yield new PathingCommand(null, PathingCommandType.REQUEST_PAUSE);
                    } else {
                        player.closeContainer();
                        scheduleHumanDelay(240, 380);
                        homeTeleportSent = false;
                        transitionTo(CycleState.RETURNING_TO_FIELD);
                    }
                } else if ((now - stateEnteredAtMs) > 3000L) {
                    homeTeleportSent = false;
                    transitionTo(CycleState.RETURNING_TO_FIELD);
                }
                yield new PathingCommand(null, PathingCommandType.REQUEST_PAUSE);
            }

            case RETURNING_TO_FIELD -> {
                if (lastFarmPos == null) {
                    transitionTo(CycleState.IDLE);
                    yield new PathingCommand(null, PathingCommandType.DEFER);
                }

                if (useHomeTeleportForReturn && !homeTeleportSent
                        && homeFarmCommand != null && !homeFarmCommand.isBlank()
                        && player.blockPosition().distSqr(lastFarmPos) > 625.0) {
                    sendServerCommand(homeFarmCommand);
                    homeTeleportSent = true;
                    scheduleHumanDelay(1100, 1650);
                    yield new PathingCommand(null, PathingCommandType.REQUEST_PAUSE);
                }

                if (player.blockPosition().closerThan(lastFarmPos, 1.6)) {
                    baritone.getInputOverrideHandler().clearAllKeys();
                    transitionTo(CycleState.ALIGNING_TO_ROW);
                    yield new PathingCommand(null, PathingCommandType.REQUEST_PAUSE);
                }

                yield new PathingCommand(new GoalBlock(lastFarmPos), PathingCommandType.SET_GOAL_AND_PATH);
            }

            case ALIGNING_TO_ROW -> {
                // Płynna interpolacja kąta Yaw przed wznowieniem sprintu (ochrona GrimAC Aim)
                float currentYaw = player.getYRot();
                float deltaYaw = Mth.wrapDegrees(lastFarmYaw - currentYaw);

                if (Math.abs(deltaYaw) <= 3.5F) {
                    player.setYRot(lastFarmYaw);
                    transitionTo(CycleState.IDLE);
                    yield new PathingCommand(null, PathingCommandType.DEFER);
                } else {
                    float step = Mth.clamp(deltaYaw * 0.38F, -18.0F, 18.0F);
                    player.setYRot(currentYaw + step);
                    yield new PathingCommand(null, PathingCommandType.REQUEST_PAUSE);
                }
            }
        };
    }

    /**
     * Listener czatu wywoływany przy odbiorze wiadomości systemowych / serwerowych.
     * Przechwytuje kwoty ze sprzedaży i aktualizuje wskaźnik Zarobek/h.
     */
    public void onServerChatMessage(String rawChatText) {
        if (rawChatText == null || rawChatText.isBlank()) {
            return;
        }
        String clean = rawChatText.replaceAll("§[0-9a-fk-orA-FK-OR]", "");
        Matcher matcher = ECONOMY_SALE_PATTERN.matcher(clean);
        if (matcher.find()) {
            String rawNumber = matcher.group(1)
                    .replace(" ", "")
                    .replace(",", ".");
            // Obsługa formatu z separatorem tysięcy np. 1.250.00
            int lastDot = rawNumber.lastIndexOf('.');
            if (lastDot > 0 && rawNumber.indexOf('.') != lastDot) {
                rawNumber = rawNumber.substring(0, lastDot).replace(".", "") + rawNumber.substring(lastDot);
            }
            try {
                double amount = Double.parseDouble(rawNumber);
                if (amount > 0.0) {
                    recordEarningEvent(amount);
                }
            } catch (NumberFormatException ignored) {
            }
        }
    }

    public void recordHarvestedItem(int count) {
        long now = System.currentTimeMillis();
        synchronized (harvestEvents) {
            harvestEvents.addLast(new TimestampedEvent(now, count));
            pruneOldEvents(harvestEvents, now);
        }
    }

    public void recordEarningEvent(double money) {
        long now = System.currentTimeMillis();
        this.totalSessionEarnings += money;
        synchronized (earningEvents) {
            earningEvents.addLast(new TimestampedEvent(now, money));
            pruneOldEvents(earningEvents, now);
        }
    }

    /**
     * Zwraca dynamiczną liczbę zebranych plonów na godzinę (Plony/h) z ruchomego okna 10 minut.
     */
    public double getCropsPerHour() {
        return computeRatePerHour(harvestEvents);
    }

    /**
     * Zwraca dynamiczny zarobek na godzinę (Zarobek/h) z ruchomego okna 10 minut.
     */
    public double getEarningsPerHour() {
        return computeRatePerHour(earningEvents);
    }

    private double computeRatePerHour(Deque<TimestampedEvent> queue) {
        long now = System.currentTimeMillis();
        synchronized (queue) {
            pruneOldEvents(queue, now);
            if (queue.isEmpty()) {
                return 0.0;
            }
            double sum = 0.0;
            long oldest = queue.peekFirst().timestampMs();
            for (TimestampedEvent ev : queue) {
                sum += ev.amount();
            }
            double elapsedHours = Math.max(15.0 / 3600.0, (now - oldest) / 3_600_000.0);
            return sum / elapsedHours;
        }
    }

    private void pruneOldEvents(Deque<TimestampedEvent> queue, long nowMs) {
        long windowMs = 10L * 60L * 1000L; // 10-minutowe okno kroczące
        while (!queue.isEmpty() && (nowMs - queue.peekFirst().timestampMs()) > windowMs) {
            queue.pollFirst();
        }
    }

    private boolean requiresSupplyRefill(Inventory inv) {
        int seeds = countItems(inv, PLANTABLE_SEEDS);
        int hoeDurability = findBestHoeRemainingDurability(inv);
        int food = countFoodItems(inv);

        return seeds < MIN_SEED_TRIGGER_COUNT
                || (hoeDurability > 0 && hoeDurability < MIN_HOE_DURABILITY)
                || food < 8;
    }

    private boolean transferOneCropStackToContainer(LocalPlayer player, AbstractContainerMenu menu) {
        int containerSlots = menu.slots.size() - 36;
        if (containerSlots <= 0 || mc.gameMode == null) {
            return false;
        }

        // Zachowujemy bufor nasion (np. gdy plonem są ziemniaki/marchewki będące jednocześnie materiałem siewnym)
        int totalSeeds = countItems(player.getInventory(), PLANTABLE_SEEDS);

        for (int i = containerSlots; i < menu.slots.size(); i++) {
            Slot slot = menu.slots.get(i);
            ItemStack stack = slot.getItem();
            if (stack.isEmpty() || !HARVEST_CROPS.contains(stack.getItem())) {
                continue;
            }
            if (PLANTABLE_SEEDS.contains(stack.getItem()) && totalSeeds <= TARGET_SEED_BUFFER_COUNT) {
                continue;
            }

            mc.gameMode.handleInventoryMouseClick(menu.containerId, slot.index, 0, ClickType.QUICK_MOVE, player);
            return true;
        }
        return false;
    }

    private boolean extractNeededSupplyFromContainer(LocalPlayer player, AbstractContainerMenu menu) {
        int containerSlots = menu.slots.size() - 36;
        if (containerSlots <= 0 || mc.gameMode == null) {
            return false;
        }

        Inventory inv = player.getInventory();
        boolean needSeeds = countItems(inv, PLANTABLE_SEEDS) < TARGET_SEED_BUFFER_COUNT;
        boolean needHoe = findBestHoeRemainingDurability(inv) < MIN_HOE_DURABILITY;
        boolean needFood = countFoodItems(inv) < TARGET_FOOD_COUNT;

        for (int i = 0; i < containerSlots; i++) {
            Slot slot = menu.slots.get(i);
            ItemStack stack = slot.getItem();
            if (stack.isEmpty()) {
                continue;
            }

            boolean shouldTake = (needSeeds && PLANTABLE_SEEDS.contains(stack.getItem()))
                    || (needHoe && stack.getItem() instanceof HoeItem && (stack.getMaxDamage() - stack.getDamageValue()) > MIN_HOE_DURABILITY * 2)
                    || (needFood && stack.has(DataComponents.FOOD));

            if (shouldTake) {
                mc.gameMode.handleInventoryMouseClick(menu.containerId, slot.index, 0, ClickType.QUICK_MOVE, player);
                return true;
            }
        }
        return false;
    }

    private void openContainerAt(LocalPlayer player, BlockPos pos) {
        if (mc.gameMode == null || mc.level == null) {
            return;
        }
        Vec3 hitVec = Vec3.atCenterOf(pos);
        BlockHitResult hit = new BlockHitResult(hitVec, Direction.UP, pos, false);
        mc.gameMode.useItemOn(player, InteractionHand.MAIN_HAND, hit);
        player.swing(InteractionHand.MAIN_HAND);
    }

    private void sendServerCommand(String command) {
        if (mc.player != null && mc.player.connection != null && command != null && !command.isBlank()) {
            String normalized = command.startsWith("/") ? command.substring(1) : command;
            mc.player.connection.sendCommand(normalized);
        }
    }

    private int countTotalCrops(Inventory inv) {
        return countItems(inv, HARVEST_CROPS);
    }

    private int countItems(Inventory inv, Set<Item> validItems) {
        int count = 0;
        for (ItemStack stack : inv.items) {
            if (!stack.isEmpty() && validItems.contains(stack.getItem())) {
                count += stack.getCount();
            }
        }
        for (ItemStack stack : inv.offhand) {
            if (!stack.isEmpty() && validItems.contains(stack.getItem())) {
                count += stack.getCount();
            }
        }
        return count;
    }

    private int countFoodItems(Inventory inv) {
        int count = 0;
        for (ItemStack stack : inv.items) {
            if (!stack.isEmpty() && stack.has(DataComponents.FOOD) && !PLANTABLE_SEEDS.contains(stack.getItem())) {
                count += stack.getCount();
            }
        }
        for (ItemStack stack : inv.offhand) {
            if (!stack.isEmpty() && stack.has(DataComponents.FOOD)) {
                count += stack.getCount();
            }
        }
        return count;
    }

    private int findBestHoeRemainingDurability(Inventory inv) {
        int bestRemaining = 0;
        for (ItemStack stack : inv.items) {
            if (!stack.isEmpty() && stack.getItem() instanceof HoeItem) {
                int remaining = stack.getMaxDamage() - stack.getDamageValue();
                if (remaining > bestRemaining) {
                    bestRemaining = remaining;
                }
            }
        }
        return bestRemaining;
    }

    private void transitionTo(CycleState nextState) {
        this.state = nextState;
        this.stateEnteredAtMs = System.currentTimeMillis();
    }

    private void scheduleHumanDelay(int minMs, int maxMs) {
        long jitter = ThreadLocalRandom.current().nextInt(minMs, maxMs + 1);
        this.nextActionAllowedAtMs = System.currentTimeMillis() + jitter;
    }

    @Override
    public void onLostControl() {
        this.state = CycleState.IDLE;
    }

    @Override
    public String displayName0() {
        return "AstraAutonomousSellFill [" + state.getHudLabel() + "]";
    }

    @Override
    public boolean isTemporary() {
        return false;
    }

    @Override
    public double priority() {
        return 3.5; // Wyższy priorytet niż standardowy FarmProcess (2.0), aby płynnie przejąć kontrolę
    }

    // Gettery i konfiguratory dla HUD / GUI / FarmProcess
    public CycleState getCurrentState() {
        return state;
    }

    public int getCompletedSellCycles() {
        return completedSellCycles;
    }

    public double getTotalSessionEarnings() {
        return totalSessionEarnings;
    }

    public int getSavedWaypointIndex() {
        return savedWaypointIndex;
    }

    public void configureSellStrategy(SellStrategy strategy, String sellCmd, BlockPos sellPos, BlockPos supplyPos) {
        this.sellStrategy = strategy;
        this.sellCommand = sellCmd;
        this.sellStationPos = sellPos;
        this.supplyChestPos = supplyPos;
    }

    public void configureTeleportCommands(String homeSellCmd, String homeFarmCmd, boolean useHomeOnReturn) {
        this.homeSellCommand = homeSellCmd;
        this.homeFarmCommand = homeFarmCmd;
        this.useHomeTeleportForReturn = useHomeOnReturn;
    }
}
