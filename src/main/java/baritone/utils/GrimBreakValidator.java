package baritone.utils;

import net.minecraft.client.player.LocalPlayer;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.tags.FluidTags;
import net.minecraft.world.effect.MobEffectInstance;
import net.minecraft.world.effect.MobEffects;
import net.minecraft.world.entity.ai.attributes.Attributes;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.BlockGetter;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.state.BlockState;

/**
 * Kompletny walidator i kontroler niszczenia bloków zgodny z GrimAC 1.21.4 (Argon 4 High & dj).
 * Eliminuje flagi FastBreak (39ms..56ms, 89ms, 199ms) oraz AirLiquidBreak.
 */
public final class GrimBreakValidator {

    // Bufor bezpieczeństwa ticków ponad matematyczne ceil(1.0 / delta)
    private static final int GRIM_SAFETY_EXTRA_TICKS = 1;

    // Bufor bezpieczeństwa czasu rzeczywistego (ms) na każdy wymagany tick (50ms)
    private static final long TICK_MS = 50L;
    private static final long JITTER_SAFETY_MS = 10L;

    // Minimalny odstęp między zakończeniem jednego bloku (STOP) a rozpoczęciem kolejnego (START)
    // Waniliowe destroyDelay = 5 ticków (250ms) wymagane przez GrimAC przy niszczeniu kolejnych bloków
    private static final int POST_BREAK_DELAY_TICKS = 5;
    private static final long POST_BREAK_DELAY_MS = 250L;

    // Stan bieżącego cyklu niszczenia
    private static BlockPos activeBreakPos = null;
    private static Direction activeBreakDir = null;
    private static long startBreakTimeMs = 0L;
    private static int startClientTick = -1;
    private static int miningTicksElapsed = 0;

    // Akumulator postępu według konserwatywnego przypadku widzianego przez GrimAC
    private static float grimAccumulatedDamage = 0.0f;
    private static float minObservedDelta = 1.0f;

    // Znaczniki zakończenia poprzedniego bloku
    private static int lastFinishClientTick = -100;
    private static long lastFinishTimeMs = 0L;
    private static boolean lastBreakWasInsta = false;

    private GrimBreakValidator() {}

    /**
     * Sprawdza, czy blok w ogóle może zostać uderzony (eliminuje AirLiquidBreak w GrimAC).
     */
    public static boolean isValidBreakTarget(Level level, BlockPos pos) {
        if (level == null || pos == null) {
            return false;
        }
        BlockState state = level.getBlockState(pos);
        if (state.isAir()) {
            return false;
        }
        if (!state.getFluidState().isEmpty() && state.getCollisionShape(level, pos).isEmpty()) {
            return false;
        }
        return state.getDestroySpeed(level, pos) >= 0.0f;
    }

    /**
     * Sprawdza, czy minął wymagany przez GrimAC odstęp po zniszczeniu poprzedniego bloku.
     */
    public static boolean canStartNewBreak(int currentClientTick) {
        if (lastBreakWasInsta) {
            return true;
        }
        long now = System.currentTimeMillis();
        if (currentClientTick - lastFinishClientTick < POST_BREAK_DELAY_TICKS) {
            return false;
        }
        return (now - lastFinishTimeMs) >= POST_BREAK_DELAY_MS;
    }

    /**
     * Oblicza dokładny postęp niszczenia na 1 tick (delta) w 100% zgodnie z silnikiem GrimAC 1.21.4.
     */
    public static float getGrimDestroyProgress(LocalPlayer player, BlockGetter level, BlockPos pos, BlockState state) {
        if (player == null || level == null || state == null || pos == null) return 0.0f;

        float hardness = state.getDestroySpeed(level, pos);
        if (hardness < 0.0f) {
            return 0.0f;
        }
        if (hardness == 0.0f) {
            return 1.0f;
        }

        ItemStack heldItem = player.getMainHandItem();
        float speed = heldItem.getDestroySpeed(state);

        // W 1.21.4 Efficiency jest aplikowane TYLKO gdy speed > 1.0f (właściwe narzędzie)
        if (speed > 1.0f) {
            double efficiencyAttr = player.getAttributeValue(Attributes.MINING_EFFICIENCY);
            speed += (float) efficiencyAttr;
        }

        // Haste / Conduit Power
        int digSpeedAmplifier = -1;
        MobEffectInstance haste = player.getEffect(MobEffects.DIG_SPEED);
        if (haste != null) {
            digSpeedAmplifier = Math.max(digSpeedAmplifier, haste.getAmplifier());
        }
        MobEffectInstance conduit = player.getEffect(MobEffects.CONDUIT_POWER);
        if (conduit != null) {
            digSpeedAmplifier = Math.max(digSpeedAmplifier, conduit.getAmplifier());
        }
        if (digSpeedAmplifier >= 0) {
            speed *= 1.0f + (float) (digSpeedAmplifier + 1) * 0.2f;
        }

        // Mining Fatigue
        MobEffectInstance fatigue = player.getEffect(MobEffects.DIG_SLOWDOWN);
        if (fatigue != null) {
            float fatigueMultiplier = switch (fatigue.getAmplifier()) {
                case 0 -> 0.3f;
                case 1 -> 0.09f;
                case 2 -> 0.0027f;
                default -> 8.1E-4f;
            };
            speed *= fatigueMultiplier;
        }

        // Woda / Submersion
        if (player.isEyeInFluid(FluidTags.WATER)) {
            float submergedFactor = (float) player.getAttributeValue(Attributes.SUBMERGED_MINING_SPEED);
            speed *= submergedFactor;
        }

        // Brak kontaktu z podłożem (onGround == false lub ruch pionowy dzieli prędkość przez 5.0f w GrimAC)
        if (!player.onGround() || Math.abs(player.getDeltaMovement().y) > 0.003) {
            speed /= 5.0f;
        }

        boolean canHarvest = !state.requiresCorrectToolForDrops() || heldItem.isCorrectToolForDrops(state);
        float divisor = canHarvest ? 30.0f : 100.0f;

        return (speed / hardness) / divisor;
    }

    public static float getGrimDestroyProgress(LocalPlayer player, BlockGetter level, BlockPos pos, BlockState state, boolean wasOnGroundLastTick) {
        return getGrimDestroyProgress(player, level, pos, state);
    }

    public static float getVanillaDestroyProgress(LocalPlayer player, BlockGetter level, BlockPos pos, BlockState state) {
        return getGrimDestroyProgress(player, level, pos, state);
    }

    /**
     * Oblicza liczbę pełnych ticków kontynuacji (po ticku START_DESTROY_BLOCK),
     * jakich GrimAC 1.21.4 potrzebuje przed zaakceptowaniem STOP_DESTROY_BLOCK.
     */
    public static int getRequiredWaitTicks(LocalPlayer player, BlockGetter level, BlockPos pos, BlockState state) {
        float currentDelta = getGrimDestroyProgress(player, level, pos, state);
        if (currentDelta >= 1.0f) {
            boolean isLog = state != null && state.is(net.minecraft.tags.BlockTags.LOGS);
            if (isLog || (state != null && state.getDestroySpeed(level, pos) >= 1.5f)) {
                return 3;
            }
            return 0; // Prawdziwy Instabreak
        }
        if (currentDelta <= 0.0f) {
            return 999;
        }

        float effectiveDelta = (activeBreakPos != null && activeBreakPos.equals(pos))
                ? Math.min(currentDelta, minObservedDelta)
                : currentDelta;

        int rawTicks = (int) Math.ceil(1.0 / (double) effectiveDelta);
        return rawTicks + GRIM_SAFETY_EXTRA_TICKS;
    }

    public static int getRequiredTicks(LocalPlayer player, BlockGetter level, BlockPos pos, BlockState state, boolean wasOnGroundLastTick) {
        return getRequiredWaitTicks(player, level, pos, state);
    }

    public static int getRequiredTicks(LocalPlayer player, BlockGetter level, BlockPos pos, BlockState state) {
        return getRequiredWaitTicks(player, level, pos, state);
    }

    /**
     * Oblicza minimalny czas w milisekundach od wysłania START_DESTROY_BLOCK do STOP_DESTROY_BLOCK.
     */
    public static long getRequiredBreakDurationMs(LocalPlayer player, BlockGetter level, BlockPos pos, BlockState state) {
        int waitTicks = getRequiredWaitTicks(player, level, pos, state);
        if (waitTicks <= 0) {
            return 0L;
        }
        return (waitTicks * TICK_MS) - JITTER_SAFETY_MS;
    }

    /**
     * Rejestruje początek niszczenia bloku (w momencie wysyłania START_DESTROY_BLOCK).
     */
    public static void onStartBreak(LocalPlayer player, Level level, BlockPos pos, Direction dir, int currentClientTick) {
        BlockState state = level.getBlockState(pos);
        float initialDelta = getGrimDestroyProgress(player, level, pos, state);

        activeBreakPos = pos.immutable();
        activeBreakDir = dir;
        startBreakTimeMs = System.currentTimeMillis();
        startClientTick = currentClientTick;
        miningTicksElapsed = 0;
        minObservedDelta = initialDelta > 0.0f ? initialDelta : 1.0f;
        grimAccumulatedDamage = initialDelta;
    }

    /**
     * Sprawdza, czy wywołanie continueDestroyBlock ma miejsce w tym samym ticku co startDestroyBlock.
     */
    public static boolean isSameTickAsStart(BlockPos pos, int currentClientTick) {
        return activeBreakPos != null
                && activeBreakPos.equals(pos)
                && currentClientTick == startClientTick;
    }

    /**
     * Aktualizuje stan niszczenia w kolejnym ticku (wywoływane raz na tick w continueDestroyBlock).
     */
    public static void onTickContinue(LocalPlayer player, Level level, BlockPos pos) {
        if (activeBreakPos == null || !activeBreakPos.equals(pos)) {
            return;
        }
        BlockState state = level.getBlockState(pos);
        float delta = getGrimDestroyProgress(player, level, pos, state);
        if (delta > 0.0f && delta < minObservedDelta) {
            minObservedDelta = delta;
        }
        miningTicksElapsed++;
        grimAccumulatedDamage += delta;
    }

    /**
     * Zwraca true TYLKO wtedy, gdy zarówno liczba ticków, skumulowane obrażenia bloku,
     * jak i czas w milisekundach spełniają wymagania GrimAC 1.21.4.
     */
    public static boolean canFinishBreak(LocalPlayer player, Level level, BlockPos pos) {
        if (activeBreakPos == null || !activeBreakPos.equals(pos)) {
            return false;
        }
        BlockState state = level.getBlockState(pos);
        if (state.isAir()) {
            return false;
        }

        int requiredWaitTicks = getRequiredWaitTicks(player, level, pos, state);
        if (requiredWaitTicks <= 0) {
            return true; // Instabreak
        }

        long elapsedMs = System.currentTimeMillis() - startBreakTimeMs;
        long requiredMs = getRequiredBreakDurationMs(player, level, pos, state);

        return miningTicksElapsed >= requiredWaitTicks
                && grimAccumulatedDamage >= 1.0f
                && elapsedMs >= requiredMs;
    }

    /**
     * Wywoływane w momencie wysłania STOP_DESTROY_BLOCK / finalizacji zniszczenia.
     */
    public static void onFinishBreak(int currentClientTick, boolean wasInsta) {
        activeBreakPos = null;
        activeBreakDir = null;
        grimAccumulatedDamage = 0.0f;
        miningTicksElapsed = 0;
        lastFinishClientTick = currentClientTick;
        lastFinishTimeMs = System.currentTimeMillis();
        lastBreakWasInsta = wasInsta;
    }

    public static void onFinishBreak(int currentClientTick) {
        onFinishBreak(currentClientTick, false);
    }

    /**
     * Resetuje stan np. przy ABORT_DESTROY_BLOCK.
     */
    public static void reset() {
        activeBreakPos = null;
        activeBreakDir = null;
        grimAccumulatedDamage = 0.0f;
        miningTicksElapsed = 0;
    }

    public static BlockPos getActiveBreakPos() {
        return activeBreakPos;
    }

    public static Direction getActiveBreakDir() {
        return activeBreakDir;
    }
}
