package baritone.bypass;

import baritone.api.utils.IPlayerContext;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.core.component.DataComponents;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.inventory.ClickType;
import net.minecraft.world.item.*;

import java.util.*;

/**
 * Moduł czyszczenia ekwipunku ze śmieciowych bloków (AutoDrop / Trash Cleaner).
 *
 * Działa zgodnie z zasadą odwróconej białej listy (Whitelist):
 * Zostawia w ekwipunku TYLKO:
 *  1. Kilofy i narzędzia/broń (Pickaxes, Diggers, Swords, Armor itp.)
 *  2. Jedzenie (DataComponents.FOOD)
 *  3. Surowce (rudy, surowe metale: raw_iron, raw_gold, raw_copper, diamenty, złoto, żelazo, węgiel itp.)
 *  4. Przedmioty zaklęte lub z własną nazwą
 *
 * Wszystko inne (bruk/cobblestone, łupek/deepslate, tuff, ziemia, żwir, granit itp.)
 * jest natychmiast lub seryjnie wyrzucane z zachowaniem opóźnienia 100ms (2 ticki)
 * pod kątem niewykrywalności przez GrimAC / Custom GrimAC.
 */
public final class InventoryCleaner {

    private static long lastDropTime = 0L;
    /** Pacing 100ms (2 ticki) – bezpieczne na GrimAC (nie wywołuje BadPackets / spam click flag) */
    public static final long DROP_COOLDOWN_MS = 100L;

    private InventoryCleaner() {}

    /**
     * Sprawdza, czy dany przedmiot jest kilofem lub chronionym narzędziem / pancerzem.
     */
    public static boolean isPickaxeOrTool(ItemStack stack) {
        if (stack == null || stack.isEmpty()) return false;
        Item item = stack.getItem();
        if (item instanceof PickaxeItem) return true;
        if (item instanceof DiggerItem) return true; // Siekiery, łopaty, motyki
        if (item instanceof SwordItem) return true;
        if (item instanceof ArmorItem) return true;
        if (item instanceof ShieldItem) return true;
        if (item instanceof BowItem) return true;
        if (item instanceof CrossbowItem) return true;
        if (item instanceof ShearsItem) return true;
        if (item instanceof FishingRodItem) return true;
        if (item instanceof FlintAndSteelItem) return true;
        return stack.isDamageableItem(); // Dowolny przedmiot z paskiem trwałości (narzędzie/zbroja/elytra)
    }

    /**
     * Sprawdza, czy dany przedmiot jest jedzeniem.
     */
    public static boolean isFood(ItemStack stack) {
        if (stack == null || stack.isEmpty()) return false;
        return stack.has(DataComponents.FOOD);
    }

    /**
     * Sprawdza, czy dany przedmiot jest surowcem (ruda, minerał, sztabka, bryłka, blok surowca).
     */
    public static boolean isResource(ItemStack stack, Set<String> extraTargets) {
        if (stack == null || stack.isEmpty()) return false;

        // Przedmioty zaklęte lub nazwane są ZAWSZE chronione przed wyrzuceniem
        if (stack.isEnchanted() || stack.has(DataComponents.CUSTOM_NAME)) {
            return true;
        }

        ResourceLocation loc = BuiltInRegistries.ITEM.getKey(stack.getItem());
        if (loc == null) return false;
        String path = loc.getPath().toLowerCase(Locale.ROOT);

        // Dodatkowe cele podane dynamicznie (np. z BypassProcess lub celu kopania)
        if (extraTargets != null && extraTargets.contains(path)) {
            return true;
        }

        // 1. Surowe metale (np. raw_iron, raw_gold, raw_copper)
        if (path.startsWith("raw_")) {
            return true;
        }

        // 2. Rudy blokowe (np. iron_ore, deepslate_iron_ore, diamond_ore itp.)
        if (path.endsWith("_ore")) {
            return true;
        }

        // 3. Sztabki i bryłki (np. iron_ingot, gold_ingot, copper_ingot, netherite_ingot, gold_nugget, iron_nugget)
        if (path.endsWith("_ingot") || path.endsWith("_nugget")) {
            return true;
        }

        // 4. Minerały, kamienie szlachetne i surowce specjalne
        if (path.equals("diamond")
                || path.equals("emerald")
                || path.equals("lapis_lazuli")
                || path.equals("redstone")
                || path.equals("coal")
                || path.equals("charcoal")
                || path.equals("quartz")
                || path.equals("amethyst_shard")
                || path.equals("netherite_scrap")
                || path.equals("ancient_debris")) {
            return true;
        }

        // 5. Bloki surowców (np. raw_iron_block, iron_block, diamond_block itp.)
        if (path.endsWith("_block") && (path.contains("iron") || path.contains("gold")
                || path.contains("diamond") || path.contains("emerald") || path.contains("lapis")
                || path.contains("redstone") || path.contains("copper") || path.contains("netherite")
                || path.contains("coal") || path.contains("raw_"))) {
            return true;
        }

        // 6. Płody rolne i nasiona z farmy — chronione, wyrzuca je tylko HomeProcess przy skrzynce
        Item farmItem = stack.getItem();
        if (farmItem == Items.WHEAT
                || farmItem == Items.WHEAT_SEEDS
                || farmItem == Items.CARROT
                || farmItem == Items.POTATO
                || farmItem == Items.POISONOUS_POTATO
                || farmItem == Items.BEETROOT
                || farmItem == Items.BEETROOT_SEEDS
                || farmItem == Items.MELON_SLICE
                || farmItem == Items.MELON_SEEDS
                || farmItem == Items.PUMPKIN_SEEDS
                || farmItem == Items.NETHER_WART
                || farmItem == Items.COCOA_BEANS
                || farmItem == Items.TORCHFLOWER_SEEDS
                || farmItem == Items.TORCHFLOWER
                || farmItem == Items.PITCHER_POD
                || farmItem == Items.SWEET_BERRIES
                || farmItem == Items.GLOW_BERRIES) {
            return true;
        }
        // Bloki farmowe jako itemy (trzcina, bambus, kaktus, dynia, melon)
        if (path.equals("sugar_cane") || path.equals("bamboo") || path.equals("cactus")
                || path.equals("melon") || path.equals("pumpkin")) {
            return true;
        }

        return false;

    }

    /**
     * Sprawdza, czy dany przedmiot jest niezbędnym przedmiotem użytkowym / survivalowym
     * (wiadro z wodą do MLG, puste wiadro, totem, perły, pochodnie, mikstury, strzały itp.).
     */
    public static boolean isUtilityOrSurvivalItem(ItemStack stack) {
        if (stack == null || stack.isEmpty()) return false;
        Item item = stack.getItem();

        // 1. Wszystkie wiadra (woda do MLG clutch, puste wiadro, lawa, mleko)
        if (item instanceof BucketItem || item == Items.BUCKET || item == Items.WATER_BUCKET
                || item == Items.LAVA_BUCKET || item == Items.MILK_BUCKET) {
            return true;
        }

        // 2. Przedmioty ratunkowe, defensywne i do przemieszczania
        if (item == Items.TOTEM_OF_UNDYING || item == Items.ENDER_PEARL || item == Items.FIREWORK_ROCKET
                || item == Items.ELYTRA) {
            return true;
        }

        // 3. Oświetlenie
        if (item == Items.TORCH || item == Items.SOUL_TORCH || item == Items.LANTERN || item == Items.SOUL_LANTERN) {
            return true;
        }

        // 4. Amunicja i mikstury
        if (item instanceof ArrowItem || item == Items.ARROW || item == Items.SPECTRAL_ARROW || item == Items.TIPPED_ARROW
                || item == Items.POTION || item == Items.SPLASH_POTION || item == Items.LINGERING_POTION) {
            return true;
        }

        // 5. Bloki użytkowe i kontenery
        if (item == Items.CHEST || item == Items.ENDER_CHEST || item == Items.BARREL
                || item == Items.CRAFTING_TABLE || item == Items.FURNACE || item == Items.BLAST_FURNACE
                || item == Items.SMOKER || item == Items.ANVIL || item == Items.RESPAWN_ANCHOR || item instanceof BedItem) {
            return true;
        }

        ResourceLocation loc = BuiltInRegistries.ITEM.getKey(item);
        if (loc != null) {
            String path = loc.getPath();
            if (path.contains("shulker_box") || path.contains("bed")) {
                return true;
            }
        }

        return false;
    }

    /**
     * Sprawdza czy przedmiot podlega ochronie (nie może zostać wyrzucony).
     * Chronione: kilofy/narzędzia, jedzenie, surowce oraz przedmioty użytkowe (wiadra, totemy, perły).
     */
    public static boolean isProtected(ItemStack stack, Set<String> extraTargets) {
        if (stack == null || stack.isEmpty()) return true;
        return isPickaxeOrTool(stack) || isFood(stack) || isResource(stack, extraTargets) || isUtilityOrSurvivalItem(stack);
    }

    /**
     * Zwraca wagę / priorytet wartości surowca (wyższa liczba = cenniejszy surowiec).
     * Służy do dynamicznej alokacji slotów dla cenniejszych rud (np. diamenty > żelazo > redstone > węgiel).
     * Przedmioty chronione (narzędzia, jedzenie, wiadra, totemy) mają priorytet 9999 (nigdy nie są poświęcane).
     */
    public static int getResourcePriority(ItemStack stack) {
        if (stack == null || stack.isEmpty()) return -1;
        if (isPickaxeOrTool(stack) || isFood(stack) || isUtilityOrSurvivalItem(stack)) {
            return 9999; // ZAWSZE CHRONIONE
        }
        if (stack.isEnchanted() || stack.has(DataComponents.CUSTOM_NAME)) {
            return 9999;
        }

        Item item = stack.getItem();
        ResourceLocation loc = BuiltInRegistries.ITEM.getKey(item);
        String name = loc != null ? loc.getPath().toLowerCase(Locale.ROOT) : "";

        // Netherite / Ancient Debris
        if (name.contains("netherite") || name.contains("ancient_debris")) return 100;

        // Diamenty
        if (name.contains("diamond")) return 90;

        // Szmaragdy
        if (name.contains("emerald")) return 80;

        // Złoto
        if (name.contains("gold")) return 70;

        // Żelazo
        if (name.contains("iron")) return 60;

        // Lapis
        if (name.contains("lapis")) return 50;

        // Redstone
        if (name.contains("redstone")) return 40;

        // Miedź
        if (name.contains("copper")) return 30;

        // Węgiel
        if (name.contains("coal")) return 20;

        // Kwarc, ametyst, krzemień
        if (name.contains("quartz") || name.contains("amethyst") || name.contains("flint")) return 15;

        // Inne rudy / surowce
        if (name.endsWith("_ore") || name.startsWith("raw_") || name.endsWith("_raw_block") || name.endsWith("_ingot") || name.endsWith("_nugget")) {
            return 10;
        }

        // Pozostałe bloki / śmieci (bruk, łupek itp.)
        return 0;
    }

    /**
     * Sprawdza, czy w ekwipunku istnieją co najmniej dwa niepełne stacki TEGO SAMEGO przedmiotu,
     * które można ze sobą scalić, aby uwolnić cały slot.
     */
    public static boolean hasMergeableStacks(LocalPlayer player) {
        if (player == null) return false;
        var inv = player.getInventory();
        for (int i = 0; i < 36; i++) {
            ItemStack stackI = inv.getItem(i);
            if (stackI.isEmpty() || stackI.getCount() >= stackI.getMaxStackSize()) continue;
            for (int j = i + 1; j < 36; j++) {
                ItemStack stackJ = inv.getItem(j);
                if (stackJ.isEmpty()) continue;
                if (ItemStack.isSameItemSameComponents(stackI, stackJ)) {
                    return true;
                }
            }
        }
        return false;
    }

    /**
     * Sprawdza czy dany przedmiot jest śmieciem do wyrzucenia.
     */
    public static boolean isTrash(ItemStack stack, BypassConfig config, Set<String> extraTargets) {
        if (stack == null || stack.isEmpty()) return false;
        // Jeśli jest na liście chronionych (narzędzie, jedzenie, surowiec) -> NIE jest śmieciem
        if (isProtected(stack, extraTargets)) {
            return false;
        }
        // Wszystko inne (bruk, łupek, ziemia, żwir itp.) to śmieć!
        return true;
    }

    /**
     * Zbiera nazwy surowców obecnych już w ekwipunku gracza oraz w konfiguracji Bypass,
     * aby bot wiedział, jakie surowce ma chronić i zbierać.
     */
    public static Set<String> collectResourceTargets(LocalPlayer player, BypassProcess bypassProcess) {
        Set<String> targets = new HashSet<>();
        if (bypassProcess != null && bypassProcess.isActive()) {
            targets.addAll(bypassProcess.getTargetDropItems());
        }
        if (player != null) {
            Inventory inv = player.getInventory();
            for (int i = 0; i < 36; i++) {
                ItemStack stack = inv.getItem(i);
                if (!stack.isEmpty() && isResource(stack, null)) {
                    ResourceLocation loc = BuiltInRegistries.ITEM.getKey(stack.getItem());
                    if (loc != null) {
                        targets.add(loc.getPath().toLowerCase(Locale.ROOT));
                    }
                }
            }
        }
        return targets;
    }

    /**
     * Liczba pustych slotów w ekwipunku (0..35).
     */
    public static int getFreeSlots(LocalPlayer player) {
        if (player == null) return 0;
        Inventory inv = player.getInventory();
        int free = 0;
        for (int i = 0; i < 36; i++) {
            if (inv.getItem(i).isEmpty()) {
                free++;
            }
        }
        return free;
    }

    /**
     * Sprawdza, czy w ekwipunku znajdują się jakiekolwiek śmieci do wyrzucenia.
     */
    public static boolean hasTrash(LocalPlayer player, BypassConfig config, Set<String> extraTargets) {
        if (player == null) return false;
        Inventory inv = player.getInventory();
        // Sloty głównego plecaka 9..35
        for (int i = 9; i < 36; i++) {
            ItemStack stack = inv.getItem(i);
            if (!stack.isEmpty() && isTrash(stack, config, extraTargets)) {
                return true;
            }
        }
        // Sloty paska hotbar 0..8 (oprócz aktualnie wybranego)
        for (int i = 0; i < 9; i++) {
            if (i == inv.selected) continue;
            ItemStack stack = inv.getItem(i);
            if (!stack.isEmpty() && isTrash(stack, config, extraTargets)) {
                return true;
            }
        }
        return false;
    }

    /**
     * Zlicza ile stacków śmieci znajduje się w ekwipunku.
     */
    public static int countTrash(LocalPlayer player, BypassConfig config, Set<String> extraTargets) {
        if (player == null) return 0;
        Inventory inv = player.getInventory();
        int count = 0;
        for (int i = 9; i < 36; i++) {
            ItemStack stack = inv.getItem(i);
            if (!stack.isEmpty() && isTrash(stack, config, extraTargets)) {
                count++;
            }
        }
        for (int i = 0; i < 9; i++) {
            if (i == inv.selected) continue;
            ItemStack stack = inv.getItem(i);
            if (!stack.isEmpty() && isTrash(stack, config, extraTargets)) {
                count++;
            }
        }
        return count;
    }

    /**
     * Wyrzuca kolejny stack śmieci z ekwipunku (GrimAC-safe: 1 stack co 100ms).
     *
     * @return true jeśli wyrzucono stack, false jeśli brak śmieci lub trwa cooldown
     */
    public static boolean dropNextTrash(IPlayerContext ctx, BypassConfig config, Set<String> extraTargets) {
        if (ctx == null || ctx.player() == null) return false;
        if (System.currentTimeMillis() - lastDropTime < DROP_COOLDOWN_MS) {
            return false;
        }

        LocalPlayer player = ctx.player();
        Inventory inv = player.getInventory();
        if (player.containerMenu != player.inventoryMenu) {
            return false;
        }

        // 1. Najpierw szukamy w głównym plecaku (sloty 9-35)
        for (int i = 9; i < 36; i++) {
            ItemStack stack = inv.getItem(i);
            if (stack.isEmpty()) continue;

            if (isTrash(stack, config, extraTargets)) {
                if (i < player.inventoryMenu.slots.size()) {
                    ctx.playerController().windowClick(player.inventoryMenu.containerId, i, 1, ClickType.THROW, player);
                    lastDropTime = System.currentTimeMillis();
                    return true;
                }
            }
        }

        // 2. Jeśli plecak czysty, sprawdzamy paski podręczne (hotbar 0-8), omijając trzymany slot
        for (int i = 0; i < 9; i++) {
            if (i == inv.selected) continue;

            ItemStack stack = inv.getItem(i);
            if (stack.isEmpty()) continue;

            if (isTrash(stack, config, extraTargets)) {
                int menuSlot = 36 + i;
                if (menuSlot < player.inventoryMenu.slots.size()) {
                    ctx.playerController().windowClick(player.inventoryMenu.containerId, menuSlot, 1, ClickType.THROW, player);
                    lastDropTime = System.currentTimeMillis();
                    return true;
                }
            }
        }

        return false;
    }

    /**
     * Zgodność z dotychczasowym API: sprawdza czy ekwipunek jest pełny i jeśli tak, wyrzuca śmieci.
     */
    public static boolean cleanIfFull(IPlayerContext ctx, BypassConfig config) {
        return cleanIfFull(ctx, config, null);
    }

    /**
     * Sprawdza czy ekwipunek jest pełny i wyrzuca kolejny stack śmieci z uwzględnieniem chronionych surowców.
     */
    public static boolean cleanIfFull(IPlayerContext ctx, BypassConfig config, Set<String> extraTargets) {
        if (config != null && !config.autoDropTrash) {
            return false;
        }
        if (ctx == null || ctx.player() == null) {
            return false;
        }
        LocalPlayer player = ctx.player();
        int free = getFreeSlots(player);
        if (free > 1 && !hasTrash(player, config, extraTargets)) {
            return false;
        }
        return dropNextTrash(ctx, config, extraTargets);
    }

    public static boolean canFit(IPlayerContext ctx, ItemStack incoming) {
        if (ctx == null || ctx.player() == null) return false;
        for (int i = 0; i < 36; i++) {
            if (canAccept(ctx.player().getInventory().getItem(i), incoming)) {
                return true;
            }
        }
        // Vanilla can also merge with a matching offhand stack.
        return !ctx.player().getOffhandItem().isEmpty()
                && canAccept(ctx.player().getOffhandItem(), incoming);
    }

    public static boolean canAccept(ItemStack existing, ItemStack incoming) {
        return existing.isEmpty() || (ItemStack.isSameItemSameComponents(existing, incoming)
                && existing.getCount() < existing.getMaxStackSize());
    }

    /**
     * Konwertuje indeks slotu gracza (0..35: 0-8 hotbar, 9-35 plecak)
     * na numer slotu w InventoryMenu (9..35 plecak, 36..44 hotbar).
     */
    public static int invToMenuSlot(int invSlot) {
        if (invSlot < 9) {
            return 36 + invSlot;
        }
        return invSlot;
    }

    /**
     * Konwertuje numer slotu w InventoryMenu na indeks slotu gracza.
     */
    public static int menuToInvSlot(int menuSlot) {
        if (menuSlot >= 36 && menuSlot <= 44) {
            return menuSlot - 36;
        }
        return menuSlot;
    }

    /**
     * Zwraca listę identyfikatorów slotów w menu (InventoryMenu), które są puste i mogą zostać zablokowane.
     * Uwzględnia zarówno główny plecak (sloty 9-35), jak i pasek podręczny (sloty 36-44).
     */
    public static List<Integer> collectEmptyMenuSlots(LocalPlayer player) {
        List<Integer> emptySlots = new ArrayList<>();
        if (player == null) return emptySlots;
        Inventory inv = player.getInventory();

        // 1. Sloty głównego plecaka 9..35
        for (int i = 9; i < 36; i++) {
            if (inv.getItem(i).isEmpty()) {
                emptySlots.add(i);
            }
        }
        // 2. Pasek podręczny hotbar 0..8
        for (int i = 0; i < 9; i++) {
            if (inv.getItem(i).isEmpty()) {
                emptySlots.add(36 + i);
            }
        }
        return emptySlots;
    }

    /**
     * Znajduje najlepszy slot w ekwipunku gracza zawierający surowiec (priorytetowo surowe żelazo / raw_iron),
     * który można rozdzielić do pustych slotów (minimum 2 sztuki w stacku).
     *
     * @return indeks slotu gracza (0..35) lub -1 jeśli brak odpowiedniego surowca
     */
    public static int findBestLockSourceSlot(LocalPlayer player, String preferredItem, Set<String> extraTargets) {
        if (player == null) return -1;
        Inventory inv = player.getInventory();

        int bestSlot = -1;
        int bestCount = 1; // Wymagamy minimum 2 sztuk do rozdzielenia

        String pref = (preferredItem != null && !preferredItem.isBlank()) ? preferredItem.toLowerCase(Locale.ROOT) : "raw_iron";

        // 1. Priorytet 1: Preferowany surowiec (domyślnie raw_iron)
        for (int i = 0; i < 36; i++) {
            ItemStack stack = inv.getItem(i);
            if (stack.isEmpty() || stack.getCount() < 2) continue;
            ResourceLocation loc = BuiltInRegistries.ITEM.getKey(stack.getItem());
            if (loc != null && loc.getPath().toLowerCase(Locale.ROOT).equals(pref)) {
                if (stack.getCount() > bestCount) {
                    bestCount = stack.getCount();
                    bestSlot = i;
                }
            }
        }
        if (bestSlot != -1) return bestSlot;

        // 2. Priorytet 2: Sztabka żelaza (iron_ingot) jeśli brak surowego żelaza
        for (int i = 0; i < 36; i++) {
            ItemStack stack = inv.getItem(i);
            if (stack.isEmpty() || stack.getCount() < 2) continue;
            ResourceLocation loc = BuiltInRegistries.ITEM.getKey(stack.getItem());
            if (loc != null && loc.getPath().equalsIgnoreCase("iron_ingot")) {
                if (stack.getCount() > bestCount) {
                    bestCount = stack.getCount();
                    bestSlot = i;
                }
            }
        }
        if (bestSlot != -1) return bestSlot;

        // 3. Priorytet 3: Dowolny inny surowiec (np. raw_gold, raw_copper, diamond itp.)
        for (int i = 0; i < 36; i++) {
            ItemStack stack = inv.getItem(i);
            if (stack.isEmpty() || stack.getCount() < 2) continue;
            if (isResource(stack, extraTargets) && !stack.isDamageableItem()) {
                if (stack.getCount() > bestCount) {
                    bestCount = stack.getCount();
                    bestSlot = i;
                }
            }
        }
        return bestSlot;
    }

    /**
     * Sprawdza, czy w ekwipunku są puste sloty oraz surowiec do ich zablokowania.
     */
    public static boolean canLockSlots(LocalPlayer player, String preferredItem, Set<String> extraTargets) {
        if (player == null) return false;
        if (getFreeSlots(player) == 0) return false;
        return findBestLockSourceSlot(player, preferredItem, extraTargets) != -1;
    }
}

