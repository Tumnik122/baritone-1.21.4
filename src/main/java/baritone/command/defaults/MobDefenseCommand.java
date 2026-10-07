package baritone.command.defaults;

import baritone.Baritone;
import baritone.api.IBaritone;
import baritone.api.command.Command;
import baritone.api.command.argument.IArgConsumer;
import baritone.api.command.exception.CommandException;

import java.util.Locale;
import java.util.stream.Stream;

/**
 * Komenda #mobdefense (lub #defend, #autoattack, #atakuj) do zarządzania obroną bota przed potworami.
 */
public class MobDefenseCommand extends Command {

    public MobDefenseCommand(IBaritone baritone) {
        super(baritone, "mobdefense", "defend", "autoattack", "atakuj");
    }

    @Override
    public void execute(String label, IArgConsumer args) throws CommandException {
        if (!args.hasAny()) {
            boolean current = Baritone.settings().mobDefense.value;
            if (!current) {
                Baritone.settings().mobDefense.value = true;
                logDirect("§a[MobDefense] Obrona przed potworami: WŁĄCZONA!");
                logDirect("§7Bot automatycznie zatrzyma obecne zadanie (kopanie/chodzenie), wyciągnie broń (miecz/topór/kilof) i zabije każdego potwora.");
            } else {
                logDirect("§a[MobDefense] Obrona przed potworami jest już WŁĄCZONA.");
                logDirect("§7Zasięg: §e" + Baritone.settings().mobDefenseRange.value + " bloków §7| Creepery: " + (Baritone.settings().mobDefenseAttackCreepers.value ? "§aTAK" : "§cNIE"));
                logDirect("§7Aby wyłączyć, wpisz: §e#defend off §7lub §e#defend toggle");
            }
            return;
        }

        String arg = args.getString().toLowerCase(Locale.ROOT);
        if (arg.equals("on") || arg.equals("enable") || arg.equals("true") || arg.equals("1") || arg.equals("start")) {
            Baritone.settings().mobDefense.value = true;
            logDirect("§a[MobDefense] Automatyczna obrona przed potworami została WŁĄCZONA.");
            logDirect("§7Bot zatrzyma obecne zadanie i zabije każdego moba (mieczem/kilofem).");
        } else if (arg.equals("off") || arg.equals("disable") || arg.equals("false") || arg.equals("0") || arg.equals("stop")) {
            Baritone.settings().mobDefense.value = false;
            logDirect("§c[MobDefense] Automatyczna obrona przed potworami została WYŁĄCZONA.");
        } else if (arg.equals("toggle")) {
            boolean current = Baritone.settings().mobDefense.value;
            Baritone.settings().mobDefense.value = !current;
            logDirect("§b[MobDefense] Obrona przed potworami: " + (!current ? "§aWŁĄCZONA" : "§cWYŁĄCZONA"));
        } else if (arg.equals("status")) {
            boolean on = Baritone.settings().mobDefense.value;
            logDirect("§b=== STATUS #mobdefense (#defend) ===");
            logDirect("  Stan: " + (on ? "§aWŁĄCZONA" : "§cWYŁĄCZONA"));
            logDirect("  Zasięg ataku: §e" + Baritone.settings().mobDefenseRange.value + " bloków");
            logDirect("  Zmiana broni: " + (Baritone.settings().mobDefenseSwitchWeapon.value ? "§aWŁĄCZONA (miecz/topór/kilof)" : "§cWYŁĄCZONA"));
            logDirect("  Atak creeperów: " + (Baritone.settings().mobDefenseAttackCreepers.value ? "§aWŁĄCZONY" : "§cWYŁĄCZONY"));
        } else if (arg.equals("range")) {
            if (!args.hasAny()) {
                logDirect("§e[MobDefense] Aktualny zasięg ataku: " + Baritone.settings().mobDefenseRange.value + " bloków.");
                return;
            }
            try {
                double r = Double.parseDouble(args.getString());
                if (r < 1.0 || r > 6.0) {
                    logDirect("§c[MobDefense] Zasięg musi być w przedziale 1.0 - 6.0 bloków.");
                    return;
                }
                Baritone.settings().mobDefenseRange.value = r;
                logDirect("§a[MobDefense] Nowy zasięg ataku: " + r + " bloków.");
            } catch (NumberFormatException e) {
                logDirect("§c[MobDefense] Nieprawidłowa liczba: " + args.getString());
            }
        } else if (arg.equals("creeper") || arg.equals("creepers")) {
            boolean current = Baritone.settings().mobDefenseAttackCreepers.value;
            Baritone.settings().mobDefenseAttackCreepers.value = !current;
            logDirect("§b[MobDefense] Atakowanie Creeperów: " + (!current ? "§aWŁĄCZONE" : "§cWYŁĄCZONE"));
        } else {
            logDirect("§b=== OBSŁUGA #mobdefense (#defend) ===");
            logDirect("  §e#defend §7- włącza obronę (zatrzymuje bota i zabija każdego moba)");
            logDirect("  §e#defend on / off §7- włącza / wyłącza obronę");
            logDirect("  §e#defend toggle §7- przełącza stan (on/off)");
            logDirect("  §e#defend status §7- wyświetla aktualny stan modułu");
            logDirect("  §e#defend range <metry> §7- ustawia zasięg ataku (domyślnie 4.2)");
            logDirect("  §e#defend creeper §7- włącza / wyłącza atakowanie creeperów");
        }
    }

    @Override
    public Stream<String> tabComplete(String label, IArgConsumer args) throws CommandException {
        if (args.hasExactlyOne()) {
            return Stream.of("on", "off", "toggle", "status", "range", "creeper");
        }
        return Stream.empty();
    }

    @Override
    public String getShortDesc() {
        return "Automatyczna obrona i bicie potworów w pobliżu bota (zatrzymuje bota i zabija moba)";
    }

    @Override
    public java.util.List<String> getLongDesc() {
        return java.util.Arrays.asList(
                "Automatycznie pauzuje obecne zadanie bota (#mine, #goto itp.) i zabija każdego wrogiego moba w pobliżu.",
                "Używa najlepszej broni z paska (miecz, topór, a w razie potrzeby kilof), zachowuje pełny cooldown dla 100% damage'u",
                "oraz respektuje zasady GrimAC. Po walce bot automatycznie wznawia poprzednie zadanie."
        );
    }
}
