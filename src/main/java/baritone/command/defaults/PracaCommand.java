package baritone.command.defaults;

import baritone.Baritone;
import baritone.api.IBaritone;
import baritone.api.command.Command;
import baritone.api.command.argument.IArgConsumer;
import baritone.api.command.exception.CommandException;
import baritone.process.FarmProcess;
import baritone.process.HomeProcess;
import net.minecraft.core.BlockPos;

import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.stream.Stream;

/**
 * #praca — Eksperymentalny tryb pracy na farmie.
 *
 * Działa jak #farm ale z dodatkową logiką:
 * Jeśli w promieniu 100 bloków jest MNIEJ niż 200 dojrzałych roślin gotowych
 * do zbioru, bot przestawia priorytet na zbieranie itemów leżących na ziemi
 * (pszenica, nasiona itp.), zamiast szukać nowych plonów.
 *
 * Użycie:
 *   #praca          — startuje z zasięgiem 100 bloków
 *   #praca stop     — zatrzymuje
 *   #praca status   — pokazuje status
 */
public class PracaCommand extends Command {

    public PracaCommand(IBaritone baritone) {
        super(baritone, "praca", "work", "robota");
    }

    @Override
    public void execute(String label, IArgConsumer args) throws CommandException {
        if (args.hasAny()) {
            String sub = args.peekString().toLowerCase(Locale.ROOT);

            if (sub.equals("stop") || sub.equals("zatrzymaj")) {
                args.getString();
                FarmProcess.setPracaMode(false);
                baritone.getCommandManager().execute("farm stop");
                logDirect("§c[Praca] Tryb pracy zatrzymany.");
                return;
            }

            if (sub.equals("status") || sub.equals("info")) {
                args.getString();
                boolean active = baritone.getFarmProcess().isActive();
                boolean pracaMode = FarmProcess.isPracaModeActive();
                int harvestable = FarmProcess.getPracaHarvestableCount();
                int drops = FarmProcess.getPracaGroundDropsCount();
                int threshold = Baritone.settings().farmLowCropThreshold.value;
                logDirect("§b=== STATUS #PRACA (EKSPERYMENTALNY) ===");
                logDirect("  §fStan: " + (active ? "§aAKTYWNY" : "§cBEZCZYNNY"));
                logDirect("  §fTryb #praca: " + (pracaMode ? "§aWŁĄCZONY" : "§7WYŁĄCZONY"));
                logDirect(String.format("  §fDojrzałe rośliny w r=100: §e%d szt. §7(próg: §e%d§7)", harvestable, threshold));
                logDirect(String.format("  §fItemy na ziemi do zebrania: §e%d szt.", drops));
                logDirect(String.format("  §fBieżący priorytet: %s",
                        (harvestable < threshold && drops > 0) ? "§6[ZBIERANIE Z ZIEMI]" : "§a[NORMALNY ZBIÓR PLONÓW]"));
                logDirect("  §7(#praca stop | #praca status)");
                return;
            }
        }

        // Aktywuj tryb #praca w FarmProcess
        HomeProcess.setSavedMiningCommand("praca");

        // Uruchom farm z zasięgiem 100 bloków (promień dla skanowania #praca)
        BlockPos center = baritone.getPlayerContext().playerFeet();
        baritone.getFarmProcess().farmPraca(100, center);


        logDirect("§a[Praca] §e[EKSPERYMENTALNY]§a Uruchamiam tryb pracy na farmie!");
        logDirect("§7  • Zasięg skanowania: §f100 bloków");
        logDirect("§7  • Jeśli dojrzałych roślin < §f200§7, priorytet: §azbieranie z ziemi");
        logDirect("§7  • Jeśli dojrzałych roślin ≥ §f200§7, priorytet: §anormalne zbieranie");
        logDirect("§7  (zatrzymaj: §f#praca stop§7)");
    }

    @Override
    public Stream<String> tabComplete(String label, IArgConsumer args) throws CommandException {
        if (args.hasExactlyOne()) {
            return Stream.of("stop", "status", "zatrzymaj", "info");
        }
        return Stream.empty();
    }

    @Override
    public String getShortDesc() {
        return "[EKSPERYMENTALNY] Tryb pracy: farm z priorytetem zbierania gdy mało plonów";
    }

    @Override
    public List<String> getLongDesc() {
        return Arrays.asList(
                "Komenda #praca uruchamia eksperymentalny tryb farmienia.",
                "",
                "Logika:",
                "  - Skanuje promień 100 bloków w poszukiwaniu dojrzałych roślin.",
                "  - Jeśli dojrzałych roślin jest MNIEJ niż 200:",
                "    → Bot priorytetyzuje zbieranie itemów leżących na ziemi",
                "      (pszenica, nasiona, marchew itp.)",
                "  - Jeśli dojrzałych roślin jest 200 lub więcej:",
                "    → Normalny tryb zbioru jak w #farm",
                "",
                "> #praca          — startuje tryb pracy",
                "> #praca stop     — zatrzymuje",
                "> #praca status   — pokazuje aktualny stan i licznik roślin"
        );
    }
}
