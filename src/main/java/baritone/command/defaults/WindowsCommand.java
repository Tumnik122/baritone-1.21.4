package baritone.command.defaults;

import baritone.Baritone;
import baritone.api.IBaritone;
import baritone.api.command.Command;
import baritone.api.command.argument.IArgConsumer;
import baritone.api.command.exception.CommandException;
import baritone.control.WindowsBotController;

import java.awt.Desktop;
import java.net.URI;
import java.util.Locale;
import java.util.stream.Stream;

/**
 * Komenda #windows (#winbot, #webgui, #dashboard) do zarządzania sterowaniem botem z poziomu systemu Windows.
 */
public class WindowsCommand extends Command {

    public WindowsCommand(IBaritone baritone) {
        super(baritone, "windows", "winbot", "webgui", "dashboard");
    }

    @Override
    public void execute(String label, IArgConsumer args) throws CommandException {
        Baritone b = (Baritone) this.baritone;
        WindowsBotController ctrl = b.getWindowsBotController();

        int port = ctrl != null ? ctrl.getBoundPort() : Baritone.settings().windowsControllerPort.value;
        String url = "http://localhost:" + port;

        if (!args.hasAny()) {
            logDirect("§b=== STEROWANIE BOTEM PRZEZ WINDOWSA ===");
            logDirect("  §aPanel WWW: §e" + url);
            logDirect("  §7Wpisz w przeglądarce powyższy adres, aby sterować botem z Windowsa!");
            logDirect("  §7Możesz też użyć w PowerShell / CMD: §e.\\bot-control.bat <komenda>");
            logDirect("  §e#windows open §7- natychmiast otwiera panel w domyślnej przeglądarce Windows");
            logDirect("  §e#windows on / off §7- włącza / wyłącza serwer sterowania");
            return;
        }

        String sub = args.getString().toLowerCase(Locale.ROOT);
        if (sub.equals("open") || sub.equals("gui") || sub.equals("web") || sub.equals("browser")) {
            logDirect("§b[WindowsController] Otwieram panel bota w przeglądarce: §e" + url);
            try {
                if (Desktop.isDesktopSupported() && Desktop.getDesktop().isSupported(Desktop.Action.BROWSE)) {
                    Desktop.getDesktop().browse(new URI(url));
                } else {
                    Runtime.getRuntime().exec("cmd /c start " + url);
                }
            } catch (Throwable t) {
                try {
                    Runtime.getRuntime().exec("cmd /c start " + url);
                } catch (Throwable ignored) {
                    logDirect("§c[WindowsController] Nie udało się automatycznie otworzyć przeglądarki. Skopiuj adres: " + url);
                }
            }
        } else if (sub.equals("on") || sub.equals("start")) {
            Baritone.settings().windowsControllerEnabled.value = true;
            if (ctrl != null) ctrl.start();
            logDirect("§a[WindowsController] Serwer sterowania Windows został włączony na: §e" + url);
        } else if (sub.equals("off") || sub.equals("stop")) {
            Baritone.settings().windowsControllerEnabled.value = false;
            if (ctrl != null) ctrl.stopServer();
            logDirect("§c[WindowsController] Serwer sterowania Windows został wyłączony.");
        } else {
            logDirect("§e[WindowsController] Użycie: #windows [open | on | off]");
        }
    }

    @Override
    public Stream<String> tabComplete(String label, IArgConsumer args) throws CommandException {
        if (args.hasExactlyOne()) {
            return Stream.of("open", "on", "off", "status");
        }
        return Stream.empty();
    }

    @Override
    public String getShortDesc() {
        return "Panel i sterowanie botem z poziomu systemu Windows (Web Dashboard & CLI)";
    }

    @Override
    public java.util.List<String> getLongDesc() {
        return java.util.Arrays.asList(
                "Zarządza modułem sterowania botem przez system Windows.",
                "Uruchamia lokalny serwer HTTP oraz panel WWW na http://localhost:21420.",
                "Pozwala kontrolować bota z przeglądarki, terminala PowerShell, CMD lub skryptów Windows."
        );
    }
}
