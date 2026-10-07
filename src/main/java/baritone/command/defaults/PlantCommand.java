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
import baritone.process.HomeProcess;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;

import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.stream.Stream;

public class PlantCommand extends Command {

    public PlantCommand(IBaritone baritone) {
        super(baritone, "sadzenie", "sadzen", "plant", "siej", "zasiej");
    }

    @Override
    public void execute(String label, IArgConsumer args) throws CommandException {
        if (args.hasAny()) {
            String first = args.peekString().toLowerCase(Locale.ROOT);
            if (first.equals("status") || first.equals("info")) {
                args.getString();
                boolean active = baritone.getFarmProcess().isActive() && baritone.getFarmProcess().isPlantOnly();
                boolean fast = Baritone.settings().farmFastMode.value;
                int seedsCount = countSeeds();
                logDirect("§b=== STATUS MODUŁU SADZENIA ===");
                logDirect("  §fStan: " + (active ? "§aAKTYWNY (Sadzenie w toku)" : "§cBEZCZYNNY"));
                logDirect("  §fTryb prędkości: " + (fast ? "§aFULL ODPAL (Co-Tick / Błyskawiczny)" : "§eLEGIT (Płynne rotacje)"));
                logDirect(String.format("  §fNasiona i sadzonki w ekwipunku: §e%d szt.", seedsCount));
                logDirect("  §7(Użycie: #sadzenie <zasięg>, #sadzen 200, #sadzenie fast, #sadzenie legit, #sadzenie stop)");
                return;
            }

            if (first.equals("fast") || first.equals("odpal") || first.equals("rage")) {
                args.getString();
                Baritone.settings().farmFastMode.value = true;
                logDirect("§a[Sadzenie] Tryb SZYBKI (Full Odpal / Co-Tick / Błyskawiczny) WŁĄCZONY!");
                return;
            }

            if (first.equals("legit") || first.equals("slow")) {
                args.getString();
                Baritone.settings().farmFastMode.value = false;
                logDirect("§e[Sadzenie] Tryb LEGIT (Płynne rotacje C2 smootherstep) WŁĄCZONY!");
                return;
            }

            if (first.equals("stop") || first.equals("cancel") || first.equals("off")) {
                args.getString();
                baritone.getFarmProcess().onLostControl();
                baritone.getPathingBehavior().cancelEverything();
                logDirect("§c[Sadzenie] Sadzenie zostało ZATRZYMANE.");
                return;
            }
        }

        args.requireMax(2);
        int range = 0;
        BetterBlockPos origin = null;

        if (args.has(1)) {
            range = args.getAs(Integer.class);
        }

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

        baritone.getFarmProcess().plant(range, origin);
        HomeProcess.setSavedMiningCommand("sadzenie" + (range > 0 ? (" " + range) : ""));
        logDirect(String.format("§a[Sadzenie] Rozpoczynam samo sadzenie nasion%s...", range > 0 ? (" w zasięgu " + range + " bloków") : " (cała dostępna farma)"));
    }

    private int countSeeds() {
        if (ctx.player() == null) return 0;
        Inventory inv = ctx.player().getInventory();
        int total = 0;
        for (ItemStack stack : inv.items) {
            if (stack == null || stack.isEmpty()) continue;
            if (stack.is(Items.WHEAT_SEEDS) || stack.is(Items.CARROT) || stack.is(Items.POTATO)
                    || stack.is(Items.BEETROOT_SEEDS) || stack.is(Items.MELON_SEEDS)
                    || stack.is(Items.PUMPKIN_SEEDS) || stack.is(Items.NETHER_WART)
                    || stack.is(Items.COCOA_BEANS) || stack.is(Items.TORCHFLOWER_SEEDS)) {
                total += stack.getCount();
            }
        }
        return total;
    }

    @Override
    public Stream<String> tabComplete(String label, IArgConsumer args) throws CommandException {
        if (args.hasExactlyOne()) {
            return Stream.of("status", "stop", "50", "100", "200");
        }
        return Stream.empty();
    }

    @Override
    public String getShortDesc() {
        return "Tylko sadzenie nasion na zaoranej ziemi (bez zbierania plonów)";
    }

    @Override
    public List<String> getLongDesc() {
        return Arrays.asList(
                "Komenda #sadzenie (lub #sadzen) wyszukuje puste grządki i natychmiast je obsadza nasionami z ekwipunku.",
                "W tym trybie bot NIE niszczy dojrzałych plonów — skupia się wyłącznie na sadzeniu.",
                "",
                "Użycie:",
                "> #sadzenie - obsadza wszystkie puste grządki w okolicy.",
                "> #sadzenie <zasięg> (np. #sadzen 200) - obsadza puste grządki w zasięgu do podanej liczby bloków.",
                "> #sadzenie stop - zatrzymuje sadzenie."
        );
    }
}
