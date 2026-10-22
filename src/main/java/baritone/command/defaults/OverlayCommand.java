package baritone.command.defaults;

import baritone.Baritone;
import baritone.api.IBaritone;
import baritone.api.command.Command;
import baritone.api.command.argument.IArgConsumer;
import baritone.api.command.exception.CommandException;
import baritone.api.utils.SettingsUtil;

import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.stream.Stream;

/**
 * Komenda #overlay / #wave do zarządzania shaderowym Block Overlay w stylu Ventura Client.
 */
public class OverlayCommand extends Command {

    public OverlayCommand(IBaritone baritone) {
        super(baritone, "overlay", "wave", "blockoverlay");
    }

    @Override
    public void execute(String label, IArgConsumer args) throws CommandException {
        var s = Baritone.settings();

        if (!args.hasAny()) {
            logDirect("§b=== VENTURA CLIENT STYLE: SHADER BLOCK OVERLAY & WAVE ===");
            logDirect(String.format("§fGłówny Block Overlay: %s", s.clientBlockOverlay.value ? "§aWŁĄCZONY" : "§cWYŁĄCZONY"));
            logDirect(String.format("§fEfekt fali (Wave Visuals): %s", s.blockOverlayWave.value ? "§aWŁĄCZONY" : "§cWYŁĄCZONY"));
            logDirect(String.format("§fPłynna interpolacja (Smooth Box): %s §7(prędkość: §b%.1f§7)",
                    s.blockOverlaySmooth.value ? "§aWŁĄCZONA" : "§cWYŁĄCZONA", s.blockOverlaySpeed.value));
            logDirect(String.format("§fGrubość obramowania (Line Width): §b%.1f px", s.blockOverlayLineWidth.value));
            logDirect(String.format("§fWidoczność przez ściany (Ignore Depth): %s", s.blockOverlayIgnoreDepth.value ? "§aTAK" : "§cNIE"));
            logDirect(String.format("§fFala na kopanym bloku bota (Mining Wave): %s", s.renderMiningWaveOverlay.value ? "§aWŁĄCZONA" : "§cWYŁĄCZONA"));
            logDirect("§fDostępne opcje:");
            logDirect("  §e#overlay <on/off> §7- włącz/wyłącz customowy block overlay");
            logDirect("  §e#overlay wave <on/off> §7- włącz/wyłącz harmoniczne fale gradientowe");
            logDirect("  §e#overlay smooth <on/off> §7- włącz/wyłącz płynny ślizg ramki między blokami");
            logDirect("  §e#overlay speed <wartość> §7- prędkość ślizgu (domyślnie: 20)");
            logDirect("  §e#overlay width <wartość> §7- grubość obramowania (domyślnie: 2.5)");
            logDirect("  §e#overlay depth <on/off> §7- przenikanie przez ściany");
            logDirect("  §e#overlay mining <on/off> §7- podświetlanie celu wydobywczego Baritone");
            return;
        }

        String sub = args.getString().toLowerCase(Locale.ROOT);

        if (sub.equals("on") || sub.equals("true") || sub.equals("enable")) {
            s.clientBlockOverlay.value = true;
            saveSettings();
            logDirect("§a[Block Overlay] Włączono shaderowy block overlay w stylu Ventura Client.");
            return;
        }
        if (sub.equals("off") || sub.equals("false") || sub.equals("disable")) {
            s.clientBlockOverlay.value = false;
            saveSettings();
            logDirect("§c[Block Overlay] Wyłączono customowy block overlay (powrót do waniliowej ramki).");
            return;
        }

        if (sub.equals("wave")) {
            if (args.hasAny()) {
                String val = args.getString().toLowerCase(Locale.ROOT);
                s.blockOverlayWave.value = val.equals("on") || val.equals("true") || val.equals("1");
            } else {
                s.blockOverlayWave.value = !s.blockOverlayWave.value;
            }
            saveSettings();
            logDirect("§b[Block Overlay] Efekt fali (Wave Visuals): " + (s.blockOverlayWave.value ? "§aWŁĄCZONY" : "§cWYŁĄCZONY"));
            return;
        }

        if (sub.equals("smooth")) {
            if (args.hasAny()) {
                String val = args.getString().toLowerCase(Locale.ROOT);
                s.blockOverlaySmooth.value = val.equals("on") || val.equals("true") || val.equals("1");
            } else {
                s.blockOverlaySmooth.value = !s.blockOverlaySmooth.value;
            }
            saveSettings();
            logDirect("§b[Block Overlay] Płynna interpolacja pozycji ramki: " + (s.blockOverlaySmooth.value ? "§aWŁĄCZONA" : "§cWYŁĄCZONA"));
            return;
        }

        if (sub.equals("speed")) {
            if (!args.hasAny()) {
                logDirect("§c[Block Overlay] Podaj prędkość interpolacji, np. #overlay speed 20");
                return;
            }
            try {
                float val = Float.parseFloat(args.getString());
                s.blockOverlaySpeed.value = Math.max(1.0F, Math.min(100.0F, val));
                saveSettings();
                logDirect(String.format("§a[Block Overlay] Ustawiono prędkość ślizgu ramki: %.1f", s.blockOverlaySpeed.value));
            } catch (NumberFormatException e) {
                logDirect("§c[Block Overlay] Błędna liczba.");
            }
            return;
        }

        if (sub.equals("width") || sub.equals("line")) {
            if (!args.hasAny()) {
                logDirect("§c[Block Overlay] Podaj grubość linii, np. #overlay width 2.5");
                return;
            }
            try {
                float val = Float.parseFloat(args.getString());
                s.blockOverlayLineWidth.value = Math.max(0.5F, Math.min(10.0F, val));
                saveSettings();
                logDirect(String.format("§a[Block Overlay] Ustawiono grubość linii obramowania: %.1f px", s.blockOverlayLineWidth.value));
            } catch (NumberFormatException e) {
                logDirect("§c[Block Overlay] Błędna liczba.");
            }
            return;
        }

        if (sub.equals("depth")) {
            if (args.hasAny()) {
                String val = args.getString().toLowerCase(Locale.ROOT);
                s.blockOverlayIgnoreDepth.value = val.equals("on") || val.equals("true") || val.equals("1");
            } else {
                s.blockOverlayIgnoreDepth.value = !s.blockOverlayIgnoreDepth.value;
            }
            saveSettings();
            logDirect("§b[Block Overlay] Ignorowanie głębokości (widok przez ściany): " + (s.blockOverlayIgnoreDepth.value ? "§aWŁĄCZONE" : "§cWYŁĄCZONE"));
            return;
        }

        if (sub.equals("radial") || sub.equals("groundwave") || sub.equals("ripple")) {
            if (args.hasAny()) {
                String val = args.getString().toLowerCase(Locale.ROOT);
                s.radialWave.value = val.equals("on") || val.equals("true") || val.equals("1");
            } else {
                s.radialWave.value = !s.radialWave.value;
            }
            saveSettings();
            logDirect("§b[Block Overlay] Fala bloków od gracza (Radial Ground Wave): " + (s.radialWave.value ? "§aWŁĄCZONA" : "§cWYŁĄCZONA"));
            return;
        }

        if (sub.equals("radius")) {
            if (!args.hasAny()) {
                logDirect("§c[Block Overlay] Podaj promień fali, np. #overlay radius 7.5");
                return;
            }
            try {
                float val = Float.parseFloat(args.getString());
                s.radialWaveRadius.value = Math.max(2.0F, Math.min(25.0F, val));
                saveSettings();
                logDirect(String.format("§a[Block Overlay] Ustawiono promień fali rozchodzącej się od gracza: %.1f m", s.radialWaveRadius.value));
            } catch (NumberFormatException e) {
                logDirect("§c[Block Overlay] Błędna liczba.");
            }
            return;
        }

        if (sub.equals("trigger") || sub.equals("jump")) {
            if (!args.hasAny()) {
                logDirect("§c[Block Overlay] Wybierz tryb wyzwalania: jump, land, always");
                return;
            }
            String mode = args.getString().toLowerCase(Locale.ROOT);
            if (mode.startsWith("jump")) {
                s.radialWaveTrigger.value = 1;
                logDirect("§a[Block Overlay] Fala wyzwalana: TYLKO PRZY SKOKU.");
            } else if (mode.startsWith("land")) {
                s.radialWaveTrigger.value = 0;
                logDirect("§a[Block Overlay] Fala wyzwalana: PRZY SKOKU I LĄDOWANIU.");
            } else {
                s.radialWaveTrigger.value = 2;
                logDirect("§a[Block Overlay] Fala wyzwalana: CIĄGŁA (ZAWSZE).");
            }
            saveSettings();
            return;
        }

        if (sub.equals("alpha") || sub.equals("glass")) {
            if (!args.hasAny()) {
                logDirect("§c[Block Overlay] Podaj przezroczystość szkła (0.05 do 0.8), np. #overlay alpha 0.25");
                return;
            }
            try {
                float val = Float.parseFloat(args.getString());
                s.blockOverlayAlpha.value = Math.max(0.02F, Math.min(0.95F, val));
                saveSettings();
                logDirect(String.format("§a[Block Overlay] Ustawiono przezroczystość szkła: %.2f", s.blockOverlayAlpha.value));
            } catch (NumberFormatException e) {
                logDirect("§c[Block Overlay] Błędna liczba.");
            }
            return;
        }

        if (sub.equals("mining")) {
            if (args.hasAny()) {
                String val = args.getString().toLowerCase(Locale.ROOT);
                s.renderMiningWaveOverlay.value = val.equals("on") || val.equals("true") || val.equals("1");
            } else {
                s.renderMiningWaveOverlay.value = !s.renderMiningWaveOverlay.value;
            }
            saveSettings();
            logDirect("§b[Block Overlay] Fala na kopanym bloku bota (Mining Target): " + (s.renderMiningWaveOverlay.value ? "§aWŁĄCZONA" : "§cWYŁĄCZONA"));
            return;
        }

        logDirect("§c[Block Overlay] Nieznana opcja: '" + sub + "'. Wpisz #overlay aby wyświetlić pomoc.");
    }

    private void saveSettings() {
        try {
            SettingsUtil.save(Baritone.settings());
        } catch (Throwable ignored) {}
    }

    @Override
    public Stream<String> tabComplete(String label, IArgConsumer args) throws CommandException {
        if (args.has(2)) {
            String sub = args.getString().toLowerCase(Locale.ROOT);
            if (sub.equals("wave") || sub.equals("smooth") || sub.equals("depth") || sub.equals("mining") || sub.equals("radial")) {
                String cur = args.hasAny() ? args.getString().toLowerCase(Locale.ROOT) : "";
                return Stream.of("on", "off").filter(s -> s.startsWith(cur));
            }
        }
        String cur = args.hasAny() ? args.getString().toLowerCase(Locale.ROOT) : "";
        return Stream.of("on", "off", "wave", "smooth", "speed", "width", "depth", "mining", "radial", "radius", "alpha").filter(s -> s.startsWith(cur));
    }

    @Override
    public String getShortDesc() {
        return "Configure Ventura Client style shader block overlay & wave visuals";
    }

    @Override
    public List<String> getLongDesc() {
        return Arrays.asList(
                "Configures smooth, shader-based block overlay and animated wave visuals.",
                "",
                "Usage:",
                "> #overlay - shows current status and settings",
                "> #overlay <on/off> - toggles custom block overlay",
                "> #overlay wave <on/off> - toggles harmonic gradient wave visuals",
                "> #overlay smooth <on/off> - toggles smooth box sliding interpolation",
                "> #overlay speed <value> - sets interpolation speed (default 20)",
                "> #overlay width <value> - sets border line width (default 2.5)"
        );
    }
}
