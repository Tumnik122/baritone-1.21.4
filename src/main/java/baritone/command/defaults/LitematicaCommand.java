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
import baritone.api.command.datatypes.RelativeBlockPos;
import baritone.api.command.exception.CommandException;
import baritone.api.command.exception.CommandInvalidStateException;
import baritone.api.utils.BetterBlockPos;
import baritone.utils.schematic.litematica.LitematicaHelper;
import org.apache.commons.io.FilenameUtils;

import java.io.File;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.stream.Stream;

public class LitematicaCommand extends Command {

    private final File schematicsDir;

    public LitematicaCommand(IBaritone baritone) {
        super(baritone, "litematica");
        this.schematicsDir = new File(baritone.getPlayerContext().minecraft().gameDirectory, "schematics");
    }

    @Override
    public void execute(String label, IArgConsumer args) throws CommandException {
        // Case 1: No arguments -> auto-build loaded placement or single schematic file
        if (!args.hasAny()) {
            if (LitematicaHelper.isLitematicaPresent()) {
                if (LitematicaHelper.hasAnyPlacements()) {
                    baritone.getBuilderProcess().buildOpenLitematic(-1);
                    return;
                }
                logDirect("Litematica is running, but no placements are loaded in GUI.");
                logDirect("Tip: Press 'M' -> 'Load Schematics' -> 'Create Placement', or use #litematica <file> to build directly.");
            } else {
                logDirect("Litematica mod is not installed. Looking for .litematic files in schematics/ folder...");
            }

            List<File> files = getLitematicFiles();
            if (files.isEmpty()) {
                logDirect("No .litematic files found in " + schematicsDir.getPath());
                logDirect("Usage: #litematica [list | <placement#> | <filename> [x y z]]");
                return;
            }
            if (files.size() == 1) {
                File single = files.get(0);
                logDirect("Found single schematic: " + single.getName() + ", building at player feet...");
                buildFromFile(single, ctx.playerFeet());
                return;
            }
            logDirect("Available .litematic files in schematics/:");
            for (File f : files) {
                logDirect("  • " + f.getName());
            }
            logDirect("Use: #litematica <filename>");
            return;
        }

        String firstArg = args.peekString();

        // Case 2: #litematica list
        if ("list".equalsIgnoreCase(firstArg)) {
            args.get();
            logDirect("=== Litematica Placements & Files ===");
            if (LitematicaHelper.isLitematicaPresent()) {
                List<String> descs = LitematicaHelper.getPlacementDescriptions();
                if (descs.isEmpty()) {
                    logDirect("Litematica GUI placements: (none loaded)");
                } else {
                    logDirect("Litematica GUI placements (" + descs.size() + "):");
                    for (String d : descs) {
                        logDirect("  " + d);
                    }
                }
            } else {
                logDirect("Litematica mod: (not installed)");
            }

            List<File> files = getLitematicFiles();
            logDirect("Files in " + schematicsDir.getName() + "/ (" + files.size() + "):");
            for (File f : files) {
                logDirect("  • " + f.getName());
            }
            return;
        }

        // Case 3: #litematica <number> -> build placement index from Litematica mod
        if (args.is(Integer.class)) {
            args.requireMax(1);
            int placementIndex = args.getAs(Integer.class) - 1;
            baritone.getBuilderProcess().buildOpenLitematic(placementIndex);
            return;
        }

        // Case 4: #litematica <filename> [x y z] -> build .litematic file directly!
        String filename = args.getString();
        File file = resolveFile(filename);

        if (!file.exists()) {
            throw new CommandInvalidStateException("Cannot find schematic file: " + file.getName() + " in " + schematicsDir.getPath());
        }

        BetterBlockPos origin = ctx.playerFeet();
        BetterBlockPos buildOrigin;
        if (args.hasAny()) {
            args.requireMax(3);
            buildOrigin = args.getDatatypePost(RelativeBlockPos.INSTANCE, origin);
        } else {
            buildOrigin = origin;
        }

        buildFromFile(file, buildOrigin);
    }

    private void buildFromFile(File file, BetterBlockPos buildOrigin) throws CommandException {
        boolean success = baritone.getBuilderProcess().build(file.getName(), file, buildOrigin);
        if (!success) {
            throw new CommandInvalidStateException("Failed to load schematic from file: " + file.getName() + ". Check logs for details.");
        }
        logDirect(String.format("Successfully loaded '%s' for building at %s", file.getName(), buildOrigin));
    }

    private File resolveFile(String filename) {
        if (!filename.toLowerCase().endsWith(".litematic")) {
            File withExt = new File(schematicsDir, filename + ".litematic");
            if (withExt.exists()) {
                return withExt;
            }
        }
        File direct = new File(schematicsDir, filename);
        if (direct.exists()) {
            return direct;
        }
        return new File(schematicsDir, filename.toLowerCase().endsWith(".litematic") ? filename : filename + ".litematic");
    }

    private List<File> getLitematicFiles() {
        if (!schematicsDir.exists() || !schematicsDir.isDirectory()) {
            return Collections.emptyList();
        }
        File[] files = schematicsDir.listFiles((dir, name) -> name.toLowerCase().endsWith(".litematic"));
        if (files == null) {
            return Collections.emptyList();
        }
        return Arrays.asList(files);
    }

    @Override
    public Stream<String> tabComplete(String label, IArgConsumer args) throws CommandException {
        if (args.hasExactlyOne()) {
            List<String> options = new ArrayList<>();
            options.add("list");
            if (LitematicaHelper.isLitematicaPresent()) {
                int count = LitematicaHelper.getPlacementCount();
                for (int i = 1; i <= count; i++) {
                    options.add(String.valueOf(i));
                }
            }
            for (File f : getLitematicFiles()) {
                options.add(f.getName());
                options.add(FilenameUtils.removeExtension(f.getName()));
            }
            String prefix = args.getString().toLowerCase();
            return options.stream().filter(s -> s.toLowerCase().startsWith(prefix)).distinct();
        } else if (args.has(2) && !args.is(Integer.class)) {
            args.get();
            return args.tabCompleteDatatype(RelativeBlockPos.INSTANCE);
        }
        return Stream.empty();
    }

    @Override
    public String getShortDesc() {
        return "Builds Litematica schematics from the mod or files";
    }

    @Override
    public List<String> getLongDesc() {
        return Arrays.asList(
                "Build a schematic currently open in Litematica, or build a .litematic file directly.",
                "",
                "Usage:",
                "> litematica                 - Build selected/loaded schematic from Litematica mod",
                "> litematica <#>             - Build placement # from Litematica mod (1, 2, ...)",
                "> litematica list            - List active placements and available .litematic files",
                "> litematica <file> [x y z]  - Build a .litematic file directly (bypasses Litematica mod!)"
        );
    }
}