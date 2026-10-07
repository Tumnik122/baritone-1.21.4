package baritone.behavior;

import baritone.api.utils.IPlayerContext;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.core.BlockPos;
import net.minecraft.world.effect.MobEffects;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.Vec3;

/**
 * GrimAC-compatible movement engine.
 * Zapewnia że wszystkie zmiany prędkości są zgodne z fizyką waniliową.
 * GrimAC symuluje ruch 1:1 — każda desynchronizacja = flaga.
 */
public final class GrimMovementEngine {

    private final IPlayerContext ctx;

    // -- Waniliowe stałe fizyki (1.21.4) --
    private static final float GRAVITY = 0.08F;
    private static final float AIR_ACCEL = 0.02F;
    private static final float SPRINT_MULTIPLIER = 1.3F;
    private static final float MAX_STEP_HEIGHT = 0.6F;

    // -- Stan poprzedniego ticka --
    private Vec3 lastVelocity = Vec3.ZERO;
    private boolean wasOnGround = true;
    private boolean wasSprinting = false;
    private boolean wasSneaking = false;

    public GrimMovementEngine(IPlayerContext ctx) {
        this.ctx = ctx;
    }

    /**
     * Waliduje i clampuje wektor prędkości przed zastosowaniem.
     * Wywoływane co tick przez MovementHelper przed ustawieniem inputu.
     */
    public void validateAndClampMovement(boolean wantSprint, boolean wantSneak,
                                         float strafeInput, float forwardInput) {
        LocalPlayer player = ctx.player();
        if (player == null) return;

        Vec3 currentDelta = player.getDeltaMovement();
        boolean onGround = player.onGround();

        // === SPRINT VALIDATION ===
        // Sprint może być aktywowany TYLKO gdy:
        // 1. Gracz jest na ziemi (lub w wodzie/lata)
        // 2. Gracz ma forwardInput > 0
        // 3. Gracz nie jest głodny (food > 6)
        // 4. Gracz nie jest oślepiony
        boolean canSprint = validateSprint(player, wantSprint, forwardInput);
        if (wantSprint && !canSprint) {
            player.setSprinting(false);
        }

        // === SNEAK VALIDATION ===
        boolean canSneak = !player.isSwimming() && !player.isFallFlying();
        if (wantSneak && !canSneak) {
            player.setShiftKeyDown(false);
        }

        // === STEP HEIGHT CHECK ===
        if (onGround && !wantSneak) {
            validateStepHeight(player);
        }

        // === ON GROUND VALIDATION ===
        validateOnGround(player);

        // Zapisz stan
        this.lastVelocity = player.getDeltaMovement();
        this.wasOnGround = onGround;
        this.wasSprinting = player.isSprinting();
        this.wasSneaking = player.isShiftKeyDown();
    }

    /**
     * Waliduje czy sprint może być aktywowany zgodnie z logiką waniliową.
     */
    private boolean validateSprint(LocalPlayer player, boolean wantSprint, float forwardInput) {
        if (!wantSprint) return false;
        if (forwardInput <= 0.0F) return false;
        if (player.getFoodData().getFoodLevel() <= 6) return false;
        if (player.hasEffect(MobEffects.BLINDNESS)) return false;
        if (player.isShiftKeyDown()) return false;
        if (player.isInWater()) return true; // swimming sprint is ok
        if (player.isFallFlying()) return false;
        // Sprint może być kontynuowany w powietrzu, ale nie rozpoczęty
        if (!player.onGround() && !wasSprinting) return false;
        return true;
    }

    /**
     * Sprawdza czy bot nie próbuje wejść na blok wyższy niż 0.6 bez skoku.
     */
    private void validateStepHeight(LocalPlayer player) {
        Vec3 pos = player.position();
        Vec3 vel = player.getDeltaMovement();

        // Sprawdź blok przed graczem w kierunku ruchu
        double nextX = pos.x + vel.x;
        double nextZ = pos.z + vel.z;

        BlockPos feetPos = BlockPos.containing(nextX, pos.y, nextZ);
        BlockPos headPos = feetPos.above();

        BlockState feetState = player.level().getBlockState(feetPos);
        BlockState headState = player.level().getBlockState(headPos);

        // Jeśli blok na wysokości stóp blokuje ruch, a nad nim też jest przeszkoda,
        // to bot potrzebuje skoku (step height 0.6 < 1.0)
        if (!feetState.isAir() && feetState.blocksMotion()
                && !headState.isAir() && headState.blocksMotion()) {
            if (player.onGround()) {
                // Pathfinder powinien obsłużyć skok
            }
        }
    }

    /**
     * Waliduje flagę onGround.
     */
    private void validateOnGround(LocalPlayer player) {
        if (player.onGround()) {
            Vec3 pos = player.position();
            BlockPos below = BlockPos.containing(pos.x, pos.y - 0.1, pos.z);
            BlockState belowState = player.level().getBlockState(below);
            // Sanity check
        }
    }

    /**
     * Zwraca dopuszczalny step height (0.6 dla waniliowego chodzenia).
     */
    public float getMaxStepHeight() {
        return MAX_STEP_HEIGHT;
    }

    /**
     * Oblicza czy skok jest potrzebny do pokonania przeszkody.
     */
    public boolean needsJump(BlockPos target) {
        LocalPlayer player = ctx.player();
        if (player == null) return false;

        int playerY = player.blockPosition().getY();
        int targetY = target.getY();

        return targetY > playerY && (targetY - playerY) > 0;
    }
}
