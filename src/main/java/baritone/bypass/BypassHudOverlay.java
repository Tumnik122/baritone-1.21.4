package baritone.bypass;

import baritone.Baritone;
import baritone.api.BaritoneAPI;
import baritone.api.IBaritone;
import net.minecraft.client.DeltaTracker;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;

import java.util.Locale;

/**
 * VISUALS — Overlay HUD dla procesu #bypass.
 * Wyświetla elegancki półprzezroczysty panel z parametrami bota:
 * stan, wykopane rudy (Diamond, Gold, itp.), XYZ, HP, Food, Uptime, rotację i status Grim GCD.
 */
public final class BypassHudOverlay {

    private BypassHudOverlay() {}

    public static void render(GuiGraphics drawContext, DeltaTracker deltaTracker) {
        Minecraft mc = Minecraft.getInstance();
        if (mc.options.hideGui || mc.player == null) return;

        IBaritone baritone = BaritoneAPI.getProvider().getPrimaryBaritone();
        if (!(baritone instanceof Baritone baritoneImpl)) return;

        BypassProcess process = baritoneImpl.getBypassProcess();
        if (process == null || !process.isActive()) return;

        int x = 10;
        int y = 10;
        int width = 175;
        int height = 80;

        // Tło — półprzezroczysty prostokąt z akcentem
        drawContext.fill(x - 4, y - 4, x + width, y + height, 0x90000000);
        drawContext.fill(x - 4, y - 4, x + width, y - 3, 0x8000FF88);

        // 1. Stan
        drawContext.drawString(mc.font, "§a[BYPASS] §f" + process.getState().name(), x, y, 0xFFFFFF, true);

        // 2. Rudy wykopane
        y += 12;
        drawContext.drawString(mc.font,
                "§bDiamond: §f" + process.getDiamondCount() + " §eGold: §f" + process.getGoldCount() + " §7(All: §f" + process.getOresMined() + "§7)",
                x, y, 0xFFFFFF, true);

        // 3. Pozycja
        y += 12;
        drawContext.drawString(mc.font,
                "§7XYZ: §f" + (int) mc.player.getX() + " " + (int) mc.player.getY() + " " + (int) mc.player.getZ() +
                " §8[§7" + process.getTunnelDirection() + "§8]",
                x, y, 0xFFFFFF, true);

        // 4. HP i Food
        y += 12;
        float hp = mc.player.getHealth();
        int food = mc.player.getFoodData().getFoodLevel();
        drawContext.drawString(mc.font,
                String.format(Locale.ROOT, "§cHP: §f%.1f §6Food: §f%d", hp, food),
                x, y, 0xFFFFFF, true);

        // 5. Uptime
        y += 12;
        long uptimeSec = (System.currentTimeMillis() - process.getStartTime()) / 1000L;
        drawContext.drawString(mc.font,
                "§7Uptime: §f" + String.format(Locale.ROOT, "%02d:%02d", uptimeSec / 60, uptimeSec % 60),
                x, y, 0xFFFFFF, true);

        // 6. Grim status & GCD
        y += 12;
        drawContext.drawString(mc.font,
                String.format(Locale.ROOT, "§aGCD ✓ §7rot: §f%.1f/%.1f", mc.player.getYRot(), mc.player.getXRot()),
                x, y, 0xFFFFFF, true);
    }
}
