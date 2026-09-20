package baritone.command.defaults;

import baritone.Baritone;
import baritone.api.IBaritone;
import baritone.api.command.Command;
import baritone.api.command.argument.IArgConsumer;
import baritone.api.command.exception.CommandException;
import baritone.bypass.BypassConfig;
import baritone.bypass.BypassProcess;
import baritone.bypass.ReconnectData;
import net.minecraft.core.BlockPos;

import java.nio.file.Path;
import java.util.*;
import java.util.stream.Collectors;
import java.util.stream.Stream;

/**
 * Komenda #bypass do zarządzania procesem wydobywczym i rotacjami zoptymalizowanymi pod kątem GrimAC.
 *
 * Użycie:
 * #bypass                    -> Wyświetla instrukcję i prosi o wybór rud
 * #bypass list               -> Pokazuje dostępne rudy i ich identyfikatory
 * #bypass stop               -> Zatrzymuje działanie bota
 * #bypass status             -> Pokazuje aktualną pozycję, stan, wykopane rudy i zdrowie
 * #bypass diamond            -> Kopie tylko diamenty
 * #bypass diamond gold       -> Kopie wybrane kombinacje rud
 */
public class BypassCommand extends Command {

    private final BypassConfig config = new BypassConfig();

    public BypassCommand(IBaritone baritone) {
        super(baritone, "bypass");
    }

    @Override
    public void execute(String label, IArgConsumer args) throws CommandException {
        Baritone baritoneImpl = (Baritone) this.baritone;
        BypassProcess process = baritoneImpl.getBypassProcess();

        if (!args.hasAny()) {
            logDirect("§b=== MODUŁ BYPASS (GRIMAC COMPLIANT) ===");
            logDirect("§fWybierz rudy do kopania, np.:");
            logDirect("  §e#bypass diamond §7- kopie tylko diamenty");
            logDirect("  §e#bypass diamond gold §7- kopie diamenty i złoto");
            logDirect("  §e#bypass diamond gold iron redstone §7- dowolna kombinacja");
            logDirect("§fInne opcje:");
            logDirect("  §e#bypass list   §7- lista dostępnych rud");
            logDirect("  §e#bypass status §7- aktualny stan i statystyki");
            logDirect("  §e#bypass stop   §7- zatrzymanie kopania");
            logDirect("  §e#bypass resume §7- wznowienie z reconnect_data.json");
            return;
        }

        String firstArg = args.getString().toLowerCase(Locale.ROOT);

        if (firstArg.equals("stop")) {
            process.cancel();
            return;
        }

        if (firstArg.equals("list")) {
            logDirect("§b[Bypass] Dostępne rudy do wyboru:");
            for (String oreKey : config.getAvailableOreKeys()) {
                List<String> blockList = config.getOreBlockNames(oreKey);
                logDirect(String.format("  §a%s §7-> %s", oreKey, blockList));
            }
            return;
        }

        if (firstArg.equals("status")) {
            if (ctx.player() == null) {
                logDirect("§c[Bypass] Gracz nie jest w świecie.");
                return;
            }
            BlockPos feet = ctx.playerFeet();
            float hp = ctx.player().getHealth();
            logDirect("§b=== STATUS BYPASS ===");
            logDirect(String.format("§fAktywny: %s | Faza: §e%s", process.isActive() ? "§aTAK" : "§cNIE", process.getPhase()));
            logDirect(String.format("§fPozycja: §bX=%d, Y=%d, Z=%d §7(Kierunek: §f%s§7)",
                    feet.getX(), feet.getY(), feet.getZ(), process.getTunnelDirection()));
            logDirect(String.format("§fZdrowie: §c%.1f / 20.0 HP", hp));
            logDirect(String.format("§fWykopane rudy w sesji: §a%d", process.getOresMined()));
            logDirect(String.format("§fWybrane rudy: §e%s", process.getTargetOres()));
            return;
        }

        if (firstArg.equals("resume")) {
            Path savePath = baritoneImpl.getDirectory().resolve(config.saveFile);
            ReconnectData data = ReconnectData.load(savePath);
            if (data == null) {
                logDirect("§c[Bypass] Nie znaleziono pliku reconnect_data.json lub jest on pusty.");
                return;
            }
            process.resumeFromData(data);
            return;
        }

        // Zbieramy listę podanych rud
        List<String> requestedOres = new ArrayList<>();
        requestedOres.add(firstArg);
        while (args.hasAny()) {
            requestedOres.add(args.getString().toLowerCase(Locale.ROOT));
        }

        Set<String> validKeys = config.getAvailableOreKeys();
        List<String> filteredOres = new ArrayList<>();
        for (String ore : requestedOres) {
            if (validKeys.contains(ore)) {
                if (!filteredOres.contains(ore)) {
                    filteredOres.add(ore);
                }
            } else {
                logDirect(String.format("§c[Bypass] Nieznana ruda: '%s'. Użyj #bypass list aby sprawdzić dostępne nazwy.", ore));
            }
        }

        if (filteredOres.isEmpty()) {
            logDirect("§c[Bypass] Nie podano żadnej poprawnej rudy. Wpisz #bypass list.");
            return;
        }

        process.startMining(filteredOres);
    }

    @Override
    public Stream<String> tabComplete(String label, IArgConsumer args) throws CommandException {
        Set<String> options = new LinkedHashSet<>();
        if (!args.has(2)) {
            options.add("stop");
            options.add("status");
            options.add("list");
            options.add("resume");
        }
        options.addAll(config.getAvailableOreKeys());

        String currentArg = args.hasAny() ? args.getString().toLowerCase(Locale.ROOT) : "";
        return options.stream().filter(opt -> opt.startsWith(currentArg));
    }

    @Override
    public String getShortDesc() {
        return "GrimAC-safe auto-mining and rotation engine";
    }

    @Override
    public List<String> getLongDesc() {
        return Arrays.asList(
                "The bypass command starts GrimAC-compliant automated branch mining at Y=-55.",
                "It uses GCD-quantized cubic bezier rotations, ore prioritisation, and safety auto-disconnect.",
                "",
                "Usage:",
                "> #bypass diamond - Mines only diamonds",
                "> #bypass diamond gold - Mines diamonds and gold",
                "> #bypass diamond gold iron redstone - Any ore combination",
                "> #bypass status - Shows position, phase, mined ores, health",
                "> #bypass stop - Stops bypass mining",
                "> #bypass list - Lists available ore names"
        );
    }
}
