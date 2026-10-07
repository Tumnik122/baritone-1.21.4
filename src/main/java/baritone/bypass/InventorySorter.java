package baritone.bypass;

import baritone.Baritone;
import baritone.api.utils.Helper;
import baritone.api.utils.IPlayerContext;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.core.component.DataComponents;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.inventory.ClickType;
import net.minecraft.world.item.*;

import java.util.Locale;

/**
 * InventorySorter — Automatyczne segregowanie ekwipunku dla Baritone / Trybu Anarchia.
 *
 * Wykonuje pełną organizację ekwipunku w 3 krokach:
 * 1. Stack Merging: łączy rozproszone niepełne stacki tych samych surowców (np. 15 żelaza + 20 żelaza = 35).
 * 2. Hotbar Organization: układa najlepszy kilof na slot 0 (menu 36), broń na slot 1 (menu 37), jedzenie na slot 8 (menu 44).
 * 3. Main Inventory Sorting: porządkuje sloty 9-35 według logicznych kategorii (diamenty, metale, węgiel, narzędzia, bloki).
 *
 * Wszystkie kliknięcia są w 100% bezpieczne dla GrimAC (opóźnienie między kliknięciami, brak ruchu w trakcie).
 */
public final class InventorySorter {

    private InventorySorter() {}

    private enum Step {
        IDLE,
        MERGE_CLICK1,
        MERGE_CLICK2,
        MERGE_RESTORE,
        SWAP_CLICK1,
        SWAP_CLICK2,
        SWAP_CLICK3
    }

    private static Step currentStep = Step.IDLE;
    private static int slotA = -1;
    private static int slotB = -1;
    private static int ticksSinceLastClick = 0;
    private static boolean active = false;
    private static int performedSwaps = 0;

    public static void start() {
        currentStep = Step.IDLE;
        slotA = -1;
        slotB = -1;
        ticksSinceLastClick = 0;
        performedSwaps = 0;
        active = true;
    }

    public static boolean isActive() {
        return active;
    }

    public static void stop() {
        currentStep = Step.IDLE;
        slotA = -1;
        slotB = -1;
        active = false;
    }

    /**
     * Sprawdza czy ekwipunek faktycznie wymaga jakichkolwiek działań segregowania.
     * Zapobiega niepotrzebnemu zatrzymywaniu bota i spamowaniu wiadomościami.
     */
    public static boolean hasAnythingToSort(LocalPlayer player) {
        if (player == null) return false;
        if (findMergeableStacks(player) != null) return true;
        if (Baritone.settings().autoSortHotbar.value && findHotbarSwap(player) != null) return true;
        if (findInventorySwap(player) != null) return true;
        return false;
    }

    /**
     * Wykonywane co tick w procesie AutoDrop / AutoSort.
     * @return true jeśli segregowanie nadal trwa, false jeśli ekwipunek jest w pełni ułożony.
     */
    public static boolean tick(IPlayerContext ctx) {
        if (!active) return false;
        LocalPlayer player = ctx != null ? ctx.player() : null;
        if (player == null || ctx.playerController() == null) {
            stop();
            return false;
        }

        int delayTicks = Math.max(1, Baritone.settings().autoSortDelayTicks.value);
        ticksSinceLastClick++;
        if (ticksSinceLastClick < delayTicks) {
            return true; // Czekamy na bezpieczny interwał GrimAC
        }

        int containerId = player.inventoryMenu.containerId;

        // Jeśli kursor ma niespodziewany przedmiot w stanie IDLE, odłóż go
        if (currentStep == Step.IDLE && !player.containerMenu.getCarried().isEmpty()) {
            int emptyMenuSlot = findFirstEmptyMenuSlot(player);
            if (emptyMenuSlot != -1) {
                ctx.playerController().windowClick(containerId, emptyMenuSlot, 0, ClickType.PICKUP, player);
                ticksSinceLastClick = 0;
                return true;
            }
        }

        switch (currentStep) {
            case IDLE -> {
                // 1. Sprawdź łączenie niepełnych stacków (Stack Compacting)
                int[] mergePair = findMergeableStacks(player);
                if (mergePair != null) {
                    slotA = mergePair[0]; // cel (biorca)
                    slotB = mergePair[1]; // źródło (dawca)
                    ctx.playerController().windowClick(containerId, slotB, 0, ClickType.PICKUP, player);
                    currentStep = Step.MERGE_CLICK2;
                    ticksSinceLastClick = 0;
                    performedSwaps++;
                    return true;
                }

                // 2. Sprawdź hotbar tylko jeśli użytkownik włączył autoSortHotbar
                if (Baritone.settings().autoSortHotbar.value) {
                    int[] hotbarSwap = findHotbarSwap(player);
                    if (hotbarSwap != null) {
                        slotA = hotbarSwap[0]; // slot docelowy w hotbarze
                        slotB = hotbarSwap[1]; // slot źródłowy
                        ctx.playerController().windowClick(containerId, slotB, 0, ClickType.PICKUP, player);
                        currentStep = Step.SWAP_CLICK2;
                        ticksSinceLastClick = 0;
                        performedSwaps++;
                        return true;
                    }
                }

                // 3. Sprawdź sortowanie głównego ekwipunku (sloty 9-35)
                int[] invSwap = findInventorySwap(player);
                if (invSwap != null) {
                    slotA = invSwap[0];
                    slotB = invSwap[1];
                    ctx.playerController().windowClick(containerId, slotB, 0, ClickType.PICKUP, player);
                    currentStep = Step.SWAP_CLICK2;
                    ticksSinceLastClick = 0;
                    performedSwaps++;
                    return true;
                }

                // Wszystko posegregowane!
                stop();
                if (performedSwaps > 0) {
                    Helper.HELPER.logDirect("§a[AutoSort] Ekwipunek został pomyślnie posegregowany (połączono stacki i ułożono surowce)!");
                }
                return false;
            }

            case MERGE_CLICK2 -> {
                // Kursor trzyma slotB, klikamy w slotA aby scalić
                ctx.playerController().windowClick(containerId, slotA, 0, ClickType.PICKUP, player);
                ticksSinceLastClick = 0;
                if (!player.containerMenu.getCarried().isEmpty()) {
                    currentStep = Step.MERGE_RESTORE;
                } else {
                    currentStep = Step.IDLE;
                }
                return true;
            }

            case MERGE_RESTORE -> {
                // Odłóż resztkę z kursora z powrotem do slotB
                ctx.playerController().windowClick(containerId, slotB, 0, ClickType.PICKUP, player);
                ticksSinceLastClick = 0;
                currentStep = Step.IDLE;
                return true;
            }

            case SWAP_CLICK2 -> {
                // Kursor trzyma slotB, klikamy slotA (bierzemy zawartość A na kursor, w A ląduje B)
                ctx.playerController().windowClick(containerId, slotA, 0, ClickType.PICKUP, player);
                ticksSinceLastClick = 0;
                if (player.containerMenu.getCarried().isEmpty()) {
                    // slotA był pusty - odłożenie B opróżniło kursor! Nie klikamy pustym kursorem w B!
                    currentStep = Step.IDLE;
                } else {
                    currentStep = Step.SWAP_CLICK3;
                }
                return true;
            }

            case SWAP_CLICK3 -> {
                // Kursor trzyma dawną zawartość A, odkładamy do slotB
                if (!player.containerMenu.getCarried().isEmpty()) {
                    ctx.playerController().windowClick(containerId, slotB, 0, ClickType.PICKUP, player);
                }
                ticksSinceLastClick = 0;
                currentStep = Step.IDLE;
                return true;
            }
        }

        return false;
    }

    /**
     * Znajduje dwa sloty z tym samym przedmiotem, które można połączyć w jeden pełniejszy stack.
     */
    private static int[] findMergeableStacks(LocalPlayer player) {
        // Sprawdzamy sloty 9 do 44 (główny ekwipunek + hotbar)
        for (int i = 9; i <= 44; i++) {
            ItemStack stackI = getMenuSlotItem(player, i);
            if (stackI.isEmpty() || stackI.getCount() >= stackI.getMaxStackSize()) continue;

            for (int j = i + 1; j <= 44; j++) {
                ItemStack stackJ = getMenuSlotItem(player, j);
                if (stackJ.isEmpty()) continue;

                if (ItemStack.isSameItemSameComponents(stackI, stackJ)) {
                    return new int[]{i, j}; // Scal j do i
                }
            }
        }
        return null;
    }

    /**
     * Sprawdza organizację paska Hotbar (sloty 36-44):
     * - slot 36: najlepszy kilof
     * - slot 37: broń (miecz / topór)
     * - slot 38: wiadro z wodą (dla natychmiastowego MLG Clutch)
     * - slot 44: najlepsze jedzenie
     */
    /**
     * Sprawdza organizację paska Hotbar (sloty 36-44):
     * - slot 36: najlepszy kilof
     * - slot 37: broń (miecz / topór)
     * - slot 38: wiadro z wodą (dla natychmiastowego MLG Clutch)
     * - slot 44: najlepsze jedzenie
     *
     * Zabezpieczenie przed pętlą: zamienia TYLKO wtedy, gdy znaleziony przedmiot jest ŚCIŚLE LEPSZY
     * niż ten, który aktualnie znajduje się na danym slocie.
     */
    private static int[] findHotbarSwap(LocalPlayer player) {
        // 1. Sprawdź czy najlepszy kilof jest na slocie 36 (hotbar 0)
        int currentPickTier = getSlotPickaxeTier(player, 36);
        int bestPickSlot = -1;
        int bestPickTier = currentPickTier;

        for (int menuSlot = 9; menuSlot <= 44; menuSlot++) {
            if (menuSlot == 36) continue;
            int tier = getSlotPickaxeTier(player, menuSlot);
            if (tier > bestPickTier) {
                bestPickTier = tier;
                bestPickSlot = menuSlot;
            }
        }
        if (bestPickSlot != -1) {
            return new int[]{36, bestPickSlot};
        }

        // 2. Sprawdź broń na slocie 37 (hotbar 1)
        float currentWepDmg = getSlotWeaponDamage(player, 37);
        int bestWepSlot = -1;
        float bestWepDmg = currentWepDmg;

        for (int menuSlot = 9; menuSlot <= 44; menuSlot++) {
            if (menuSlot == 36 || menuSlot == 37) continue;
            float dmg = getSlotWeaponDamage(player, menuSlot);
            if (dmg > bestWepDmg) {
                bestWepDmg = dmg;
                bestWepSlot = menuSlot;
            }
        }
        if (bestWepSlot != -1) {
            return new int[]{37, bestWepSlot};
        }

        // 3. Sprawdź wiadro z wodą na slocie 38 (hotbar 2)
        if (!getMenuSlotItem(player, 38).is(Items.WATER_BUCKET)) {
            int waterBucketSlot = findWaterBucketMenuSlot(player);
            if (waterBucketSlot != -1 && waterBucketSlot != 38 && waterBucketSlot != 36 && waterBucketSlot != 37) {
                return new int[]{38, waterBucketSlot};
            }
        }

        // 4. Sprawdź jedzenie na slocie 44 (hotbar 8)
        int currentFoodNutr = getSlotFoodNutrition(player, 44);
        int bestFoodSlot = -1;
        int bestFoodNutr = currentFoodNutr;

        for (int menuSlot = 9; menuSlot <= 44; menuSlot++) {
            if (menuSlot == 36 || menuSlot == 37 || menuSlot == 38 || menuSlot == 44) continue;
            int nutr = getSlotFoodNutrition(player, menuSlot);
            if (nutr > bestFoodNutr) {
                bestFoodNutr = nutr;
                bestFoodSlot = menuSlot;
            }
        }
        if (bestFoodSlot != -1) {
            return new int[]{44, bestFoodSlot};
        }

        return null;
    }

    private static int getSlotPickaxeTier(LocalPlayer player, int menuSlot) {
        ItemStack stack = getMenuSlotItem(player, menuSlot);
        if (stack.getItem() instanceof PickaxeItem pick) {
            return getPickaxeTier(pick);
        }
        return -1;
    }

    private static float getSlotWeaponDamage(LocalPlayer player, int menuSlot) {
        ItemStack stack = getMenuSlotItem(player, menuSlot);
        if (stack.getItem() instanceof SwordItem || stack.getItem() instanceof AxeItem) {
            return getApproximateDamage(stack.getItem());
        }
        return -1.0f;
    }

    private static int getSlotFoodNutrition(LocalPlayer player, int menuSlot) {
        ItemStack stack = getMenuSlotItem(player, menuSlot);
        if (InventoryCleaner.isFood(stack)) {
            int nutrition = 1;
            if (stack.has(DataComponents.FOOD)) {
                nutrition = stack.get(DataComponents.FOOD).nutrition();
            }
            if (stack.is(Items.ENCHANTED_GOLDEN_APPLE)) nutrition = 100;
            else if (stack.is(Items.GOLDEN_APPLE)) nutrition = 50;
            return nutrition;
        }
        return -1;
    }

    private static int findWaterBucketMenuSlot(LocalPlayer player) {
        for (int menuSlot = 9; menuSlot <= 44; menuSlot++) {
            ItemStack stack = getMenuSlotItem(player, menuSlot);
            if (!stack.isEmpty() && stack.is(Items.WATER_BUCKET)) {
                return menuSlot;
            }
        }
        return -1;
    }

    /**
     * Sprawdza główny ekwipunek (sloty 9-35) i znajduje pierwszą parę do zamiany według priorytetu surowców.
     */
    private static int[] findInventorySwap(LocalPlayer player) {
        for (int i = 9; i < 35; i++) {
            ItemStack stackI = getMenuSlotItem(player, i);
            int scoreI = getItemScore(stackI);

            int bestJ = -1;
            int bestScoreJ = scoreI;

            for (int j = i + 1; j <= 35; j++) {
                ItemStack stackJ = getMenuSlotItem(player, j);
                int scoreJ = getItemScore(stackJ);

                if (scoreJ < bestScoreJ) {
                    bestScoreJ = scoreJ;
                    bestJ = j;
                }
            }

            if (bestJ != -1) {
                return new int[]{i, bestJ}; // Zamień i oraz bestJ
            }
        }
        return null;
    }

    public static int getItemScore(ItemStack stack) {
        if (stack == null || stack.isEmpty()) return 9999;
        Item item = stack.getItem();
        ResourceLocation loc = BuiltInRegistries.ITEM.getKey(item);
        String name = loc != null ? loc.getPath().toLowerCase(Locale.ROOT) : "";

        // Totemy i utility walki
        if (name.equals("totem_of_undying")) return 5;
        if (name.equals("water_bucket") || name.equals("bucket")) return 8;
        if (name.equals("enchanted_golden_apple")) return 10;
        if (name.equals("golden_apple")) return 15;
        if (name.equals("ender_pearl")) return 20;

        // Netherite
        if (name.contains("netherite_block")) return 30;
        if (name.contains("netherite_ingot") || name.contains("netherite_scrap") || name.contains("ancient_debris")) return 35;

        // Diamenty (zawsze na samym początku głównego ekwipunku)
        if (name.equals("diamond_block")) return 40;
        if (name.equals("diamond")) return 42;
        if (name.contains("diamond_ore")) return 44;

        // Szmaragdy
        if (name.equals("emerald_block")) return 48;
        if (name.equals("emerald")) return 50;
        if (name.contains("emerald_ore")) return 52;

        // Złoto
        if (name.contains("raw_gold_block")) return 58;
        if (name.equals("raw_gold")) return 60;
        if (name.equals("gold_block") || name.equals("gold_ingot")) return 62;
        if (name.contains("gold_ore")) return 64;

        // Żelazo
        if (name.contains("raw_iron_block")) return 68;
        if (name.equals("raw_iron")) return 70;
        if (name.equals("iron_block") || name.equals("iron_ingot")) return 72;
        if (name.contains("iron_ore")) return 74;

        // Lapis
        if (name.contains("lapis_block")) return 78;
        if (name.contains("lapis")) return 80;

        // Redstone
        if (name.contains("redstone_block")) return 82;
        if (name.contains("redstone")) return 84;

        // Miedź
        if (name.contains("raw_copper_block") || name.equals("raw_copper")) return 86;
        if (name.contains("copper_ingot") || name.contains("copper_block") || name.contains("copper_ore")) return 88;

        // Węgiel
        if (name.contains("coal_block")) return 90;
        if (name.contains("coal")) return 92;

        // Kwarc / Ametyst / Krzemień
        if (name.contains("quartz")) return 96;
        if (name.contains("amethyst")) return 98;
        if (name.contains("flint")) return 100;

        // Pozostałe surowce
        if (name.endsWith("_ore")) return 115;
        if (name.startsWith("raw_")) return 120;
        if (name.endsWith("_ingot") || name.endsWith("_nugget")) return 125;

        // Narzędzia / Broń / Zbroje
        if (InventoryCleaner.isPickaxeOrTool(stack)) return 150;

        // Jedzenie
        if (InventoryCleaner.isFood(stack)) return 200;

        // Bloki użytkowe
        if (name.contains("torch") || name.contains("lantern")) return 300;
        if (name.contains("log") || name.contains("wood") || name.contains("planks")) return 310;
        if (name.contains("obsidian")) return 320;
        if (name.contains("chest")) return 330;

        // Pozostałe przedmioty
        return 500 + (Math.abs(name.hashCode()) % 400);
    }

    private static int getPickaxeTier(PickaxeItem pick) {
        Item item = pick.asItem();
        if (item == Items.NETHERITE_PICKAXE) return 6;
        if (item == Items.DIAMOND_PICKAXE) return 5;
        if (item == Items.IRON_PICKAXE) return 4;
        if (item == Items.STONE_PICKAXE) return 3;
        if (item == Items.GOLDEN_PICKAXE) return 2;
        if (item == Items.WOODEN_PICKAXE) return 1;
        return 0;
    }

    private static float getApproximateDamage(Item item) {
        if (item == Items.NETHERITE_SWORD) return 8.0f;
        if (item == Items.DIAMOND_SWORD) return 7.0f;
        if (item == Items.NETHERITE_AXE) return 10.0f;
        if (item == Items.DIAMOND_AXE) return 9.0f;
        if (item == Items.IRON_SWORD) return 6.0f;
        if (item == Items.IRON_AXE) return 9.0f;
        if (item == Items.STONE_SWORD) return 5.0f;
        return 4.0f;
    }

    private static int findFirstEmptyMenuSlot(LocalPlayer player) {
        for (int menuSlot = 9; menuSlot <= 44; menuSlot++) {
            if (getMenuSlotItem(player, menuSlot).isEmpty()) {
                return menuSlot;
            }
        }
        return -1;
    }

    private static ItemStack getMenuSlotItem(LocalPlayer player, int menuSlot) {
        if (menuSlot >= 0 && menuSlot < player.inventoryMenu.slots.size()) {
            return player.inventoryMenu.getSlot(menuSlot).getItem();
        }
        return ItemStack.EMPTY;
    }
}
