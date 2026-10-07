/*
 * This file is part of Baritone.
 *
 * Baritone is free software: you can redistribute it and/or modify
 * it under the terms of the GNU Lesser General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * Baritone is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU Lesser General Public License for more details.
 *
 * You should have received a copy of the GNU Lesser General Public License
 * along with Baritone.  If not, see <https://www.gnu.org/licenses/>.
 */

package baritone.command.defaults;

import baritone.Baritone;
import baritone.api.IBaritone;
import baritone.api.cache.IWaypoint;
import baritone.api.command.Command;
import baritone.api.command.argument.IArgConsumer;
import baritone.api.command.datatypes.ForWaypoints;
import baritone.api.command.exception.CommandException;
import baritone.api.command.exception.CommandInvalidStateException;
import baritone.api.utils.BetterBlockPos;
import baritone.process.FarmProcess;
import baritone.process.HomeProcess;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;

import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.stream.Stream;

public class FarmCommand extends Command {

    public FarmCommand(IBaritone baritone) {
        super(baritone, "farm");
    }

    @Override
    public void execute(String label, IArgConsumer args) throws CommandException {
        if (args.hasAny()) {
            String first = args.peekString().toLowerCase(Locale.ROOT);
            if (first.equals("status") || first.equals("info")) {
                args.getString(); // consume
                boolean active = baritone.getFarmProcess().isActive();
                boolean replant = Baritone.settings().replantCrops.value;
                boolean fast = Baritone.settings().farmFastMode.value;
                int seedsCount = countSeeds();
                int wheatCount = countWheat();

                long totalWheat = FarmProcess.getTotalWheatHarvested();
                double wheatPerHour = FarmProcess.getWheatPerHour();
                long sessionMs = FarmProcess.isFarmSessionRunning()
                        ? System.currentTimeMillis() - FarmProcess.getFarmSessionStartMs()
                        : 0L;
                long sessionMin = sessionMs / 60000L;
                long sessionSec = (sessionMs % 60000L) / 1000L;

                logDirect("§b=== STATUS MODUŁU FARM ===");
                logDirect("  §fStan: " + (active ? "§aAKTYWNY (Farmienie w toku)" : "§cBEZCZYNNY"));
                logDirect("  §fAutomatyczny replant: " + (replant ? "§aWŁĄCZONY" : "§cWYŁĄCZONY"));
                logDirect("  §fTryb prędkości: " + (fast ? "§aFULL ODPAL (Co-Tick / Błyskawiczny)" : "§eLEGIT (Płynne rotacje)"));
                logDirect(String.format("  §fNasiona w EQ: §e%d szt.  §fPszenica w EQ: §e%d szt.", seedsCount, wheatCount));
                logDirect("  §b── Statystyki pszenicy ──");
                logDirect(String.format("  §fZebrana pszenica (sesja): §a%d bloków", totalWheat));
                logDirect(String.format("  §fŚrednia: §a%.1f psz/h", wheatPerHour));
                if (sessionMs > 0) {
                    logDirect(String.format("  §fCzas sesji: §e%dm %ds", sessionMin, sessionSec));
                }
                boolean isPraca = FarmProcess.isPracaModeActive();
                int harvestable = FarmProcess.getPracaHarvestableCount();
                int groundDrops = FarmProcess.getPracaGroundDropsCount();
                int threshold = Baritone.settings().farmLowCropThreshold.value;
                logDirect("  §fTryb #praca (zbieranie gdy <" + threshold + " plonów w r=100): " + (isPraca ? "§aWŁĄCZONY" : "§7WYŁĄCZONY"));
                if (isPraca) {
                    logDirect(String.format("    • Dojrzałe w r=100: §e%d szt. §7(próg: §e%d§7)  • Na ziemi: §e%d szt.", harvestable, threshold, groundDrops));
                    logDirect(String.format("    • Bieżący priorytet: %s", (harvestable < threshold && groundDrops > 0) ? "§6ZBIERANIE Z ZIEMI" : "§aZBIÓR PLONÓW"));
                }
                logDirect("  §7(#farm status | #farm fast | #farm legit | #farm praca | #farm replant on/off | #farm stop)");
                return;
            }

            if (first.equals("fast") || first.equals("odpal") || first.equals("rage")) {
                args.getString();
                Baritone.settings().farmFastMode.value = true;
                logDirect("§a[Farm] Tryb SZYBKI (Full Odpal / Co-Tick / 1-tick replant) WŁĄCZONY!");
                return;
            }

            if (first.equals("legit") || first.equals("slow")) {
                args.getString();
                Baritone.settings().farmFastMode.value = false;
                logDirect("§e[Farm] Tryb LEGIT (Płynne rotacje C2 smootherstep) WŁĄCZONY!");
                return;
            }

            if (first.equals("stop") || first.equals("cancel") || first.equals("off")) {
                args.getString(); // consume
                FarmProcess.setPracaMode(false);
                baritone.getFarmProcess().onLostControl();
                baritone.getPathingBehavior().cancelEverything();
                logDirect("§c[Farm] Farmienie zostało ZATRZYMANE.");
                return;
            }

            if (first.equals("praca") || first.equals("pickup") || first.equals("zbieraj")) {
                args.getString(); // consume
                if (args.hasAny()) {
                    String val = args.getString().toLowerCase(Locale.ROOT);
                    if (val.equals("on") || val.equals("true") || val.equals("1")) {
                        Baritone.settings().farmCollectDropsWhenLowCrops.value = true;
                        FarmProcess.setPracaMode(true);
                        logDirect("§a[Farm] Eksperymentalny tryb zbierania z ziemi (<200 plonów w r=100) WŁĄCZONY.");
                    } else if (val.equals("off") || val.equals("false") || val.equals("0")) {
                        Baritone.settings().farmCollectDropsWhenLowCrops.value = false;
                        FarmProcess.setPracaMode(false);
                        logDirect("§c[Farm] Eksperymentalny tryb zbierania z ziemi WYŁĄCZONY.");
                    }
                } else {
                    boolean cur = !Baritone.settings().farmCollectDropsWhenLowCrops.value;
                    Baritone.settings().farmCollectDropsWhenLowCrops.value = cur;
                    FarmProcess.setPracaMode(cur);
                    logDirect("§e[Farm] Zbieranie z ziemi (<200 plonów w r=100): " + (cur ? "§aWŁĄCZONE" : "§cWYŁĄCZONE"));
                }
                return;
            }

            if (first.equals("replant")) {
                args.getString(); // consume
                if (args.hasAny()) {
                    String val = args.getString().toLowerCase(Locale.ROOT);
                    if (val.equals("on") || val.equals("true") || val.equals("1")) {
                        Baritone.settings().replantCrops.value = true;
                        logDirect("§a[Farm] Automatyczne ponowne sadzenie (replant) WŁĄCZONE.");
                    } else if (val.equals("off") || val.equals("false") || val.equals("0")) {
                        Baritone.settings().replantCrops.value = false;
                        logDirect("§c[Farm] Automatyczne ponowne sadzenie (replant) WYŁĄCZONE.");
                    }
                } else {
                    boolean cur = !Baritone.settings().replantCrops.value;
                    Baritone.settings().replantCrops.value = cur;
                    logDirect("§e[Farm] Replant przestawiono na: " + (cur ? "§aWŁĄCZONY" : "§cWYŁĄCZONY"));
                }
                return;
            }
        }

        args.requireMax(2);
        int range = 0;
        BetterBlockPos origin = null;

        // range
        if (args.has(1)) {
            range = args.getAs(Integer.class);
        }

        // waypoint
        if (args.has(1)) {
            IWaypoint[] waypoints = args.getDatatypeFor(ForWaypoints.INSTANCE);
            IWaypoint waypoint = null;
            switch (waypoints.length) {
                case 0:
                    throw new CommandInvalidStateException("No waypoints found");
                case 1:
                    waypoint = waypoints[0];
                    break;
                default:
                    throw new CommandInvalidStateException("Multiple waypoints were found");
            }
            origin = waypoint.getLocation();
        }

        baritone.getFarmProcess().farm(range, origin);
        HomeProcess.setSavedMiningCommand("farm" + (range > 0 ? (" " + range) : ""));
        logDirect(String.format("§a[Farm] Rozpoczynam inteligentne farmienie%s...", range > 0 ? (" w zasięgu " + range + " bloków") : " (cała dostępna farma)"));
    }

    private int countSeeds() {
        if (ctx.player() == null) return 0;
        Inventory inv = ctx.player().getInventory();
        int total = 0;
        for (ItemStack stack : inv.items) {
            if (stack == null || stack.isEmpty()) continue;
            if (stack.is(Items.WHEAT_SEEDS) || stack.is(Items.CARROT) || stack.is(Items.POTATO)
                    || stack.is(Items.BEETROOT_SEEDS) || stack.is(Items.MELON_SEEDS)
                    || stack.is(Items.PUMPKIN_SEEDS) || stack.is(Items.NETHER_WART)) {
                total += stack.getCount();
            }
        }
        return total;
    }

    private int countWheat() {
        if (ctx.player() == null) return 0;
        Inventory inv = ctx.player().getInventory();
        int total = 0;
        for (ItemStack stack : inv.items) {
            if (stack == null || stack.isEmpty()) continue;
            if (stack.is(Items.WHEAT)) {
                total += stack.getCount();
            }
        }
        return total;
    }

    @Override
    public Stream<String> tabComplete(String label, IArgConsumer args) throws CommandException {
        if (args.hasExactlyOne()) {
            return Stream.of("status", "stop", "replant", "praca", "pickup", "fast", "legit", "10", "20", "30", "50", "100");
        }
        if (args.has(2)) {
            String first = args.getString().toLowerCase(Locale.ROOT);
            if (first.equals("replant") || first.equals("praca") || first.equals("pickup")) {
                return Stream.of("on", "off");
            }
        }
        return Stream.empty();
    }

    @Override
    public String getShortDesc() {
        return "Inteligentne, zoptymalizowane farmienie plonów";
    }

    @Override
    public List<String> getLongDesc() {
        return Arrays.asList(
                "Komenda #farm rozpoczyna zbieranie dojrzałych plonów, natychmiastowe ponowne obsadzanie nasionami",
                "oraz automatyczne zbieranie wyrzuconych plonów z grządek.",
                "",
                "Użycie:",
                "> farm - farmi wszystkie dojrzałe rośliny w zasięgu wzroku.",
                "> farm <zasięg> - farmi w określonym promieniu od aktualnej pozycji.",
                "> farm <zasięg> <waypoint> - farmi w promieniu od danego waypointa.",
                "> farm status - wyświetla stan modułu farmienia i liczbę nasion w ekwipunku.",
                "> farm replant <on/off> - włącza/wyłącza ponowne sadzenie nasion.",
                "> farm stop - natychmiast zatrzymuje farmienie."
        );
    }
}
