package com.astra.revolution.pathing.aquatic;

import baritone.api.IBaritone;
import baritone.api.pathing.movement.IMovement;
import baritone.api.utils.BetterBlockPos;
import baritone.api.utils.IInputOverrideHandler;
import baritone.api.utils.IPlayerContext;
import baritone.api.utils.Rotation;
import baritone.api.utils.input.Input;
import com.astra.revolution.pathing.AstraLocomotionMath;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.core.BlockPos;
import net.minecraft.tags.FluidTags;
import net.minecraft.util.Mth;
import net.minecraft.world.effect.MobEffects;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.material.FluidState;
import net.minecraft.world.phys.Vec3;

/**
 * Bezalokacyjny silnik hydrodynamiki i pływania 3D (Dolphin Sprint) dla Minecraft 1.21.4.
 * - Dokładna wektorowa kompensacja prądów rzecznych i wirów Magma Block.
 * - Dynamiczny Oxygen Watchdog z histerezą i sondą sufitu (nie blokuje się w zalanych jaskiniach).
 * - Amphibious Breach z poprawnym ujemnym Pitch (-15 do -30 deg) i płynnym wyjściem na ląd.
 * - 100% kwantyzacji GCD zgodnej z GrimAC 1.21.4.
 */
public final class AstraAquaticLocomotionEngine {

    public static final AstraAquaticLocomotionEngine INSTANCE = new AstraAquaticLocomotionEngine();

    private static final double BREACH_DIST_SQ = 0.62D * 0.62D;
    private static final double FLOW_COMPENSATION_GAIN = 0.65D;
    private static final float MAX_AQUATIC_YAW_STEP = 38.0F;
    private static final float MAX_AQUATIC_PITCH_STEP = 28.0F;
    private static final int MIN_OXYGEN_TRIGGER = 85;   // ~4.25s zapasu na płytkiej wodzie
    private static final int OXYGEN_SAFE_RELEASE = 265; // Histereza: wynurzaj aż tlen wróci prawie do pełna
    private static final int MAX_SURFACE_SCAN_HEIGHT = 24;

    private final BlockPos.MutableBlockPos mpos = new BlockPos.MutableBlockPos();
    private boolean surfacingForAir = false;

    private AstraAquaticLocomotionEngine() {}

    /**
     * Wykrywa wyłącznie faktyczne środowisko wodne (nie przejmuje kontroli podczas czołgania pod trapdoorem na lądzie!).
     */
    public boolean isInWaterEnvironment(LocalPlayer player, Level world) {
        if (player == null || world == null) return false;
        if (player.isInLava()) return false;
        if (player.isInWater() || player.isUnderWater() || player.isEyeInFluid(FluidTags.WATER)) {
            return true;
        }
        // Przypadek brzegowy: gracz w pozie SWIMMING dokładnie na granicy powierzchni wody
        if (player.isSwimming()) {
            return AstraLocomotionMath.isWater(world, mpos, Mth.floor(player.getX()), Mth.floor(player.getY()), Mth.floor(player.getZ()));
        }
        return false;
    }

    /** Resetuje stan silnika i zwalnia wymuszone klawisze przy wyjściu z wody. */
    public void reset(IInputOverrideHandler ioh) {
        surfacingForAir = false;
        if (ioh != null) {
            ioh.setInputForceState(Input.SNEAK, false);
            ioh.setInputForceState(Input.JUMP, false);
        }
    }

    public boolean handleAquaticMovement(
            IBaritone baritone,
            LocalPlayer player,
            IMovement currentMovement,
            BetterBlockPos currentDest,
            BetterBlockPos nextDest
    ) {
        IPlayerContext ctx = baritone.getPlayerContext();
        Level world = ctx.world();
        if (!isInWaterEnvironment(player, world)) {
            reset(baritone.getInputOverrideHandler());
            return false;
        }

        IInputOverrideHandler ioh = baritone.getInputOverrideHandler();

        double px = player.getX();
        double py = player.getY();
        double pz = player.getZ();
        double eyeY = player.getEyeY();
        int bx = Mth.floor(px);
        int by = Mth.floor(py);
        int bz = Mth.floor(pz);

        // 1. Dynamiczny Oxygen Watchdog z histerezą i sondą sufitu
        int airSupply = player.getAirSupply();
        boolean eyesInWater = player.isEyeInFluid(FluidTags.WATER);
        int depthToSurface = scanWaterColumnToAir(world, bx, Mth.floor(eyeY), bz);

        if (!eyesInWater || airSupply >= OXYGEN_SAFE_RELEASE) {
            surfacingForAir = false;
        } else if (depthToSurface > 0) {
            // Dynamiczny próg: im głębiej jest bot, tym wcześniej zaczyna wynurzanie
            int dynamicThreshold = Math.min(220, Math.max(MIN_OXYGEN_TRIGGER, depthToSurface * 11 + 35));
            if (airSupply <= dynamicThreshold) {
                surfacingForAir = true;
            }
        } else {
            // Brak bezpośredniego powietrza nad głową (zalana jaskinia) — nie wbijaj się w sufit, płyń dalej ścieżką A*
            surfacingForAir = false;
        }

        // 2. Wyznaczenie punktu docelowego + Lookahead w otwartej wodzie
        double targetX = currentDest.x + 0.5D;
        double targetY = currentDest.y + 0.45D;
        double targetZ = currentDest.z + 0.5D;

        boolean targetIsLand = !AstraLocomotionMath.isWater(world, mpos, currentDest.x, currentDest.y, currentDest.z)
                && AstraLocomotionMath.hasCollision(world, mpos, currentDest.x, currentDest.y - 1, currentDest.z);

        if (surfacingForAir) {
            // Płyń po skosie do góry (zachowując część postępu poziomego, o ile nad nami nie ma przeszkody)
            targetX = Mth.lerp(0.25D, px, targetX);
            targetZ = Mth.lerp(0.25D, pz, targetZ);
            targetY = eyeY + Math.min(depthToSurface + 0.8D, 3.5D);
        } else if (!targetIsLand && nextDest != null) {
            // Wygładzenie 3D Lookahead tylko gdy korytarz w wodzie jest drożny
            double lx = Mth.lerp(0.35D, targetX, nextDest.x + 0.5D);
            double ly = Mth.lerp(0.35D, targetY, nextDest.y + 0.45D);
            double lz = Mth.lerp(0.35D, targetZ, nextDest.z + 0.5D);
            if (!AstraLocomotionMath.hasCollision(world, mpos, Mth.floor(lx), Mth.floor(ly), Mth.floor(lz))) {
                targetX = lx;
                targetY = ly;
                targetZ = lz;
            }
        } else if (targetIsLand) {
            // Przy wyjściu na brzeg celuj lekko ponad krawędź bloku lądowego
            targetY = currentDest.y + 0.75D;
        }

        // 3. Wektor bazowy 3D do celu
        double dx = targetX - px;
        double dy = targetY - eyeY;
        double dz = targetZ - pz;
        double hDist = Math.hypot(dx, dz);
        double dist3D = Math.sqrt(dx * dx + dy * dy + dz * dz);

        // 4. Dokładna wektorowa kompensacja prądów rzecznych + wirów Magma Block
        double dirX = dist3D > 1.0E-4D ? (dx / dist3D) : 0.0D;
        double dirY = dist3D > 1.0E-4D ? (dy / dist3D) : 0.0D;
        double dirZ = dist3D > 1.0E-4D ? (dz / dist3D) : 0.0D;

        mpos.set(bx, by, bz);
        FluidState fluid = world.getFluidState(mpos);
        if (!fluid.isEmpty()) {
            Vec3 flow = fluid.getFlow(world, mpos);
            double flowLenSq = flow.x * flow.x + flow.z * flow.z;
            if (flowLenSq > 1.0E-4D) {
                // Odejmujemy wektor znoszenia prądu od znormalizowanego kierunku celu
                dirX -= flow.x * FLOW_COMPENSATION_GAIN;
                dirZ -= flow.z * FLOW_COMPENSATION_GAIN;
            }
        }

        // Kontra na wir Magma Block (DRAG_DOWN) w bieżącym lub docelowym wokselu
        int bubbleSign = AstraLocomotionMath.getBubbleColumnSign(world, mpos, bx, by, bz);
        if (bubbleSign == -1) {
            // Silne wynurzanie i unikanie wciągnięcia na blok magmy
            dirY = Math.max(dirY + 0.55D, 0.35D);
        }

        double compH = Math.max(Math.hypot(dirX, dirZ), 1.0E-4D);
        float desiredYaw = (float) (Mth.atan2(dirZ, dirX) * AstraLocomotionMath.RAD_TO_DEG) - 90.0F;
        float desiredPitch = (float) -(Mth.atan2(dirY, compH) * AstraLocomotionMath.RAD_TO_DEG);
        desiredPitch = Mth.clamp(desiredPitch, -75.0F, 75.0F);

        // 5. Amphibious Breach (Woda -> Ląd): ujemny Pitch (-15 do -30 deg = patrzenie w górę!) + skok na krawędzi
        boolean triggerBreachJump = false;
        if (targetIsLand && !surfacingForAir) {
            double hDistSq = dx * dx + dz * dz;
            boolean nearBank = hDistSq <= BREACH_DIST_SQ || player.horizontalCollision;
            if (nearBank) {
                // Ujemny pitch w Minecraft = w górę; wyprowadza z Pose.SWIMMING na krawędź brzegu
                desiredPitch = Mth.clamp(desiredPitch, -30.0F, -15.0F);
                triggerBreachJump = (currentDest.y >= by) || player.horizontalCollision;
            }
        }

        // 6. Kwantyzacja GCD i aktualizacja kamery (100% GrimAC Safe)
        Rotation qRot = AstraLocomotionMath.quantizeRotationGcd(
                ctx,
                desiredYaw,
                desiredPitch,
                player.getYRot(),
                player.getXRot(),
                MAX_AQUATIC_YAW_STEP,
                MAX_AQUATIC_PITCH_STEP
        );
        baritone.getLookBehavior().updateTarget(qRot, true);

        // 7. Legalne wejścia Vanilla (Dolphin Sprint tylko gdy głód > 6 i gracz nie używa przedmiotu)
        boolean canDolphinSprint = player.getFoodData().getFoodLevel() > 6
                && !player.isUsingItem()
                && !player.hasEffect(MobEffects.BLINDNESS);

        ioh.setInputForceState(Input.MOVE_FORWARD, true);
        ioh.setInputForceState(Input.MOVE_BACK, false);
        ioh.setInputForceState(Input.MOVE_LEFT, false);
        ioh.setInputForceState(Input.MOVE_RIGHT, false);
        ioh.setInputForceState(Input.SPRINT, canDolphinSprint);

        // Kontrola pionowa: w otwartej wodzie napędza nas Pitch + Sprint; JUMP/SNEAK tylko w kominach, przy breache lub braku tlenu
        if (triggerBreachJump || bubbleSign == -1) {
            ioh.setInputForceState(Input.JUMP, true);
            ioh.setInputForceState(Input.SNEAK, false);
        } else if (surfacingForAir) {
            ioh.setInputForceState(Input.JUMP, true);
            ioh.setInputForceState(Input.SNEAK, false);
        } else if (dy > 0.75D && hDist < 0.95D) {
            // Pionowy komin wodny w górę
            ioh.setInputForceState(Input.JUMP, true);
            ioh.setInputForceState(Input.SNEAK, false);
        } else if (dy < -0.75D && hDist < 0.95D && !player.isSwimming()) {
            // Pionowy szyb w dół (tylko jeśli nie jesteśmy w Dolphin Sprint, który sam nurkuje po Pitch)
            ioh.setInputForceState(Input.JUMP, false);
            ioh.setInputForceState(Input.SNEAK, true);
        } else {
            ioh.setInputForceState(Input.JUMP, false);
            ioh.setInputForceState(Input.SNEAK, false);
        }

        return true;
    }

    /**
     * Skanuje słup wody w górę od (x, startY, z).
     * Zwraca liczbę bloków do powierzchni powietrza lub -1, jeśli drogę blokuje sufit jaskini.
     */
    private int scanWaterColumnToAir(Level world, int x, int startY, int z) {
        for (int dy = 1; dy <= MAX_SURFACE_SCAN_HEIGHT; dy++) {
            int cy = startY + dy;
            if (AstraLocomotionMath.hasCollision(world, mpos, x, cy, z)) {
                return -1; // Sufit blokuje wynurzenie w tej kolumnie
            }
            if (AstraLocomotionMath.isAirAndNoFluid(world, mpos, x, cy, z)) {
                return dy;
            }
        }
        return -1;
    }
}

