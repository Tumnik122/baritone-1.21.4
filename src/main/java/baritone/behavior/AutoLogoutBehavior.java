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
        boolean healthEnabled = Baritone.settings().disconnectOnLowHealth.value || Baritone.settings().autoLogout.value;
        boolean fallEnabled = Baritone.settings().disconnectOnFall.value;
        if (!healthEnabled && !fallEnabled) {
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

        // 1 serce = 2 HP. Domyślny próg to 3 serca = 6 HP (lub 6 serc w trybie Anarchia).
        if (healthEnabled) {
            double hearts = health / 2.0D;
            double thresholdHearts = Baritone.settings().disconnectHealthHearts.value;
            if (Baritone.settings().anarchiaMode.value && thresholdHearts < 6.0D) {
                thresholdHearts = 6.0D;
            }

            // Jeśli ma mniej niż próg serc
            if (hearts < thresholdHearts) {
                triggerAutoLogout(hearts, thresholdHearts, health);
                return;
            }
        }

        // Sprawdź czy auto-logout przy upadku z wysokości jest włączony (Anarchia / FallProtect)
        if (fallEnabled && !ctx.player().isFallFlying()) {
            // Jeśli bot ma wiadro z wodą i włączony MLG Water Clutch, pozwól mu bezpiecznie wylądować
            boolean canWaterClutch = (Baritone.settings().autoWaterClutch.value || Baritone.settings().allowWaterBucketFall.value)
                    && ctx.world().dimension() != net.minecraft.world.level.Level.NETHER
                    && WaterClutchBehavior.findWaterBucketSlot(ctx.player()) != -1;

            if (!canWaterClutch) {
                double maxFall = Baritone.settings().disconnectFallDistance.value;

                // 1. Rzeczywisty fallDistance w Minecraftcie
                if (ctx.player().fallDistance >= (float) maxFall) {
                    triggerFallAutoLogout(ctx.player().fallDistance, maxFall);
                    return;
                }

                // 2. Predykcja upadku w locie: gracz w powietrzu, ujemna prędkość pionowa i przepaść pod stopami
                if (!ctx.player().onGround() && !ctx.player().isInWater() && ctx.player().getDeltaMovement().y < -0.35) {
                    double groundDrop = calculateDistanceToGround(ctx.player());
                    if (groundDrop >= maxFall) {
                        triggerFallAutoLogout(groundDrop, maxFall);
                    }
                }
            }
        }
    }

    private double calculateDistanceToGround(net.minecraft.world.entity.player.Player player) {
        if (ctx.world() == null || player == null) return 0.0;
        int px = player.getBlockX();
        int py = player.getBlockY();
        int pz = player.getBlockZ();

        for (int y = py; y >= ctx.world().getMinY() && (py - y) <= 40; y--) {
            net.minecraft.core.BlockPos pos = new net.minecraft.core.BlockPos(px, y, pz);
            net.minecraft.world.level.block.state.BlockState state = ctx.world().getBlockState(pos);

            // Woda, pajęczyna, slime, liany, drabiny neutralizują obrażenia z upadku
            if (state.getFluidState().getType() instanceof net.minecraft.world.level.material.WaterFluid
                    || state.is(net.minecraft.world.level.block.Blocks.COBWEB)
                    || state.is(net.minecraft.world.level.block.Blocks.SLIME_BLOCK)
                    || state.is(net.minecraft.world.level.block.Blocks.HAY_BLOCK)
                    || state.is(net.minecraft.world.level.block.Blocks.LADDER)
                    || state.is(net.minecraft.world.level.block.Blocks.VINE)) {
                return 0.0;
            }

            if (!state.isAir() && state.blocksMotion()) {
                double groundY = y + 1.0;
                return Math.max(0.0, player.getY() - groundY);
            }
        }
        return 0.0;
    }

    private void triggerFallAutoLogout(double fallDist, double maxFall) {
        Baritone.settings().disconnectOnFall.value = false;
        wasAutoLoggedOut = true;
        joinReminderDelay = 40;

        try {
            SettingsUtil.save(Baritone.settings());
        } catch (Throwable t) {
            t.printStackTrace();
        }

        logDirect(String.format(Locale.ROOT,
                "§c[AutoLogout] KRYTYCZNY UPADEK: %.1f kratek >= próg %.1f kratek!",
                fallDist, maxFall));
        logDirect("§e[AutoLogout] Opcja FallProtect została przestawiona na OFF, aby nie logało ciągle po ponownym wejściu!");

        saveBypassSessionIfActive();

        baritone.getPathingControlManager().cancelEverything();
        baritone.getInputOverrideHandler().clearAllKeys();

        Component reason = Component.literal(String.format(Locale.ROOT,
                "§c[Baritone Anarchia] Upadek z wysokości: %.1f kratek (próg %.1f). Ochrona Anarchia rozłączyła bota!",
                fallDist, maxFall));

        if (ctx.player().connection != null && ctx.player().connection.getConnection() != null) {
            ctx.player().connection.getConnection().disconnect(reason);
        }
    }

    private void saveBypassSessionIfActive() {
        if (baritone.getBypassProcess() != null && baritone.getBypassProcess().isActive()) {
            try {
                BypassProcess bp = baritone.getBypassProcess();
                BypassConfig cfg = bp.getConfig();
                Path savePath = baritone.getDirectory().resolve(cfg.saveFile);
                String dir = bp.getTunnelDirection() != null ? bp.getTunnelDirection().name() : "NORTH";
                ReconnectData.save(savePath, ctx.player(), new ArrayList<>(bp.getTargetOres()), dir, bp.getState().name(), bp.getOresMined());
            } catch (Throwable ignored) {}
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
        saveBypassSessionIfActive();

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
