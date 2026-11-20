package com.astra.revolution.pathing.kinetic;

import baritone.api.IBaritone;
import baritone.api.pathing.calc.IPath;
import baritone.api.pathing.movement.IMovement;
import baritone.api.utils.BetterBlockPos;
import baritone.api.utils.IInputOverrideHandler;
import baritone.api.utils.IPlayerContext;
import baritone.api.utils.Rotation;
import baritone.api.utils.input.Input;
import baritone.pathing.movement.movements.MovementDiagonal;
import baritone.pathing.movement.movements.MovementTraverse;
import com.astra.revolution.pathing.AstraLocomotionMath;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.core.BlockPos;
import net.minecraft.util.Mth;
import net.minecraft.world.effect.MobEffects;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.Vec3;

import java.util.List;

/**
 * Silnik kinetyki lądowej dla Minecraft 1.21.4 (Zero-GC, GrimAC Safe):
 * 1. Momentum-Preserving Bunnyhop (7.12 m/s) z weryfikacją pasa startowego (Runway Lookahead)
 *    i trajektorii sufitu (Swept Ceiling Head-Bump Probe).
 * 2. Prawdziwy 4-punktowy Centripetal Catmull-Rom Corner Smoothing (alpha = 0.5)
 *    z bezalokacyjnym testem marginesu 0.18m od ścian.
 */
public final class AstraKineticWalkingEngine {

    public static final AstraKineticWalkingEngine INSTANCE = new AstraKineticWalkingEngine();

    // Próg prędkości poziomej per tick (~3.6 m/s), powyżej którego skok sprintowy daje pełny impuls 7.12 m/s
    private static final double MIN_BUNNYHOP_SPEED_SQ = 0.18D * 0.18D;
    private static final double SPLINE_LOOKAHEAD_U = 0.35D;
    private static final float MAX_TURN_FOR_BUNNYHOP_DEG = 22.0F;
    private static final float MAX_LAND_YAW_STEP = 34.0F;

    // Prealokowane bufory Zero-GC
    private final BlockPos.MutableBlockPos mpos = new BlockPos.MutableBlockPos();
    private final double[] splineOutXZ = new double[2];

    private AstraKineticWalkingEngine() {}

    public void reset(IInputOverrideHandler ioh) {
        if (ioh != null) {
            ioh.setInputForceState(Input.JUMP, false);
        }
    }

    /**
     * Optymalizuje ruch na lądzie w oparciu o bieżący segment oraz okno trasy IPath.
     */
    public void optimizeTerrestrialLocomotion(
            IBaritone baritone,
            LocalPlayer player,
            IPath path,
            int pathPos
    ) {
        if (player == null || baritone == null || path == null) return;

        IInputOverrideHandler ioh = baritone.getInputOverrideHandler();
        IPlayerContext ctx = baritone.getPlayerContext();
        Level world = ctx.world();
        if (world == null) return;

        List<IMovement> movements = path.movements();
        if (pathPos < 0 || pathPos >= movements.size()) return;

        IMovement curMov = movements.get(pathPos);
        BetterBlockPos curSrc = curMov.getSrc();
        BetterBlockPos curDest = curMov.getDest();
        BetterBlockPos nextDest = (pathPos + 1 < movements.size()) ? movements.get(pathPos + 1).getDest() : null;

        // 1. Warunki brzegowe Vanilla + GrimAC (zero ryzyk NoSlowdown / Simulation)
        boolean canSprintPhysically = player.getFoodData().getFoodLevel() > 6
                && !player.isUsingItem()
                && !player.hasEffect(MobEffects.BLINDNESS)
                && !player.onClimbable()
                && !player.isInLava()
                && !player.isInWater()
                && !ioh.isInputForcedDown(Input.SNEAK);

        if (!canSprintPhysically) {
            ioh.setInputForceState(Input.JUMP, false);
            return;
        }

        double px = player.getX();
        double py = player.getY();
        double pz = player.getZ();

        // 2. Centripetal Catmull-Rom Corner Smoothing (P0 -> P1 -> P2 -> P3)
        float steeringYaw = player.getYRot();
        boolean appliedSpline = false;

        if (nextDest != null && curDest.y == nextDest.y && curDest.y == Mth.floor(py + 0.1D)) {
            appliedSpline = applyCentripetalCatmullRom(baritone, ctx, world, player, movements, pathPos);
            if (appliedSpline) {
                double sdx = splineOutXZ[0] - px;
                double sdz = splineOutXZ[1] - pz;
                steeringYaw = (float) (Mth.atan2(sdz, sdx) * AstraLocomotionMath.RAD_TO_DEG) - 90.0F;
            }
        }

        // 3. Momentum-Preserving Bunnyhop (7.12 m/s)
        Vec3 vel = player.getDeltaMovement();
        double horizSpeedSq = vel.x * vel.x + vel.z * vel.z;

        if (!player.onGround()) {
            // W powietrzu natychmiast puszczamy spację (brak kolejkowania przedwczesnego skoku na GrimAC)
            ioh.setInputForceState(Input.JUMP, false);
            return;
        }

        boolean runwaySafe = isBunnyhopRunwaySafe(world, player, movements, pathPos, steeringYaw);
        boolean ceilingClear = checkSweptCeilingClearance(world, px, py, pz, curDest.x + 0.5D - px, curDest.z + 0.5D - pz);

        boolean shouldBunnyhop = horizSpeedSq >= MIN_BUNNYHOP_SPEED_SQ
                && !player.horizontalCollision
                && runwaySafe
                && ceilingClear
                && AstraLocomotionMath.isFastWalkableFloor(world, mpos, Mth.floor(px), Mth.floor(py) - 1, Mth.floor(pz));

        if (shouldBunnyhop) {
            ioh.setInputForceState(Input.SPRINT, true);
            ioh.setInputForceState(Input.MOVE_FORWARD, true);
            ioh.setInputForceState(Input.JUMP, true);
        } else {
            ioh.setInputForceState(Input.JUMP, false);
        }
    }

    /**
     * Sonda prześwitu sufitu wzdłuż trajektorii skoku (bieżący woksel + 1 i 2 bloki w przód).
     * Zabezpiecza przed uderzeniem głową w sufit 2-blokowego tunelu oraz nadproże na wysokości +3Y
     * (wierzchołek skoku gracza sięga Y + 1.8 + 1.252 = Y + 3.052m).
     */
    private boolean checkSweptCeilingClearance(Level world, double px, double py, double pz, double dirX, double dirZ) {
        double len = Math.hypot(dirX, dirZ);
        double ux = len > 1.0E-4D ? (dirX / len) : 0.0D;
        double uz = len > 1.0E-4D ? (dirZ / len) : 0.0D;
        int baseY = Mth.floor(py + 0.05D);

        // Próbkujemy 0.0m (pozycja obecna), 0.9m i 1.8m w przód wzdłuż toru lotu
        for (int step = 0; step <= 2; step++) {
            double probeX = px + ux * (step * 0.9D);
            double probeZ = pz + uz * (step * 0.9D);
            int bx = Mth.floor(probeX);
            int bz = Mth.floor(probeZ);

            // Sprawdzamy dy = +2 (sufit tunelu 2-wysokiego) oraz dy = +3 (strefa szczytu skoku 3.05m)
            if (AstraLocomotionMath.hasCollision(world, mpos, bx, baseY + 2, bz)) {
                return false;
            }
            if (AstraLocomotionMath.hasCollision(world, mpos, bx, baseY + 3, bz)) {
                return false;
            }
        }
        return true;
    }

    /**
     * Weryfikuje, czy przed graczem znajduje się co najmniej 2-blokowy, prosty, płaski i bezpieczny pas
     * (zapobiega wyskakiwaniu z zakrętów 90 deg, z krawędzi klifów lub w lawę/pajęczynę).
     */
    private boolean isBunnyhopRunwaySafe(
            Level world,
            LocalPlayer player,
            List<IMovement> movements,
            int pathPos,
            float currentHeadingYaw
    ) {
        // Nie skacz na ostatnich 2 węzłach przed celem (unikamy przeskoczenia punktu docelowego)
        if (pathPos + 2 >= movements.size()) {
            return false;
        }

        int baseY = movements.get(pathPos).getSrc().y;
        double px = player.getX();
        double pz = player.getZ();

        for (int offset = 0; offset <= 2; offset++) {
            IMovement m = movements.get(pathPos + offset);
            if (!(m instanceof MovementTraverse) && !(m instanceof MovementDiagonal)) {
                return false;
            }
            BetterBlockPos src = m.getSrc();
            BetterBlockPos dst = m.getDest();
            if (src.y != baseY || dst.y != baseY) {
                return false; // Zmiana wysokości (schody/spadek)
            }

            // Podłoże musi być szybkie i stabilne, a ciało wolne od cieczy/pajęczyn
            if (!AstraLocomotionMath.isFastWalkableFloor(world, mpos, dst.x, dst.y - 1, dst.z)) {
                return false;
            }
            if (AstraLocomotionMath.isBodyHazardOrSlowdown(world, mpos, dst.x, dst.y, dst.z)
                    || AstraLocomotionMath.isBodyHazardOrSlowdown(world, mpos, dst.x, dst.y + 1, dst.z)) {
                return false;
            }

            // Kąt między bieżącym kierunkiem a kolejnym węzłem nie może przekraczać 22 stopni
            double segDx = (dst.x + 0.5D) - px;
            double segDz = (dst.z + 0.5D) - pz;
            if (segDx * segDx + segDz * segDz > 0.25D) {
                float segYaw = (float) (Mth.atan2(segDz, segDx) * AstraLocomotionMath.RAD_TO_DEG) - 90.0F;
                if (Math.abs(Mth.wrapDegrees(segYaw - currentHeadingYaw)) > MAX_TURN_FOR_BUNNYHOP_DEG) {
                    return false;
                }
            }
        }
        return true;
    }

    /**
     * Wyznacza bezalokacyjny punkt Lookahead na krzywej Centripetal Catmull-Rom (P0, P1, P2, P3)
     * i aplikuje go WYŁĄCZNIE po weryfikacji marginesu 0.18m od ścian oraz kwantyzacji GCD.
     */
    private boolean applyCentripetalCatmullRom(
            IBaritone baritone,
            IPlayerContext ctx,
            Level world,
            LocalPlayer player,
            List<IMovement> movements,
            int pathPos
    ) {
        IMovement curMov = movements.get(pathPos);
        IMovement nextMov = movements.get(pathPos + 1);

        // Ścinamy zakręty wyłącznie na płaskich odcinkach lądowych
        if (!(nextMov instanceof MovementTraverse) && !(nextMov instanceof MovementDiagonal)) {
            return false;
        }

        // Okno 4 punktów kontrolnych: P0 (poprzedni/src), P1 (aktualny dest), P2 (kolejny dest), P3 (ekstrapolacja/pathPos+2)
        BetterBlockPos p0Pos = (pathPos > 0) ? movements.get(pathPos - 1).getSrc() : curMov.getSrc();
        BetterBlockPos p1Pos = curMov.getDest();
        BetterBlockPos p2Pos = nextMov.getDest();

        double x0 = p0Pos.x + 0.5D;
        double z0 = p0Pos.z + 0.5D;
        double x1 = p1Pos.x + 0.5D;
        double z1 = p1Pos.z + 0.5D;
        double x2 = p2Pos.x + 0.5D;
        double z2 = p2Pos.z + 0.5D;

        double x3;
        double z3;
        if (pathPos + 2 < movements.size() && movements.get(pathPos + 2).getDest().y == p1Pos.y) {
            BetterBlockPos p3Pos = movements.get(pathPos + 2).getDest();
            x3 = p3Pos.x + 0.5D;
            z3 = p3Pos.z + 0.5D;
        } else {
            // Liniowa ekstrapolacja P3 dla ostatniego zakrętu
            x3 = x2 + (x2 - x1);
            z3 = z2 + (z2 - z1);
        }

        // Ewaluacja krzywej między P1 a P2 dla u = 0.35 (zero alokacji)
        AstraLocomotionMath.evalCentripetalCatmullRom2D(
                x0, z0, x1, z1, x2, z2, x3, z3, SPLINE_LOOKAHEAD_U, splineOutXZ
        );

        double targetX = splineOutXZ[0];
        double targetZ = splineOutXZ[1];
        double px = player.getX();
        double py = player.getY();
        double pz = player.getZ();

        // Sprawdź punkt pośredni (50%) oraz docelowy (100%) pod kątem kolizji z marginesem 0.18m i ciągłości podłogi
        double midX = 0.5D * (px + targetX);
        double midZ = 0.5D * (pz + targetZ);
        int floorY = Mth.floor(py + 0.05D) - 1;

        if (!AstraLocomotionMath.isHorizontalPointClear(world, mpos, midX, py, midZ)
                || !AstraLocomotionMath.isHorizontalPointClear(world, mpos, targetX, py, targetZ)
                || !AstraLocomotionMath.isFastWalkableFloor(world, mpos, Mth.floor(midX), floorY, Mth.floor(midZ))
                || !AstraLocomotionMath.isFastWalkableFloor(world, mpos, Mth.floor(targetX), floorY, Mth.floor(targetZ))) {
            return false;
        }

        double dx = targetX - px;
        double dz = targetZ - pz;
        if (dx * dx + dz * dz < 0.04D) {
            return false;
        }

        float rawYaw = (float) (Mth.atan2(dz, dx) * AstraLocomotionMath.RAD_TO_DEG) - 90.0F;
        Rotation qRot = AstraLocomotionMath.quantizeRotationGcd(
                ctx,
                rawYaw,
                player.getXRot(),
                player.getYRot(),
                player.getXRot(),
                MAX_LAND_YAW_STEP,
                0.0F
        );

        baritone.getLookBehavior().updateTarget(qRot, true);
        baritone.getInputOverrideHandler().setInputForceState(Input.MOVE_FORWARD, true);
        baritone.getInputOverrideHandler().setInputForceState(Input.SPRINT, true);
        return true;
    }
}

