package baritone.command.defaults;

import baritone.Baritone;
import baritone.api.IBaritone;
import baritone.api.command.Command;
import baritone.api.command.argument.IArgConsumer;
import baritone.api.command.exception.CommandException;

import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.stream.Stream;

/**
 * Komenda #test do natychmiastowego przetestowania całego cyklu:
 * - #stop (zatrzymanie bota)
 * - /home 1 (teleport do bazy, celownik na skrzynkę)
 * - otwarcie skrzynki i oddanie wydobytych surowców
 * - /home 2 (powrót do kopalni)
 * - wznowienie zadania kopania (#mine ... / #bypass ...)
 *
 * Dodatkowo wspiera:
 * - #test clean - natychmiastowe wyrzucenie śmieci i najszybsze posortowanie ekwipunku
 * - #test sort - ekspresowe sortowanie ekwipunku
 */
public class TestCommand extends Command {

    public TestCommand(IBaritone baritone) {
        super(baritone, "test", "testhome", "testcycle");
    }

    @Override
    public void execute(String label, IArgConsumer args) throws CommandException {
        Baritone b = (Baritone) baritone;

        if (!args.hasAny()) {
            // Domyślnie pod #test uruchamiamy test pełnego cyklu home + skrzynka + powrót
            b.getHomeProcess().startTestCycle(null);
            return;
        }

        String sub = args.getString().toLowerCase(Locale.ROOT);
        if (sub.equals("clean") || sub.equals("drop") || sub.equals("cleaneq")) {
            logDirect("§e[Test] Testuję czyszczenie ekwipunku (wyrzucenie śmieci + ekspresowe sortowanie)...");
            b.getAutoDropProcess().cleanNow();
        } else if (sub.equals("sort")) {
            logDirect("§b[Test] Testuję natychmiastowe, najszybsze sortowanie ekwipunku...");
            b.getAutoDropProcess().sortNow();
        } else if (sub.equals("deposit") || sub.equals("home") || sub.equals("cycle")) {
            String resumeCmd = args.hasAny() ? args.rawRest().trim() : null;
            b.getHomeProcess().startTestCycle(resumeCmd);
        } else if (sub.startsWith("mine")) {
            // np. #test mine iron
            String resumeCmd = sub + (args.hasAny() ? " " + args.rawRest().trim() : "");
            b.getHomeProcess().startTestCycle(resumeCmd);
        } else {
            // np. #test iron -> odpala test i ustawia wznawianie "mine iron"
            String resumeCmd = "mine " + sub + (args.hasAny() ? " " + args.rawRest().trim() : "");
            b.getHomeProcess().startTestCycle(resumeCmd);
        }
    }

    @Override
    public Stream<String> tabComplete(String label, IArgConsumer args) {
        if (args.hasExactlyOne()) {
            return Stream.of("deposit", "clean", "sort", "mine iron");
        }
        return Stream.empty();
    }

    @Override
    public String getShortDesc() {
        return "Testuje pełny cykl powrotu do bazy (/home 1 -> skrzynka -> /home 2 -> wznowienie) oraz clean eq";
    }

    @Override
    public List<String> getLongDesc() {
        return Arrays.asList(
                "Komenda testowa do natychmiastowego sprawdzenia automatycznego cyklu:",
                "1. Wykonuje #stop",
                "2. Teleportuje do bazy (/home 1)",
                "3. Otwiera skrzynkę w celowniku i oddaje wydobyte surowce",
                "4. Wraca do kopalni (/home 2)",
                "5. Wznawia kopanie (#mine)",
                "",
                "Użycie:",
                "> test - uruchamia od razu pełny test cyklu oddania do skrzynki",
                "> test mine iron - uruchamia test, po którym bot wznawia #mine iron",
                "> test clean - testuje natychmiastowe wyrzucenie śmieci i najszybsze sortowanie ekwipunku",
                "> test sort - testuje samo najszybsze sortowanie"
        );
    }
}
