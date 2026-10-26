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
                int seeds = ((FarmProcess) baritone.getFarmProcess()).countWheatSeedsInInventory();
                boolean wheatPriority = FarmProcess.isPracaWheatPriorityActive();
                int highThresh = Baritone.settings().pracaSeedsHighThreshold.value;
                int lowThresh = Baritone.settings().pracaSeedsLowThreshold.value;
                logDirect("§b=== STATUS #PRACA (EKSPERYMENTALNY) ===");
                logDirect("  §fStan: " + (active ? "§aAKTYWNY" : "§cBEZCZYNNY"));
                logDirect("  §fTryb #praca: " + (pracaMode ? "§aWŁĄCZONY" : "§7WYŁĄCZONY"));
                logDirect(String.format("  §fDojrzałe rośliny w r=100: §e%d szt. §7(próg: §e%d§7)", harvestable, threshold));
                logDirect(String.format("  §fItemy na ziemi do zebrania: §e%d szt.", drops));
                logDirect(String.format("  §fNasiona w EQ: §e%d szt. §7(skupienie na pszenicy: gdy >0 nasion; zbiór nasion i pszenicy: gdy 0 nasion)", seeds));
                logDirect("  §fFokus zbierania: " + (wheatPriority ? "§a[SKUPIENIE NA PSZENICY (nasiona > 0 - nasiona na ziemi ignorowane)]" : "§e[AWARYJNY ZBIÓR NASION I PSZENICY (0 nasion w EQ)]"));
                logDirect(String.format("  §fBieżący priorytet: %s",
                        (harvestable < threshold && drops > 0) ? "§6[ZBIERANIE Z ZIEMI]" : "§a[NORMALNY ZBIÓR PLONÓW]"));
                logDirect("  §7(#praca stop | #praca status)");
                return;
            }
        }

        // Wymuś optymalne ustawienia dla maksymalnej wydajności #praca
        Baritone.settings().farmHarvestPriority.value = false; // Natychmiastowy siew, nie odkładaj sadzenia na później!
        Baritone.settings().farmTwoPassMode.value = false;
        Baritone.settings().farmFastMode.value = true;
        Baritone.settings().replantCrops.value = true;
        Baritone.settings().farmWheatOnly.value = true;
        Baritone.settings().allowSprint.value = true;
        Baritone.settings().mineAvoidWater.value = true;
        Baritone.settings().antiCheatCompat.value = true;
        Baritone.settings().grimCompat.value = true;
        Baritone.settings().antiCheatCompatibility.value = true;
        Baritone.settings().grimSafeReach.value = 3.0;

        int range = 0; // Domyślnie 0 = całe pole bez ograniczenia zasięgu (jak w #farm)
        if (args.hasAny()) {
            try {
                range = Integer.parseInt(args.getString());
            } catch (Exception ignored) {
            }
        }

        // Aktywuj tryb #praca w FarmProcess
        HomeProcess.setSavedMiningCommand("praca" + (range > 0 ? (" " + range) : ""));

        // Uruchom farm z dynamicznym zasięgiem (0 = cała farma)
        BlockPos center = (range > 0 && ctx.player() != null) ? baritone.getPlayerContext().playerFeet() : null;
        baritone.getFarmProcess().farmPraca(range, center);

        logDirect(String.format("§a[Praca] Uruchamiam zoptymalizowany DO MAKA tryb pracy na farmie (Argon 4 Engine)%s!",
                range > 0 ? (" w zasięgu " + range + " bloków") : " (cała dostępna farma bez limitu zasięgu)"));
        logDirect("§7  • Mózg nawigacji: §aBoustrophedon Serpentine Spine (korytarz 3.5m, 4 rzędy/przelot, 0 zatrzymań!)");
        logDirect("§7  • Rotacja: §aUltra-Fast 1-Tick GCD Snap §7(100% legalności GrimAC, brak flag BadSensitivity/AimInvalid)");
        logDirect("§7  • Celowanie: §aIn-Reach Snap Replant §7(celuje w glebę dopiero w zasięgu ręki - 0 wgapiania się w ziemię z dystansu!)");
        logDirect("§7  • Sadzenie: §a1-Tick Shared-Ray Replant §7(brak przestojów, idealne trafienie w Farmland Direction.UP)");
        logDirect("§7  • Nawroty: §aCatmull-Rom U-Turn Arc §7(płynne 180° z odpychaniem od wody, brak wpadania do kanałów)");
        logDirect("§7  • Sterowanie: §aDecoupled Sprint WASD (5.61 m/s) §7(bieg w przód niezależny od obrotów głowy)");
        logDirect("§7  • Priorytet pszenicy: §agdy nasiona > 0, bot w 100% kosi pszenicę (nasiona na ziemi są ignorowane)§7");
        logDirect("§7  • Zbiór nasion i pszenicy: §ctylko awaryjnie gdy 0 nasion w EQ§7 (zbiera nasiona i pszenicę aby siać)");
        logDirect("§7  • Oś Boustrophedon: §awprost w kłosy dojrzałej pszenicy §7(replant w 1 tick w pełnym biegu)");
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
        return "[EKSPERYMENTALNY] Tryb pracy: farm z priorytetem zbierania pszenicy (>100 nasion) i dropów gdy mało plonów";
    }

    @Override
    public List<String> getLongDesc() {
        return Arrays.asList(
                "Komenda #praca uruchamia eksperymentalny tryb farmienia pszenicy.",
                "",
                "Logika:",
                "  - Skanuje promień 100 bloków w poszukiwaniu dojrzałej pszenicy.",
                "  - Inteligentne nasiona (histereza 100 -> 30):",
                "    • Gdy w EQ jest >100 nasion: bot skupia się na pszenicy (zbiera leżącą pszenicę i kosi plony,",
                "      nie tracąc czasu na bieganie za pojedynczymi nasionami).",
                "    • Gdy nasiona spadną do <=30: bot ponownie aktywnie zbiera nasiona z ziemi.",
                "  - Jeśli dojrzałych roślin jest MNIEJ niż 200:",
                "    → Bot priorytetyzuje zbieranie itemów leżących na ziemi",
                "  - Jeśli dojrzałych roślin jest 200 lub więcej:",
                "    → Szybki bieg wzdłuż alei (lane traversal) z natychmiastowym zbiorem i replantem",
                "",
                "> #praca          — startuje tryb pracy",
                "> #praca stop     — zatrzymuje",
                "> #praca status   — pokazuje aktualny stan, licznik roślin i nasion"
        );
    }
}
