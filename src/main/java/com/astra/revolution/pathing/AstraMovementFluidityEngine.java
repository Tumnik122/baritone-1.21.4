package com.astra.revolution.pathing;

import baritone.api.IBaritone;
import baritone.api.pathing.calc.IPath;
import baritone.api.pathing.path.IPathExecutor;
import baritone.api.utils.BetterBlockPos;
import baritone.api.utils.IPlayerContext;
import baritone.api.utils.input.Input;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.util.Mth;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;

import java.util.List;

/**
 * GrimAC-Compliant Movement Fluidity & Predictive Anti-Stall Engine (1.21.4 Mojmap).
 *
 * Eliminuje:
 * 1. Yaw-Movement Arc Drift (wygładzanie rotacji nie ściąga gracza na narożniki bloków).
 * 2. Zacięcia na zakrętach i schodkach (MovementAscend / MovementTraverse).
 * 3. Utratę pędu sprintu przy podejściu pod blok dzięki sondzie kolizyjnej i asyście skoku.
 */
public final class AstraMovementFluidityEngine {

    public static final AstraMovementFluidityEngine INSTANCE = new AstraMovementFluidityEngine();

    // 8-kierunkowa legalna siatka klawiszy GrimAC: {forward (W/S), strafe (A/D), kąt względny w stopniach}
    private static final int[][] KEY_GRID = {
        { 1,  0,    0}, // W
        { 1, -1,   45}, // W + D (w prawo)
        { 0, -1,   90}, // D
        {-1, -1,  135}, // S + D
        {-1,  0,  180}, // S
        {-1,  1, -135}, // S + A
        { 0,  1,  -90}, // A
        { 1,  1,  -45}  // W + A
    };

    private Vec3 lastPos = Vec3.ZERO;
    private int stallTicks = 0;
    private int unstuckStrafeDir = 0; // -1 = prawo, 1 = lewo
    private int unstuckPulseRemaining = 0;

    private AstraMovementFluidityEngine() {}

    /**
     * Główna metoda wywoływana pod koniec ticku ruchu ścieżki.
     */
    public void onTickMovement(IBaritone baritone, boolean isActivelyMiningOrPlacing) {
        if (baritone == null) return;
        IPlayerContext ctx = baritone.getPlayerContext();
        if (ctx == null) return;
        LocalPlayer player = ctx.player();
        if (player == null || ctx.world() == null) return;

        IPathExecutor exec = baritone.getPathingBehavior() != null ? baritone.getPathingBehavior().getCurrent() : null;
        if (exec == null || exec.getPath() == null) {
            resetState();
            return;
        }

        IPath path = exec.getPath();
        List<BetterBlockPos> positions = path.positions();
        int pathPos = exec.getPosition();
        if (pathPos < 0 || pathPos >= positions.size()) return;

        // 1. Lookahead Target Vector: miksujemy węzeł N_i oraz N_{i+1}, gdy zbliżamy się do środka węzła
        BetterBlockPos currentTarget = positions.get(Math.min(pathPos + 1, positions.size() - 1));
        double targetX = currentTarget.x + 0.5;
        double targetZ = currentTarget.z + 0.5;

        double distToCurrentSq = sq(player.getX() - targetX) + sq(player.getZ() - targetZ);
        if (distToCurrentSq < 0.32 && pathPos + 2 < positions.size()) {
            BetterBlockPos nextNode = positions.get(pathPos + 2);
            double blend = 1.0 - (Math.sqrt(distToCurrentSq) / 0.565);
            double candidateX = Mth.lerp(blend * 0.55, targetX, nextNode.x + 0.5);
            double candidateZ = Mth.lerp(blend * 0.55, targetZ, nextNode.z + 0.5);

            // Weryfikacja AABB: pre-rounding dozwolony TYLKO wtedy, gdy linia nie przecina narożnika ściany!
            AABB pbox = player.getBoundingBox();
            if (player.level().noCollision(player, pbox.move(candidateX - player.getX(), 0.05, candidateZ - player.getZ()))) {
                targetX = candidateX;
                targetZ = candidateZ;
            }
        }

        double dx = targetX - player.getX();
        double dz = targetZ - player.getZ();
        double horizDist = Math.hypot(dx, dz);
        if (horizDist < 0.05) return;

        float desiredWorldYaw = (float) (Mth.atan2(dz, dx) * (180.0 / Math.PI)) - 90.0F;
        float currentServerYaw = player.getYRot();

        // 2. Detekcja Mikro-Zacięcia (3-Tick Predictive Stall Detector)
        double movedHoriz = Math.hypot(player.getX() - lastPos.x, player.getZ() - lastPos.z);
        lastPos = player.position();

        boolean wantsToMove = baritone.getInputOverrideHandler().isInputForcedDown(Input.MOVE_FORWARD);
        if (wantsToMove && !isActivelyMiningOrPlacing && movedHoriz < 0.024 && horizDist > 0.25) {
            stallTicks++;
        } else {
            stallTicks = Math.max(0, stallTicks - 1);
        }

        // 3. Corner Feeler & Micro-Unstuck Injection
        if ((stallTicks >= 2 || player.horizontalCollision) && !isActivelyMiningOrPlacing) {
            double normDx = dx / horizDist;
            double normDz = dz / horizDist;

            if (unstuckPulseRemaining <= 0) {
                unstuckStrafeDir = computeCornerEscapeStrafe(player, normDx, normDz);
                unstuckPulseRemaining = 3; // 150ms mikro-korekty omijającej narożnik
            }

            // A) Early Ascend Assist: jeśli docelowy węzeł jest wyżej o 1 blok, skaczemy zanim pęd spadnie do 0
            boolean targetIsAbove = currentTarget.y > Mth.floor(player.getY() + 0.05);

            // B) Step-Up Obstacle Assist: przed graczem jest schodek/krawędź, ale nad nią jest puste miejsce
            boolean stepUpClear = false;
            if (player.onGround()) {
                AABB box = player.getBoundingBox();
                boolean highClear = player.level().noCollision(player, box.move(normDx * 0.32, 1.05, normDz * 0.32));
                boolean lowBlocked = !player.level().noCollision(player, box.move(normDx * 0.32, 0.05, normDz * 0.32));
                stepUpClear = highClear && lowBlocked;
            }

            if ((targetIsAbove || stepUpClear || stallTicks >= 4) && player.onGround()) {
                baritone.getInputOverrideHandler().setInputForceState(Input.JUMP, true);
            }
        }

        // 4. GrimAC Silent Move Quantizer: dobierz optymalną kombinację W/A/S/D dla aktualnego Yaw
        float effectiveTargetYaw = desiredWorldYaw;
        if (unstuckPulseRemaining > 0) {
            unstuckPulseRemaining--;
            effectiveTargetYaw += unstuckStrafeDir * (-38.0F);
        }

        if (wantsToMove && !player.isInWater() && !player.isInLava()) {
            applyQuantizedMovementInputs(baritone, currentServerYaw, effectiveTargetYaw);
        }
    }

    /**
     * Dobiera z 8 legalnych kierunków ten, który idealnie koryguje ruch w stronę celu
     * nawet gdy SmoothAim jest w trakcie obracania kamery.
     */
    private void applyQuantizedMovementInputs(IBaritone baritone, float currentYaw, float targetWorldYaw) {
        float deltaYaw = Mth.wrapDegrees(targetWorldYaw - currentYaw);

        int bestIdx = 0;
        float minError = Float.MAX_VALUE;
        for (int i = 0; i < KEY_GRID.length; i++) {
            float err = Math.abs(Mth.wrapDegrees(deltaYaw - KEY_GRID[i][2]));
            if (err < minError) {
                minError = err;
                bestIdx = i;
            }
        }

        var ioh = baritone.getInputOverrideHandler();
        int fwd = KEY_GRID[bestIdx][0];
        int str = KEY_GRID[bestIdx][1];

        ioh.setInputForceState(Input.MOVE_FORWARD, fwd > 0);
        ioh.setInputForceState(Input.MOVE_BACK, fwd < 0);
        ioh.setInputForceState(Input.MOVE_LEFT, str > 0);
        ioh.setInputForceState(Input.MOVE_RIGHT, str < 0);

        // GrimAC Sprint Safety: sprint jest dozwolony tylko przy ruchu w przód (W)
        if (fwd <= 0) {
            ioh.setInputForceState(Input.SPRINT, false);
        }
    }

    /**
     * Sprint-Preserving Decoupled Input (rekomendacja Argon 4).
     * Utrzymuje wciśnięty klawisz W (forwardImpulse = 1.0, sprint 5.61 m/s)
     * dopóki kąt kamery do osi korytarza wynosi <= 70°, kompensując kierunek klawiszami strafe (A/D).
     */
    public void applySprintSafeDecoupledInput(IBaritone baritone, float serverYaw, float spineYaw) {
        if (baritone == null) return;
        var ioh = baritone.getInputOverrideHandler();
        float diff = Mth.wrapDegrees(spineYaw - serverYaw);

        if (Math.abs(diff) <= 70.0f) {
            ioh.setInputForceState(Input.MOVE_FORWARD, true);
            ioh.setInputForceState(Input.MOVE_BACK, false);
            ioh.setInputForceState(Input.SPRINT, true);

            if (diff > 18.0f) {
                ioh.setInputForceState(Input.MOVE_RIGHT, true);
                ioh.setInputForceState(Input.MOVE_LEFT, false);
            } else if (diff < -18.0f) {
                ioh.setInputForceState(Input.MOVE_LEFT, true);
                ioh.setInputForceState(Input.MOVE_RIGHT, false);
            } else {
                ioh.setInputForceState(Input.MOVE_LEFT, false);
                ioh.setInputForceState(Input.MOVE_RIGHT, false);
            }
        } else {
            // Poza stożkiem 70° użyj pełnej kwantyzacji 8-kierunkowej
            applyQuantizedMovementInputs(baritone, serverYaw, spineYaw);
        }
    }

    /**
     * Inteligentna sonda narożnikowa AABB (3-kierunkowy sensor lewo/prawo/skośny).
     * Wyznacza optymalny wektor ominięcia krawędzi bloku, unikając oscylacji w przód i w tył.
     */
    private int computeCornerEscapeStrafe(LocalPlayer player, double dirX, double dirZ) {
        AABB box = player.getBoundingBox();
        double perpX = dirZ * 0.32;
        double perpZ = -dirX * 0.32;
        double probeFwdX = dirX * 0.20;
        double probeFwdZ = dirZ * 0.20;

        // 1. Sprawdzenie skosów (lewy i prawy przedni narożnik)
        boolean leftFwdBlocked = !player.level().noCollision(player, box.move(probeFwdX + perpX, 0.05, probeFwdZ + perpZ));
        boolean rightFwdBlocked = !player.level().noCollision(player, box.move(probeFwdX - perpX, 0.05, probeFwdZ - perpZ));

        if (leftFwdBlocked && !rightFwdBlocked) return -1; // Lewa strona haczy -> omiń w prawo (D)
        if (rightFwdBlocked && !leftFwdBlocked) return 1;  // Prawa strona haczy -> omiń w lewo (A)

        // 2. Jeśli oba skosy są zablokowane (np. płaska ściana przed graczem), sprawdź czysty ruch boczny
        boolean leftLateralClear = player.level().noCollision(player, box.move(perpX * 1.25, 0.05, perpZ * 1.25));
        boolean rightLateralClear = player.level().noCollision(player, box.move(-perpX * 1.25, 0.05, -perpZ * 1.25));

        if (leftLateralClear && !rightLateralClear) return 1;  // Wolne z lewej -> idź w lewo
        if (rightLateralClear && !leftLateralClear) return -1; // Wolne z prawej -> idź w prawo

        // 3. Jeśli obie strony są otwarte lub zablokowane, zachowaj stabilny kierunek (brak oscylacji)
        if (unstuckStrafeDir != 0) {
            return unstuckStrafeDir;
        }
        return (player.getId() % 2 == 0) ? 1 : -1;
    }

    public void resetState() {
        stallTicks = 0;
        unstuckPulseRemaining = 0;
        lastPos = Vec3.ZERO;
    }

    private static double sq(double v) {
        return v * v;
    }
}
