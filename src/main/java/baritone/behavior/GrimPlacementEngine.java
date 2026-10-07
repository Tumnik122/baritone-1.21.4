package baritone.behavior;

import baritone.api.utils.IPlayerContext;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.item.BlockItem;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;

/**
 * GrimAC-compatible block placement engine.
 * Waliduje reach, LOS i kolejność pakietów przy stawianiu bloków.
 */
public final class GrimPlacementEngine {

    private final IPlayerContext ctx;
    private int placeCooldown = 0;

    private static final float MAX_PLACE_REACH = 4.0F;

    public GrimPlacementEngine(IPlayerContext ctx) {
        this.ctx = ctx;
    }

    /**
     * Próbuje postawić blok na danej pozycji (na danej ścianie sąsiedniego bloku).
     *
     * @param placeAgainstPos pozycja bloku, na którym stawiamy
     * @param face            ściana bloku, na której stawiamy
     * @return true jeśli postawiono
     */
    public boolean tryPlace(BlockPos placeAgainstPos, Direction face) {
        if (placeCooldown > 0) {
            placeCooldown--;
            return false;
        }

        LocalPlayer player = ctx.player();
        if (player == null || ctx.world() == null) return false;

        // === REACH VALIDATION ===
        Vec3 eyePos = player.getEyePosition();
        Vec3 faceCenter = getFaceCenter(placeAgainstPos, face);
        double distance = eyePos.distanceTo(faceCenter);
        if (distance > MAX_PLACE_REACH) {
            return false;
        }

        // === LINE OF SIGHT ===
        if (!hasLineOfSight(eyePos, faceCenter)) {
            return false;
        }

        // === ITEM CHECK ===
        ItemStack heldItem = player.getMainHandItem();
        if (heldItem.isEmpty() || !(heldItem.getItem() instanceof BlockItem)) {
            return false;
        }

        // === WYŚLIJ PAKIETY ===
        BlockHitResult hitResult = new BlockHitResult(faceCenter, face, placeAgainstPos, false);
        GrimPacketOrder.sendBlockPlace(player, hitResult, InteractionHand.MAIN_HAND);

        // Ustaw cooldown (2 ticki domyślnie — konfigurowalne)
        placeCooldown = GrimCompatSettings.getInstance().blockPlaceDelay;

        return true;
    }

    private boolean hasLineOfSight(Vec3 eyePos, Vec3 target) {
        BlockHitResult trace = ctx.world().clip(
                new net.minecraft.world.level.ClipContext(
                        eyePos, target,
                        net.minecraft.world.level.ClipContext.Block.COLLIDER,
                        net.minecraft.world.level.ClipContext.Fluid.NONE,
                        ctx.player()
                )
        );
        return trace.getType() == HitResult.Type.MISS;
    }

    private Vec3 getFaceCenter(BlockPos pos, Direction face) {
        Vec3 center = Vec3.atCenterOf(pos);
        return center.add(face.getStepX() * 0.5, face.getStepY() * 0.5, face.getStepZ() * 0.5);
    }

    public int getPlaceCooldown() {
        return placeCooldown;
    }

    public void setPlaceCooldown(int cooldown) {
        this.placeCooldown = cooldown;
    }
}
