package baritone.bypass;

import baritone.api.utils.IPlayerContext;
import baritone.utils.ToolSet;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.core.BlockPos;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.state.BlockState;

/**
 * Inteligentny menedżer narzędzi (AutoTool) z modułem ochrony trwałości (Durability Guard).
 *
 * Cechy:
 * 1. Automatycznie wybiera z hotbara najszybsze narzędzie dla wskazanego bloku.
 * 2. OCHRONA KILOFÓW (Durability Guard jak w Exosware):
 *    Nigdy nie dopuszcza do złamania cennego kilofa/narzędzia (np. Netherite/Diamond Fortune III).
 *    Jeśli trwałość narzędzia <= durabilityThreshold (domyślnie 10), narzędzie jest natychmiast omijane.
 * 3. GRIMAC-COMPLIANT: Zmiana slotu odbywa się wyłącznie na hotbarze poprzez standardowy mechanizm wyboru slotu,
 *    co odpowiada normalnemu przewijaniu kółkiem myszy i nie wysyła niedozwolonych pakietów klikania w GUI w locie.
 */
public final class AutoToolManager {

    private AutoToolManager() {}

    /**
     * Dobiera optymalne narzędzie i ustawia slot w ekwipunku gracza.
     *
     * @return true jeśli wybrano sprawne narzędzie lub bezpieczny slot; false jeśli wszystkie narzędzia są na wyczerpaniu
     */
    public static boolean selectBestTool(IPlayerContext ctx, BlockPos pos, BypassConfig config) {
        if (ctx == null || ctx.player() == null || ctx.world() == null || pos == null) {
            return false;
        }

        if (!config.autoTool) {
            return isUsable(ctx.player().getMainHandItem(), ctx.world().getBlockState(pos), config);
        }

        LocalPlayer player = ctx.player();
        Inventory inv = player.getInventory();
        BlockState state = ctx.world().getBlockState(pos);

        int bestSlot = -1;
        double bestSpeed = -1.0;

        for (int i = 0; i < 9; i++) {
            ItemStack stack = inv.getItem(i);
            if (!isUsable(stack, state, config)) {
                continue; // Never destroy diamonds with a hand/wooden pickaxe.
            }

            double speed = ToolSet.calculateSpeedVsBlock(stack, state);
            if (speed > bestSpeed) {
                bestSpeed = speed;
                bestSlot = i;
            }
        }

        if (bestSlot == -1) {
            return false;
        }
        inv.selected = bestSlot;
        ctx.playerController().syncHeldItem();
        return true;
    }

    static boolean isUsable(ItemStack stack, BlockState state, BypassConfig config) {
        if (state.requiresCorrectToolForDrops() && !stack.isCorrectToolForDrops(state)) {
            return false;
        }
        return !stack.isDamageableItem()
                || stack.getMaxDamage() - stack.getDamageValue() > config.durabilityThreshold;
    }
}
