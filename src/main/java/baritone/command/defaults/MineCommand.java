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

import baritone.api.BaritoneAPI;
import baritone.api.IBaritone;
import baritone.api.command.Command;
import baritone.api.command.argument.IArgConsumer;
import baritone.api.command.datatypes.ForBlockOptionalMeta;
import baritone.api.command.exception.CommandException;
import baritone.api.utils.BlockOptionalMeta;
import net.minecraft.core.registries.BuiltInRegistries;

import java.util.*;
import java.util.stream.Stream;

public class MineCommand extends Command {

    private static final Map<String, List<String>> ORE_ALIASES = new LinkedHashMap<>();
    private static final Map<String, String> COUNTERPARTS = new LinkedHashMap<>();

    private static final List<String> BASE_LOGS = List.of(
            // Overworld logs
            "oak_log", "spruce_log", "birch_log", "jungle_log", "acacia_log",
            "dark_oak_log", "mangrove_log", "cherry_log", "pale_oak_log",
            // Nether stems
            "crimson_stem", "warped_stem",
            // Stripped logs
            "stripped_oak_log", "stripped_spruce_log", "stripped_birch_log", "stripped_jungle_log",
            "stripped_acacia_log", "stripped_dark_oak_log", "stripped_mangrove_log",
            "stripped_cherry_log", "stripped_pale_oak_log",
            // Stripped nether stems
            "stripped_crimson_stem", "stripped_warped_stem",
            // Wood blocks (all-sided bark)
            "oak_wood", "spruce_wood", "birch_wood", "jungle_wood", "acacia_wood",
            "dark_oak_wood", "mangrove_wood", "cherry_wood", "pale_oak_wood",
            // Nether hyphae
            "crimson_hyphae", "warped_hyphae",
            // Stripped wood & hyphae
            "stripped_oak_wood", "stripped_spruce_wood", "stripped_birch_wood", "stripped_jungle_wood",
            "stripped_acacia_wood", "stripped_dark_oak_wood", "stripped_mangrove_wood",
            "stripped_cherry_wood", "stripped_pale_oak_wood",
            "stripped_crimson_hyphae", "stripped_warped_hyphae",
            // Bamboo
            "bamboo_block", "stripped_bamboo_block"
    );

    static {
        // Rudy / Ores
        ORE_ALIASES.put("diamond", List.of("diamond_ore", "deepslate_diamond_ore"));
        ORE_ALIASES.put("diamonds", List.of("diamond_ore", "deepslate_diamond_ore"));
        ORE_ALIASES.put("iron", List.of("iron_ore", "deepslate_iron_ore"));
        ORE_ALIASES.put("gold", List.of("gold_ore", "deepslate_gold_ore", "nether_gold_ore"));
        ORE_ALIASES.put("coal", List.of("coal_ore", "deepslate_coal_ore"));
        ORE_ALIASES.put("copper", List.of("copper_ore", "deepslate_copper_ore"));
        ORE_ALIASES.put("lapis", List.of("lapis_ore", "deepslate_lapis_ore"));
        ORE_ALIASES.put("redstone", List.of("redstone_ore", "deepslate_redstone_ore"));
        ORE_ALIASES.put("emerald", List.of("emerald_ore", "deepslate_emerald_ore"));
        ORE_ALIASES.put("emeralds", List.of("emerald_ore", "deepslate_emerald_ore"));
        ORE_ALIASES.put("debris", List.of("ancient_debris"));
        ORE_ALIASES.put("netherite", List.of("ancient_debris"));
        ORE_ALIASES.put("quartz", List.of("nether_quartz_ore"));

        // Drewno / Logs (ogólne skróty ścinające wszystkie pnie)
        ORE_ALIASES.put("log", BASE_LOGS);
        ORE_ALIASES.put("logs", BASE_LOGS);
        ORE_ALIASES.put("wood", BASE_LOGS);
        ORE_ALIASES.put("drewno", BASE_LOGS);
        ORE_ALIASES.put("drzewo", BASE_LOGS);
        ORE_ALIASES.put("tree", BASE_LOGS);
        ORE_ALIASES.put("trees", BASE_LOGS);

        // Poszczególne gatunki drewna
        ORE_ALIASES.put("oak", List.of("oak_log", "stripped_oak_log", "oak_wood", "stripped_oak_wood"));
        ORE_ALIASES.put("spruce", List.of("spruce_log", "stripped_spruce_log", "spruce_wood", "stripped_spruce_wood"));
        ORE_ALIASES.put("birch", List.of("birch_log", "stripped_birch_log", "birch_wood", "stripped_birch_wood"));
        ORE_ALIASES.put("jungle", List.of("jungle_log", "stripped_jungle_log", "jungle_wood", "stripped_jungle_wood"));
        ORE_ALIASES.put("acacia", List.of("acacia_log", "stripped_acacia_log", "acacia_wood", "stripped_acacia_wood"));
        ORE_ALIASES.put("dark_oak", List.of("dark_oak_log", "stripped_dark_oak_log", "dark_oak_wood", "stripped_dark_oak_wood"));
        ORE_ALIASES.put("mangrove", List.of("mangrove_log", "stripped_mangrove_log", "mangrove_wood", "stripped_mangrove_wood"));
        ORE_ALIASES.put("cherry", List.of("cherry_log", "stripped_cherry_log", "cherry_wood", "stripped_cherry_wood"));
        ORE_ALIASES.put("pale_oak", List.of("pale_oak_log", "stripped_pale_oak_log", "pale_oak_wood", "stripped_pale_oak_wood"));
        ORE_ALIASES.put("crimson", List.of("crimson_stem", "stripped_crimson_stem", "crimson_hyphae", "stripped_crimson_hyphae"));
        ORE_ALIASES.put("warped", List.of("warped_stem", "stripped_warped_stem", "warped_hyphae", "stripped_warped_hyphae"));
        ORE_ALIASES.put("bamboo", List.of("bamboo_block", "stripped_bamboo_block"));

        registerPair("diamond_ore", "deepslate_diamond_ore");
        registerPair("iron_ore", "deepslate_iron_ore");
        registerPair("gold_ore", "deepslate_gold_ore");
        registerPair("copper_ore", "deepslate_copper_ore");
        registerPair("coal_ore", "deepslate_coal_ore");
        registerPair("lapis_ore", "deepslate_lapis_ore");
        registerPair("redstone_ore", "deepslate_redstone_ore");
        registerPair("emerald_ore", "deepslate_emerald_ore");
    }

    private static void registerPair(String a, String b) {
        COUNTERPARTS.put(a, b);
        COUNTERPARTS.put(b, a);
    }

    private static List<String> getAllLogBlocks() {
        Set<String> logs = new LinkedHashSet<>(BASE_LOGS);
        try {
            for (var key : BuiltInRegistries.BLOCK.keySet()) {
                String path = key.getPath();
                if (path.endsWith("_log") || path.endsWith("_stem") || path.endsWith("_wood") || path.endsWith("_hyphae")
                        || path.equals("bamboo_block") || path.equals("stripped_bamboo_block")) {
                    logs.add("minecraft".equals(key.getNamespace()) ? path : key.toString());
                }
            }
        } catch (Throwable ignored) {}
        return new ArrayList<>(logs);
    }

    public MineCommand(IBaritone baritone) {
        super(baritone, "mine");
    }

    @Override
    public void execute(String label, IArgConsumer args) throws CommandException {
        if (!args.hasAny()) {
            logDirect("§b=== INTELIGENTNE KOPANIE / ŚCINANIE (#MINE) ===");
            logDirect("§fUżycie: §e#mine [ilość] <blok/skrót_rudy/log> [kolejne_bloki...]");
            logDirect("§fInteligentne skróty:");
            logDirect("  §e#mine log / logs §7- ścina wszystkie rodzaje drewna (oak, birch, spruce...)");
            logDirect("  §e#mine diamond   §7- kopie diamond_ore + deepslate_diamond_ore");
            logDirect("  §e#mine iron      §7- kopie iron_ore + deepslate_iron_ore");
            logDirect("  §e#mine gold      §7- kopie gold_ore + deepslate + nether_gold");
            logDirect("  §e#mine coal / copper / lapis / redstone / emerald");
            logDirect("  §e#mine debris    §7- kopie ancient_debris (netheryt)");
            logDirect("  §e#mine 64 log    §7- ścina 64 sztuki drewna i kończy");
            logDirect("  §e#mine 64 diamond §7- wykopuje 64 sztuki i kończy");
            logDirect("§7Ochrona: unikanie lawy (#set mineAvoidLava true), auto-logout (#autologout on).");
            return;
        }

        int quantity = args.getAsOrDefault(Integer.class, 0);
        args.requireMin(1);
        List<BlockOptionalMeta> boms = new ArrayList<>();

        while (args.hasAny()) {
            Integer trailingQty = args.peekAsOrDefault(Integer.class, null);
            if (quantity == 0 && trailingQty != null && trailingQty > 0) {
                args.getAs(Integer.class);
                quantity = trailingQty;
                continue;
            }

            String peek = args.peekString().toLowerCase(Locale.ROOT);
            if (peek.startsWith("minecraft:")) {
                peek = peek.substring("minecraft:".length());
            }

            if (isLogAlias(peek)) {
                args.getString(); // konsumuj token
                List<String> blockNames = getAllLogBlocks();
                for (String name : blockNames) {
                    try {
                        BlockOptionalMeta bom = new BlockOptionalMeta(name);
                        if (!boms.contains(bom)) {
                            boms.add(bom);
                        }
                    } catch (Throwable ignored) {}
                }
            } else if (ORE_ALIASES.containsKey(peek)) {
                args.getString(); // konsumuj token
                List<String> blockNames = ORE_ALIASES.get(peek);
                for (String name : blockNames) {
                    try {
                        BlockOptionalMeta bom = new BlockOptionalMeta(name);
                        if (!boms.contains(bom)) {
                            boms.add(bom);
                        }
                    } catch (Throwable ignored) {}
                }
            } else {
                BlockOptionalMeta bom = args.getDatatypeFor(ForBlockOptionalMeta.INSTANCE);
                if (!boms.contains(bom)) {
                    boms.add(bom);
                }
                // Automatycznie dołącz odpowiednik deepslate/stone jeśli dostępny
                if (bom.getBlock() != null) {
                    String blockPath = BuiltInRegistries.BLOCK.getKey(bom.getBlock()).getPath();
                    String counterpart = COUNTERPARTS.get(blockPath);
                    if (counterpart != null) {
                        try {
                            BlockOptionalMeta cp = new BlockOptionalMeta(counterpart);
                            if (!boms.contains(cp)) {
                                boms.add(cp);
                            }
                        } catch (Throwable ignored) {}
                    }
                }
            }
        }

        BaritoneAPI.getProvider().getWorldScanner().repack(ctx);
        String targetDesc = boms.size() > 6
                ? String.format("drewno / pnie (%d rodzajów bloków)", boms.size())
                : boms.toString();
        logDirect(String.format("§a[Mine] Rozpoczynam kopanie: §f%s %s",
                targetDesc,
                quantity > 0 ? "§e(cel: " + quantity + " sztuk)" : "§7(ciągłe)"));
        baritone.getMineProcess().mine(quantity, boms.toArray(new BlockOptionalMeta[0]));
    }

    private static boolean isLogAlias(String peek) {
        return "log".equals(peek) || "logs".equals(peek) || "wood".equals(peek)
                || "drewno".equals(peek) || "drzewo".equals(peek)
                || "tree".equals(peek) || "trees".equals(peek);
    }

    @Override
    public Stream<String> tabComplete(String label, IArgConsumer args) throws CommandException {
        args.getAsOrDefault(Integer.class, 0);
        while (args.has(2)) {
            String peek = args.peekString().toLowerCase(Locale.ROOT);
            if (ORE_ALIASES.containsKey(peek) || isLogAlias(peek)) {
                args.getString();
            } else {
                args.getDatatypeFor(ForBlockOptionalMeta.INSTANCE);
            }
        }

        String prefix = args.hasAny() ? args.peekString().toLowerCase(Locale.ROOT) : "";
        Stream<String> aliasStream = ORE_ALIASES.keySet().stream()
                .filter(a -> a.startsWith(prefix));
        Stream<String> defaultStream = args.tabCompleteDatatype(ForBlockOptionalMeta.INSTANCE);
        return Stream.concat(aliasStream, defaultStream).distinct();
    }

    @Override
    public String getShortDesc() {
        return "Mine some blocks with smart ore and log resolution";
    }

    @Override
    public List<String> getLongDesc() {
        return Arrays.asList(
                "The mine command tells Baritone to search for and mine individual blocks.",
                "Supports ore and log aliases (e.g. #mine diamond, #mine log).",
                "",
                "Usage:",
                "> #mine log - Cuts all types of logs and wood",
                "> #mine 64 log - Cuts 64 logs and stops",
                "> #mine diamond - Mines all diamonds (including deepslate)",
                "> #mine iron gold coal",
                "> #mine 64 ancient_debris"
        );
    }
}
