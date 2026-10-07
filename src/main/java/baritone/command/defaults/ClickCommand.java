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

import com.astra.revolution.gui.BaritoneBypassScreen;
import net.minecraft.client.Minecraft;

import java.util.Arrays;
import java.util.List;
import java.util.stream.Stream;

public class ClickCommand extends Command {

    public ClickCommand(IBaritone baritone) {
        super(baritone, "click", "gui", "astra", "menu");
    }

    @Override
    public void execute(String label, IArgConsumer args) throws CommandException {
        args.requireMax(0);
        Minecraft mc = Minecraft.getInstance();
        mc.execute(() -> mc.setScreen(new BaritoneBypassScreen()));
        logDirect("Otwarto Baritone Astra // Revolution GUI (v2.4). Klawisz: Prawy Shift (Right Shift)");
    }

    @Override
    public Stream<String> tabComplete(String label, IArgConsumer args) {
        return Stream.empty();
    }

    @Override
    public String getShortDesc() {
        return "Open Astra Revolution GUI";
    }

    @Override
    public List<String> getLongDesc() {
        return Arrays.asList(
                "Otwiera nowoczesne GUI Baritone Astra // Revolution (v2.4).",
                "Możesz także użyć klawisza Prawy Shift (Right Shift) w grze.",
                "",
                "Użycie:",
                "> #click",
                "> #gui",
                "> #astra",
                "> #menu"
        );
    }
}
