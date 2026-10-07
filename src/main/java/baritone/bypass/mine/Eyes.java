package baritone.bypass.mine;

import baritone.api.utils.LastSent;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.world.phys.Vec3;

import java.util.List;

/**
 * Pozycje oczu, względem których celownik musi być poprawny:
 *  [0] ostatnia pozycja znana serwerowi (to ją zobaczy Grim przy pakiecie START/STOP_DESTROY_BLOCK),
 *  [1] przewidywana pozycja po najbliższym pakiecie ruchu (pozycja + deltaMovement).
 * Gdy bot stoi w miejscu, obie są identyczne i zwracamy jedną.
 */
public final class Eyes {
    private Eyes() {}

    public static List<Vec3> forNextPacket(LocalPlayer p) {
        if (p == null) {
            return List.of(Vec3.ZERO);
        }
        double h = p.getEyeHeight();
        if (p instanceof LastSent ls) {
            Vec3 sent = new Vec3(ls.mine$lastX(), ls.mine$lastY() + h, ls.mine$lastZ());
            Vec3 predicted = p.position().add(p.getDeltaMovement()).add(0, h, 0);
            return sent.distanceToSqr(predicted) < 1.0E-6 ? List.of(sent) : List.of(sent, predicted);
        }
        Vec3 eye = p.getEyePosition();
        return List.of(eye);
    }
}
