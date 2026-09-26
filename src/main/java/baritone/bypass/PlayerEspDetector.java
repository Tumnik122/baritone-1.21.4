package baritone.bypass;

import baritone.api.utils.IPlayerContext;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.phys.Vec3;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;

/**
 * Moduł cichej detekcji graczy (ESP-Style Player Threat Detection).
 *
 * ZASADY GRIMAC-COMPLIANCE:
 * 1. Zgodnie z wymaganiem użytkownika, bot wykrywa graczy czysto algorytmicznie (jak ESP).
 * 2. Bot NIGDY NIE OBRACA KAMERY ani celownika w stronę wykrytego gracza!
 *    Obrót w stronę gracza znajdującego się za ścianami lub pod ziemią natychmiast
 *    aktywuje flagi Raytrace / Illegal Aim / Wallhack w silniku GrimAC.
 * 3. Monitoruje broń (w tym Mace - zabójcza broń 1.21.4) oraz wektory ruchu.
 */
public final class PlayerEspDetector {

    public record PlayerThreat(
            Player player,
            double distance,
            boolean hasMace,
            boolean isFallingFromAbove,
            boolean isDangerous
    ) {}

    private PlayerEspDetector() {}

    /**
     * Skanuje załadowanych graczy w świecie bez jakiejkolwiek ingerencji w rotację celownika.
     */
    public static List<PlayerThreat> scanThreats(IPlayerContext ctx, BypassConfig config) {
        List<PlayerThreat> threats = new ArrayList<>();
        if (ctx == null || ctx.player() == null || ctx.world() == null || !config.espPlayerDetect) {
            return threats;
        }

        Player self = ctx.player();
        double maxDistSq = config.playerWarningRadius * config.playerWarningRadius;

        for (Player other : ctx.world().players()) {
            if (other == null || other == self) continue;
            if (!other.isAlive() || other.isSpectator()) continue;

            String name = other.getScoreboardName();
            if (config.friendList != null && config.friendList.contains(name.toLowerCase(Locale.ROOT))) {
                continue;
            }

            double distSq = other.distanceToSqr(self);
            if (distSq <= maxDistSq) {
                double dist = Math.sqrt(distSq);

                ItemStack mainHand = other.getMainHandItem();
                boolean hasMace = mainHand.is(Items.MACE);
                String itemName = mainHand.getItem().toString().toLowerCase(Locale.ROOT);
                boolean hasWeapon = hasMace || itemName.contains("sword") || itemName.contains("axe") || mainHand.is(Items.END_CRYSTAL);

                double dy = other.getY() - self.getY();
                Vec3 velocity = other.getDeltaMovement();
                boolean isFallingFromAbove = dy > 1.5 && velocity.y < -0.1 && !other.onGround();

                // Zagrożenie krytyczne: gracz w strefie niebezpieczeństwa LUB gracz spadający z Mace z góry
                boolean inDangerZone = dist <= config.playerDangerRadius;
                boolean maceDive = hasMace && isFallingFromAbove && dist <= 25.0;
                boolean isDangerous = inDangerZone || maceDive || (hasWeapon && dist <= 12.0);

                threats.add(new PlayerThreat(other, dist, hasMace, isFallingFromAbove, isDangerous));
            }
        }

        threats.sort(Comparator.comparingDouble(PlayerThreat::distance));
        return threats;
    }

    /**
     * Zwraca najważniejsze bezpośrednie zagrożenie (najbliższy niebezpieczny gracz).
     */
    public static PlayerThreat getPrimaryThreat(IPlayerContext ctx, BypassConfig config) {
        List<PlayerThreat> threats = scanThreats(ctx, config);
        if (threats.isEmpty()) return null;

        // Priorytet dla gracza z Mace spadającego z góry
        for (PlayerThreat t : threats) {
            if (t.hasMace && t.isFallingFromAbove) {
                return t;
            }
        }

        // Następnie najbliższy gracz
        return threats.get(0);
    }
}
