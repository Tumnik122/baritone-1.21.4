package baritone.command.defaults;

import baritone.api.IBaritone;
import baritone.api.command.Command;
import baritone.api.command.argument.IArgConsumer;
import baritone.api.command.exception.CommandException;
import com.astra.revolution.nuker.VenturaNukerModule;

import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.stream.Stream;

/**
 * Komenda #nuker do zarządzania modułem Ventura Nuker.
 */
public class NukerCommand extends Command {

    public NukerCommand(IBaritone baritone) {
        super(baritone, "nuker", "venturanuker");
    }

    @Override
    public void execute(String label, IArgConsumer args) throws CommandException {
        VenturaNukerModule nuker = VenturaNukerModule.getInstance();

        if (!args.hasAny()) {
            boolean newState = !nuker.isEnabled();
            nuker.setEnabled(newState);
            logDirect("§d[Ventura Nuker] §fStatus: " + (newState ? "§aWŁĄCZONY" : "§cWYŁĄCZONY"));
            logDirect("§7Tryb: §e" + nuker.getMode().getTitle() + " §7| Rotacja: §b" + nuker.getRotationMode().label + " §7| Zasięg: §a" + nuker.getRange() + "m");
            return;
        }

        String sub = args.getString().toLowerCase(Locale.ROOT);
        switch (sub) {
            case "on", "enable", "start" -> {
                nuker.setEnabled(true);
                logDirect("§d[Ventura Nuker] §aAktywowano moduł.");
            }
            case "off", "disable", "stop" -> {
                nuker.setEnabled(false);
                logDirect("§d[Ventura Nuker] §cWyłączono moduł.");
            }
            case "range" -> {
                if (args.hasAny()) {
                    float val = Float.parseFloat(args.getString());
                    nuker.setRange(val);
                    logDirect("§d[Ventura Nuker] §fUstawiono zasięg: §a" + val + "m");
                } else {
                    logDirect("§d[Ventura Nuker] §fAktualny zasięg: §a" + nuker.getRange() + "m");
                }
            }
            case "delay" -> {
                if (args.hasAny()) {
                    float val = Float.parseFloat(args.getString());
                    nuker.setDelayMs(val);
                    logDirect("§d[Ventura Nuker] §fUstawiono delay: §e" + val + "ms");
                } else {
                    logDirect("§d[Ventura Nuker] §fAktualny delay: §e" + nuker.getDelayMs() + "ms");
                }
            }
            case "flatten", "flat" -> {
                boolean state = !nuker.isFlat();
                nuker.setFlat(state);
                logDirect("§d[Ventura Nuker] §fFlat (ochrona podłogi): " + (state ? "§aWŁĄCZONA" : "§cWYŁĄCZONA"));
            }
            case "lava" -> {
                boolean state = !nuker.isAvoidLava();
                nuker.setAvoidLava(state);
                logDirect("§d[Ventura Nuker] §fAvoid Lava: " + (state ? "§aWŁĄCZONA" : "§cWYŁĄCZONA"));
            }
            case "mode" -> {
                if (args.hasAny()) {
                    String m = args.getString().toUpperCase(Locale.ROOT);
                    try {
                        nuker.setMode(VenturaNukerModule.Mode.valueOf(m));
                        logDirect("§d[Ventura Nuker] §fUstawiono tryb: §e" + nuker.getMode().getTitle());
                    } catch (IllegalArgumentException e) {
                        logDirect("§cDostępne tryby: NORMAL, FAST, FASTEST");
                    }
                } else {
                    logDirect("§d[Ventura Nuker] §fAktualny tryb: §e" + nuker.getMode().getTitle());
                }
            }
            case "rot", "rotation" -> {
                if (args.hasAny()) {
                    String r = args.getString().toUpperCase(Locale.ROOT);
                    try {
                        nuker.setRotationMode(VenturaNukerModule.RotationMode.valueOf(r));
                        logDirect("§d[Ventura Nuker] §fTryb rotacji: §b" + nuker.getRotationMode().label);
                    } catch (IllegalArgumentException e) {
                        logDirect("§cDostępne rotacje: SILENT_GCD, CLIENT_SMOOTH, OFF");
                    }
                } else {
                    logDirect("§d[Ventura Nuker] §fAktualna rotacja: §b" + nuker.getRotationMode().label);
                }
            }
            default -> {
                logDirect("§d=== VENTURA NUKER POMOC ===");
                logDirect("§e#nuker §7- przełącza on/off");
                logDirect("§e#nuker on / off §7- włącza / wyłącza");
                logDirect("§e#nuker range <1-50> §7- promień niszczenia");
                logDirect("§e#nuker delay <ms> §7- opóźnienie w trybie delay");
                logDirect("§e#nuker mode <NORMAL|FAST|FASTEST> §7- wybór trybu");
                logDirect("§e#nuker rot <SILENT_GCD|CLIENT_SMOOTH|OFF> §7- tryb rotacji");
                logDirect("§e#nuker flatten §7- ochrona przed dziurami w podłodze");
                logDirect("§e#nuker lava §7- unikanie odsłonięcia lawy");
            }
        }
    }

    @Override
    public Stream<String> tabComplete(String label, IArgConsumer args) {
        try {
            if (args.hasExactlyOne()) {
                String cur = args.getString().toLowerCase(Locale.ROOT);
                return Stream.of("on", "off", "range", "delay", "mode", "rot", "flatten", "lava")
                        .filter(s -> s.startsWith(cur));
            }
        } catch (Exception ignored) {}
        return Stream.empty();
    }

    @Override
    public String getShortDesc() {
        return "Zarządza wielowątkowym modułem Ventura Nuker";
    }

    @Override
    public List<String> getLongDesc() {
        return Arrays.asList(
                "Włącza lub konfiguruje moduł Ventura Nuker.",
                "Użycie:",
                "#nuker - przełącza stan modułu",
                "#nuker on / off - włącza lub wyłącza",
                "#nuker range <metry> - zasięg niszczenia bloków",
                "#nuker delay <ms> - interwał niszczenia",
                "#nuker mode <PACKET|DELAY|CLICK> - tryb niszczenia",
                "#nuker rot <SILENT_GCD|CLIENT_SMOOTH|OFF> - rotacja raycasta"
        );
    }
}
