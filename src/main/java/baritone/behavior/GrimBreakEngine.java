package baritone.behavior;

import baritone.api.utils.IPlayerContext;
import baritone.utils.ToolSet;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.Holder;
import net.minecraft.network.protocol.game.ServerboundPlayerActionPacket;
import net.minecraft.network.protocol.game.ServerboundSwingPacket;
import net.minecraft.tags.FluidTags;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.effect.MobEffects;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.enchantment.Enchantment;
import net.minecraft.world.item.enchantment.Enchantments;
import net.minecraft.world.level.ClipContext;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;

import java.util.Random;

/**
 * GrimAC-compatible block breaking engine.
 * Zapewnia poprawną sekwencję pakietów, walidację LOS,
 * reach limit i czas niszczenia zgodny z serwerem.
 */
public final class GrimBreakEngine {

    private final IPlayerContext ctx;
    private final Random random = new Random();

    // -- Stan kopania --
    private BlockPos currentTarget = null;
    private Direction currentFace = null;
    private int breakProgressTicks = 0;
    private int requiredBreakTicks = -1;
    private boolean isBreaking = false;

    // -- Konfiguracja --
    private static final float MAX_REACH = 4.0F;       // Bezpieczny reach (zamiast 4.5)

    // -- Swing interval --
    private static final int SWING_INTERVAL_TICKS = 5; // Co 5 ticków = 4 swingi/sek

    public GrimBreakEngine(IPlayerContext ctx) {
        this.ctx = ctx;
    }

    /**
     * Rozpoczyna kopanie bloku. Waliduje LOS i reach przed wysłaniem pakietu.
     *
     * @param pos  pozycja bloku do zniszczenia
     * @param face ściana bloku, w którą celujemy
     * @return true jeśli rozpoczęto kopanie
     */
    public boolean startBreak(BlockPos pos, Direction face) {
        LocalPlayer player = ctx.player();
        if (player == null || ctx.world() == null) return false;

        // === REACH VALIDATION ===
        double distance = getEyeDistanceToBlock(player, pos, face);
        if (distance > MAX_REACH) {
            return false; // Za daleko — nie wysyłaj pakietu
        }

        // === LINE OF SIGHT VALIDATION ===
        if (!hasLineOfSight(player, pos, face)) {
            return false; // Brak widoczności — Grim to wykryje
        }

        // === OBLICZ CZAS NISZCZENIA ===
        BlockState state = ctx.world().getBlockState(pos);
        this.requiredBreakTicks = calculateBreakTicks(player, state);
        if (this.requiredBreakTicks <= 0) {
            return false; // Unbreakable
        }

        // === WYŚLIJ START_DESTROY_BLOCK + SWING ===
        GrimPacketOrder.sendBreakStart(player, pos, face);

        this.currentTarget = pos;
        this.currentFace = face;
        this.breakProgressTicks = 0;
        this.isBreaking = true;

        return true;
    }

    /**
     * Tick kopania — wywoływane co tick. Wysyła swingi i sprawdza postęp.
     */
    public void tickBreak() {
        if (!isBreaking || currentTarget == null) return;

        LocalPlayer player = ctx.player();
        if (player == null || ctx.world() == null) return;

        // Sprawdź czy blok nadal istnieje
        BlockState state = ctx.world().getBlockState(currentTarget);
        if (state.isAir()) {
            // Blok już zniszczony
            finishBreak();
            return;
        }

        // === SWING ANIMATION ===
        // Wysyłaj swing co SWING_INTERVAL_TICKS ticków
        if (breakProgressTicks % SWING_INTERVAL_TICKS == 0) {
            GrimPacketOrder.sendSwing(player);
        }

        breakProgressTicks++;

        // === SPRAWDŹ CZY KONIEC KOPANIA ===
        if (requiredBreakTicks > 0 && breakProgressTicks >= requiredBreakTicks) {
            // Wyślij STOP_DESTROY_BLOCK
            GrimPacketOrder.sendBreakStop(player, currentTarget, currentFace);
            finishBreak();
        }
    }

    /**
     * Przerywa kopanie aktualnego bloku.
     */
    public void abortBreak() {
        if (!isBreaking || currentTarget == null) return;

        LocalPlayer player = ctx.player();
        if (player != null) {
            GrimPacketOrder.sendBreakAbort(player, currentTarget, currentFace);
        }
        reset();
    }

    /**
     * Kończy kopanie i resetuje stan.
     */
    private void finishBreak() {
        reset();
    }

    private void reset() {
        this.currentTarget = null;
        this.currentFace = null;
        this.breakProgressTicks = 0;
        this.requiredBreakTicks = -1;
        this.isBreaking = false;
    }

    /**
     * Oblicza liczbę ticków potrzebnych do zniszczenia bloku.
     * Dokładnie odpowiada formule serwera 1.21.4 (z uwzględnieniem
     * narzędzia, enchantów, efektów potionów i podłoża).
     */
    private int calculateBreakTicks(LocalPlayer player, BlockState state) {
        float hardness = state.getDestroySpeed(ctx.world(), currentTarget);
        if (hardness < 0) return -1; // Unbreakable (bedrock, etc.)
        if (hardness == 0) return 1; // Instant break

        ItemStack tool = player.getMainHandItem();
        double baseSpeed = ToolSet.calculateSpeedVsBlock(tool, state);
        if (baseSpeed <= 0) return -1;

        double speedMultiplier = 1.0;

        // Haste / Mining Fatigue
        if (player.hasEffect(MobEffects.DIG_SPEED)) {
            int hasteLevel = player.getEffect(MobEffects.DIG_SPEED).getAmplifier() + 1;
            speedMultiplier *= 1.0 + hasteLevel * 0.2;
        }
        if (player.hasEffect(MobEffects.DIG_SLOWDOWN)) {
            switch (player.getEffect(MobEffects.DIG_SLOWDOWN).getAmplifier()) {
                case 0 -> speedMultiplier *= 0.3;
                case 1 -> speedMultiplier *= 0.09;
                case 2 -> speedMultiplier *= 0.0027;
                default -> speedMultiplier *= 0.00081;
            }
        }

        // Conduit Power
        if (player.hasEffect(MobEffects.CONDUIT_POWER)) {
            speedMultiplier *= 1.2;
        }

        // Underwater check (Aqua Affinity)
        if (player.isEyeInFluid(FluidTags.WATER)) {
            boolean hasAquaAffinity = false;
            ItemStack helmet = player.getItemBySlot(EquipmentSlot.HEAD);
            if (helmet != null && !helmet.isEmpty()) {
                for (Holder<Enchantment> ench : helmet.getEnchantments().keySet()) {
                    if (ench.is(Enchantments.AQUA_AFFINITY)) {
                        hasAquaAffinity = true;
                        break;
                    }
                }
            }
            if (!hasAquaAffinity) {
                speedMultiplier /= 5.0;
            }
        }

        // On ground check (not on ground = 5x slower)
        if (!player.onGround()) {
            speedMultiplier /= 5.0;
        }

        double damage = baseSpeed * speedMultiplier;
        if (damage >= 1.0) return 1; // Instant

        // Ticks to break = ceil(1 / damage)
        return (int) Math.ceil(1.0 / damage);
    }

    /**
     * Sprawdza czy gracz ma linię wzroku do danej ściany bloku.
     * Grim oblicza raytrace od eyePosition — musi być widoczna
     * co najmniej jedna część ściany.
     */
    private boolean hasLineOfSight(LocalPlayer player, BlockPos pos, Direction face) {
        Vec3 eyePos = player.getEyePosition();

        // Sprawdź środek ściany
        Vec3 centerOfFace = getFaceCenter(pos, face);
        BlockHitResult rayTrace = ctx.world().clip(
                new ClipContext(
                        eyePos, centerOfFace,
                        ClipContext.Block.COLLIDER,
                        ClipContext.Fluid.NONE,
                        player
                )
        );

        if (rayTrace.getType() == HitResult.Type.MISS) {
            return true; // Nic nie blokuje — pełna widoczność
        }

        // Jeśli raytrace trafił w ten sam blok — OK
        if (rayTrace.getBlockPos().equals(pos)) {
            return true;
        }

        // Sprawdź wierzchołki ściany (Grim dopuszcza widoczność fragmentu)
        Vec3[] corners = getFaceCorners(pos, face);
        for (Vec3 corner : corners) {
            BlockHitResult cornerTrace = ctx.world().clip(
                    new ClipContext(
                            eyePos, corner,
                            ClipContext.Block.COLLIDER,
                            ClipContext.Fluid.NONE,
                            player
                    )
            );
            if (cornerTrace.getType() == HitResult.Type.MISS || cornerTrace.getBlockPos().equals(pos)) {
                return true;
            }
        }

        return false;
    }

    /**
     * Zwraca odległość od oka gracza do środka ściany bloku.
     */
    private double getEyeDistanceToBlock(LocalPlayer player, BlockPos pos, Direction face) {
        Vec3 eyePos = player.getEyePosition();
        Vec3 faceCenter = getFaceCenter(pos, face);
        return eyePos.distanceTo(faceCenter);
    }

    private Vec3 getFaceCenter(BlockPos pos, Direction face) {
        Vec3 center = Vec3.atCenterOf(pos);
        return center.add(face.getStepX() * 0.5, face.getStepY() * 0.5, face.getStepZ() * 0.5);
    }

    private Vec3[] getFaceCorners(BlockPos pos, Direction face) {
        double x = pos.getX();
        double y = pos.getY();
        double z = pos.getZ();

        Vec3[] corners = new Vec3[4];

        switch (face) {
            case UP -> {
                corners[0] = new Vec3(x, y + 1, z);
                corners[1] = new Vec3(x + 1, y + 1, z);
                corners[2] = new Vec3(x, y + 1, z + 1);
                corners[3] = new Vec3(x + 1, y + 1, z + 1);
            }
            case DOWN -> {
                corners[0] = new Vec3(x, y, z);
                corners[1] = new Vec3(x + 1, y, z);
                corners[2] = new Vec3(x, y, z + 1);
                corners[3] = new Vec3(x + 1, y, z + 1);
            }
            case NORTH -> {
                corners[0] = new Vec3(x, y, z);
                corners[1] = new Vec3(x + 1, y, z);
                corners[2] = new Vec3(x, y + 1, z);
                corners[3] = new Vec3(x + 1, y + 1, z);
            }
            case SOUTH -> {
                corners[0] = new Vec3(x, y, z + 1);
                corners[1] = new Vec3(x + 1, y, z + 1);
                corners[2] = new Vec3(x, y + 1, z + 1);
                corners[3] = new Vec3(x + 1, y + 1, z + 1);
            }
            case WEST -> {
                corners[0] = new Vec3(x, y, z);
                corners[1] = new Vec3(x, y + 1, z);
                corners[2] = new Vec3(x, y, z + 1);
                corners[3] = new Vec3(x, y + 1, z + 1);
            }
            case EAST -> {
                corners[0] = new Vec3(x + 1, y, z);
                corners[1] = new Vec3(x + 1, y + 1, z);
                corners[2] = new Vec3(x + 1, y, z + 1);
                corners[3] = new Vec3(x + 1, y + 1, z + 1);
            }
        }

        return corners;
    }

    // Getters
    public boolean isBreaking() { return isBreaking; }
    public BlockPos getCurrentTarget() { return currentTarget; }
    public float getBreakProgress() {
        if (requiredBreakTicks <= 0) return 1.0F;
        return (float) breakProgressTicks / requiredBreakTicks;
    }
}
