/*  */
package com.astra.revolution.nuker;

import baritone.utils.accessor.IPlayerControllerMP;
import it.unimi.dsi.fastutil.longs.Long2LongOpenHashMap;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.multiplayer.MultiPlayerGameMode;
import net.minecraft.client.multiplayer.prediction.PredictiveAction;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.network.protocol.Packet;
import net.minecraft.network.protocol.game.ServerboundPlayerActionPacket;
import net.minecraft.network.protocol.game.ServerboundSwingPacket;
import net.minecraft.tags.BlockTags;
import net.minecraft.util.Mth;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.level.ClipContext;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.CropBlock;
import net.minecraft.world.level.block.LiquidBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;
import net.minecraft.world.phys.shapes.VoxelShape;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.Comparator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * VENTURA NUKER MODULE (Astra Client Port)
 * Ported from Ventura Client & engineered with Argon 4 for GrimAC 2.3.65
 * bypass.
 *
 * GrimAC Bypass Protocol (Zero Flags & Zero Cofek):
 * 1. AirLiquidBreak Elimination:
 * - For instamine blocks (progress >= 1.0f), only START_DESTROY_BLOCK is
 * dispatched (with sequence prediction).
 * NEVER send STOP_DESTROY_BLOCK on instamine, as GrimAC's compensatedWorld
 * immediately converts it to air.
 * - All mined block positions are quarantined in quarantineMap
 * (Long2LongOpenHashMap) for 350ms, preventing
 * subsequent packets from targeting transitioning positions.
 * - Strict pre-validation: rejects air, liquids, and unbreakable blocks before
 * packet dispatch.
 * 2. FastBreak Elimination (Fencepost & onGround Sync):
 * - In Tick 0 of non-instamine digging, damage starts strictly at 0.0f
 * (eliminating the 50ms off-by-one tick error).
 * - Elapsed wall-clock time and tick progress are synchronized:
 * STOP_DESTROY_BLOCK is sent ONLY when
 * currentHardDamage >= 1.0f AND (now - hardStartTimeMs) >= minElapsedMs.
 * - Transition ticks during jump/sprint (player.onGround() == true while server
 * still sees false from previous flying packet)
 * are scaled by 0.2x, eliminating the 250ms airborne FastBreak diff.
 * 3. Rate Limiting Fuse (Spam Kick Protection):
 * - Sliding ring buffer strictly enforces max 65 packets/second, making the 101
 * cancelled packet/s threshold
 * mathematically impossible to reach.
 * 4. Pre-Tick Digging & Silent GCD Aim:
 * - Digging occurs in onPrePlayerUpdate before sendPosition().
 * - Quantized GCD mouse rotation is temporarily placed on the entity for
 * sendPosition() and restored in
 * onPostPlayerUpdate, eliminating 'Post player digging' and 'TickTimer
 * (packets=2)'.
 * 5. FLAT Floor Protection:
 * - Never breaks blocks with pos.getY() < player.getBlockY(), allowing the
 * player to sprint smoothly forward
 * without ever dropping into holes or cavities.
 * 6. FASTEST Mode:
 * - Breaks up to 5 instamine blocks per tick along the player's movement/facing
 * corridor.
 */
public final class VenturaNukerModule {

    private static final VenturaNukerModule INSTANCE = new VenturaNukerModule();

    public static VenturaNukerModule getInstance() {
        return INSTANCE;
    }

    public enum Mode {
        NORMAL("Normal", "Legit delay"),
        FAST("Fast", "0ms rotacji"),
        FASTEST("Fastest", "Packet bypass");

        private final String title;
        private final String subtitle;

        Mode(String title, String subtitle) {
            this.title = title;
            this.subtitle = subtitle;
        }

        public String getTitle() {
            return title;
        }

        public String getSubtitle() {
            return subtitle;
        }
    }

    public enum RaycastMode {
        MULTI_POINT("Multi-Point (0.2 Step)"),
        CENTER_LOS("Center Line-of-Sight"),
        THROUGH_WALLS("Through Walls");

        public final String label;

        RaycastMode(String label) {
            this.label = label;
        }
    }

    public enum RotationMode {
        SILENT_GCD("Silent (Grim GCD)"),
        CLIENT_SMOOTH("Client Smooth"),
        OFF("Off");

        public final String label;

        RotationMode(String label) {
            this.label = label;
        }
    }

    public enum FilterMode {
        ALL("All Destructible"),
        STONE_ORES("Stone & Ores Only"),
        MATURE_CROPS("Mature Crops Only");

        public final String label;

        FilterMode(String label) {
            this.label = label;
        }
    }

    public static final class NukerTarget {
        public final BlockPos pos;
        public final Vec3 hitVec;
        public final Direction direction;

        public NukerTarget(BlockPos pos, Vec3 hitVec, Direction direction) {
            this.pos = pos;
            this.hitVec = hitVec;
            this.direction = direction;
        }
    }

    private static final int MAX_INSTAMINE_PER_TICK = 5;
    private static final int SPHERE_MAX_BLOCKS_PER_TICK = 55;
    private static final int SPHERE_MAX_PACKETS_PER_TICK = 64;
    private static final int MAX_PACKETS_PER_SECOND = 90; // Twardy limit zapobiegający kickowi (101 anulowanych pkt/s)
    private static final long POSITION_QUARANTINE_MS = 450L;
    private static final float GRIM_SAFE_BALANCE_LIMIT_MS = 850.0f;

    // Zero-allocation bufory pre-ticku
    private final Long2LongOpenHashMap quarantineMap = new Long2LongOpenHashMap(512);
    private final long[] packetTimestamps = new long[MAX_PACKETS_PER_SECOND];
    private int packetRingIndex = 0;

    // Lokalny symulator bufora blockBreakBalance z GrimAC FastBreak
    private float simulatedGrimBalanceMs = 0.0f;
    private long lastBalanceDecayMs = System.currentTimeMillis();

    // GrimAC + Custom Anticheat (Vulcan/Polar/Matrix/Paper) Onion-Peeling & FOV
    // Clustering
    // dj dyrektywa: max 2 bloki na tick z trackerem delay-threshold GrimAC
    private static final int CUSTOM_AC_MAX_INSTAMINE_PER_TICK = 2; // max 2 bloki w ticku (40 bloków/s)
    private static final int CUSTOM_AC_MAX_FAST07_PER_TICK = 2;
    private static final double FOV_CONE_COS = 0.743; // cos(42°) — wszystkie bloki w ticku w jednym stożku wzroku

    // Tracker długu delay-threshold w GrimAC (limit w Grim to 1000ms, próg
    // bezpieczny 600ms)
    private int grimDelayBalanceMs = 0;
    private static final int MAX_SAFE_DELAY_BALANCE_MS = 600;

    private final BlockPos[] clusterTargets = new BlockPos[CUSTOM_AC_MAX_INSTAMINE_PER_TICK];
    private final Direction[] clusterFaces = new Direction[CUSTOM_AC_MAX_INSTAMINE_PER_TICK];
    private final double[] clusterScores = new double[CUSTOM_AC_MAX_INSTAMINE_PER_TICK];

    private long lastRejectedPosLong = Long.MIN_VALUE;
    private long strictSyncUntilMs = 0L;

    // Maszyna stanów dla bloku non-instamine w trybie FASTEST (np. kamień 2 ticki,
    // deepslate 3 ticki)
    private BlockPos fastestHardPos = null;
    private Direction fastestHardFace = Direction.UP;
    private float fastestHardAccumulatedProgress = 0.0f;
    private int fastestHardTicks = 0;

    // Maszyna stanów dla pojedynczego bloku non-instamine (stone, deepslate,
    // andesite, tuff)
    private BlockPos currentHardBlock = null;
    private Direction currentHardFace = Direction.UP;
    private float currentHardDamage = 0.0f;
    private int hardTicksElapsed = 0;
    private long hardStartTimeMs = 0L;

    // Synchronizacja stanu onGround widzianego przez serwer GrimAC przed wysłaniem
    // sendPosition()
    private boolean lastServerOnGround = true;

    // Ustawienia
    private volatile boolean enabled = false;
    private volatile Mode mode = Mode.FASTEST;
    private volatile RaycastMode raycastMode = RaycastMode.MULTI_POINT;
    private volatile RotationMode rotationMode = RotationMode.SILENT_GCD;
    private volatile FilterMode filterMode = FilterMode.ALL;
    private volatile float range = 4.5f;
    private volatile float delayMs = 50.0f;
    private volatile float maxRotationSpeed = 180.0f;
    private volatile boolean flat = true; // Ochrona podłogi pod stopami (pos.getY() < player.getBlockY())
    private volatile boolean avoidLava = true;
    private volatile int maxBlocksPerTick = 5;

    // Runtime state
    private volatile NukerTarget currentTarget = null;
    private volatile float currentNukerYaw = 0.0f;
    private volatile float currentNukerPitch = 0.0f;
    private volatile boolean hasActiveRotation = false;
    private float savedPreYaw = 0.0f;
    private float savedPrePitch = 0.0f;
    private boolean isPreRotated = false;

    private long lastBreakTimeMs = 0L;
    private final AtomicBoolean scannerRunning = new AtomicBoolean(false);
    private Thread scannerThread = null;

    private final Set<Block> customWhitelist = Collections.synchronizedSet(new LinkedHashSet<>());

    private VenturaNukerModule() {
        startScannerThread();
    }

    public void reset() {
        Minecraft mc = Minecraft.getInstance();
        if (currentHardBlock != null && mc.level != null && mc.player != null) {
            mc.level.destroyBlockProgress(mc.player.getId(), currentHardBlock, -1);
        }
        currentHardBlock = null;
        currentHardDamage = 0.0f;
        hardTicksElapsed = 0;
        hardStartTimeMs = 0L;
        currentTarget = null;
        hasActiveRotation = false;
        isPreRotated = false;
        quarantineMap.clear();
        Arrays.fill(packetTimestamps, 0L);
        Arrays.fill(clusterTargets, null);
        Arrays.fill(clusterFaces, null);
        Arrays.fill(clusterScores, Double.MAX_VALUE);
        simulatedGrimBalanceMs = 0.0f;
        lastBalanceDecayMs = System.currentTimeMillis();
        lastRejectedPosLong = Long.MIN_VALUE;
        strictSyncUntilMs = 0L;
        if (fastestHardPos != null && mc.level != null && mc.player != null) {
            mc.level.destroyBlockProgress(mc.player.getId(), fastestHardPos, -1);
        }
        fastestHardPos = null;
        fastestHardFace = Direction.UP;
        fastestHardAccumulatedProgress = 0.0f;
        fastestHardTicks = 0;
        lastServerOnGround = true;
    }

    // =========================================================================
    // GETTERS & SETTERS (INTEGRACJA Z KARTAMI W BARITONE BYPASS GUI)
    // =========================================================================

    public boolean isEnabled() {
        return enabled;
    }

    public void setEnabled(boolean enabled) {
        this.enabled = enabled;
        if (!enabled) {
            reset();
        }
    }

    public Mode getMode() {
        return mode;
    }

    public void setMode(Mode mode) {
        this.mode = mode != null ? mode : Mode.FASTEST;
    }

    public void setModeIndex(int idx) {
        Mode[] values = Mode.values();
        this.mode = (idx >= 0 && idx < values.length) ? values[idx] : Mode.FASTEST;
    }

    public RaycastMode getRaycastMode() {
        return raycastMode;
    }

    public void setRaycastMode(RaycastMode rm) {
        this.raycastMode = rm != null ? rm : RaycastMode.MULTI_POINT;
    }

    public void setRaycastModeIndex(int idx) {
        RaycastMode[] values = RaycastMode.values();
        this.raycastMode = (idx >= 0 && idx < values.length) ? values[idx] : RaycastMode.MULTI_POINT;
    }

    public RotationMode getRotationMode() {
        return rotationMode;
    }

    public void setRotationMode(RotationMode rm) {
        this.rotationMode = rm != null ? rm : RotationMode.SILENT_GCD;
        if (this.rotationMode == RotationMode.OFF) {
            this.hasActiveRotation = false;
        }
    }

    public void setRotationModeIndex(int idx) {
        RotationMode[] values = RotationMode.values();
        setRotationMode((idx >= 0 && idx < values.length) ? values[idx] : RotationMode.SILENT_GCD);
    }

    public FilterMode getFilterMode() {
        return filterMode;
    }

    public void setFilterMode(FilterMode fm) {
        this.filterMode = fm != null ? fm : FilterMode.ALL;
    }

    public void setFilterModeIndex(int idx) {
        FilterMode[] values = FilterMode.values();
        this.filterMode = (idx >= 0 && idx < values.length) ? values[idx] : FilterMode.ALL;
    }

    public float getRange() {
        return range;
    }

    public void setRange(float range) {
        this.range = Mth.clamp(range, 1.0f, 10.0f);
    }

    public float getDelayMs() {
        return delayMs;
    }

    public void setDelayMs(float delayMs) {
        this.delayMs = Math.max(0.0f, delayMs);
    }

    public float getMaxRotationSpeed() {
        return maxRotationSpeed;
    }

    public void setMaxRotationSpeed(float speed) {
        this.maxRotationSpeed = Mth.clamp(speed, 10.0f, 180.0f);
    }

    public boolean isFlat() {
        return flat;
    }

    public void setFlat(boolean flat) {
        this.flat = flat;
    }

    public boolean isFlatten() {
        return flat;
    }

    public void setFlatten(boolean flatten) {
        this.flat = flatten;
    }

    public boolean isAvoidLava() {
        return avoidLava;
    }

    public void setAvoidLava(boolean avoidLava) {
        this.avoidLava = avoidLava;
    }

    public int getMaxBlocksPerTick() {
        return maxBlocksPerTick;
    }

    public void setMaxBlocksPerTick(int val) {
        this.maxBlocksPerTick = Mth.clamp(val, 1, 20);
    }

    public NukerTarget getCurrentTarget() {
        return currentTarget;
    }

    public boolean hasActiveRotation() {
        return enabled && hasActiveRotation && currentTarget != null;
    }

    public float getCurrentNukerYaw() {
        return currentNukerYaw;
    }

    public float getCurrentNukerPitch() {
        return currentNukerPitch;
    }

    public Set<Block> getCustomWhitelist() {
        return customWhitelist;
    }

    public void addCustomBlock(Block block) {
        if (block != null)
            customWhitelist.add(block);
    }

    public void removeCustomBlock(Block block) {
        if (block != null)
            customWhitelist.remove(block);
    }

    public void clearCustomBlocks() {
        customWhitelist.clear();
    }

    // =========================================================================
    // RATE LIMITER & QUARANTINE CACHE
    // =========================================================================

    private boolean canSendPacket(long now, int count) {
        int checkIdx = (packetRingIndex + count - 1) % MAX_PACKETS_PER_SECOND;
        return (now - packetTimestamps[checkIdx]) >= 1000L;
    }

    private boolean canSendPacket(long now) {
        return canSendPacket(now, 1);
    }

    private void recordPacketSent(long now, int count) {
        for (int i = 0; i < count; i++) {
            packetTimestamps[packetRingIndex] = now;
            packetRingIndex = (packetRingIndex + 1) % MAX_PACKETS_PER_SECOND;
        }
    }

    private void recordPacketSent(long now) {
        recordPacketSent(now, 1);
    }

    // =========================================================================
    // BACKGROUND SCANNER THREAD
    // =========================================================================

    private synchronized void startScannerThread() {
        if (scannerThread != null && scannerThread.isAlive())
            return;

        scannerRunning.set(true);
        scannerThread = new Thread(() -> {
            while (scannerRunning.get()) {
                try {
                    if (enabled) {
                        Minecraft mc = Minecraft.getInstance();
                        if (mc != null && mc.player != null && mc.level != null) {
                            NukerTarget target = scanForBestBlock(mc);
                            this.currentTarget = target;
                            if (target == null) {
                                this.hasActiveRotation = false;
                            }
                        } else {
                            this.currentTarget = null;
                            this.hasActiveRotation = false;
                        }
                    } else {
                        this.currentTarget = null;
                        this.hasActiveRotation = false;
                    }
                    Thread.sleep(25L);
                } catch (InterruptedException ignored) {
                    break;
                } catch (Throwable t) {
                    try {
                        Thread.sleep(50L);
                    } catch (Throwable ignored) {
                    }
                }
            }
        }, "Astra-VenturaNuker-Scanner");
        scannerThread.setDaemon(true);
        scannerThread.start();
    }

    // =========================================================================
    // SELECTION & SCANNING ALGORITHMS
    // =========================================================================

    public NukerTarget scanForBestBlock(Minecraft mc) {
        LocalPlayer player = mc.player;
        Level level = mc.level;
        if (player == null || level == null)
            return null;

        Vec3 eyePos = player.getEyePosition(1.0F);
        float currentRange = this.range;
        double maxDistSq = currentRange * currentRange;
        int rad = (int) Math.floor(currentRange) + 1;
        BlockPos origin = BlockPos.containing(player.getX(), player.getY() + 1.0, player.getZ());
        int playerFeetY = player.getBlockY();

        double bestDistSq = Double.MAX_VALUE;
        NukerTarget bestTarget = null;

        for (int dx = -rad; dx <= rad; dx++) {
            for (int dy = -rad; dy <= rad; dy++) {
                for (int dz = -rad; dz <= rad; dz++) {
                    BlockPos candidate = origin.offset(dx, dy, dz);
                    long packed = candidate.asLong();

                    if (quarantineMap.containsKey(packed))
                        continue;
                    if (flat && candidate.getY() < playerFeetY)
                        continue;

                    Vec3 center = Vec3.atCenterOf(candidate);
                    double distSq = eyePos.distanceToSqr(center);
                    if (distSq > maxDistSq || distSq >= bestDistSq)
                        continue;

                    BlockState state = level.getBlockState(candidate);
                    if (state.isAir() || state.getDestroySpeed(level, candidate) < 0.0f)
                        continue;
                    if (state.getBlock() instanceof LiquidBlock || !state.getFluidState().isEmpty())
                        continue;
                    if (!matchesFilter(state, candidate))
                        continue;
                    if (avoidLava && isAdjacentToLava(level, candidate))
                        continue;

                    NukerTarget validTarget = validateRaycast(player, level, eyePos, candidate);
                    if (validTarget != null) {
                        bestTarget = validTarget;
                        bestDistSq = distSq;
                    }
                }
            }
        }

        return bestTarget;
    }

    public List<NukerTarget> scanAllBlocksInReach(Minecraft mc, int maxCount) {
        LocalPlayer player = mc.player;
        Level level = mc.level;
        if (player == null || level == null)
            return Collections.emptyList();

        Vec3 eyePos = player.getEyePosition(1.0F);
        float currentRange = this.range;
        double maxDistSq = currentRange * currentRange;
        int rad = (int) Math.floor(currentRange) + 1;
        BlockPos origin = BlockPos.containing(player.getX(), player.getY() + 1.0, player.getZ());
        int playerFeetY = player.getBlockY();
        Vec3 forward = Vec3.directionFromRotation(0.0f, player.getYRot());

        List<NukerTarget> targets = new ArrayList<>(maxCount * 2);

        for (int dx = -rad; dx <= rad; dx++) {
            for (int dy = -rad; dy <= rad; dy++) {
                for (int dz = -rad; dz <= rad; dz++) {
                    BlockPos candidate = origin.offset(dx, dy, dz);
                    long packed = candidate.asLong();

                    if (quarantineMap.containsKey(packed))
                        continue;
                    if (flat && candidate.getY() < playerFeetY)
                        continue;

                    Vec3 center = Vec3.atCenterOf(candidate);
                    double distSq = eyePos.distanceToSqr(center);
                    if (distSq > maxDistSq)
                        continue;

                    BlockState state = level.getBlockState(candidate);
                    if (state.isAir() || state.getDestroySpeed(level, candidate) < 0.0f)
                        continue;
                    if (state.getBlock() instanceof LiquidBlock || !state.getFluidState().isEmpty())
                        continue;
                    if (!matchesFilter(state, candidate))
                        continue;
                    if (avoidLava && isAdjacentToLava(level, candidate))
                        continue;

                    double diffX = (candidate.getX() + 0.5) - eyePos.x;
                    double diffZ = (candidate.getZ() + 0.5) - eyePos.z;
                    double forwardDot = diffX * forward.x + diffZ * forward.z;
                    if (forwardDot < -0.25 && !targets.isEmpty())
                        continue;

                    NukerTarget validTarget = validateRaycast(player, level, eyePos, candidate);
                    if (validTarget != null) {
                        targets.add(validTarget);
                        if (targets.size() >= maxCount * 3)
                            break;
                    }
                }
            }
        }

        targets.sort((a, b) -> {
            double da = eyePos.distanceToSqr(a.hitVec);
            double db = eyePos.distanceToSqr(b.hitVec);
            return Double.compare(da, db);
        });

        if (targets.size() > maxCount) {
            return targets.subList(0, maxCount);
        }
        return targets;
    }

    private boolean matchesFilter(BlockState state, BlockPos pos) {
        Block block = state.getBlock();

        // 1. Priorytet: Selektywna lista bloków wybranych w GUI przez gracza (dj)
        // Zgodnie z bezwzględną dyrektywą dj: "nuker ma kopać tylko te zaznaczone"
        if (!customWhitelist.isEmpty()) {
            return customWhitelist.contains(block);
        }

        // 2. Jeśli lista wybranych bloków jest pusta (0 wybranych):
        // Nie niszcz żadnych bloków dopóki gracz nie wskaże ich w GUI
        return false;
    }

    private boolean isAdjacentToLava(Level level, BlockPos pos) {
        for (Direction dir : Direction.values()) {
            BlockState neighbor = level.getBlockState(pos.relative(dir));
            if (neighbor.is(Blocks.LAVA)) {
                return true;
            }
        }
        return false;
    }

    private NukerTarget validateRaycast(LocalPlayer player, Level level, Vec3 eyePos, BlockPos pos) {
        if (raycastMode == RaycastMode.THROUGH_WALLS) {
            return new NukerTarget(pos, Vec3.atCenterOf(pos), Direction.UP);
        }

        if (raycastMode == RaycastMode.CENTER_LOS) {
            Vec3 center = Vec3.atCenterOf(pos);
            BlockHitResult hit = level.clip(new ClipContext(
                    eyePos, center,
                    ClipContext.Block.COLLIDER,
                    ClipContext.Fluid.NONE,
                    player));
            if (hit.getType() == HitResult.Type.BLOCK && hit.getBlockPos().equals(pos)) {
                return new NukerTarget(pos, hit.getLocation(), hit.getDirection());
            }
            return null;
        }

        // MULTI_POINT (0.2 step bounding scan)
        for (double ox = 0.15; ox <= 0.85; ox += 0.25) {
            for (double oy = 0.15; oy <= 0.85; oy += 0.25) {
                for (double oz = 0.15; oz <= 0.85; oz += 0.25) {
                    Vec3 point = new Vec3(pos.getX() + ox, pos.getY() + oy, pos.getZ() + oz);
                    BlockHitResult hit = level.clip(new ClipContext(
                            eyePos, point,
                            ClipContext.Block.OUTLINE,
                            ClipContext.Fluid.NONE,
                            player));
                    if (hit.getType() == HitResult.Type.BLOCK && hit.getBlockPos().equals(pos)) {
                        return new NukerTarget(pos, point, hit.getDirection());
                    }
                }
            }
        }

        return null;
    }

    // =========================================================================
    // DEDYKOWANA ROTACJA DLA NUKERA (ŚCIŚLE IZOLOWANA, GCD QUANTIZED)
    // =========================================================================

    public void updateRotation(Minecraft mc) {
        if (!enabled || currentTarget == null || rotationMode == RotationMode.OFF) {
            hasActiveRotation = false;
            return;
        }
        LocalPlayer player = mc.player;
        if (player == null) {
            hasActiveRotation = false;
            return;
        }

        Vec3 eyePos = player.getEyePosition(1.0F);
        Vec3 hitVec = currentTarget.hitVec;
        double dx = hitVec.x - eyePos.x;
        double dy = hitVec.y - eyePos.y;
        double dz = hitVec.z - eyePos.z;

        float targetYaw = (float) Mth.wrapDegrees(Math.toDegrees(Math.atan2(dz, dx)) - 90.0);
        float targetPitch = (float) Mth.wrapDegrees(-Math.toDegrees(Math.atan2(dy, Math.sqrt(dx * dx + dz * dz))));

        // GCD Quantization matching mouse sensitivity (GrimAC Rotation Analysis Bypass)
        double sensitivity = 0.5D;
        if (mc.options != null && mc.options.sensitivity() != null) {
            sensitivity = mc.options.sensitivity().get();
        }
        double f = sensitivity * 0.6D + 0.2D;
        double gcd = f * f * f * 8.0D * 0.15D;
        if (gcd > 0.0001D) {
            double deltaYaw = Mth.wrapDegrees(targetYaw - player.getYRot());
            double deltaPitch = targetPitch - player.getXRot();

            float maxSpeed = this.maxRotationSpeed;
            deltaYaw = Mth.clamp(deltaYaw, -maxSpeed, maxSpeed);
            deltaPitch = Mth.clamp(deltaPitch, -maxSpeed, maxSpeed);

            double qYaw = Math.round(deltaYaw / gcd) * gcd;
            double qPitch = Math.round(deltaPitch / gcd) * gcd;

            targetYaw = (float) (player.getYRot() + qYaw);
            targetPitch = (float) Mth.clamp(player.getXRot() + qPitch, -89.5, 89.5);
        }

        this.currentNukerYaw = targetYaw;
        this.currentNukerPitch = targetPitch;
        this.hasActiveRotation = true;

        if (rotationMode == RotationMode.CLIENT_SMOOTH) {
            player.setYRot(targetYaw);
            player.setXRot(targetPitch);
        }
    }

    // =========================================================================
    // GRIMAC SYNCED DESTROY PROGRESS
    // =========================================================================

    /**
     * Oblicza postęp kopania zsynchronizowany z kompensacją GrimAC:
     * uwzględnia stan onGround zarówno z bieżącego ticku klienta, jak i z
     * poprzedniego pakietu ruchu na serwerze.
     */
    private float getGrimSyncedDestroyProgress(
            LocalPlayer player,
            ClientLevel level,
            BlockPos pos,
            BlockState state) {
        float progress = state.getDestroyProgress(player, level, pos);
        // Jeśli klient w tym ticku już wylądował (player.onGround() == true), ale
        // serwer w pre-ticku
        // ma jeszcze lastServerOnGround == false z poprzedniego pakietu, GrimAC naliczy
        // karę 5x (0.2f).
        if (player.onGround() && !lastServerOnGround) {
            progress *= 0.2f;
        }
        return progress;
    }

    private void sendPredictiveAction(MultiPlayerGameMode gameMode, ClientLevel level, PredictiveAction action) {
        if (gameMode instanceof IPlayerControllerMP mp) {
            mp.callStartPrediction(level, action);
        } else {
            Packet<?> packet = action.predict(0);
            Minecraft mc = Minecraft.getInstance();
            if (packet != null && mc.getConnection() != null) {
                mc.getConnection().send(packet);
            }
        }
    }

    private static Direction getClosestFace(Vec3 eyePos, BlockPos pos) {
        double dx = eyePos.x - (pos.getX() + 0.5);
        double dy = eyePos.y - (pos.getY() + 0.5);
        double dz = eyePos.z - (pos.getZ() + 0.5);
        return Direction.getApproximateNearest(dx, dy, dz);
    }

    /**
     * Zwraca odsłoniętą ścianę bloku (graniczącą z blokiem niepełnym/powietrzem),
     * która jest skierowana przodem do oczu gracza (eliminuje GhostHand /
     * IllegalDirection / Through-Walls).
     */
    private static Direction getExposedVisibleFace(ClientLevel level, BlockPos pos, BlockState state, Vec3 eyePos) {
        Direction bestFace = null;
        double bestDot = -1.0;

        double centerX = pos.getX() + 0.5;
        double centerY = pos.getY() + 0.5;
        double centerZ = pos.getZ() + 0.5;

        for (Direction dir : Direction.values()) {
            BlockPos neighborPos = pos.relative(dir);
            BlockState neighborState = level.getBlockState(neighborPos);

            // Ściana jest odsłonięta, jeśli sąsiad nie zasłania pełnej bryły
            if (neighborState.isSolidRender())
                continue;

            double faceCenterX = centerX + dir.getStepX() * 0.5;
            double faceCenterY = centerY + dir.getStepY() * 0.5;
            double faceCenterZ = centerZ + dir.getStepZ() * 0.5;

            double toEyeX = eyePos.x - faceCenterX;
            double toEyeY = eyePos.y - faceCenterY;
            double toEyeZ = eyePos.z - faceCenterZ;
            double len = Math.sqrt(toEyeX * toEyeX + toEyeY * toEyeY + toEyeZ * toEyeZ);
            if (len < 1e-4)
                return dir;

            // Iloczyn skalarny normalnej ściany z wektorem do oczu gracza (> -0.05 oznacza
            // widoczną ścianę)
            double dot = (toEyeX * dir.getStepX() + toEyeY * dir.getStepY() + toEyeZ * dir.getStepZ()) / len;
            if (dot > -0.05 && dot > bestDot) {
                bestDot = dot;
                bestFace = dir;
            }
        }
        return bestFace;
    }

    private static boolean isInsideFovCone(Vec3 eyePos, Vec3 lookVec, BlockPos target, Direction face) {
        double tx = target.getX() + 0.5 + face.getStepX() * 0.45;
        double ty = target.getY() + 0.5 + face.getStepY() * 0.45;
        double tz = target.getZ() + 0.5 + face.getStepZ() * 0.45;

        double dx = tx - eyePos.x;
        double dy = ty - eyePos.y;
        double dz = tz - eyePos.z;
        double len = Math.sqrt(dx * dx + dy * dy + dz * dz);
        if (len < 1e-4)
            return true;

        double cosAngle = (dx * lookVec.x + dy * lookVec.y + dz * lookVec.z) / len;
        return cosAngle >= FOV_CONE_COS;
    }

    private static Vec3 applyGcdSilentRotation(
            Minecraft mc,
            LocalPlayer player,
            Vec3 eyePos,
            BlockPos target,
            Direction face) {
        double tx = target.getX() + 0.5 + face.getStepX() * 0.48;
        double ty = target.getY() + 0.5 + face.getStepY() * 0.48;
        double tz = target.getZ() + 0.5 + face.getStepZ() * 0.48;

        double dx = tx - eyePos.x;
        double dy = ty - eyePos.y;
        double dz = tz - eyePos.z;
        double distXZ = Math.sqrt(dx * dx + dz * dz);

        float targetYaw = (float) (Mth.atan2(dz, dx) * (180.0 / Math.PI)) - 90.0f;
        float targetPitch = (float) (-(Mth.atan2(dy, distXZ) * (180.0 / Math.PI)));

        float deltaYaw = Mth.wrapDegrees(targetYaw - player.getYRot());
        float deltaPitch = targetPitch - player.getXRot();

        double sens = 0.5;
        if (mc.options != null && mc.options.sensitivity() != null) {
            sens = mc.options.sensitivity().get();
        }
        double f = sens * 0.6 + 0.2;
        double gcd = f * f * f * 1.2 * 0.15;
        if (gcd < 0.0001)
            gcd = 0.0001;

        float finalYaw = player.getYRot() + (float) (Math.round(deltaYaw / gcd) * gcd);
        float finalPitch = Mth.clamp(player.getXRot() + (float) (Math.round(deltaPitch / gcd) * gcd), -90.0f, 90.0f);

        player.setYRot(finalYaw);
        player.setXRot(finalPitch);
        return Vec3.directionFromRotation(finalPitch, finalYaw);
    }

    private void insertClusterCandidate(BlockPos pos, Direction face, double score) {
        for (int i = 0; i < CUSTOM_AC_MAX_INSTAMINE_PER_TICK; i++) {
            if (score < clusterScores[i]) {
                for (int j = CUSTOM_AC_MAX_INSTAMINE_PER_TICK - 1; j > i; j--) {
                    clusterScores[j] = clusterScores[j - 1];
                    clusterTargets[j] = clusterTargets[j - 1];
                    clusterFaces[j] = clusterFaces[j - 1];
                }
                clusterScores[i] = score;
                clusterTargets[i] = pos;
                clusterFaces[i] = face;
                break;
            }
        }
    }

    // =========================================================================
    // GRIMAC BYPASS: PRE-TICK CYKL WYKONYWANIA
    // =========================================================================

    /**
     * Wywoływane w PRE-TICK (MixinClientPlayerEntity.tick HEAD), przed
     * LocalPlayer.sendPosition().
     * Wykonuje rotację i pakiety niszczenia bloków PRZED ruchem gracza,
     * gwarantując idealną kolejność pakietów i brak flag na GrimAC.
     */
    public void onPrePlayerUpdate(LocalPlayer player) {
        if (!enabled || player == null || player.level() == null) {
            this.currentTarget = null;
            this.hasActiveRotation = false;
            this.isPreRotated = false;
            return;
        }

        Minecraft mc = Minecraft.getInstance();
        if (mc.gameMode == null)
            return;

        MultiPlayerGameMode gameMode = mc.gameMode;
        ClientLevel level = (ClientLevel) player.level();
        long now = System.currentTimeMillis();

        try {
            // 1. Czyszczenie wygasłych pozycji z kwarantanny
            quarantineMap.long2LongEntrySet().removeIf(e -> (now - e.getLongValue()) > POSITION_QUARANTINE_MS);

            switch (mode) {
                case NORMAL: {
                    if (this.currentTarget == null || level.getBlockState(this.currentTarget.pos).isAir()
                            || quarantineMap.containsKey(this.currentTarget.pos.asLong())) {
                        this.currentTarget = scanForBestBlock(mc);
                    }
                    NukerTarget target = this.currentTarget;
                    if (target == null) {
                        this.hasActiveRotation = false;
                        this.isPreRotated = false;
                        return;
                    }
                    updateRotation(mc);
                    if (rotationMode == RotationMode.SILENT_GCD && hasActiveRotation) {
                        savedPreYaw = player.getYRot();
                        savedPrePitch = player.getXRot();
                        player.setYRot(currentNukerYaw);
                        player.setXRot(currentNukerPitch);
                        isPreRotated = true;
                    }

                    if (now - lastBreakTimeMs >= (long) delayMs && canSendPacket(now)) {
                        recordPacketSent(now);
                        boolean isHitting = (gameMode instanceof IPlayerControllerMP mp) && mp.isHittingBlock();
                        BlockPos currentHitting = (gameMode instanceof IPlayerControllerMP mp) ? mp.getCurrentBlock()
                                : null;

                        if (currentHitting != null && currentHitting.equals(target.pos) && isHitting) {
                            gameMode.continueDestroyBlock(target.pos, target.direction);
                        } else {
                            gameMode.startDestroyBlock(target.pos, target.direction);
                        }
                        player.swing(InteractionHand.MAIN_HAND);
                        lastBreakTimeMs = now;

                        if (level.getBlockState(target.pos).isAir()) {
                            quarantineMap.put(target.pos.asLong(), now);
                            this.currentTarget = null;
                            this.hasActiveRotation = false;
                        }
                    }
                    break;
                }

                case FAST: {
                    if (gameMode instanceof IPlayerControllerMP mp) {
                        mp.setDestroyDelay(0);
                    }

                    if (currentHardBlock != null) {
                        BlockState hardState = level.getBlockState(currentHardBlock);
                        if (hardState.isAir() || quarantineMap.containsKey(currentHardBlock.asLong())) {
                            level.destroyBlockProgress(player.getId(), currentHardBlock, -1);
                            currentHardBlock = null;
                            currentHardDamage = 0.0f;
                            hardTicksElapsed = 0;
                            hardStartTimeMs = 0L;
                        } else {
                            float prog = getGrimSyncedDestroyProgress(player, level, currentHardBlock, hardState);
                            currentHardDamage += prog;
                            hardTicksElapsed++;

                            this.currentTarget = new NukerTarget(currentHardBlock, Vec3.atCenterOf(currentHardBlock),
                                    currentHardFace);
                            updateRotation(mc);
                            if (rotationMode == RotationMode.SILENT_GCD && hasActiveRotation) {
                                savedPreYaw = player.getYRot();
                                savedPrePitch = player.getXRot();
                                player.setYRot(currentNukerYaw);
                                player.setXRot(currentNukerPitch);
                                isPreRotated = true;
                            }

                            long minElapsedMs = (long) hardTicksElapsed * 50L - 6L;
                            boolean timeReady = (now - hardStartTimeMs) >= minElapsedMs;

                            if (currentHardDamage >= 1.0f && timeReady && canSendPacket(now)) {
                                final BlockPos posToFinish = currentHardBlock;
                                final Direction faceToFinish = currentHardFace;

                                level.destroyBlockProgress(player.getId(), posToFinish, -1);
                                recordPacketSent(now);

                                sendPredictiveAction(gameMode, level, seq -> {
                                    gameMode.destroyBlock(posToFinish);
                                    return new ServerboundPlayerActionPacket(
                                            ServerboundPlayerActionPacket.Action.STOP_DESTROY_BLOCK,
                                            posToFinish,
                                            faceToFinish,
                                            seq);
                                });

                                quarantineMap.put(posToFinish.asLong(), now);
                                currentHardBlock = null;
                                currentHardDamage = 0.0f;
                                hardTicksElapsed = 0;
                                hardStartTimeMs = 0L;
                                player.swing(InteractionHand.MAIN_HAND);
                                return;
                            } else {
                                int stage = Mth.clamp((int) (currentHardDamage * 10.0f) - 1, 0, 9);
                                level.destroyBlockProgress(player.getId(), currentHardBlock, stage);
                                player.swing(InteractionHand.MAIN_HAND);
                                return;
                            }
                        }
                    }

                    if (this.currentTarget == null || level.getBlockState(this.currentTarget.pos).isAir()
                            || quarantineMap.containsKey(this.currentTarget.pos.asLong())) {
                        this.currentTarget = scanForBestBlock(mc);
                    }
                    NukerTarget target = this.currentTarget;
                    if (target == null) {
                        this.hasActiveRotation = false;
                        this.isPreRotated = false;
                        return;
                    }
                    updateRotation(mc);
                    if (rotationMode == RotationMode.SILENT_GCD && hasActiveRotation) {
                        savedPreYaw = player.getYRot();
                        savedPrePitch = player.getXRot();
                        player.setYRot(currentNukerYaw);
                        player.setXRot(currentNukerPitch);
                        isPreRotated = true;
                    }

                    BlockState state = level.getBlockState(target.pos);
                    float progress = getGrimSyncedDestroyProgress(player, level, target.pos, state);

                    if (progress >= 1.0f || player.getAbilities().instabuild) {
                        if (canSendPacket(now)) {
                            recordPacketSent(now);
                            quarantineMap.put(target.pos.asLong(), now);
                            sendPredictiveAction(gameMode, level, seq -> {
                                gameMode.destroyBlock(target.pos);
                                return new ServerboundPlayerActionPacket(
                                        ServerboundPlayerActionPacket.Action.START_DESTROY_BLOCK,
                                        target.pos,
                                        target.direction,
                                        seq);
                            });
                            player.swing(InteractionHand.MAIN_HAND);
                            this.currentTarget = null;
                            this.hasActiveRotation = false;
                        }
                    } else {
                        if (canSendPacket(now)) {
                            recordPacketSent(now);
                            currentHardBlock = target.pos;
                            currentHardFace = target.direction;
                            currentHardDamage = 0.0f;
                            hardTicksElapsed = 0;
                            hardStartTimeMs = now;

                            sendPredictiveAction(gameMode, level, seq -> new ServerboundPlayerActionPacket(
                                    ServerboundPlayerActionPacket.Action.START_DESTROY_BLOCK,
                                    target.pos,
                                    target.direction,
                                    seq));
                            level.destroyBlockProgress(player.getId(), target.pos, 0);
                            player.swing(InteractionHand.MAIN_HAND);
                        }
                    }
                    break;
                }

                case FASTEST: {
                    if (gameMode instanceof IPlayerControllerMP mp) {
                        mp.setDestroyDelay(0);
                    }
                    if (gameMode.isDestroying()) {
                        gameMode.stopDestroyBlock();
                    }

                    // Reset stanu z trybu NORMAL/FAST jeśli istniał
                    if (currentHardBlock != null) {
                        level.destroyBlockProgress(player.getId(), currentHardBlock, -1);
                        currentHardBlock = null;
                        currentHardDamage = 0.0f;
                        hardTicksElapsed = 0;
                        hardStartTimeMs = 0L;
                    }

                    long dt = Math.max(0L, now - lastBalanceDecayMs);
                    lastBalanceDecayMs = now;
                    simulatedGrimBalanceMs = Math.max(0.0f, simulatedGrimBalanceMs - dt * 0.65f);

                    // Detekcja odrzucenia/cofki bloku przez Custom AC:
                    // Jeśli blok wysłany > 250ms temu nadal nie jest powietrzem,
                    // włączamy Strict Sync na 2.5s, aby natychmiast zatrzymać cofki gracza.
                    var qIter = quarantineMap.long2LongEntrySet().fastIterator();
                    while (qIter.hasNext()) {
                        var entry = qIter.next();
                        long age = now - entry.getLongValue();
                        if (age > 250L) {
                            BlockPos checkPos = BlockPos.of(entry.getLongKey());
                            if (!level.getBlockState(checkPos).isAir() && entry.getLongKey() != lastRejectedPosLong) {
                                lastRejectedPosLong = entry.getLongKey();
                                simulatedGrimBalanceMs = 900.0f;
                                strictSyncUntilMs = now + 2500L;
                            }
                            qIter.remove();
                        }
                    }

                    Vec3 eyePos = player.getEyePosition(1.0F);
                    double reachSq = (double) this.range * (double) this.range;
                    int r = Mth.ceil(this.range);
                    int minFloorY = player.getBlockY();

                    // DYREKTYWA DJ: Jeśli lista wybranych bloków jest pusta — NIE KOP NIC!
                    if (customWhitelist.isEmpty()) {
                        if (fastestHardPos != null) {
                            sendPredictiveAction(gameMode, level, seq -> new ServerboundPlayerActionPacket(
                                    ServerboundPlayerActionPacket.Action.ABORT_DESTROY_BLOCK,
                                    fastestHardPos,
                                    fastestHardFace,
                                    seq));
                            level.destroyBlockProgress(player.getId(), fastestHardPos, -1);
                            fastestHardPos = null;
                        }
                        this.currentTarget = null;
                        this.hasActiveRotation = false;
                        grimDelayBalanceMs = Math.max(0, grimDelayBalanceMs - 50);
                        break;
                    }

                    // DYREKTYWA GROUND (Anty-Cofka & Anty-FastBreak 250ms):
                    // W locie lub w trakcie skoku serwer nakłada na gracza 5x karę prędkości (0.2x
                    // speed).
                    // Kopanie w powietrzu lub w trakcie opadania powoduje natychmiastowy desync z
                    // GrimAC (delay=247ms).
                    // Wymagamy pełnej synchronizacji onGround klienta i poprzedniego stanu na
                    // serwerze!
                    if ((!player.onGround() || !lastServerOnGround) && !player.getAbilities().flying) {
                        this.currentTarget = null;
                        this.hasActiveRotation = false;
                        break;
                    }

                    // =========================================================================
                    // ETAP 1: Kontynuacja aktywnego celu NON-INSTAMINE (np. kamień 2 ticki,
                    // deepslate 3 ticki)
                    // =========================================================================
                    if (fastestHardPos != null) {
                        BlockState sState = level.getBlockState(fastestHardPos);
                        Direction visibleFace = getExposedVisibleFace(level, fastestHardPos, sState, eyePos);

                        if (sState.isAir() || !matchesFilter(sState, fastestHardPos) || visibleFace == null
                                || eyePos.distanceToSqr(Vec3.atCenterOf(fastestHardPos)) > reachSq) {
                            // Gracz wybiegł z zasięgu lub cel zniknął — czysty ABORT_DESTROY_BLOCK (brak
                            // cofki)
                            if (!sState.isAir()) {
                                final BlockPos abortPos = fastestHardPos;
                                final Direction abortFace = fastestHardFace;
                                sendPredictiveAction(gameMode, level, seq -> new ServerboundPlayerActionPacket(
                                        ServerboundPlayerActionPacket.Action.ABORT_DESTROY_BLOCK,
                                        abortPos,
                                        abortFace,
                                        seq));
                            }
                            level.destroyBlockProgress(player.getId(), fastestHardPos, -1);
                            fastestHardPos = null;
                            fastestHardFace = Direction.UP;
                            fastestHardAccumulatedProgress = 0.0f;
                            fastestHardTicks = 0;
                        } else {
                            float currentDelta = getGrimSyncedDestroyProgress(player, level, fastestHardPos, sState);
                            fastestHardAccumulatedProgress += currentDelta;
                            fastestHardTicks++;
                            fastestHardFace = visibleFace;

                            if (rotationMode == RotationMode.SILENT_GCD) {
                                savedPreYaw = player.getYRot();
                                savedPrePitch = player.getXRot();
                                applyGcdSilentRotation(mc, player, eyePos, fastestHardPos, fastestHardFace);
                                isPreRotated = true;
                            }

                            // Podwójna weryfikacja GrimAC + Vanilla Server Engine (Argon 4 Dual Invariant):
                            // 1. GrimAC: suma postępu ze wszystkich ticków >= 1.0f
                            // 2. Vanilla Server: delta w bieżącym ticku STOP * liczba ticków >= 1.0f
                            float vanillaServerProgress = currentDelta * fastestHardTicks;
                            boolean canFinish = fastestHardAccumulatedProgress >= 1.0f && vanillaServerProgress >= 1.0f;

                            if (canFinish && canSendPacket(now, 1)) {
                                final BlockPos finishPos = fastestHardPos;
                                final Direction finishFace = fastestHardFace;

                                level.destroyBlockProgress(player.getId(), finishPos, -1);
                                recordPacketSent(now, 1);

                                // ATOMOWY STOP: Wywołanie destroyBlock wewnątrz predykcji STOP_DESTROY_BLOCK
                                sendPredictiveAction(gameMode, level, seq -> {
                                    gameMode.destroyBlock(finishPos);
                                    return new ServerboundPlayerActionPacket(
                                            ServerboundPlayerActionPacket.Action.STOP_DESTROY_BLOCK,
                                            finishPos,
                                            finishFace,
                                            seq);
                                });

                                quarantineMap.put(finishPos.asLong(), now);
                                fastestHardPos = null;
                                fastestHardFace = Direction.UP;
                                fastestHardAccumulatedProgress = 0.0f;
                                fastestHardTicks = 0;
                                player.connection.send(new ServerboundSwingPacket(InteractionHand.MAIN_HAND));
                                player.swing(InteractionHand.MAIN_HAND);
                                this.currentTarget = null;
                                this.hasActiveRotation = false;
                                return; // 1 akcja w ticku: zapobiega wysyłaniu kolejnego pakietu (eliminuje delay=1ms)
                            } else {
                                // Blok nadal wymaga kolejnego ticka (np. tick 1 z 2 dla kamienia z Eff 5).
                                // KRYTYCZNA ZASADA ARGONA 4:
                                // W tickach pośrednich NIE WYSYŁAMY żadnego START_DESTROY_BLOCK dla instamine,
                                // aby nie zresetować pozycji destroyPos na serwerze!
                                int stage = Mth.clamp((int) (fastestHardAccumulatedProgress * 10.0f) - 1, 0, 9);
                                level.destroyBlockProgress(player.getId(), fastestHardPos, stage);
                                player.connection.send(new ServerboundSwingPacket(InteractionHand.MAIN_HAND));
                                player.swing(InteractionHand.MAIN_HAND);
                                this.currentTarget = null;
                                this.hasActiveRotation = false;
                                return; // Czekaj na kolejny tick (50ms)
                            }
                        }
                    }

                    // =========================================================================
                    // ETAP 2: Skanowanie sfery (Onion-Peeling + FOV Cone 42°)
                    // Wykonywane TYLKO gdy żaden blok non-instamine nie jest aktualnie w trakcie
                    // kopania
                    // =========================================================================
                    Vec3 moveDir = player.getDeltaMovement().horizontalDistanceSqr() > 0.002
                            ? player.getDeltaMovement().normalize()
                            : Vec3.directionFromRotation(0.0f, player.getYRot());

                    List<BlockPos> instamineCandidates = new ArrayList<>(CUSTOM_AC_MAX_INSTAMINE_PER_TICK * 2);
                    List<Direction> instamineFaces = new ArrayList<>(CUSTOM_AC_MAX_INSTAMINE_PER_TICK * 2);

                    BlockPos bestHardCandidate = null;
                    Direction bestHardFace = Direction.UP;
                    float bestHardDelta = 0.0f;
                    double bestHardScore = Double.MAX_VALUE;

                    BlockPos origin = BlockPos.containing(player.getX(), player.getY() + 1.0, player.getZ());
                    for (BlockPos pos : BlockPos.betweenClosed(origin.offset(-r, -r, -r), origin.offset(r, r, r))) {
                        long packed = pos.asLong();
                        if (quarantineMap.containsKey(packed))
                            continue;
                        if (this.flat && pos.getY() < minFloorY)
                            continue;

                        BlockState state = level.getBlockState(pos);
                        if (state.isAir() || !state.getFluidState().isEmpty())
                            continue;
                        if (state.getDestroySpeed(level, pos) < 0.0f)
                            continue;
                        if (!matchesFilter(state, pos))
                            continue;

                        double dx = Mth.clamp(eyePos.x, pos.getX(), pos.getX() + 1.0) - eyePos.x;
                        double dy = Mth.clamp(eyePos.y, pos.getY(), pos.getY() + 1.0) - eyePos.y;
                        double dz = Mth.clamp(eyePos.z, pos.getZ(), pos.getZ() + 1.0) - eyePos.z;
                        if ((dx * dx + dy * dy + dz * dz) > reachSq)
                            continue;

                        Direction exposedFace = getExposedVisibleFace(level, pos, state, eyePos);
                        if (exposedFace == null)
                            continue;

                        double cx = (pos.getX() + 0.5) - eyePos.x;
                        double cy = (pos.getY() + 0.5) - eyePos.y;
                        double cz = (pos.getZ() + 0.5) - eyePos.z;
                        double distSq = cx * cx + cy * cy + cz * cz;
                        double forwardAlignment = (cx * moveDir.x + cz * moveDir.z);
                        double score = distSq - (forwardAlignment * 2.2);

                        float delta = getGrimSyncedDestroyProgress(player, level, pos, state);
                        // Zgodne z perkiem Haste II + Wydajność 10: każdy blok z delta >= 1.0f (w tym
                        // Deepslate) to czysty instamine
                        if (delta >= 1.0f || player.getAbilities().instabuild) {
                            instamineCandidates.add(pos.immutable());
                            instamineFaces.add(exposedFace);
                        } else if (score < bestHardScore) {
                            bestHardScore = score;
                            bestHardCandidate = pos.immutable();
                            bestHardFace = exposedFace;
                            bestHardDelta = delta;
                        }
                    }

                    // =========================================================================
                    // ETAP 3: Błyskawiczny Instamine (1 blok na tick = 20 bloków/s)
                    // Ściśle 1 blok w ticku na serwerach z GrimAC/Custom AC (eliminuje delay=0ms,
                    // 1ms i cofki)!
                    // =========================================================================
                    int instamineBroken = 0;
                    if (!instamineCandidates.isEmpty()) {
                        // 1. Sortujemy kandydatów: bliższe i bardziej przed wzrokiem gracza na początku
                        Vec3 currentLook = Vec3.directionFromRotation(player.getXRot(), player.getYRot());
                        List<Integer> sortedIndices = new ArrayList<>(instamineCandidates.size());
                        for (int i = 0; i < instamineCandidates.size(); i++)
                            sortedIndices.add(i);

                        sortedIndices.sort(Comparator.comparingDouble(idx -> {
                            BlockPos pos = instamineCandidates.get(idx);
                            Vec3 toBlock = Vec3.atCenterOf(pos).subtract(eyePos);
                            double distSq = toBlock.lengthSqr();
                            double dot = currentLook.dot(toBlock.normalize());
                            return distSq - (dot * 2.5);
                        }));

                        int firstIdx = sortedIndices.get(0);
                        BlockPos target1 = instamineCandidates.get(firstIdx);
                        Direction face1 = instamineFaces.get(firstIdx);

                        if (!level.getBlockState(target1).isAir() && canSendPacket(now, 1)) {
                            // Silent GCD rotacja na cel przed wysłaniem pakietu (PositionBreak &
                            // RotationBreak bypass)
                            if (rotationMode == RotationMode.SILENT_GCD) {
                                savedPreYaw = player.getYRot();
                                savedPrePitch = player.getXRot();
                                applyGcdSilentRotation(mc, player, eyePos, target1, face1);
                                isPreRotated = true;
                            }

                            recordPacketSent(now, 1);
                            sendPredictiveAction(gameMode, level, seq -> {
                                gameMode.destroyBlock(target1);
                                return new ServerboundPlayerActionPacket(
                                        ServerboundPlayerActionPacket.Action.START_DESTROY_BLOCK,
                                        target1,
                                        face1,
                                        seq);
                            });
                            player.connection.send(new ServerboundSwingPacket(InteractionHand.MAIN_HAND));
                            player.swing(InteractionHand.MAIN_HAND);
                            quarantineMap.put(target1.asLong(), now);
                            instamineBroken++;
                            this.currentTarget = null;
                            this.hasActiveRotation = false;
                            return; // 1 INSTAMINE NA TICK (20 bloków/s) - ZERO GRIM DEBT!
                        }
                    }

                    grimDelayBalanceMs = Math.max(0, grimDelayBalanceMs - 50);

                    // =========================================================================
                    // ETAP 4: Rozpoczęcie nowego celu NON-INSTAMINE (tylko jeśli brak instamine w
                    // tym ticku)
                    // =========================================================================
                    if (instamineBroken == 0 && bestHardCandidate != null && canSendPacket(now, 1)) {
                        fastestHardPos = bestHardCandidate;
                        fastestHardFace = bestHardFace;
                        fastestHardAccumulatedProgress = bestHardDelta;
                        fastestHardTicks = 1;

                        if (rotationMode == RotationMode.SILENT_GCD) {
                            savedPreYaw = player.getYRot();
                            savedPrePitch = player.getXRot();
                            applyGcdSilentRotation(mc, player, eyePos, fastestHardPos, fastestHardFace);
                            isPreRotated = true;
                        }

                        recordPacketSent(now, 1);
                        final BlockPos startPos = fastestHardPos;
                        final Direction startFace = fastestHardFace;

                        // KRYTYCZNE POD ANTY-COFKI (Argon 4):
                        // W ticku T_0 wysyłamy TYLKO START_DESTROY_BLOCK bez lokalnego destroyBlock()!
                        // Wyświetlamy pęknięcie (stage 0), a blok w pamięci klienta zostaje nietknięty.
                        sendPredictiveAction(gameMode, level, seq -> new ServerboundPlayerActionPacket(
                                ServerboundPlayerActionPacket.Action.START_DESTROY_BLOCK,
                                startPos,
                                startFace,
                                seq));
                        level.destroyBlockProgress(player.getId(), startPos, 0);
                        player.connection.send(new ServerboundSwingPacket(InteractionHand.MAIN_HAND));
                        player.swing(InteractionHand.MAIN_HAND);
                        this.currentTarget = null;
                        this.hasActiveRotation = false;
                        return; // T_0 zainicjalizowany - czekaj na tick T_1
                    }

                    this.currentTarget = null;
                    this.hasActiveRotation = false;
                    break;
                }
            }
        } finally {
            lastServerOnGround = player.onGround();
        }
    }

    /**
     * Wywoływane w POST-TICK (MixinClientPlayerEntity.tick RETURN), po
     * LocalPlayer.sendPosition().
     * Natychmiast przywraca kąty kamery gracza, aby cicha rotacja była niewidoczna
     * na ekranie.
     */
    public void onPostPlayerUpdate(LocalPlayer player) {
        if (isPreRotated) {
            player.setYRot(savedPreYaw);
            player.setXRot(savedPrePitch);
            isPreRotated = false;
        }
    }

    /**
     * Client tick lifecycle cleanup.
     */
    public void onClientTick(Minecraft mc) {
        if (!enabled || mc.player == null || mc.level == null) {
            this.currentTarget = null;
            this.hasActiveRotation = false;
        }
    }
}
