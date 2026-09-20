package baritone.bypass;

import baritone.Baritone;
import baritone.api.pathing.goals.GoalBlock;
import baritone.api.utils.Helper;
import baritone.api.utils.IPlayerContext;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.ConnectScreen;
import net.minecraft.client.gui.screens.TitleScreen;
import net.minecraft.client.multiplayer.ServerData;
import net.minecraft.client.multiplayer.resolver.ServerAddress;
import net.minecraft.core.BlockPos;

import java.nio.file.Path;

/**
 * 1. ReconnectManager.java
 *
 * Obsługuje procedurę ponownego łączenia i wznawiania pracy:
 * 1. Wczytuje reconnect_data.json.
 * 2. Jeśli gracz jest w menu -> łączy z zapisanym serwerem.
 * 3. Czeka 2-3 sekundy na załadowanie świata i chunków.
 * 4. Sprawdza odległość od zapisanej pozycji:
 *    - Jeśli odległość > 5 bloków -> używa Baritone pathfinding (GoalBlock) do przejścia.
 * 5. Przywraca stan (rudy, kierunek tunelu, faza).
 * 6. AUTOMATYCZNIE wznawia #bypass (bez konieczności wpisywania komendy).
 */
public class ReconnectManager {

    private enum State {
        IDLE,
        WAITING_WORLD_LOAD,
        PATHFINDING_TO_SAVED_POS,
        RESUMING
    }

    private static State currentState = State.IDLE;
    private static ReconnectData pendingData = null;
    private static int waitTicks = 0;
    private static BlockPos targetSavedPos = null;

    /**
     * Rozpoczyna proces wczytania i wznowienia z pliku reconnect_data.json.
     */
    public static void start(Baritone baritone) {
        Path filePath = baritone.getDirectory().resolve("reconnect_data.json");
        ReconnectData data = ReconnectData.load(filePath);
        if (data == null) {
            Helper.HELPER.logDirect("§c[Reconnect] Nie znaleziono poprawnego pliku reconnect_data.json!");
            return;
        }

        pendingData = data;
        targetSavedPos = new BlockPos((int) Math.floor(data.x), (int) Math.floor(data.y), (int) Math.floor(data.z));
        Minecraft mc = Minecraft.getInstance();

        // 1. Jeśli gracz nie jest jeszcze w świecie, a znamy IP serwera -> połącz z serwerem
        if (mc.player == null || mc.level == null) {
            if (data.server_ip != null && !data.server_ip.isEmpty()) {
                Helper.HELPER.logDirect("§b[Reconnect] Łączę z serwerem: " + data.server_ip);
                try {
                    ServerAddress address = ServerAddress.parseString(data.server_ip);
                    ServerData serverData = new ServerData("Bypass Auto-Reconnect", data.server_ip, ServerData.Type.OTHER);
                    ConnectScreen.startConnecting(new TitleScreen(), mc, address, serverData, false, null);
                } catch (Throwable t) {
                    Helper.HELPER.logDirect("§c[Reconnect] Nie udało się automatycznie połączyć: " + t.getMessage());
                }
            } else {
                Helper.HELPER.logDirect("§e[Reconnect] Wejdź na serwer ręcznie, a następnie wpisz #reconnect.");
            }
        }

        // 2. Oczekiwanie na załadowanie świata (2-3 sekundy = ok. 50 ticków)
        currentState = State.WAITING_WORLD_LOAD;
        waitTicks = 50;
        Helper.HELPER.logDirect("§a[Reconnect] Załadowano dane sesji. Oczekiwanie na synchronizację świata...");
    }

    /**
     * Wywoływany co tick gry (np. przez zdarzenie tick / pętlę Baritone).
     */
    public static void onTick(Baritone baritone) {
        if (currentState == State.IDLE || pendingData == null) return;
        IPlayerContext ctx = baritone.getPlayerContext();
        if (ctx == null || ctx.player() == null || ctx.world() == null) {
            return;
        }

        switch (currentState) {
            case WAITING_WORLD_LOAD:
                waitTicks--;
                if (waitTicks <= 0) {
                    BlockPos currentPos = ctx.playerFeet();
                    double dist = Math.sqrt(currentPos.distSqr(targetSavedPos));

                    // 4. Jeśli odległość > 5 bloków -> pathfind do zapisanej pozycji
                    if (dist > 5.0D) {
                        Helper.HELPER.logDirect(String.format("§e[Reconnect] Odległość od miejsca zapisu: %.1f bloków (>5). Prowadzę gracza na pozycję...", dist));
                        baritone.getCustomGoalProcess().setGoalAndPath(new GoalBlock(targetSavedPos));
                        currentState = State.PATHFINDING_TO_SAVED_POS;
                    } else {
                        currentState = State.RESUMING;
                    }
                }
                break;

            case PATHFINDING_TO_SAVED_POS:
                BlockPos feet = ctx.playerFeet();
                double remainingDist = Math.sqrt(feet.distSqr(targetSavedPos));
                if (remainingDist <= 3.0D || !baritone.getPathingBehavior().isPathing()) {
                    Helper.HELPER.logDirect("§a[Reconnect] Osiągnięto zapisaną pozycję bota.");
                    currentState = State.RESUMING;
                }
                break;

            case RESUMING:
                // 5 & 6. Przywróć stan i AUTOMATYCZNIE wznów #bypass
                Helper.HELPER.logDirect("§a[Reconnect] Przywracanie parametrów i automatyczne wznawianie kopania #bypass...");
                baritone.getBypassProcess().resumeFromData(pendingData);
                currentState = State.IDLE;
                pendingData = null;
                targetSavedPos = null;
                break;
        }
    }

    public static boolean isReconnecting() {
        return currentState != State.IDLE;
    }
}
