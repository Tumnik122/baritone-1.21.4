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

import baritone.api.IBaritone;
import baritone.api.command.Command;
import baritone.api.command.argument.IArgConsumer;
import baritone.api.command.exception.CommandException;
import baritone.hud.AiActionLogger;

import java.io.File;
import java.util.Arrays;
import java.util.List;
import java.util.stream.Stream;

/**
 * Komenda #ai / #status generująca szczegółowy zrzut diagnostyczny
 * aktualnego stanu, ścieżki i procesów Baritone do skopiowania dla AI.
 */
public class AiStatusCommand extends Command {

    public AiStatusCommand(IBaritone baritone) {
        super(baritone, "ai", "aistatus", "status");
    }

    @Override
    public void execute(String label, IArgConsumer args) throws CommandException {
        args.requireMax(0);

        String report = AiActionLogger.generateAiDiagnosticReport();
        File file = AiActionLogger.saveReportToFile();

        logDirect(report);
        if (file != null) {
            logDirect("💾 Zapisano pełny raport do pliku: " + file.getAbsolutePath());
        }
    }

    @Override
    public Stream<String> tabComplete(String label, IArgConsumer args) {
        return Stream.empty();
    }

    @Override
    public String getShortDesc() {
        return "Generuje szczegółowy raport stanu Baritone dla AI";
    }

    @Override
    public List<String> getLongDesc() {
        return Arrays.asList(
                "Komenda #ai generuje kompletny raport w formacie Markdown z informacjami o:",
                "- Aktualnym procesie i zadaniu",
                "- Krokach ścieżki, typie ruchu (traverse/ascend/fall) i kosztach",
                "- Stanie wyszukiwania A* (nodes explored)",
                "- Celowniku, blokach do wykopania i pozycjach",
                "- Wciskanych klawiszach",
                "",
                "Raport jest również automatycznie zapisywany do .minecraft/baritone/ai_state.md",
                "",
                "Użycie:",
                "> #ai - Wyświetl i zapisz raport dla AI"
        );
    }
}
