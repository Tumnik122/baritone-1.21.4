package baritone.command.defaults;

import baritone.Baritone;
import baritone.api.IBaritone;
import baritone.api.command.Command;
import baritone.api.command.argument.IArgConsumer;
import baritone.api.command.exception.CommandException;
import baritone.process.SpruceTreeProcess;

import java.util.Arrays;
import java.util.List;
import java.util.stream.Stream;

public class SpruceCommand extends Command {

    public SpruceCommand(IBaritone baritone) {
        super(baritone, "spruce", "treefarm", "farmtree");
    }

    @Override
    public void execute(String label, IArgConsumer args) throws CommandException {
        SpruceTreeProcess process = ((Baritone) baritone).getSpruceTreeProcess();

        if (args.hasAny()) {
            String action = args.getString().toLowerCase();
            if (action.equals("stop") || action.equals("off")) {
                process.stop();
                logDirect("§c[Astra/Spruce2x2] Proces zostal zatrzymany.");
                return;
            }
        }

        if (process.isActive()) {
            process.stop();
            logDirect("§c[Astra/Spruce2x2] Proces zostal wylaczony.");
        } else {
            process.start();
        }
    }

    @Override
    public Stream<String> tabComplete(String label, IArgConsumer args) {
        if (args.hasExactlyOne()) {
            return Stream.of("start", "stop", "off");
        }
        return Stream.empty();
    }

    @Override
    public String getShortDesc() {
        return "Autonomiczna farma swierkow 2x2 (GrimAC 3.9 reach, zero-WASD)";
    }

    @Override
    public List<String> getLongDesc() {
        return Arrays.asList(
                "Uruchamia lub zatrzymuje nieskonczona petle farmienia swierkow 2x2.",
                "",
                "Uzycie:",
                "> spruce - przelacza proces (start/stop)",
                "> spruce stop - natychmiast zatrzymuje bota",
                "> treefarm - alias dla komendy spruce"
        );
    }
}

