package baritone.command.defaults;

import baritone.Baritone;
import baritone.api.IBaritone;
import baritone.api.command.Command;
import baritone.api.command.argument.IArgConsumer;
import baritone.api.command.exception.CommandException;
import baritone.api.utils.SettingsUtil;

import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.stream.Stream;

/**
 * Komenda #autologout / #logout do zarządzania zabezpieczeniem auto-rozłączania przy niskim zdrowiu.
 *
 * Użycie:
 * #autologout            -> Pokazuje aktualny stan i próg w sercach
 * #autologout on         -> Włącza auto-logout
 * #autologout off        -> Wyłącza auto-logout
 * #autologout <serca>    -> Ustawia próg w sercach (np. 3 lub 4.5) i włącza ochronę
 */
public class AutoLogoutCommand extends Command {

    public AutoLogoutCommand(IBaritone baritone) {
        super(baritone, "autologout", "logout");
    }

    @Override
    public void execute(String label, IArgConsumer args) throws CommandException {
        boolean currentActive = Baritone.settings().disconnectOnLowHealth.value || Baritone.settings().autoLogout.value;
        double currentHearts = Baritone.settings().disconnectHealthHearts.value;

        if (!args.hasAny()) {
            logDirect("§b=== AUTOLOGOUT / AUTO-DISCONNECT ===");
            logDirect(String.format(Locale.ROOT,
                    "§fStatus: %s",
                    currentActive ? "§aWŁĄCZONY (ON)" : "§cWYŁĄCZONY (OFF)"));
            logDirect(String.format(Locale.ROOT,
                    "§fPróg rozłączenia: §e%.1f serc §7(czyli %.1f HP - uwaga: 1 serce = 2 HP)",
                    currentHearts, currentHearts * 2.0));
            logDirect("§fGdy zdrowie spadnie poniżej tego progu:");
            logDirect("  §7- Gracz zostaje natychmiast rozłączony z serwera");
            logDirect("  §7- Opcja automatycznie przestawia się na §cOFF§7, aby po ponownym wejściu nie wyrzucało w pętli!");
            logDirect("§fUżycie:");
            logDirect("  §e#autologout on        §7- włącza auto-logout");
            logDirect("  §e#autologout off       §7- wyłącza auto-logout");
            logDirect("  §e#autologout <serca>   §7- ustawia próg (np. #autologout 3) i włącza");
            logDirect("  §7Działa również przez #settings: §e#set autoLogout true §7lub §e#set disconnectOnLowHealth true");
            return;
        }

        String first = args.getString().toLowerCase(Locale.ROOT);

        if (first.equals("on") || first.equals("true") || first.equals("enable") || first.equals("1")) {
            Baritone.settings().disconnectOnLowHealth.value = true;
            Baritone.settings().autoLogout.value = true;
            SettingsUtil.save(Baritone.settings());
            logDirect(String.format(Locale.ROOT,
                    "§a[AutoLogout] WŁĄCZONO ochronę. Bot rozłączy się, gdy zdrowie spadnie poniżej %.1f serc (%.1f HP).",
                    currentHearts, currentHearts * 2.0));
            logDirect("§7Po rozłączeniu opcja automatycznie wyłączy się na OFF, zapobiegając pętli przy powrocie.");
            return;
        }

        if (first.equals("off") || first.equals("false") || first.equals("disable") || first.equals("0")) {
            Baritone.settings().disconnectOnLowHealth.value = false;
            Baritone.settings().autoLogout.value = false;
            SettingsUtil.save(Baritone.settings());
            logDirect("§c[AutoLogout] WYŁĄCZONO ochronę.");
            return;
        }

        // Sprawdź czy argument to liczba (nowy próg w sercach)
        try {
            double newHearts = Double.parseDouble(first);
            if (newHearts <= 0 || newHearts > 100) {
                logDirect("§c[AutoLogout] Podaj poprawną liczbę serc (np. 3 lub 4.5).");
                return;
            }
            Baritone.settings().disconnectHealthHearts.value = newHearts;
            Baritone.settings().disconnectOnLowHealth.value = true;
            Baritone.settings().autoLogout.value = true;
            SettingsUtil.save(Baritone.settings());
            logDirect(String.format(Locale.ROOT,
                    "§a[AutoLogout] Ustawiono próg na §e%.1f serc §a(%.1f HP) i włączono ochronę (ON).",
                    newHearts, newHearts * 2.0));
            logDirect("§7Pamiętaj: 3 serca = 6 HP (nie 3 HP!). Opcja wyłączy się automatycznie po zadziałaniu.");
        } catch (NumberFormatException e) {
            logDirect("§c[AutoLogout] Nieznany argument: " + first + ". Użyj: #autologout <on|off|liczba_serc>");
        }
    }

    @Override
    public Stream<String> tabComplete(String label, IArgConsumer args) throws CommandException {
        if (args.hasExactly(1)) {
            String prefix = args.getString().toLowerCase(Locale.ROOT);
            return Stream.of("on", "off", "3", "3.5", "4", "5")
                    .filter(s -> s.startsWith(prefix));
        }
        return Stream.empty();
    }

    @Override
    public String getShortDesc() {
        return "Auto-logout when health drops below hearts threshold";
    }

    @Override
    public List<String> getLongDesc() {
        return Arrays.asList(
                "Disconnects from server when health drops below specified hearts (default 3 hearts = 6 HP).",
                "Automatically toggles OFF upon disconnect to prevent infinite loops when rejoining.",
                "",
                "Usage:",
                "> #autologout - view status",
                "> #autologout on/off - toggle",
                "> #autologout 3 - set to 3 hearts and enable"
        );
    }
}
