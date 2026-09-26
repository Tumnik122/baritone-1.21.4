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

    static {
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

    public MineCommand(IBaritone baritone) {
        super(baritone, "mine");
    }

    @Override
    public void execute(String label, IArgConsumer args) throws CommandException {
        if (!args.hasAny()) {
            logDirect("§b=== INTELIGENTNE KOPANIE (#MINE) ===");
            logDirect("§fUżycie: §e#mine [ilość] <blok/skrót_rudy> [kolejne_bloki...]");
            logDirect("§fInteligentne skróty (automatycznie dodają wariant stone i deepslate!):");
            logDirect("  §e#mine diamond   §7- kopie diamond_ore + deepslate_diamond_ore");
            logDirect("  §e#mine iron      §7- kopie iron_ore + deepslate_iron_ore");
            logDirect("  §e#mine gold      §7- kopie gold_ore + deepslate + nether_gold");
            logDirect("  §e#mine coal / copper / lapis / redstone / emerald");
            logDirect("  §e#mine debris    §7- kopie ancient_debris (netheryt)");
            logDirect("  §e#mine 64 diamond §7- wykopuje 64 sztuki i kończy");
            logDirect("§7Ochrona: unikanie lawy (#set mineAvoidLava true), auto-logout (#autologout on).");
            return;
        }

        int quantity = args.getAsOrDefault(Integer.class, 0);
        args.requireMin(1);
        List<BlockOptionalMeta> boms = new ArrayList<>();

        while (args.hasAny()) {
            String peek = args.peekString().toLowerCase(Locale.ROOT);
            if (peek.startsWith("minecraft:")) {
                peek = peek.substring("minecraft:".length());
            }

            if (ORE_ALIASES.containsKey(peek)) {
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
        logDirect(String.format("§a[Mine] Rozpoczynam kopanie: %s %s",
                boms.toString(),
                quantity > 0 ? "§e(cel: " + quantity + " sztuk)" : "§7(ciągłe)"));
        baritone.getMineProcess().mine(quantity, boms.toArray(new BlockOptionalMeta[0]));
    }

    @Override
    public Stream<String> tabComplete(String label, IArgConsumer args) throws CommandException {
        args.getAsOrDefault(Integer.class, 0);
        while (args.has(2)) {
            String peek = args.peekString().toLowerCase(Locale.ROOT);
            if (ORE_ALIASES.containsKey(peek)) {
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
        return "Mine some blocks with smart ore resolution";
    }

    @Override
    public List<String> getLongDesc() {
        return Arrays.asList(
                "The mine command tells Baritone to search for and mine individual blocks.",
                "Supports ore aliases (e.g. #mine diamond mines both diamond_ore and deepslate_diamond_ore).",
                "",
                "Usage:",
                "> #mine diamond - Mines all diamonds (including deepslate)",
                "> #mine iron gold coal",
                "> #mine 64 ancient_debris"
        );
    }
}
