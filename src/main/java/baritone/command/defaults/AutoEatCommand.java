package baritone.command.defaults;

import baritone.Baritone;
import baritone.api.IBaritone;
import baritone.api.command.Command;
import baritone.api.command.argument.IArgConsumer;
import baritone.api.command.exception.CommandException;
import baritone.api.utils.SettingsUtil;
import baritone.process.AutoEatProcess;

import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.stream.Stream;

/**
 * Komenda #autoeat / #eat do zarządzania automatycznym jedzeniem.
 *
 * Użycie:
 * #autoeat                 -> Pokazuje aktualny stan i ustawienia
 * #autoeat on / off        -> Włącza / wyłącza moduł
 * #autoeat now             -> Natychmiast zjada jedzenie (wymuszenie)
 * #autoeat threshold <1-20>-> Ustawia próg głodu (domyślnie 16)
 * #autoeat health <hp>     -> Ustawia próg HP poniżej którego bot zjada (domyślnie 18)
 * #autoeat gapple on/off   -> Włącza priorytet złotych jabłek przy niskim HP
 */
public class AutoEatCommand extends Command {

    public AutoEatCommand(IBaritone baritone) {
        super(baritone, "autoeat", "eat");
    }

    @Override
    public void execute(String label, IArgConsumer args) throws CommandException {
        boolean active = Baritone.settings().autoEat.value;
        int hungerThreshold = Baritone.settings().autoEatThreshold.value;
        double healthThreshold = Baritone.settings().autoEatHealthThreshold.value;
        boolean gapple = Baritone.settings().autoEatGoldenApple.value;
        double gappleThreshold = Baritone.settings().autoEatGoldenAppleThreshold.value;
        boolean invSearch = Baritone.settings().autoEatSearchInventory.value;

        if (!args.hasAny()) {
            logDirect("§b=== AUTOEAT (GRIM-SAFE) ===");
            logDirect(String.format(Locale.ROOT,
                    "§fStatus: %s",
                    active ? "§aWŁĄCZONY (ON)" : "§cWYŁĄCZONY (OFF)"));
            logDirect(String.format(Locale.ROOT,
                    "§fPróg głodu: §e%.1f udek §7(czyli %d/20 punktów głodu - bot zjada gdy spadnie <= %.1f udek)",
                    hungerThreshold / 2.0, hungerThreshold, hungerThreshold / 2.0));
            logDirect(String.format(Locale.ROOT,
                    "§fPróg leczenia: §e%.1f HP §7(czyli %.1f serc - zjada jeśli HP spadnie poniżej)",
                    healthThreshold, healthThreshold / 2.0));
            logDirect(String.format(Locale.ROOT,
                    "§fZłote jabłka (GApple): %s §7(próg: %.1f HP = %.1f serc)",
                    gapple ? "§aTAK" : "§cNIE", gappleThreshold, gappleThreshold / 2.0));
            logDirect(String.format(Locale.ROOT,
                    "§fSzukanie w plecaku (sloty 9-35): %s",
                    invSearch ? "§aTAK" : "§cNIE"));
            logDirect("§fDziałanie na GrimAC:");
            logDirect("  §7- Zatrzymuje ruch bota w 100% (#stop) podczas jedzenia");
            logDirect("  §7- Po zjedzeniu natychmiast wznawia poprzednie zadanie (#mine, #bypass, #goto itp.)");
            logDirect("§fUżycie:");
            logDirect("  §e#autoeat on / off         §7- włącz/wyłącz");
            logDirect("  §e#autoeat now              §7- zjedz natychmiast");
            logDirect("  §e#autoeat threshold <liczba> §7- ustaw próg udek/głodu (np. #autoeat threshold 5)");
            logDirect("  §e#autoeat health <hp>      §7- ustaw próg HP (np. #autoeat health 16)");
            logDirect("  §e#autoeat gapple on/off    §7- włącz/wyłącz jedzenie koxów/złotych jabłek");
            return;
        }

        String first = args.getString().toLowerCase(Locale.ROOT);

        if (first.equals("on") || first.equals("true") || first.equals("enable") || first.equals("1")) {
            Baritone.settings().autoEat.value = true;
            SettingsUtil.save(Baritone.settings());
            logDirect("§a[AutoEat] WŁĄCZONO moduł automatycznego jedzenia (GrimAC-safe).");
            return;
        }

        if (first.equals("off") || first.equals("false") || first.equals("disable") || first.equals("0")) {
            Baritone.settings().autoEat.value = false;
            SettingsUtil.save(Baritone.settings());
            logDirect("§c[AutoEat] WYŁĄCZONO moduł automatycznego jedzenia.");
            return;
        }

        if (first.equals("now") || first.equals("eat") || first.equals("force")) {
            AutoEatProcess proc = ((Baritone) baritone).getAutoEatProcess();
            if (proc != null) {
                proc.forceEatNow();
                logDirect("§a[AutoEat] Wymuszono natychmiastowe zjedzenie...");
            }
            return;
        }

        if (first.equals("threshold") || first.equals("hunger") || first.equals("glod")) {
            int inputVal = args.getAs(Integer.class);
            if (inputVal < 1 || inputVal > 20) {
                logDirect("§c[AutoEat] Próg głodu musi mieścić się w przedziale 1 - 20 (lub 1 - 10 udek).");
                return;
            }
            int points;
            if (inputVal <= 10) {
                points = inputVal * 2;
                logDirect(String.format(Locale.ROOT, "§a[AutoEat] Ustawiono próg głodu na: §e%d udek §7(czyli %d/20 punktów głodu).", inputVal, points));
            } else {
                points = inputVal;
                logDirect(String.format(Locale.ROOT, "§a[AutoEat] Ustawiono próg głodu na: §e%d/20 punktów głodu §7(czyli %.1f udek).", points, points / 2.0));
            }
            Baritone.settings().autoEatThreshold.value = points;
            SettingsUtil.save(Baritone.settings());
            return;
        }

        if (first.equals("health") || first.equals("hp")) {
            double newHp = args.getAs(Double.class);
            if (newHp <= 0 || newHp > 20) {
                logDirect("§c[AutoEat] Próg HP musi mieścić się w przedziale 1.0 - 20.0.");
                return;
            }
            Baritone.settings().autoEatHealthThreshold.value = newHp;
            SettingsUtil.save(Baritone.settings());
            logDirect(String.format(Locale.ROOT, "§a[AutoEat] Ustawiono próg leczenia na: §e%.1f HP (%.1f serc)", newHp, newHp / 2.0));
            return;
        }

        if (first.equals("gapple") || first.equals("apple") || first.equals("kox")) {
            if (args.hasAny()) {
                String sub = args.getString().toLowerCase(Locale.ROOT);
                boolean enable = sub.equals("on") || sub.equals("true") || sub.equals("1");
                Baritone.settings().autoEatGoldenApple.value = enable;
                SettingsUtil.save(Baritone.settings());
                logDirect(String.format("§a[AutoEat] Priorytet złotych jabłek: %s", enable ? "§aWŁĄCZONY" : "§cWYŁĄCZONY"));
            } else {
                boolean toggle = !Baritone.settings().autoEatGoldenApple.value;
                Baritone.settings().autoEatGoldenApple.value = toggle;
                SettingsUtil.save(Baritone.settings());
                logDirect(String.format("§a[AutoEat] Priorytet złotych jabłek przełączony na: %s", toggle ? "§aWŁĄCZONY" : "§cWYŁĄCZONY"));
            }
            return;
        }

        logDirect("§c[AutoEat] Nieznany argument: " + first + ". Wpisz #autoeat aby zobaczyć opcje.");
    }

    @Override
    public Stream<String> tabComplete(String label, IArgConsumer args) throws CommandException {
        if (args.hasExactly(1)) {
            String prefix = args.getString().toLowerCase(Locale.ROOT);
            return Stream.of("on", "off", "now", "threshold", "health", "gapple")
                    .filter(s -> s.startsWith(prefix));
        }
        if (args.hasExactly(2)) {
            String first = args.getString().toLowerCase(Locale.ROOT);
            if (first.equals("gapple")) {
                return Stream.of("on", "off");
            }
            if (first.equals("threshold")) {
                return Stream.of("14", "16", "18");
            }
            if (first.equals("health")) {
                return Stream.of("10", "14", "18");
            }
        }
        return Stream.empty();
    }

    @Override
    public String getShortDesc() {
        return "Configure GrimAC-safe AutoEat module";
    }

    @Override
    public List<String> getLongDesc() {
        return Arrays.asList(
                "Configures the GrimAC and Custom GrimAC safe automated eating module.",
                "Halts movement completely (#stop style) while eating, then seamlessly resumes",
                "the previous task (e.g. #mine, #bypass, #goto) without cancelling it.",
                "",
                "Usage:",
                "> #autoeat - display status and settings",
                "> #autoeat on/off - toggle auto eat",
                "> #autoeat now - force eating immediately",
                "> #autoeat threshold <1-20> - set hunger threshold",
                "> #autoeat health <hp> - set HP threshold",
                "> #autoeat gapple on/off - toggle Golden Apple priority"
        );
    }
}
