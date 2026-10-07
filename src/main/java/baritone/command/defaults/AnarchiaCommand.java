package baritone.command.defaults;

import baritone.Baritone;
import baritone.api.IBaritone;
import baritone.api.command.Command;
import baritone.api.command.argument.IArgConsumer;
import baritone.api.command.exception.CommandException;
import baritone.api.utils.SettingsUtil;
import com.astra.revolution.gui.BaritoneBypassScreen;

import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.stream.Stream;

/**
 * Komenda #anarchia
 * #anarchia on   -> Włącza tryb Anarchia (AutoDrop + AutoSort + AutoLock + AutoLog <6 serc + FallProtect >5 blk)
 * #anarchia off  -> Wyłącza tryb Anarchia
 * #anarchia      -> Pokazuje aktualny status trybu Anarchia
 */
public class AnarchiaCommand extends Command {

    public AnarchiaCommand(IBaritone baritone) {
        super(baritone, "anarchia", "anarchy");
    }

    @Override
    public void execute(String label, IArgConsumer args) throws CommandException {
        boolean active = Baritone.settings().anarchiaMode.value;

        if (!args.hasAny()) {
            logDirect("§6=== TRYB ANARCHIA (Baritone) ===");
            logDirect(String.format(Locale.ROOT,
                    "§fStatus: %s",
                    active ? "§aWŁĄCZONY (ON)" : "§cWYŁĄCZONY (OFF)"));
            logDirect("§fFunkcje trybu Anarchia:");
            logDirect("  §7- §eAutoDrop Trash§7: Automatyczne wyrzucanie śmieci (bruk, ziemia itp.)");
            logDirect("  §7- §eAutoSort Inventory§7: Porządkowanie i segregacja ekwipunku");
            logDirect("  §7- §eAutoLock Resource§7: Blokada slotów przed przypadkową utratą surowców");
            logDirect(String.format(Locale.ROOT,
                    "  §7- §eAutoLog Health§7: Natychmiastowe rozłączenie gdy zdrowie < §c%.1f serc §7(%.1f HP)",
                    Baritone.settings().disconnectHealthHearts.value, Baritone.settings().disconnectHealthHearts.value * 2.0));
            logDirect(String.format(Locale.ROOT,
                    "  §7- §eFallProtect§7: Rozłączenie przy upadku > §e%.1f bloków §7(gdy brak MLG water)",
                    Baritone.settings().disconnectFallDistance.value));
            logDirect("§fUżycie:");
            logDirect("  §e#anarchia on        §7- włącza pełny tryb Anarchia");
            logDirect("  §e#anarchia off       §7- wyłącza tryb Anarchia");
            return;
        }

        String first = args.getString().toLowerCase(Locale.ROOT);

        if (first.equals("on") || first.equals("true") || first.equals("enable") || first.equals("1")) {
            Baritone.settings().anarchiaMode.value = true;
            Baritone.settings().autoDropTrash.value = true;
            Baritone.settings().autoSortInventory.value = true;
            Baritone.settings().autoLockResource.value = true;
            Baritone.settings().disconnectOnLowHealth.value = true;
            Baritone.settings().disconnectHealthHearts.value = 6.0D;
            Baritone.settings().disconnectOnFall.value = true;
            Baritone.settings().disconnectFallDistance.value = 5.0D;

            BaritoneBypassScreen.anarchiaMode = true;
            BaritoneBypassScreen.autoDropTrash = true;
            BaritoneBypassScreen.autoSortInventory = true;
            BaritoneBypassScreen.itemLockProtection = true;
            BaritoneBypassScreen.disconnectOnLowHealth = true;
            BaritoneBypassScreen.disconnectHealthHearts = 6.0f;
            BaritoneBypassScreen.disconnectOnFall = true;
            BaritoneBypassScreen.disconnectFallDistance = 5.0f;

            SettingsUtil.save(Baritone.settings());
            logDirect("§6§l[Anarchia] §aWłączono tryb Anarchia! §7(AutoDrop ON, AutoSort ON, AutoLock ON, AutoLog <6 serc ON, FallProtect >5 kratek ON)");
            return;
        }

        if (first.equals("off") || first.equals("false") || first.equals("disable") || first.equals("0")) {
            Baritone.settings().anarchiaMode.value = false;
            Baritone.settings().autoDropTrash.value = false;
            Baritone.settings().autoSortInventory.value = false;
            Baritone.settings().autoLockResource.value = false;

            BaritoneBypassScreen.anarchiaMode = false;
            BaritoneBypassScreen.autoDropTrash = false;
            BaritoneBypassScreen.autoSortInventory = false;
            BaritoneBypassScreen.itemLockProtection = false;

            SettingsUtil.save(Baritone.settings());
            logDirect("§6§l[Anarchia] §cWyłączono tryb Anarchia (AutoDrop OFF, AutoSort OFF, AutoLock OFF).");
            return;
        }

        logDirect("§cNieznany argument. Użyj: #anarchia on | #anarchia off");
    }

    @Override
    public Stream<String> tabComplete(String label, IArgConsumer args) {
        if (args.hasExactlyOne()) {
            return Stream.of("on", "off");
        }
        return Stream.empty();
    }

    @Override
    public String getShortDesc() {
        return "Przełącza tryb Anarchia (AutoDrop + AutoSort + AutoLock + AutoLog <6 serc + FallProtect)";
    }

    @Override
    public List<String> getLongDesc() {
        return Arrays.asList(
                "Komenda zarządza trybem Anarchia.",
                "",
                "Użycie:",
                "> #anarchia       - Wyświetla aktualny stan modułów trybu Anarchia",
                "> #anarchia on    - Włącza AutoDrop, AutoSort, AutoLock, AutoLog <6 serc i FallProtect >5 bloków",
                "> #anarchia off   - Wyłącza moduły trybu Anarchia"
        );
    }
}
