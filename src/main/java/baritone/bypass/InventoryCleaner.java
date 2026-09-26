package baritone.bypass;

import baritone.api.utils.IPlayerContext;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.inventory.ClickType;
import net.minecraft.world.item.ItemStack;

import java.util.Locale;

/**
 * Moduł czyszczenia zapchanego ekwipunku ze śmieciowych bloków (AutoDrop / Trash Cleaner).
 *
 * Zapobiega sytuacji, w której wykopane diamenty/złoto przepadają na ziemi po wyczerpaniu
 * 5-sekundowego limitu podchodzenia (COLLECTING_DROP) przez brak wolnych slotów w ekwipunku.
 */
public final class InventoryCleaner {

    private static long lastDropTime = 0L;
    private static final long DROP_COOLDOWN_MS = 1500L; // Ochrona przed spamem pakietów

    private InventoryCleaner() {}

    /**
     * Sprawdza czy ekwipunek jest pełny i jeśli tak, wyrzuca pojedynczy stack śmieci (np. bruku/tufu).
     *
     * @return true jeśli wyrzucono stack śmieci
     */
    public static boolean cleanIfFull(IPlayerContext ctx, BypassConfig config) {
        if (!config.autoDropTrash || ctx == null || ctx.player() == null) {
            return false;
        }

        if (System.currentTimeMillis() - lastDropTime < DROP_COOLDOWN_MS) {
            return false;
        }

        LocalPlayer player = ctx.player();
        Inventory inv = player.getInventory();
        if (player.containerMenu != player.inventoryMenu) {
            return false; // Do not send inventory clicks to a chest/other open container.
        }

        int freeSlots = 0;
        // Hotbar slots can receive pickups too.
        for (int i = 0; i < 36; i++) {
            if (inv.getItem(i).isEmpty()) {
                freeSlots++;
            }
        }

        if (freeSlots > 0) {
            return false;
        }

        // Szukamy pierwszego stacka śmieci do wyrzucenia
        for (int i = 9; i < 36; i++) {
            ItemStack stack = inv.getItem(i);
            if (stack.isEmpty()) continue;

            ResourceLocation loc = BuiltInRegistries.ITEM.getKey(stack.getItem());
            if (loc == null) continue;

            String path = loc.getPath().toLowerCase(Locale.ROOT);
            if (config.trashBlocks.contains(path)) {
                // Wyrzucamy cały stack: containerId, slotId, mouseButton=1 (throw all), ClickType.THROW
                // InventoryMenu indices 9..35 map directly to the main inventory.
                if (i >= player.inventoryMenu.slots.size()) {
                    return false;
                }
                ctx.playerController().windowClick(player.inventoryMenu.containerId, i, 1, ClickType.THROW, player);
                lastDropTime = System.currentTimeMillis();
                return true;
            }
        }

        return false;
    }

    static boolean canFit(IPlayerContext ctx, ItemStack incoming) {
        for (int i = 0; i < 36; i++) {
            if (canAccept(ctx.player().getInventory().getItem(i), incoming)) {
                return true;
            }
        }
        // Vanilla can also merge with a matching offhand stack.
        return !ctx.player().getOffhandItem().isEmpty()
                && canAccept(ctx.player().getOffhandItem(), incoming);
    }

    static boolean canAccept(ItemStack existing, ItemStack incoming) {
        return existing.isEmpty() || (ItemStack.isSameItemSameComponents(existing, incoming)
                && existing.getCount() < existing.getMaxStackSize());
    }
}
