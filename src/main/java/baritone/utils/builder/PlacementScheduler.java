package baritone.utils.builder;

import baritone.Baritone;
import baritone.api.utils.BetterBlockPos;
import net.minecraft.core.BlockPos;
import net.minecraft.util.Mth;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.state.BlockState;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Owns the *when* of block placement: cooldowns, click -> server confirmation,
 * and ghost-block exponential backoff.
 *
 * FIX 1: Verification never blocks indefinitely — hard cap of max 20 ticks.
 * FIX 1: Ghost blocks get exponential backoff: 200 -> 400 -> 800 ticks.
 */
public final class PlacementScheduler {

    /**
     * A pending build target: the block position and desired state.
     * Kept here for backward compatibility with BuilderProcess.
     */
    public static final class Target {
        public final BlockPos pos;
        public final BlockState want; // null = break-only target (#cleararea)

        public Target(BlockPos pos, BlockState want) {
            this.pos = pos.immutable();
            this.want = want;
        }
    }

    /** Emitted when a placement timed out without server confirmation. */
    public record Ghost(BlockPos pos, int backoffTicks) {}

    private static final class Pending {
        final BlockPos pos;
        final BlockState state;
        final int placedTick;

        Pending(BlockPos pos, BlockState state, int placedTick) {
            this.pos = pos.immutable();
            this.state = state;
            this.placedTick = placedTick;
        }
    }

    private Pending pendingVerification;
    private int cooldownTicks;
    private int verified, ghosted;
    private final Map<BlockPos, Integer> ghostStreaks = new HashMap<>();
    /** Maps block position -> world-tick at which it becomes reachable again. */
    private final Map<BlockPos, Long> unreachableUntil = new HashMap<>();
    /** Maps block position -> world-tick until which it is protected as recently placed. */
    private final Map<BlockPos, Long> recentlyPlacedUntil = new HashMap<>();

    // Visual state — last confirmed placement (for PathRenderer orb animation)
    private BlockPos lastPlacedPos;
    private long lastPlacedTick;

    public void reset() {
        pendingVerification = null;
        cooldownTicks = 0;
        ghostStreaks.clear();
        unreachableUntil.clear();
        recentlyPlacedUntil.clear();
        verified = ghosted = 0;
        lastPlacedPos = null;
        lastPlacedTick = 0;
    }

    /**
     * Advance the scheduler by one tick.
     *
     * @return a {@link Ghost} event if a pending verification hit the hard cap, else null.
     */
    /** Last world tick seen — kept in sync on every {@link #tick} call. */
    private long lastWorldTick;

    public Ghost tick(Level level, int tick) {
        lastWorldTick = level.getGameTime();
        if (cooldownTicks > 0) cooldownTicks--;
        if (pendingVerification == null) return null;

        Pending p = pendingVerification;
        int age = tick - p.placedTick;
        BlockState world = level.getBlockState(p.pos);

        // Check if the server confirmed the block appeared
        if (BlockStateResolver.statesMatch(world, p.state)) {
            pendingVerification = null;              // server confirmed the placement
            ghostStreaks.remove(p.pos);
            verified++;
            lastPlacedPos = p.pos;
            lastPlacedTick = tick;
            return null;
        }

        // FIX 1: Hard cap — a pending verification may never outlive VERIFY_HARD_CAP_TICKS.
        // Force-reset and classify the click as a ghost block with exponential backoff.
        if (age >= hardCap()) {
            pendingVerification = null;
            ghosted++;
            int streak = ghostStreaks.merge(p.pos.immutable(), 1, Integer::sum);
            int base = Math.max(20, Baritone.settings().builderGhostBackoffBaseTicks.value);
            // Exponential: 200 -> 400 -> 800, then cap
            int backoff = (int) Math.min(800L, base * (1L << Math.min(streak - 1, 2)));
            return new Ghost(p.pos, backoff);
        }
        return null;
    }

    private static int hardCap() {
        return Mth.clamp(Baritone.settings().builderPlacementVerifyHardCapTicks.value, 1, 20);
    }

    /** True when a new click is allowed right now. */
    public boolean canPlaceNow() {
        return pendingVerification == null && cooldownTicks == 0;
    }

    /**
     * Called after a successful {@code useItemOn()} client-side result.
     * Starts the verification window and placement cooldown.
     */
    public void onPlaced(BlockPos pos, BlockState state, int tick) {
        pendingVerification = new Pending(pos, state, tick);
        cooldownTicks = Math.max(2, Baritone.settings().builderPlacementCooldownTicks.value);
        markRecentlyPlaced(pos, 40L);
        lastPlacedPos = pos.immutable();
    }

    /** Server refused the click (bad face / occupied cell). Short retry delay. */
    public void onRejected(BlockPos pos, int tick) {
        cooldownTicks = Math.max(cooldownTicks, 2);
        pendingVerification = null;
        // Count as ghost so this position gets backoff if it keeps failing
        ghostStreaks.merge(pos.immutable(), 1, Integer::sum);
    }

    /** Called when the world is seen to already match the wanted state at pos. */
    public void onConfirmed(BlockPos pos) {
        ghostStreaks.remove(pos);
        recentlyPlacedUntil.remove(pos);
        if (pendingVerification != null && pendingVerification.pos.equals(pos)) {
            pendingVerification = null;
        }
    }

    /**
     * Force-reset the verification window (safety valve for anti-stall logic).
     * Counts the abandoned verification as a ghost failure.
     */
    public void forceResetVerification() {
        if (pendingVerification != null) {
            ghostStreaks.merge(pendingVerification.pos, 1, Integer::sum);
        }
        pendingVerification = null;
        cooldownTicks = 0;
    }

    public boolean isPending()   { return pendingVerification != null; }
    public int verifiedCount()   { return verified; }
    public int ghostCount()      { return ghosted; }

    /** True if this position has repeated ghost failures (used by overlay coloring). */
    public boolean isKnownGhost(BlockPos pos) {
        return ghostStreaks.getOrDefault(pos, 0) >= 2;
    }

    // ---- Visual accessors for PathRenderer ----

    /** Position of the most recently confirmed placed block (for orb animation). May be null. */
    public BlockPos getLastPlacedPos() { return lastPlacedPos; }

    /** Game tick when the last block was confirmed placed. */
    public long getLastPlacedTick() { return lastPlacedTick; }

    // ---- Backward-compat shims for existing BuilderProcess callers ----

    /** @deprecated Use {@link #onPlaced(BlockPos, BlockState, int)} */
    @Deprecated
    public void notifyPlaced(BlockPos pos) {
        cooldownTicks = Math.max(2, Baritone.settings().builderPlacementCooldownTicks.value);
        markRecentlyPlaced(pos, 40L);
        lastPlacedPos = pos.immutable();
    }

    /** @deprecated Use {@link #onRejected(BlockPos, int)} */
    @Deprecated
    public void notifyRejected(BlockPos pos) {
        cooldownTicks = Math.max(cooldownTicks, 2);
        ghostStreaks.merge(pos.immutable(), 1, Integer::sum);
    }

    /** @deprecated Use {@link #isKnownGhost(BlockPos)} */
    @Deprecated
    public int ghostFailures(BlockPos pos) {
        return ghostStreaks.getOrDefault(pos, 0);
    }

    /**
     * Returns the current game tick from the last level seen by this scheduler,
     * or 0 if never ticked. Used by BuilderProcess for retry deadlines.
     */
    public long currentTick() { return lastWorldTick; }

    // ---- Recently placed tracking to prevent immediate re-queue loop ----

    public void markRecentlyPlaced(BlockPos pos, long durationTicks) {
        recentlyPlacedUntil.put(pos.immutable(), lastWorldTick + durationTicks);
    }

    public boolean isRecentlyPlaced(BlockPos pos) {
        Long deadline = recentlyPlacedUntil.get(pos.immutable());
        return deadline != null && lastWorldTick < deadline;
    }

    // ---- Unreachable / retry-deadline tracking ----

    /**
     * Returns true if this position is currently marked as unreachable
     * or was recently placed and awaiting server confirmation.
     */
    public boolean isUnreachable(BlockPos pos) {
        if (isRecentlyPlaced(pos)) {
            return true;
        }
        Long deadline = unreachableUntil.get(pos.immutable());
        return deadline != null && lastWorldTick < deadline;
    }

    /**
     * Mark a position as unreachable until the given world tick.
     *
     * @param pos     the block position
     * @param retryAt the world tick at which it becomes eligible again
     */
    public void markUnreachable(BlockPos pos, long retryAt) {
        unreachableUntil.put(pos.immutable(), retryAt);
    }

    /** @deprecated Exponential backoff is automatic; use isKnownGhost() */
    @Deprecated
    public void markUnreachableWithBackoff(BlockPos pos) { /* handled by caller */ }

    public enum ClearMode { AUTO, TOP_DOWN, BOTTOM_UP }
    public enum Strategy { ARENA, TOP_DOWN, BOTTOM_UP }

    private ClearMode clearMode = ClearMode.AUTO;

    public void setClearMode(ClearMode mode) { this.clearMode = mode != null ? mode : ClearMode.AUTO; }
    public ClearMode getClearMode() { return this.clearMode; }
    public String describeStrategy(int areaHeight) { return resolveStrategy(areaHeight).name(); }

    public Strategy resolveStrategy(int areaHeight) {
        return switch (clearMode) {
            case TOP_DOWN -> Strategy.TOP_DOWN;
            case BOTTOM_UP -> Strategy.BOTTOM_UP;
            case AUTO -> areaHeight <= 6 ? Strategy.ARENA : Strategy.TOP_DOWN;
        };
    }

    /**
     * Sort and return a list of build targets using Y-first order (bottom-up),
     * then by distance from the player's feet (closest first).
     *
     * @param candidates unsorted candidate targets
     * @param world      current level (used to query the current tick)
     * @param playerFeet player position for distance sorting
     * @return sorted, mutable list
     */
    public List<Target> orderCandidates(List<Target> candidates, Level world, BetterBlockPos playerFeet) {
        List<Target> sorted = new ArrayList<>(candidates);

        // Determine clear bounding box height if in clearing mode
        int clearMinY = Integer.MAX_VALUE;
        int clearMaxY = Integer.MIN_VALUE;
        boolean hasClear = false;
        for (Target t : candidates) {
            if (t.want == null) {
                hasClear = true;
                if (t.pos.getY() < clearMinY) clearMinY = t.pos.getY();
                if (t.pos.getY() > clearMaxY) clearMaxY = t.pos.getY();
            }
        }
        final int areaHeight = hasClear ? Math.max(1, clearMaxY - clearMinY + 1) : 1;
        final Strategy strategy = resolveStrategy(areaHeight);
        final BlockPos standPos = playerFeet != null ? playerFeet.below() : null;

        sorted.sort((a, b) -> {
            boolean aClear = a.want == null;
            boolean bClear = b.want == null;
            if (aClear != bClear) {
                return aClear ? -1 : 1; // Clear obstructions before placing
            }
            if (aClear && bClear) {
                // 1) Ground protection: never dig the block directly beneath our feet until other blocks are done!
                if (standPos != null) {
                    boolean aIsStand = a.pos.equals(standPos);
                    boolean bIsStand = b.pos.equals(standPos);
                    if (aIsStand != bIsStand) {
                        return aIsStand ? 1 : -1;
                    }
                }

                // 2) Layer ordering based on strategy
                int ay = a.pos.getY(), by = b.pos.getY();
                switch (strategy) {
                    case ARENA -> {
                        int pa = arenaPhase(ay, playerFeet);
                        int pb = arenaPhase(by, playerFeet);
                        if (pa != pb) return Integer.compare(pa, pb);
                        if (pa == 1) { int c = Integer.compare(ay, by); if (c != 0) return c; } // ceiling: bottom-up
                        if (pa == 2) { int c = Integer.compare(by, ay); if (c != 0) return c; } // floor: top-down
                    }
                    case TOP_DOWN -> { int c = Integer.compare(by, ay); if (c != 0) return c; }
                    case BOTTOM_UP -> { int c = Integer.compare(ay, by); if (c != 0) return c; }
                }

                // 3) Within layer: closest to player feet
                if (playerFeet != null) {
                    int distCmp = Double.compare(a.pos.distSqr(playerFeet), b.pos.distSqr(playerFeet));
                    if (distCmp != 0) return distCmp;
                }
                int dx = Integer.compare(a.pos.getX(), b.pos.getX());
                if (dx != 0) return dx;
                return Integer.compare(a.pos.getZ(), b.pos.getZ());
            }

            // Construction: BOTTOM-UP (lower Y first), then topological phase, then closest
            int dy = Integer.compare(a.pos.getY(), b.pos.getY());
            if (dy != 0) return dy;
            int dp = Integer.compare(VantagePointSolver.placementPhase(a.want), VantagePointSolver.placementPhase(b.want));
            if (dp != 0) return dp;
            if (playerFeet != null) {
                int distCmp = Double.compare(a.pos.distSqr(playerFeet), b.pos.distSqr(playerFeet));
                if (distCmp != 0) return distCmp;
            }
            int dx = Integer.compare(a.pos.getX(), b.pos.getX());
            if (dx != 0) return dx;
            return Integer.compare(a.pos.getZ(), b.pos.getZ());
        });
        return sorted;
    }

    /** 0 = poziom stóp i klatki/głowy, 1 = sufit (wyżej), 2 = podłoga (niżej). */
    private static int arenaPhase(int y, BlockPos feet) {
        if (feet == null) return 0;
        int fy = feet.getY();
        if (y >= fy && y <= fy + 1) return 0;
        return y > fy + 1 ? 1 : 2;
    }
}

