package baritone.command.defaults;

import baritone.Baritone;
import baritone.api.IBaritone;
import baritone.api.command.Command;
import baritone.api.command.argument.IArgConsumer;
import baritone.api.command.exception.CommandException;
import baritone.bypass.BypassConfig;
import baritone.bypass.InventoryCleaner;
import baritone.process.AutoDropProcess;

import java.util.Locale;
import java.util.Set;
import java.util.stream.Stream;

/**
 * Komenda #clean (oraz #autodrop, #trash, #drop) do zarządzania czyszczeniem ekwipunku ze śmieci.
 *
 * Użycie:
 * #clean                  -> Natychmiast wyrzuca wszystkie śmieci (zostawia surowce, kilofy i jedzenie)
 * #clean status           -> Wyświetla stan ekwipunku, liczbę śmieci i wolne sloty
 * #clean on/off           -> Włącza / wyłącza automatyczne czyszczenie przy pełnym EQ
 * #clean threshold <ile>  -> Ustawia próg wolnych slotów (domyślnie 1)
 * #clean delay <ms>       -> Ustawia opóźnienie między wyrzucaniem stacków (domyślnie 100ms)
 */
public class AutoDropCommand extends Command {

    public AutoDropCommand(IBaritone baritone) {
        super(baritone, "clean", "autodrop", "trash", "drop", "lock", "autolock", "sort", "autosort", "segreguj");
    }

    @Override
    public void execute(String label, IArgConsumer args) throws CommandException {
        Baritone baritoneImpl = (Baritone) this.baritone;
        AutoDropProcess autoDropProcess = baritoneImpl.getAutoDropProcess();

        if (ctx.player() == null) {
            logDirect("§c[AutoDrop] Gracz nie jest na serwerze.");
            return;
        }

        // Bezpośrednie wywołanie jako #sort, #autosort lub #segreguj
        if (label.equalsIgnoreCase("sort") || label.equalsIgnoreCase("autosort") || label.equalsIgnoreCase("segreguj")) {
            if (args.hasAny()) {
                String sub = args.peekString().toLowerCase(Locale.ROOT);
                if (sub.equals("on") || sub.equals("true") || sub.equals("1")) {
                    Baritone.settings().autoSortInventory.value = true;
                    logDirect("§a[AutoSort] Automatyczne segregowanie ekwipunku zostało WŁĄCZONE.");
                    return;
                } else if (sub.equals("off") || sub.equals("false") || sub.equals("0")) {
                    Baritone.settings().autoSortInventory.value = false;
                    autoDropProcess.onLostControl();
                    logDirect("§c[AutoSort] Automatyczne segregowanie ekwipunku zostało WYŁĄCZONE.");
                    return;
                }
            }
            logDirect("§b[AutoSort] Rozpoczynam natychmiastowe segregowanie i organizację ekwipunku (GrimAC-safe)...");
            autoDropProcess.sortNow();
            return;
        }

        // Bezpośrednie wywołanie jako #lock lub #autolock
        if (label.equalsIgnoreCase("lock") || label.equalsIgnoreCase("autolock") || label.equalsIgnoreCase("fill")) {
            if (!args.hasAny() || args.peekString().equalsIgnoreCase("now")) {
                Set<String> targets = InventoryCleaner.collectResourceTargets(ctx.player(), baritoneImpl.getBypassProcess());
                String lockItem = Baritone.settings().autoLockItemName.value;
                int sourceSlot = InventoryCleaner.findBestLockSourceSlot(ctx.player(), lockItem, targets);
                int freeSlots = InventoryCleaner.getFreeSlots(ctx.player());

                if (freeSlots == 0) {
                    logDirect("§a[AutoDrop] Wszystkie sloty w ekwipunku są już zablokowane!");
                    return;
                }
                if (sourceSlot == -1) {
                    logDirect("§c[AutoDrop] Nie masz w ekwipunku odpowiedniego surowca (np. surowego żelaza >= 2 szt.), aby zablokować sloty.");
                    return;
                }
                logDirect("§b[AutoDrop] Rozpoczynam blokowanie " + freeSlots + " pustych slotów surowcem (" + lockItem + ")...");
                autoDropProcess.lockNow();
                return;
            }
        }

        if (!args.hasAny()) {
            Set<String> targets = InventoryCleaner.collectResourceTargets(ctx.player(), baritoneImpl.getBypassProcess());
            int trashCount = InventoryCleaner.countTrash(ctx.player(), new BypassConfig(), targets);
            int freeSlots = InventoryCleaner.getFreeSlots(ctx.player());

            if (trashCount == 0 && freeSlots == 0) {
                logDirect("§a[AutoDrop] Ekwipunek jest czysty i w pełni zablokowany surowcami!");
                return;
            }

            if (trashCount > 0) {
                logDirect("§b[AutoDrop] Czyszczę śmieci (" + trashCount + " stacków) i od razu najszybciej segreguję ekwipunek...");
            } else {
                logDirect("§b[AutoDrop] Brak śmieci do wyrzucenia - od razu najszybciej segreguję ekwipunek...");
            }
            autoDropProcess.cleanNow();
            return;
        }

        String arg = args.getString().toLowerCase(Locale.ROOT);

        if (arg.equals("status") || arg.equals("info")) {
            boolean dropEnabled = Baritone.settings().autoDropTrash.value;
            boolean lockEnabled = Baritone.settings().autoLockResource.value;
            String lockItem = Baritone.settings().autoLockItemName.value;
            int threshold = Baritone.settings().autoDropThreshold.value;
            long cooldownMs = Baritone.settings().cleanSortCooldownMs.value;
            int freeSlots = InventoryCleaner.getFreeSlots(ctx.player());
            Set<String> targets = InventoryCleaner.collectResourceTargets(ctx.player(), baritoneImpl.getBypassProcess());
            int trashCount = InventoryCleaner.countTrash(ctx.player(), new BypassConfig(), targets);

            logDirect("§b=== STATUS AUTODROP / BLOKOWANIA EQ ===");
            logDirect(String.format("§fModuł AutoDrop (wyrzucanie śmieci): %s", dropEnabled ? "§aWŁĄCZONY" : "§cWYŁĄCZONY"));
            logDirect(String.format("§fModuł AutoSort (segregowanie EQ): %s", Baritone.settings().autoSortInventory.value ? "§aWŁĄCZONY" : "§cWYŁĄCZONY"));
            logDirect(String.format("§fModuł Slot Locking (blokowanie żelazem): %s", lockEnabled ? "§aWŁĄCZONY" : "§cWYŁĄCZONY"));
            logDirect(String.format("§fMinimalny odstęp czyszczenia/sortowania: §e%d s (%d min)", cooldownMs / 1000L, cooldownMs / 60000L));
            if (autoDropProcess.isOnCleanSortCooldown()) {
                logDirect(String.format("§7(Aktywny cooldown: jeszcze §c%d s§7 przed kolejnym automatycznym cyklem)", autoDropProcess.getRemainingCooldownMs() / 1000L));
            } else {
                logDirect("§7(Cooldown czyszczenia/sortowania: §agotowy§7)");
            }
            logDirect(String.format("§fPreferowany surowiec do blokowania: §e%s", lockItem));
            logDirect(String.format("§fWolne sloty w EQ: §e%d / 36", freeSlots));
            logDirect(String.format("§fWykryte stacki śmieci: §c%d", trashCount));
            logDirect(String.format("§fPróg aktywacji (wolne sloty <=): §e%d", threshold));
            logDirect(String.format("§fWykryte surowce w EQ: §a%s", targets.isEmpty() ? "brak" : targets));
            logDirect("§7Zasada: Bot sprawdza otoczenie i wyrzuca śmieci w wolną przestrzeń (nigdy w ścianę),");
            logDirect("§7a wywalanie i sortowanie może powtarzać się maksymalnie raz na 2 minuty!");
            return;
        }

        if (arg.equals("lock") || arg.equals("fill")) {
            if (args.hasAny()) {
                String sub = args.getString().toLowerCase(Locale.ROOT);
                if (sub.equals("on") || sub.equals("true") || sub.equals("1")) {
                    Baritone.settings().autoLockResource.value = true;
                    logDirect("§a[AutoDrop] Automatyczne blokowanie slotów surowcem zostało WŁĄCZONE.");
                    return;
                } else if (sub.equals("off") || sub.equals("false") || sub.equals("0")) {
                    Baritone.settings().autoLockResource.value = false;
                    logDirect("§c[AutoDrop] Automatyczne blokowanie slotów surowcem zostało WYŁĄCZONE.");
                    return;
                }
            }
            Set<String> targets = InventoryCleaner.collectResourceTargets(ctx.player(), baritoneImpl.getBypassProcess());
            String lockItem = Baritone.settings().autoLockItemName.value;
            int sourceSlot = InventoryCleaner.findBestLockSourceSlot(ctx.player(), lockItem, targets);
            int freeSlots = InventoryCleaner.getFreeSlots(ctx.player());

            if (freeSlots == 0) {
                logDirect("§a[AutoDrop] Wszystkie sloty w ekwipunku są już zablokowane!");
                return;
            }
            if (sourceSlot == -1) {
                logDirect("§c[AutoDrop] Nie masz w ekwipunku surowca (np. surowego żelaza >= 2 szt.) do zablokowania slotów.");
                return;
            }
            logDirect("§b[AutoDrop] Rozpoczynam natychmiastowe blokowanie " + freeSlots + " pustych slotów surowcem (" + lockItem + ")...");
            autoDropProcess.lockNow();
            return;
        }

        if (arg.equals("lockitem") || arg.equals("item")) {
            if (!args.hasAny()) {
                logDirect("§e[AutoDrop] Aktualny surowiec blokujący: " + Baritone.settings().autoLockItemName.value);
                return;
            }
            String item = args.getString().toLowerCase(Locale.ROOT);
            Baritone.settings().autoLockItemName.value = item;
            logDirect("§a[AutoDrop] Ustawiono surowiec blokujący: §e" + item);
            return;
        }

        if (arg.equals("on") || arg.equals("enable") || arg.equals("true") || arg.equals("1")) {
            Baritone.settings().autoDropTrash.value = true;
            Baritone.settings().autoLockResource.value = true;
            logDirect("§a[AutoDrop] Automatyczne czyszczenie i blokowanie slotów zostało WŁĄCZONE.");
            return;
        }

        if (arg.equals("off") || arg.equals("disable") || arg.equals("false") || arg.equals("0")) {
            Baritone.settings().autoDropTrash.value = false;
            Baritone.settings().autoLockResource.value = false;
            logDirect("§c[AutoDrop] Automatyczne czyszczenie i blokowanie slotów zostało WYŁĄCZONE.");
            return;
        }

        if (arg.equals("threshold") || arg.equals("limit")) {
            if (!args.hasAny()) {
                logDirect("§e[AutoDrop] Aktualny próg wolnych slotów: " + Baritone.settings().autoDropThreshold.value);
                return;
            }
            try {
                int val = Integer.parseInt(args.getString());
                if (val < 0 || val > 10) {
                    logDirect("§c[AutoDrop] Próg musi być w zakresie 0..10.");
                    return;
                }
                Baritone.settings().autoDropThreshold.value = val;
                logDirect("§a[AutoDrop] Nowy próg wolnych slotów: " + val);
            } catch (NumberFormatException e) {
                logDirect("§c[AutoDrop] Nieprawidłowa liczba: " + args.getString());
            }
            return;
        }

        if (arg.equals("sort") || arg.equals("segreguj")) {
            if (args.hasAny()) {
                String sub = args.getString().toLowerCase(Locale.ROOT);
                if (sub.equals("on") || sub.equals("true") || sub.equals("1")) {
                    Baritone.settings().autoSortInventory.value = true;
                    logDirect("§a[AutoSort] Automatyczne segregowanie ekwipunku zostało WŁĄCZONE.");
                    return;
                } else if (sub.equals("off") || sub.equals("false") || sub.equals("0")) {
                    Baritone.settings().autoSortInventory.value = false;
                    logDirect("§c[AutoSort] Automatyczne segregowanie ekwipunku zostało WYŁĄCZONE.");
                    return;
                }
            }
            logDirect("§b[AutoSort] Rozpoczynam segregowanie i organizację ekwipunku (GrimAC-safe)...");
            autoDropProcess.sortNow();
            return;
        }

        if (arg.equals("cooldown") || arg.equals("interval") || arg.equals("czas")) {
            if (!args.hasAny()) {
                long cur = Baritone.settings().cleanSortCooldownMs.value;
                logDirect(String.format("§e[AutoDrop] Aktualny odstęp między czyszczeniem/sortowaniem: %d s (%d min)", cur / 1000L, cur / 60000L));
                return;
            }
            String valStr = args.getString().toLowerCase(Locale.ROOT);
            long millis;
            try {
                if (valStr.endsWith("ms")) {
                    millis = Long.parseLong(valStr.substring(0, valStr.length() - 2));
                } else if (valStr.endsWith("min")) {
                    millis = (long) (Double.parseDouble(valStr.substring(0, valStr.length() - 3)) * 60000.0);
                } else if (valStr.endsWith("m")) {
                    millis = (long) (Double.parseDouble(valStr.substring(0, valStr.length() - 1)) * 60000.0);
                } else if (valStr.endsWith("sek")) {
                    millis = (long) (Double.parseDouble(valStr.substring(0, valStr.length() - 3)) * 1000.0);
                } else if (valStr.endsWith("s")) {
                    millis = (long) (Double.parseDouble(valStr.substring(0, valStr.length() - 1)) * 1000.0);
                } else {
                    double num = Double.parseDouble(valStr);
                    if (num <= 10) {
                        millis = (long) (num * 60000L);
                    } else {
                        millis = (long) (num * 1000L);
                    }
                }
                if (millis < 5000L) {
                    logDirect("§c[AutoDrop] Odstęp musi wynosić co najmniej 5 sekund.");
                    return;
                }
                Baritone.settings().cleanSortCooldownMs.value = millis;
                logDirect(String.format("§a[AutoDrop] Nowy odstęp czyszczenia i sortowania: §e%d s (%.1f min)", millis / 1000L, millis / 60000.0));
            } catch (NumberFormatException e) {
                logDirect("§c[AutoDrop] Nieprawidłowa wartość czasu: " + valStr + " (użyj np. 2m, 120s)");
            }
            return;
        }

        if (arg.equals("delay")) {
            if (!args.hasAny()) {
                logDirect("§e[AutoDrop] Aktualne opóźnienie: " + Baritone.settings().autoDropDelayMs.value + " ms");
                return;
            }
            try {
                int val = Integer.parseInt(args.getString());
                if (val < 50 || val > 2000) {
                    logDirect("§c[AutoDrop] Opóźnienie musi być w zakresie 50..2000 ms (zalecane: 100 ms dla GrimAC).");
                    return;
                }
                Baritone.settings().autoDropDelayMs.value = val;
                logDirect("§a[AutoDrop] Nowe opóźnienie: " + val + " ms");
            } catch (NumberFormatException e) {
                logDirect("§c[AutoDrop] Nieprawidłowa liczba: " + args.getString());
            }
            return;
        }

        logDirect("§b=== KOMENDA #clean / #sort / #lock ===");
        logDirect("  §e#clean §7- wyrzuca śmieci (w bezpieczną stronę) i od razu po tym segreguje ekwipunek");
        logDirect("  §e#sort §7(lub #segreguj) - natychmiast łączy stacki, porządkuje hotbar i sortuje surowce");
        logDirect("  §e#clean cooldown <czas> §7- ustawia minimalny odstęp między czyszczeniem/sortowaniem (domyślnie 2m)");
        logDirect("  §e#clean sort <on/off> §7- włącza/wyłącza automatyczne segregowanie");
        logDirect("  §e#clean lock §7(lub #clean fill) - natychmiast blokuje wszystkie puste sloty żelazem");
        logDirect("  §e#clean lock <on/off> §7- włącza/wyłącza blokowanie slotów surowcem");
        logDirect("  §e#clean lockitem <nazwa> §7- ustawia surowiec do blokowania (np. raw_iron)");
        logDirect("  §e#clean status §7- wyświetla stan ekwipunku, liczbę śmieci, wolne sloty i cooldown");
        logDirect("  §e#clean <on/off> §7- włącza/wyłącza automatyczne czyszczenie");
        logDirect("  §e#clean threshold <0-10> §7- ustawia próg wolnych slotów");
        logDirect("  §e#clean delay <ms> §7- opóźnienie między wyrzucaniem stacków");
    }

    @Override
    public Stream<String> tabComplete(String label, IArgConsumer args) throws CommandException {
        if (args.hasExactlyOne()) {
            return Stream.of("status", "sort", "lock", "fill", "lockitem", "cooldown", "on", "off", "threshold", "delay");
        }
        if (args.has(2)) {
            String first = args.getString().toLowerCase(Locale.ROOT);
            if (first.equals("lock") || first.equals("fill") || first.equals("sort")) {
                return Stream.of("on", "off");
            }
            if (first.equals("cooldown") || first.equals("interval") || first.equals("czas")) {
                return Stream.of("2m", "1m", "3m", "120s", "60s");
            }
            if (first.equals("lockitem")) {
                return Stream.of("raw_iron", "iron_ingot", "raw_gold", "raw_copper", "diamond");
            }
        }
        return Stream.empty();
    }

    @Override
    public String getShortDesc() {
        return "Czyszczenie ekwipunku ze śmieci oraz blokowanie slotów surowcem (np. żelazem)";
    }

    @Override
    public java.util.List<String> getLongDesc() {
        return java.util.Arrays.asList(
                "Komenda #clean wyrzuca z ekwipunku wszystkie bloki śmieciowe (bruk, łupek, ziemię, żwir itp.),",
                "pozostawiając w nim wyłącznie surowce (rudy, metale, diamenty), kilofy/narzędzia i jedzenie.",
                "Następnie rozdziela po 1 sztuce surowca (np. surowego żelaza) do wszystkich pustych slotów,",
                "dzięki czemu bot nigdy nie podnosi śmieci i zbiera wyłącznie surowiec (zgodnie z metodą Anarchii).",
                "Działa w 100% niewykrywalnie na GrimAC."
        );
    }
}

