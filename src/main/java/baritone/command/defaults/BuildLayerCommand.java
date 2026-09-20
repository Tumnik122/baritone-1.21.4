package baritone.command.defaults;

import baritone.Baritone;
import baritone.api.IBaritone;
import baritone.api.command.Command;
import baritone.api.command.argument.IArgConsumer;
import baritone.api.command.exception.CommandException;
import baritone.process.BuilderProcess;

import java.util.Arrays;
import java.util.List;
import java.util.stream.Stream;

/**
 * IMPROVEMENT 5 — #buildlayer
 *
 *   #buildlayer                     -> toggle layer mode on the running build (current settings)
 *   #buildlayer 3                   -> layer mode with 3-block layers
 *   #buildlayer 3 top               -> 3-block layers, top to bottom
 */
public final class BuildLayerCommand extends Command {

    public BuildLayerCommand(IBaritone baritone) {
        super(baritone, "buildlayer", "buildlayers");
    }

    @Override
    public void execute(String label, IArgConsumer args) throws CommandException {
        BuilderProcess proc = (BuilderProcess) baritone.getBuilderProcess();
        if (proc == null || !proc.isActive()) {
            logDirect("No build in progress — start one with #build <schematic> first.");
            return;
        }
        if (args.hasAny()) {
            try {
                Baritone.settings().layerHeight.value = args.getAs(Integer.class);
            } catch (Exception e) {
                logDirect("Usage: #buildlayer [layerHeight] [bottom|top]");
                return;
            }
        }
        if (args.hasAny()) {
            Baritone.settings().layerOrder.value = args.getString().equalsIgnoreCase("top");
        }
        boolean ok = proc.applyLayerMode();
        logDirect(ok
                ? "Layer mode ON — height " + Baritone.settings().layerHeight.value
                  + ", " + (Baritone.settings().layerOrder.value ? "top->bottom" : "bottom->top")
                : "Layer mode could not be applied (no schematic loaded).");
    }

    @Override
    public Stream<String> tabComplete(String label, IArgConsumer args) {
        if (args.hasExactlyOne()) {
            return Stream.of("1", "2", "3", "4", "5");
        }
        if (args.has(2)) {
            return Stream.of("bottom", "top");
        }
        return Stream.empty();
    }

    @Override
    public String getShortDesc() {
        return "Configure layer building mode";
    }

    @Override
    public List<String> getLongDesc() {
        return Arrays.asList(
                "Configure layer building mode on the running build.",
                "",
                "Usage:",
                "> buildlayer - Toggle layer mode on current build",
                "> buildlayer <height> - Build in layers of given height",
                "> buildlayer <height> top - Build in layers from top to bottom",
                "> buildlayer <height> bottom - Build in layers from bottom to top"
        );
    }
}
