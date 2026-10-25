package com.astra.revolution.pathing;

import baritone.api.IBaritone;
import baritone.api.pathing.calc.IPath;
import baritone.api.pathing.movement.IMovement;
import baritone.api.pathing.path.IPathExecutor;
import baritone.api.utils.BetterBlockPos;
import baritone.api.utils.IInputOverrideHandler;
import baritone.api.utils.IPlayerContext;
import baritone.api.utils.Rotation;
import baritone.api.utils.input.Input;
import baritone.pathing.movement.movements.MovementTraverse;
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

    // 5-kierunkowa legalna siatka klawiszy Forward-Only GrimAC (całkowity zakaz cofania S / MOVE_BACK)
    private static final int[][] KEY_GRID = {
        { 1,  0,    0}, // W
        { 1, -1,   45}, // W + D (w prawo)
        { 0, -1,   90}, // D (w prawo)
        { 0,  1,  -90}, // A (w lewo)
        { 1,  1,  -45}  // W + A (w lewo)
    };

    private Vec3 lastPos = Vec3.ZERO;
    private int stallTicks = 0;
    private int unstuckStrafeDir = 0; // -1 = prawo, 1 = lewo
    private int unstuckPulseRemaining = 0;
    private float lastValidWorldYaw = Float.NaN;

    // Rejestr dzierżawy celowania dla procesów nadrzędnych (np. FarmProcess)
    private int externalAimLockTicks = 0;
    private Rotation lastExternalRotation = null;

    private AstraMovementFluidityEngine() {}

    /**
     * Rezerwuje celowanie dla procesu nadrzędnego (np. FarmProcess), blokując
     * nadpisywanie kamery przez wektor trasy w trakcie ścinania/sadzenia plonów.
     */
    public void lockAimForProcess(Rotation targetRotation, int ticks) {
        this.lastExternalRotation = targetRotation;
        this.externalAimLockTicks = Math.max(this.externalAimLockTicks, ticks);
    }

    public boolean isAimLockedByProcess() {
        return this.externalAimLockTicks > 0;
    }

    /**
     * Główna metoda wywoływana pod koniec ticku ruchu ścieżki.
     */
    public void onTickMovement(IBaritone baritone, boolean isActivelyMiningOrPlacing) {
        if (baritone == null) return;
        IPlayerContext ctx = baritone.getPlayerContext();
        if (ctx == null) return;
        LocalPlayer player = ctx.player();
        if (player == null || ctx.world() == null) return;

        if (this.externalAimLockTicks > 0) {
            this.externalAimLockTicks--;
        }

        IPathExecutor exec = baritone.getPathingBehavior() != null ? baritone.getPathingBehavior().getCurrent() : null;
        if (exec == null || exec.getPath() == null) {
            resetState();
            return;
        }

        IPath path = exec.getPath();
        List<IMovement> movements = path.movements();
        int pathPos = exec.getPosition();
        if (pathPos < 0 || pathPos >= movements.size()) return;

        IMovement currentMovement = movements.get(pathPos);
        IInputOverrideHandler ioh = baritone.getInputOverrideHandler();

        // 1. Ruchy precyzyjne (schody, skoki, diagonale, cofanie na krawędzi)
        // Dla nich NIGDY nie używamy lookahead N+1/N+2 ani feelerów -38°!
        boolean isFlatTraverse = (currentMovement instanceof MovementTraverse)
                && currentMovement.getSrc().y == currentMovement.getDest().y;
        boolean baritoneWantsBackOrSneak = ioh.isInputForcedDown(Input.MOVE_BACK)
                || ioh.isInputForcedDown(Input.SNEAK);
        boolean allowLookaheadSmoothing = isFlatTraverse && !baritoneWantsBackOrSneak && !player.isInWater() && !player.isInLava();

        // Wyzeruj sztuczny impuls -38° podczas aktywnej ścieżki A*, aby nie zrzucać bota z bloków
        this.unstuckPulseRemaining = 0;

        // 2. Oblicz docelowy kąt ścieżki (pathYaw):
        float desiredPathYaw = computeBaseMovementYaw(player, currentMovement);
        if (allowLookaheadSmoothing && pathPos + 1 < movements.size()) {
            IMovement nextMovement = movements.get(pathPos + 1);
            if (nextMovement instanceof MovementTraverse && nextMovement.getSrc().y == nextMovement.getDest().y) {
                desiredPathYaw = blendLookaheadYaw(player, currentMovement, nextMovement, 0.25f);
            }
        }

        boolean holdExternalAim = isActivelyMiningOrPlacing || this.externalAimLockTicks > 0;

        // 3. Aktualizuj kamerę TYLKO gdy proces (np. FarmProcess) nie celuje w blok
        // i gdy ruch to płaski traverse (dla MovementAscend/Descend/Diagonal Baritone sam ustawia optymalną rotację!)
        if (!holdExternalAim && baritone.getLookBehavior() != null) {
            if (allowLookaheadSmoothing) {
                BetterBlockPos dest = currentMovement.getDest();
                double dy = (dest.y + 0.5) - player.getEyeY();
                double dx = (dest.x + 0.5) - player.getX();
                double dz = (dest.z + 0.5) - player.getZ();
                double hDist = Math.max(Math.hypot(dx, dz), 0.4);
                float pitchToNode = (float) -Math.toDegrees(Math.atan2(dy, hDist));
                float targetPitch = Mth.clamp(pitchToNode, -22.0f, 22.0f);
                baritone.getLookBehavior().updateTarget(new Rotation(desiredPathYaw, targetPitch), true);
            }
        } else if (this.externalAimLockTicks > 0 && this.lastExternalRotation != null && baritone.getLookBehavior() != null) {
            baritone.getLookBehavior().updateTarget(this.lastExternalRotation, true);
        }

        // 4. GrimAC 8-Octant MoveFix: przelicz wektor ruchu względem serwerowego Yaw kamery na bieżący tick!
        if (!player.isInWater() && !player.isInLava()) {
            float effectiveTickYaw = getEffectiveCurrentTickYaw(baritone, player);
            applyGrimCompliantMoveFix(ioh, desiredPathYaw, effectiveTickYaw, baritoneWantsBackOrSneak);
        }

        // KRYTYCZNE DLA GRIMAC (Argon 4): Całkowicie wyłączono suppressFarmlandMicroJump!
        // GrimAC wymaga, aby przejście 0.9375 (Farmland) <-> 1.000 (Dirt) odbywało się wyłącznie przez Vanilla Entity.collide()
    }

    private float getEffectiveCurrentTickYaw(IBaritone baritone, LocalPlayer player) {
        if (this.lastExternalRotation != null && this.externalAimLockTicks > 0) {
            return this.lastExternalRotation.getYaw();
        }
        if (baritone.getLookBehavior() != null && baritone.getLookBehavior().getServerRotation() != null) {
            return baritone.getLookBehavior().getServerRotation().getYaw();
        }
        return player.getYRot();
    }

    private float computeBaseMovementYaw(LocalPlayer player, IMovement currentMovement) {
        BetterBlockPos dest = currentMovement.getDest();
        double dx = (dest.x + 0.5) - player.getX();
        double dz = (dest.z + 0.5) - player.getZ();
        if (Math.hypot(dx, dz) < 0.15 && !Float.isNaN(lastValidWorldYaw)) {
            return lastValidWorldYaw;
        }
        float yaw = (float) (Mth.atan2(dz, dx) * (180.0 / Math.PI)) - 90.0F;
        lastValidWorldYaw = yaw;
        return yaw;
    }

    private float blendLookaheadYaw(LocalPlayer player, IMovement current, IMovement next, float weightNext) {
        BetterBlockPos curDest = current.getDest();
        BetterBlockPos nextDest = next.getDest();
        double curX = curDest.x + 0.5;
        double curZ = curDest.z + 0.5;
        double nextX = nextDest.x + 0.5;
        double nextZ = nextDest.z + 0.5;
        double targetX = Mth.lerp(weightNext, curX, nextX);
        double targetZ = Mth.lerp(weightNext, curZ, nextZ);

        AABB pbox = player.getBoundingBox();
        if (player.level().noCollision(player, pbox.move(targetX - player.getX(), 0.05, targetZ - player.getZ()))) {
            double dx = targetX - player.getX();
            double dz = targetZ - player.getZ();
            return (float) (Mth.atan2(dz, dx) * (180.0 / Math.PI)) - 90.0F;
        }
        return computeBaseMovementYaw(player, current);
    }

    private void applyGrimCompliantMoveFix(
            IInputOverrideHandler ioh,
            float desiredMoveYaw,
            float actualCameraYaw,
            boolean preserveNativeBackOrSneak) {

        if (preserveNativeBackOrSneak) {
            if (!ioh.isInputForcedDown(Input.MOVE_FORWARD)) {
                ioh.setInputForceState(Input.SPRINT, false);
            }
            return;
        }

        boolean wantsMove = ioh.isInputForcedDown(Input.MOVE_FORWARD)
                || ioh.isInputForcedDown(Input.MOVE_LEFT)
                || ioh.isInputForcedDown(Input.MOVE_RIGHT);
        if (!wantsMove) return;

        float delta = Mth.wrapDegrees(desiredMoveYaw - actualCameraYaw);

        boolean w = delta >= -67.5f && delta <= 67.5f;
        boolean s = delta > 112.5f || delta < -112.5f;
        boolean d = delta >= -157.5f && delta <= -22.5f;
        boolean a = delta >= 22.5f && delta <= 157.5f;

        ioh.setInputForceState(Input.MOVE_FORWARD, w);
        ioh.setInputForceState(Input.MOVE_BACK, s);
        ioh.setInputForceState(Input.MOVE_LEFT, a);
        ioh.setInputForceState(Input.MOVE_RIGHT, d);

        if (!w) {
            ioh.setInputForceState(Input.SPRINT, false);
        }
    }

    private void suppressFarmlandMicroJump(IBaritone baritone, LocalPlayer player) {
        // Celowo puste: GrimAC wymaga czystej symulacji fizyki Vanilla Entity.collide() na styku 0.9375 i 1.000.
    }

    private void applyQuantizedMovementInputs(IBaritone baritone, float currentYaw, float targetWorldYaw) {
        var ioh = baritone.getInputOverrideHandler();
        ioh.setInputForceState(Input.MOVE_BACK, false);

        float deltaYaw = Mth.wrapDegrees(targetWorldYaw - currentYaw);

        // Gdy kąt przekracza 112.5° (np. w ułamku ticka przed obrotem kamery):
        // NIGDY nie wciskamy S — wymuszamy strafe w stronę krótszego obrotu
        if (Math.abs(deltaYaw) > 112.5f) {
            ioh.setInputForceState(Input.MOVE_FORWARD, false);
            ioh.setInputForceState(Input.MOVE_LEFT, deltaYaw < 0.0f);
            ioh.setInputForceState(Input.MOVE_RIGHT, deltaYaw > 0.0f);
            ioh.setInputForceState(Input.SPRINT, false);
            return;
        }

        int bestIdx = 0;
        float minError = Float.MAX_VALUE;
        for (int i = 0; i < KEY_GRID.length; i++) {
            float err = Math.abs(Mth.wrapDegrees(deltaYaw - KEY_GRID[i][2]));
            if (err < minError) {
                minError = err;
                bestIdx = i;
            }
        }

        int fwd = KEY_GRID[bestIdx][0];
        int str = KEY_GRID[bestIdx][1];

        ioh.setInputForceState(Input.MOVE_FORWARD, fwd > 0);
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
        lastValidWorldYaw = Float.NaN;
        externalAimLockTicks = 0;
        lastExternalRotation = null;
    }

    /**
     * Normalizuje rzędną Y gracza na blokach 15/16 (Farmland 0.9375 / DirtPath),
     * eliminując sztuczne błędy wysokości i fałszywe wykrywanie schodków.
     */
    public static double getEffectiveGroundY(net.minecraft.world.entity.player.Player player) {
        if (player == null) return 0.0;
        double y = player.getY();
        double frac = y - Math.floor(y);
        if (frac >= 0.93 && frac <= 0.95) {
            return Math.ceil(y);
        }
        return y;
    }

    private static double sq(double v) {
        return v * v;
    }
}
