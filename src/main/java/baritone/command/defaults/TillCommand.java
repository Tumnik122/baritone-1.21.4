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
import baritone.api.command.Command;
import baritone.api.command.argument.IArgConsumer;
import baritone.api.command.exception.CommandException;
import baritone.api.command.exception.CommandInvalidStateException;
import baritone.api.selection.ISelection;
import baritone.process.TillProcess;

import java.util.Arrays;
import java.util.List;
import java.util.stream.Stream;

/**
 * #till / #zaorz — Zaora wszystkie bloki ziemi i trawy w zaznaczeniu (#sel 1 + #sel 2).
 *
 * Użycie:
 *   #sel 1    → ustaw narożnik 1
 *   #sel 2    → ustaw narożnik 2
 *   #till     → start orania
 *   #stop     → stop
 */
public class TillCommand extends Command {

    private TillProcess tillProcess;

    public TillCommand(IBaritone baritone) {
        super(baritone, "till", "zaorz");
        // TillProcess wymaga konkretnej klasy Baritone
        if (baritone instanceof Baritone concrete) {
            this.tillProcess = concrete.getTillProcess();
        }
    }

    @Override
    public void execute(String label, IArgConsumer args) throws CommandException {
        args.requireMax(0);

        if (tillProcess == null) {
            throw new CommandInvalidStateException("TillProcess niedostępny.");
        }

        ISelection[] selections = baritone.getSelectionManager().getSelections();
        if (selections == null || selections.length == 0) {
            throw new CommandInvalidStateException(
                    "Brak zaznaczenia! Użyj #sel 1 i #sel 2 aby zaznaczyć obszar do zaorania."
            );
        }

        tillProcess.till();
    }

    @Override
    public Stream<String> tabComplete(String label, IArgConsumer args) throws CommandException {
        return Stream.empty();
    }

    @Override
    public String getShortDesc() {
        return "Zaora zaznaczony obszar motyką";
    }

    @Override
    public List<String> getLongDesc() {
        return Arrays.asList(
                "Komenda #till zaora wszystkie bloki ziemi i trawy w zaznaczonym obszarze.",
                "",
                "Jak używać:",
                "> #sel 1  — ustaw narożnik 1 zaznaczenia (Twoja pozycja lub podaj XYZ)",
                "> #sel 2  — ustaw narożnik 2 zaznaczenia",
                "> #till   — bot zacznie chodzić i orać motyką (prawy przycisk myszy)",
                "> #stop   — zatrzymaj",
                "",
                "Wymagania:",
                "- Dowolna motyka (drewniana, kamienna, żelazna, złota, diamentowa, netherite) w hotbarze",
                "- Aktywne zaznaczenie (#sel 1 + #sel 2)",
                "",
                "Zaorywane bloki: Trawa, Ziemia, Coarse Dirt, Rooted Dirt, Dirt Path, Podzol, Mycelium, Mud"
        );
    }
}
