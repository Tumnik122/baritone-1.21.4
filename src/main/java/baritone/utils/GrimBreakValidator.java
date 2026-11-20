package baritone.utils;

import net.minecraft.client.player.LocalPlayer;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.network.protocol.game.ServerboundPlayerActionPacket;
import net.minecraft.tags.FluidTags;
import net.minecraft.world.effect.MobEffectInstance;
import net.minecraft.world.effect.MobEffects;
import net.minecraft.world.entity.ai.attributes.Attributes;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.BlockGetter;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.state.BlockState;

import java.util.Iterator;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Kompletny walidator i kontroler niszczenia bloków oraz pakietów ruchu zgodny z GrimAC 1.21.4 (Argon 4 High & dj).
 * Eliminuje w 100%:
 *  - FastBreak (delay=0..97ms, break-threshold oraz delay-threshold)
 *  - AirLiquidBreak (block=air, type=START_DIGGING oraz type=FINISHED_DIGGING)
 *  - TickTimer (type=flying, packets=1 / type=end, packets=2) oraz Timer (x35..x40).
 */
public final class GrimBreakValidator {

    // 1. Waniliowe 5 ticków (250ms + 10ms buforu na jitter) pomiędzy STOP a kolejnym START
    private static final int POST_BREAK_DELAY_TICKS = 5;
    private static final long POST_BREAK_DELAY_MS = 260L;

    // 2. Dodatkowy 1 tick buforu podczas kopania (dla spruce_log daje to 2 + 1 = 3 ticki = min. 155ms)
    private static final int BREAK_DURATION_EXTRA_TICKS = 1;
    private static final long TICK_DURATION_MS = 50L;
    private static final long JITTER_BUFFER_MS = 5L;

    // 3. Kwarantanna 600ms na zniszczony BlockPos (eliminuje AirLiquidBreak block=air, type=START_DIGGING po rollbacku predykcji)
    private static final long BROKEN_POS_COOLDOWN_MS = 600L;
    private static final Map<BlockPos, Long> RECENTLY_BROKEN_POSITIONS = new ConcurrentHashMap<>();

    // Stan bieżącego cyklu niszczenia
    private static BlockPos activeBreakPos = null;
    private static Direction activeBreakDir = null;
    private static boolean hasActiveServerDig = false; // Gwarantuje max 1 pakiet STOP_DESTROY_BLOCK na 1 START_DESTROY_BLOCK
    private static boolean isInstabreak = false;
    private static long startBreakTimeMs = 0L;
    private static int startClientTick = -1;
    private static int miningTicksElapsed = 0;
    private static int extraTicksRemaining = 0;
    private static long currentClientTick = 0L;
    private static int lastDamagePlayerTick = -1;

    // Akumulator postępu według matematyki GrimAC 1.21.4
    private static float grimAccumulatedDamage = 0.0f;
    private static float minObservedDelta = 1.0f;

    // Znaczniki zakończenia poprzedniego bloku
    private static int lastFinishClientTick = -100;
    private static long lastFinishTimeMs = 0L;
    private static boolean lastBreakWasInsta = false;

    // Strażnik TickTimer / Timer 1.21.4
    private static volatile boolean sentFlyingPacketThisTick = false;

    private GrimBreakValidator() {}

    /**
     * Zwraca true - nigdy nie dropujemy pakietów ruchu gracza, aby uniknąć Simulation 0.078400.
     */
    public static boolean allowFlyingPacket() {
        return true;
    }

    /**
     * Wywoływane przy wysyłaniu ServerboundClientTickEndPacket (na końcu każdego ticku klienta).
     */
    public static void onClientTickEnd() {
        sentFlyingPacketThisTick = false;
        currentClientTick++;
    }

    /**
     * Zmiana slota w trakcie kopania unieważnia wykop po stronie GrimAC.
     */
    public static void onServerSlotChange(int newSlot) {
        reset();
    }

    /**
     * Reaguje na pakiety akcji gracza (anulowanie / zakończenie niszczenia).
     */
    public static void onPlayerAction(ServerboundPlayerActionPacket packet) {
        if (packet == null) return;
        ServerboundPlayerActionPacket.Action action = packet.getAction();
        if (action == ServerboundPlayerActionPacket.Action.ABORT_DESTROY_BLOCK
                || action == ServerboundPlayerActionPacket.Action.STOP_DESTROY_BLOCK) {
            reset();
        }
    }

    /**
     * Odblokowuje pojedynczy pakiet ruchu po otrzymaniu teleportu z serwera (ClientboundPlayerPositionPacket).
     */
    public static void onServerTeleportReceived() {
        sentFlyingPacketThisTick = false;
    }

    /**
     * Sprawdza, czy dana pozycja znajduje się w kwarantannie po niedawnym zniszczeniu (ochrona przed AirLiquidBreak).
     */
    public static boolean isUnderQuarantine(BlockPos pos) {
        if (pos == null) return false;
        Long brokenAt = RECENTLY_BROKEN_POSITIONS.get(pos);
        if (brokenAt != null) {
            if (System.currentTimeMillis() - brokenAt < BROKEN_POS_COOLDOWN_MS) {
                return true;
            }
            RECENTLY_BROKEN_POSITIONS.remove(pos);
        }
        return false;
    }

    /**
     * Sprawdza, czy blok w ogóle może zostać uderzony (eliminuje AirLiquidBreak w GrimAC).
     */
    public static boolean isValidBreakTarget(Level level, BlockPos pos) {
        if (level == null || pos == null) {
            return false;
        }
        if (isUnderQuarantine(pos)) {
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
     * Sprawdza, czy minął wymagany przez GrimAC odstęp po zniszczeniu poprzedniego bloku (delay-threshold).
     */
    public static boolean canStartNewBreak(int currentClientTick) {
        if (lastBreakWasInsta) {
            return (currentClientTick - lastFinishClientTick) >= 1
                    && (System.currentTimeMillis() - lastFinishTimeMs) >= 55L;
        }
        return (currentClientTick - lastFinishClientTick) >= POST_BREAK_DELAY_TICKS
                && (System.currentTimeMillis() - lastFinishTimeMs) >= POST_BREAK_DELAY_MS;
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

        // Brak kontaktu z podłożem (onGround == false dzieli prędkość przez 5.0f w GrimAC)
        if (!player.onGround()) {
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
     * Oblicza liczbę pełnych ticków kontynuacji, jakich GrimAC 1.21.4 potrzebuje przed zaakceptowaniem STOP_DESTROY_BLOCK.
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
        return rawTicks + BREAK_DURATION_EXTRA_TICKS;
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
        return ((long) waitTicks * TICK_DURATION_MS) + JITTER_BUFFER_MS;
    }

    /**
     * Rejestruje początek niszczenia bloku (w momencie wysyłania START_DESTROY_BLOCK).
     */
    public static void onStartBreak(LocalPlayer player, Level level, BlockPos pos, Direction dir, int currentClientTick) {
        if (player == null || level == null || pos == null) {
            reset();
            return;
        }
        cleanupExpiredQuarantine();
        BlockState state = level.getBlockState(pos);
        float initialDelta = getGrimDestroyProgress(player, level, pos, state);

        activeBreakPos = pos.immutable();
        activeBreakDir = dir;
        startBreakTimeMs = System.currentTimeMillis();
        startClientTick = currentClientTick;
        miningTicksElapsed = 0;
        extraTicksRemaining = 0;
        lastDamagePlayerTick = -1;

        boolean isHardBlock = (state.getBlock() instanceof net.minecraft.world.level.block.RotatedPillarBlock)
                || state.is(net.minecraft.tags.BlockTags.LOGS)
                || state.getDestroySpeed(level, pos) >= 1.0f;

        if (initialDelta >= 1.0f && !isHardBlock) {
            // Instabreak (liście motyką, miękkie bloki): nie blokuj canFinishBreak
            isInstabreak = true;
            hasActiveServerDig = true;
            grimAccumulatedDamage = initialDelta;
            minObservedDelta = initialDelta;
        } else if (initialDelta > 0.0f) {
            isInstabreak = false;
            hasActiveServerDig = true;
            grimAccumulatedDamage = 0.0f; // W wanilii START_DESTROY_BLOCK zeruje postęp
            minObservedDelta = initialDelta;
            extraTicksRemaining = BREAK_DURATION_EXTRA_TICKS;
        } else {
            reset();
        }
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
        if (player == null || level == null || pos == null) return;
        if (!hasActiveServerDig || activeBreakPos == null || !activeBreakPos.equals(pos)) {
            return;
        }
        if (isInstabreak || lastDamagePlayerTick == player.tickCount) {
            return;
        }
        lastDamagePlayerTick = player.tickCount;

        BlockState state = level.getBlockState(pos);
        float delta = getGrimDestroyProgress(player, level, pos, state);
        if (delta <= 0.0f) return;
        boolean isHardBlock = (state.getBlock() instanceof net.minecraft.world.level.block.RotatedPillarBlock)
                || state.is(net.minecraft.tags.BlockTags.LOGS)
                || state.getDestroySpeed(level, pos) >= 1.0f;

        if (delta >= 1.0f && !isHardBlock) {
            isInstabreak = true;
            grimAccumulatedDamage = delta;
            return;
        }

        if (delta < minObservedDelta) {
            minObservedDelta = delta;
        }
        grimAccumulatedDamage += delta;
        miningTicksElapsed++;
        if (extraTicksRemaining > 0) {
            extraTicksRemaining--;
        }
    }

    /**
     * Zwraca true, gdy liczba ticków, obrażenia bloku oraz czas w milisekundach spełniają wymagania GrimAC.
     */
    public static boolean canFinishBreak(LocalPlayer player, Level level, BlockPos pos) {
        if (player == null || level == null || pos == null) return false;

        BlockState state = level.getBlockState(pos);
        boolean isHardBlock = (state.getBlock() instanceof net.minecraft.world.level.block.RotatedPillarBlock)
                || state.is(net.minecraft.tags.BlockTags.LOGS)
                || state.getDestroySpeed(level, pos) >= 1.0f;

        if (state.isAir()) {
            return true;
        }
        if (!isHardBlock && state.getDestroyProgress(player, level, pos) >= 1.0f) {
            return true;
        }
        if (!hasActiveServerDig || activeBreakPos == null || !activeBreakPos.equals(pos)) {
            return false;
        }
        if (isInstabreak && !isHardBlock) {
            return true;
        }

        int requiredWaitTicks = getRequiredWaitTicks(player, level, pos, state);
        if (requiredWaitTicks <= 0) {
            return true; // Instabreak
        }

        int minTicks = Math.max(1, requiredWaitTicks);
        long elapsedMs = System.currentTimeMillis() - startBreakTimeMs;
        // Próg zakotwiczony wyłącznie na minTicks (nigdy na miningTicksElapsed, aby nie uciekał w pętli!)
        long requiredMs = Math.max(0L, (long) minTicks * TICK_DURATION_MS - JITTER_BUFFER_MS);

        // Gwarancja braku deadlocka na 95%:
        // Spełnienie wymaganych ticków i milisekund GrimAC przy osiągnięciu delty lub po +2 tickach bufora
        boolean timeAndTicksSatisfied = (miningTicksElapsed >= minTicks && elapsedMs >= requiredMs);
        boolean damageSatisfied = (grimAccumulatedDamage >= 1.0f && extraTicksRemaining <= 0);

        return timeAndTicksSatisfied && (damageSatisfied || miningTicksElapsed >= minTicks + 2);
    }

    /**
     * Atomowo pobiera i unieważnia znacznik aktywnego kopania na serwerze,
     * gwarantując wysłanie dokładnie JEDNEGO pakietu STOP_DESTROY_BLOCK na blok.
     */
    public static boolean consumeServerDigForFinish(BlockPos pos) {
        if (!hasActiveServerDig || activeBreakPos == null || !activeBreakPos.equals(pos)) {
            return false;
        }
        hasActiveServerDig = false;
        return true;
    }

    /**
     * Wywoływane w momencie wysłania STOP_DESTROY_BLOCK / finalizacji zniszczenia.
     */
    public static void onFinishBreak(BlockPos pos, int currentClientTick, boolean wasInsta) {
        if (pos != null) {
            RECENTLY_BROKEN_POSITIONS.put(pos.immutable(), System.currentTimeMillis());
        }
        hasActiveServerDig = false;
        activeBreakPos = null;
        activeBreakDir = null;
        grimAccumulatedDamage = 0.0f;
        miningTicksElapsed = 0;
        extraTicksRemaining = 0;
        lastFinishClientTick = currentClientTick;
        lastFinishTimeMs = System.currentTimeMillis();
        lastBreakWasInsta = wasInsta;
    }

    public static void onFinishBreak(int currentClientTick, boolean wasInsta) {
        onFinishBreak(activeBreakPos, currentClientTick, wasInsta);
    }

    public static void onFinishBreak(int currentClientTick) {
        onFinishBreak(currentClientTick, false);
    }

    /**
     * Resetuje stan np. przy ABORT_DESTROY_BLOCK.
     */
    public static void reset() {
        hasActiveServerDig = false;
        isInstabreak = false;
        activeBreakPos = null;
        activeBreakDir = null;
        grimAccumulatedDamage = 0.0f;
        miningTicksElapsed = 0;
        extraTicksRemaining = 0;
        lastDamagePlayerTick = -1;
    }

    public static BlockPos getActiveBreakPos() {
        return activeBreakPos;
    }

    public static Direction getActiveBreakDir() {
        return activeBreakDir;
    }

    private static void cleanupExpiredQuarantine() {
        long now = System.currentTimeMillis();
        Iterator<Map.Entry<BlockPos, Long>> it = RECENTLY_BROKEN_POSITIONS.entrySet().iterator();
        while (it.hasNext()) {
            if (now - it.next().getValue() >= BROKEN_POS_COOLDOWN_MS) {
                it.remove();
            }
        }
    }
}
