package baritone.command.defaults;

import baritone.Baritone;
import baritone.api.IBaritone;
import baritone.api.command.Command;
import baritone.api.command.argument.IArgConsumer;
import baritone.api.command.exception.CommandException;
import baritone.bypass.ReconnectManager;

import java.util.Arrays;
import java.util.List;
import java.util.stream.Stream;

/**
 * 1. ReconnectCommand.java
 *
 * Komenda #reconnect:
 * 1. Wczytuje reconnect_data.json
 * 2. Czeka na załadowanie świata
 * 3. Jeśli odległość > 5 bloków -> Baritone pathfinds do zapisanej pozycji
 * 4. Przywraca stan (rudy, kierunek tunelu, faza)
 * 5. AUTOMATYCZNIE wznawia #bypass
 */
public class ReconnectCommand extends Command {

    public ReconnectCommand(IBaritone baritone) {
        super(baritone, "reconnect");
    }

    @Override
    public void execute(String label, IArgConsumer args) throws CommandException {
        Baritone baritoneImpl = (Baritone) this.baritone;
        logDirect("§b[Reconnect] Inicjalizacja procedury ponownego połączenia i odzyskiwania sesji...");
        ReconnectManager.start(baritoneImpl);
    }

    @Override
    public Stream<String> tabComplete(String label, IArgConsumer args) {
        return Stream.empty();
    }

    @Override
    public String getShortDesc() {
        return "Reconnect and auto-resume bypass mining";
    }

    @Override
    public List<String> getLongDesc() {
        return Arrays.asList(
                "Reads reconnect_data.json, paths back to saved coordinates if > 5 blocks away,",
                "restores the mining state, and automatically resumes #bypass without re-typing.",
                "",
                "Usage:",
                "> #reconnect"
        );
    }
}
