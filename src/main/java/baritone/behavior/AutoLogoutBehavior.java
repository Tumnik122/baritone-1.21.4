package baritone.behavior;

import baritone.Baritone;
import baritone.api.event.events.PlayerUpdateEvent;
import baritone.api.event.events.type.EventState;
import baritone.api.utils.Helper;
import baritone.api.utils.SettingsUtil;
import baritone.bypass.BypassConfig;
import baritone.bypass.BypassProcess;
import baritone.bypass.ReconnectData;
import net.minecraft.network.chat.Component;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Locale;

/**
 * AutoLogoutBehavior
 *
 * Automatyczne rozłączanie bota z serwerem, gdy poziom zdrowia spadnie poniżej określonej liczby SERC
 * (domyślnie 3 serca = 6.0 HP, nie 3 HP!).
 *
 * Zapobiega pętli rozłączeń po ponownym wejściu na serwer:
 * W momencie wyzwolenia natychmiast przestawia opcje disconnectOnLowHealth oraz autoLogout z ON na OFF
 * i zapisuje konfigurację, dzięki czemu ponowne dołączenie z niskim HP nie powoduje ciągłego wyrzucania.
 */
public class AutoLogoutBehavior extends Behavior implements Helper {

    private boolean wasAutoLoggedOut = false;
    private int joinReminderDelay = 0;

    public AutoLogoutBehavior(Baritone baritone) {
        super(baritone);
    }

    @Override
    public void onPlayerUpdate(PlayerUpdateEvent event) {
        if (event.getState() != EventState.POST) {
            return;
        }

        if (ctx.player() == null || ctx.world() == null) {
            return;
        }

        // Powiadomienie gracza po ponownym wejściu na serwer po wcześniejszym auto-logoucie
        if (wasAutoLoggedOut) {
            if (joinReminderDelay > 0) {
                joinReminderDelay--;
            } else {
                wasAutoLoggedOut = false;
                logDirect("§e[AutoLogout] Przypomnienie: AutoLogout został automatycznie WYŁĄCZONY po ostatnim rozłączeniu (< 3 serc).");
                logDirect("§7Gdy uleczysz postać, możesz włączyć go ponownie: §e#set autoLogout true §7lub §e#autologout on");
            }
        }

        // Sprawdź czy którakolwiek opcja auto-logoutu jest włączona w #settings
        boolean isEnabled = Baritone.settings().disconnectOnLowHealth.value || Baritone.settings().autoLogout.value;
        if (!isEnabled) {
            return;
        }

        // Ignoruj graczy w trybie kreatywnym, widza lub podczas ekranu śmierci
        if (ctx.player().isCreative() || ctx.player().isSpectator() || ctx.player().isDeadOrDying()) {
            return;
        }

        float health = ctx.player().getHealth();
        if (health <= 0.0f) {
            return;
        }

        // 1 serce = 2 HP. Domyślny próg to 3 serca = 6 HP.
        double hearts = health / 2.0D;
        double thresholdHearts = Baritone.settings().disconnectHealthHearts.value;

        // Jeśli ma mniej niż 3 serca (nie 3 HP, tylko serca!)
        if (hearts < thresholdHearts) {
            triggerAutoLogout(hearts, thresholdHearts, health);
        }
    }

    private void triggerAutoLogout(double hearts, double thresholdHearts, float health) {
        // 1. Zabezpieczenie przed pętlą: WYŁĄCZ opcję z ON na OFF
        Baritone.settings().disconnectOnLowHealth.value = false;
        Baritone.settings().autoLogout.value = false;
        wasAutoLoggedOut = true;
        joinReminderDelay = 40; // ~2 sekundy po ponownym wejściu

        // 2. Trwałe zapisanie ustawień na dysk
        try {
            SettingsUtil.save(Baritone.settings());
        } catch (Throwable t) {
            t.printStackTrace();
        }

        // 3. Wiadomości w konsoli i logach
        logDirect(String.format(Locale.ROOT,
                "§c[AutoLogout] KRYTYCZNIE NISKIE ZDROWIE: %.1f serc (%.1f HP) < próg %.1f serc!",
                hearts, health, thresholdHearts));
        logDirect("§e[AutoLogout] Opcja została automatycznie przestawiona na OFF, aby nie logało ciągle po ponownym wejściu!");

        // 4. Zapisanie sesji bypass/kopania jeśli proces był aktywny (do wznowienia przez #reconnect)
        if (baritone.getBypassProcess() != null && baritone.getBypassProcess().isActive()) {
            try {
                BypassProcess bp = baritone.getBypassProcess();
                BypassConfig cfg = bp.getConfig();
                Path savePath = baritone.getDirectory().resolve(cfg.saveFile);
                String dir = bp.getTunnelDirection() != null ? bp.getTunnelDirection().name() : "NORTH";
                ReconnectData.save(savePath, ctx.player(), new ArrayList<>(bp.getTargetOres()), dir, bp.getState().name(), bp.getOresMined());
            } catch (Throwable ignored) {}
        }

        // 5. Zatrzymanie ruchu i anulowanie operacji
        baritone.getPathingControlManager().cancelEverything();
        baritone.getInputOverrideHandler().clearAllKeys();

        // 6. Rozłączenie z serwerem
        Component reason = Component.literal(String.format(Locale.ROOT,
                "§c[Baritone AutoLogout] Niskie zdrowie: %.1f/%.1f serc (%.1f HP). Opcja przestawiona na OFF.",
                hearts, thresholdHearts, health));

        if (ctx.player().connection != null && ctx.player().connection.getConnection() != null) {
            ctx.player().connection.getConnection().disconnect(reason);
        }
    }
}
