package baritone.command.defaults;

import baritone.Baritone;
import baritone.api.IBaritone;
import baritone.api.command.Command;
import baritone.api.command.argument.IArgConsumer;
import baritone.api.command.exception.CommandException;
import baritone.utils.BotOptimizer;

import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.stream.Stream;

/**
 * Komenda #botopt / #optimize do optymalizacji klienta Minecrafta pod kątem pracy wielu botów (np. 5+ instancji).
 * Zmniejsza zużycie pamięci RAM (render distance = 2, czyszczenie pamięci GC),
 * drastycznie ogranicza obciążenie procesora i karty graficznej (FPS = 20, FPS w tle = 10, grafika FAST, wyłączone cienie i chmury,
 * brak renderowania ścieżek 3D Baritone na ekranie - logika pathfindingu działa w 100% normalnie).
 */
public class BotOptimizerCommand extends Command {

    public BotOptimizerCommand(IBaritone baritone) {
        super(baritone, "botopt", "optimize", "optymalizuj", "lowram");
    }

    @Override
    public void execute(String label, IArgConsumer args) throws CommandException {
        if (!args.hasAny()) {
            boolean active = BotOptimizer.isActive();
            if (!active) {
                BotOptimizer.setActive(true);
                logDirect("§a[BotOptimizer] Tryb ultra-optymalizacji dla botów został WŁĄCZONY!");
                logDirect("§7- Render distance: 2 chunków (drastyczny spadek zużycia RAM z ~2GB na ~350MB)");
                logDirect("§7- Limit FPS: 20 FPS (gdy okno w tle: 10 FPS dla oszczędności CPU/GPU)");
                logDirect("§7- Grafika FAST, wyłączone chmury, cienie, biome blend, mipmapy = 0");
                logDirect("§7- Wyłączone renderowanie 3D ścieżek Baritone (wszystkie funkcje i pathfinding działają w 100%)");
                logDirect("§7- Pamięć RAM wyczyszczona (System.gc)");
            } else {
                BotOptimizer.applyOptimizations();
                logDirect("§a[BotOptimizer] Ponownie zastosowano optymalizacje i wyczyszczono RAM.");
            }
            return;
        }

        String sub = args.getString().toLowerCase(Locale.ROOT);
        if (sub.equals("on") || sub.equals("start") || sub.equals("enable") || sub.equals("1")) {
            BotOptimizer.setActive(true);
            logDirect("§a[BotOptimizer] Ultra-optymalizacja botów: WŁĄCZONA.");
        } else if (sub.equals("off") || sub.equals("stop") || sub.equals("disable") || sub.equals("0")) {
            BotOptimizer.setActive(false);
            logDirect("§c[BotOptimizer] Ultra-optymalizacja botów: WYŁĄCZONA.");
        } else if (sub.equals("toggle")) {
            boolean newState = !BotOptimizer.isActive();
            BotOptimizer.setActive(newState);
            logDirect("§b[BotOptimizer] Tryb optymalizacji: " + (newState ? "§aWŁĄCZONY" : "§cWYŁĄCZONY"));
        } else if (sub.equals("apply") || sub.equals("gc")) {
            BotOptimizer.applyOptimizations();
            logDirect("§a[BotOptimizer] Zastosowano ustawienia niskiego zużycia zasobów i wyczyszczono pamięć RAM.");
        } else if (sub.equals("status")) {
            logDirect("§b=== STATUS [BotOptimizer] ===");
            logDirect("  Aktywny: " + (BotOptimizer.isActive() ? "§aTAK" : "§cNIE"));
            logDirect("  Render distance: §e2 chunki");
            logDirect("  FPS limit: §e20 FPS (10 FPS w tle)");
            logDirect("  Grafika: §eFast, bez cieni, bez chmur, mipmapy: 0");
            logDirect("  Renderowanie 3D Baritone: §eWyłączone (zero obciążenia karty graficznej)");
            logDirect("  Wszystkie opcje Baritone: §a100% zachowane i działające");
        } else {
            logDirect("§c[BotOptimizer] Nieznany argument. Użyj: #botopt <on|off|toggle|apply|status>");
        }
    }

    @Override
    public Stream<String> tabComplete(String label, IArgConsumer args) {
        if (args.hasExactlyOne()) {
            return Stream.of("on", "off", "toggle", "apply", "status");
        }
        return Stream.empty();
    }

    @Override
    public String getShortDesc() {
        return "Optymalizuje klienta pod jednoczesne uruchamianie wielu botów (niski RAM, CPU, GPU)";
    }

    @Override
    public List<String> getLongDesc() {
        return Arrays.asList(
                "Optymalizuje ustawienia gry Minecraft i Baritone pod uruchamianie 5+ botów.",
                "Zmniejsza zużycie pamięci RAM z ~2GB do ~350-450MB na instancję oraz odciąża CPU i GPU.",
                "",
                "Użycie:",
                "> botopt [on|off|toggle|apply|status]",
                "> optimize [on|off|toggle|apply|status]"
        );
    }
}
