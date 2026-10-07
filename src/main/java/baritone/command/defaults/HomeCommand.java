package baritone.command.defaults;

import baritone.Baritone;
import baritone.api.IBaritone;
import baritone.api.command.Command;
import baritone.api.command.argument.IArgConsumer;
import baritone.api.command.exception.CommandException;
import baritone.process.HomeProcess;

import java.util.Locale;
import java.util.stream.Stream;

/**
 * Komenda #home [1-7] do automatycznego teleportu przez menu GUI /home "Twoje domki".
 *
 * Użycie:
 *  #home          -> wysyła /home i klika w Home 1 (Kamień / Stone, slot 19)
 *  #home 1        -> wysyła /home i klika w Home 1 (Kamień, slot 19)
 *  #home 2        -> wysyła /home i klika w Home 2 (Zielone łóżko, slot 20)
 *  #home 3..7     -> wysyła /home i klika w Home 3..7 (Czerwone łóżka, sloty 21..25)
 *
 * Działa bez ruszania fizyczną myszką, również przy zminimalizowanej grze.
 */
public class HomeCommand extends Command {

    public HomeCommand(IBaritone baritone) {
        super(baritone, "home", "domek", "dom", "deposit");
    }

    @Override
    public void execute(String label, IArgConsumer args) throws CommandException {
        Baritone baritoneImpl = (Baritone) this.baritone;
        HomeProcess homeProcess = baritoneImpl.getHomeProcess();

        if (ctx.player() == null) {
            logDirect("§c[Home] Gracz nie jest na serwerze.");
            return;
        }

        int targetHome = 1;
        boolean autoDeposit = true;

        if (label.equalsIgnoreCase("deposit")) {
            targetHome = 1;
            autoDeposit = true;
        } else if (args.hasAny()) {
            String arg = args.getString().toLowerCase(Locale.ROOT);

            if (arg.equals("auto")) {
                if (args.hasAny()) {
                    String sub = args.getString().toLowerCase(Locale.ROOT);
                    if (sub.equals("on") || sub.equals("true") || sub.equals("1")) {
                        Baritone.settings().autoHomeOnFull.value = true;
                        logDirect("§a[Home] Automatyczny powrót do bazy przy pełnym EQ został WŁĄCZONY.");
                        return;
                    } else if (sub.equals("off") || sub.equals("false") || sub.equals("0")) {
                        Baritone.settings().autoHomeOnFull.value = false;
                        logDirect("§c[Home] Automatyczny powrót do bazy przy pełnym EQ został WYŁĄCZONY.");
                        return;
                    }
                }
                logDirect("§e[Home] Auto-Home przy pełnym EQ: " + (Baritone.settings().autoHomeOnFull.value ? "§aWŁĄCZONY" : "§cWYŁĄCZONY"));
                return;
            }

            if (arg.equals("status") || arg.equals("info") || arg.equals("list")) {
                logDirect("§b=== SYSTEM TELEPORTU /home (GUI Twoje domki) ===");
                logDirect("  §e#home 1 §7(lub samo #home / #deposit) - Pełny cykl: Home 1 (Kamień) -> otwarcie skrzynki -> oddanie wydobytych surowców -> Home 2 (kopalnia)!");
                logDirect("  §e#home auto on/off §7- automatyczny powrót przy pełnym EQ: " + (Baritone.settings().autoHomeOnFull.value ? "§aWŁĄCZONY" : "§cWYŁĄCZONY"));
                if (HomeProcess.getSavedMiningCommand() != null) {
                    logDirect("  §eZapisane zadanie do wznowienia: §f#" + HomeProcess.getSavedMiningCommand());
                }
                logDirect("  §e#home 1 stay §7- tylko teleport do Home 1 bez oddawania");
                logDirect("  §e#home 2 §7- Zielone łóżko (kopalnia, Slot 20)");
                logDirect("  §e#home 3..7 §7- Czerwone łóżka (Sloty 21..25)");
                logDirect("§7Bot automatycznie wysyła /home na czacie, czeka na GUI i klika pakietowo bez ruszania myszką!");
                return;
            }

            if (arg.equals("stay") || arg.equals("only")) {
                targetHome = 1;
                autoDeposit = false;
            } else if (arg.equals("kamien") || arg.equals("stone")) {
                targetHome = 1;
            } else if (arg.equals("zielone") || arg.equals("green") || arg.equals("lime")) {
                targetHome = 2;
                autoDeposit = false;
            } else {
                try {
                    targetHome = Integer.parseInt(arg);
                    if (targetHome < 1 || targetHome > 7) {
                        logDirect("§c[Home] Numer domku musi być w przedziale 1..7.");
                        return;
                    }
                    if (targetHome != 1) {
                        autoDeposit = false;
                    } else if (args.hasAny()) {
                        String sub = args.getString().toLowerCase(Locale.ROOT);
                        if (sub.equals("stay") || sub.equals("only")) {
                            autoDeposit = false;
                        }
                    }
                } catch (NumberFormatException e) {
                    logDirect("§c[Home] Nieznany argument: " + arg + ". Użyj: #home [1-7]");
                    return;
                }
            }
        }

        if (targetHome == 1 && autoDeposit) {
            logDirect("§a[Home] Rozpoczynam pełny cykl: Home 1 (Kamień) -> otwarcie skrzynki i oddanie wydobytych surowców -> Home 2 (Zielone łóżko)...");
        } else {
            logDirect(String.format("§a[Home] Rozpoczynam procedurę teleportu do Home %d (wysyłanie /home i klik w GUI)...", targetHome));
        }
        homeProcess.teleportToHome(targetHome, autoDeposit);
    }

    @Override
    public Stream<String> tabComplete(String label, IArgConsumer args) throws CommandException {
        if (args.hasExactlyOne()) {
            return Stream.of("1", "2", "3", "4", "5", "6", "7", "status", "stay");
        }
        if (args.has(2)) {
            String first = args.getString().toLowerCase(Locale.ROOT);
            if (first.equals("1")) {
                return Stream.of("stay");
            }
        }
        return Stream.empty();
    }

    @Override
    public String getShortDesc() {
        return "Teleportacja do wybranego domku przez menu GUI /home oraz automatyczne oddawanie żelaza do skrzynki";
    }

    @Override
    public java.util.List<String> getLongDesc() {
        return java.util.Arrays.asList(
                "Komenda #home [1-7] wysyła komendę /home na serwerze, czeka na pojawienie się",
                "okienka GUI 'Twoje domki', po czym automatycznie wysyła kliknięcie w wybrany domek:",
                "  Home 1 -> Kamień (slot 19) - domyślnie otwiera skrzynkę obok, oddaje całe żelazo i wraca do Home 2!",
                "  Home 2 -> Zielone łóżko (slot 20)",
                "  Home 3..7 -> Czerwone łóżka (sloty 21..25)",
                "Działa płynnie na GrimAC oraz przy zminimalizowanym oknie gry."
        );
    }
}

