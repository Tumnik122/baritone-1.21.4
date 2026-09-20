package baritone.utils.builder;

import baritone.Baritone;
import baritone.api.utils.Rotation;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.context.BlockPlaceContext;
import net.minecraft.world.level.ClipContext;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.*;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.Property;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;

import java.util.Arrays;
import java.util.Set;

/**
 * BUG 4 — exact placement of directional blocks.
 * BUG 3 — line-of-sight validation of the click point.
 * IMPROVEMENT 3 — dynamic property ignoring when comparing states.
 */
public final class BlockStateResolver {

    private BlockStateResolver() {}

    /**
     * IMPROVEMENT 3: properties that are auto-computed by the server from
     * neighbors / redstone / fluids. Never used for "is it built?" checks.
     *
     * NOTE: Properties that are auto-computed by the server, redstone-driven,
     * or change dynamically at runtime. Never used for "is it built?" checks.
     */
    private static final Set<String> DYNAMIC_PROPERTIES = Set.of(
            "shape",                                             // StairBlock.SHAPE
            "north", "east", "south", "west", "up", "down",     // PipeBlock / RedStoneWire / walls / panes / fences
            "attached", "disarmed", "power",                    // TripWireBlock / RedStoneWireBlock
            "waterlogged",                                       // filled from surroundings
            "in_wall",                                           // FenceGateBlock (auto)
            "extended",                                          // pistons (extend via redstone)
            "triggered",                                         // CrafterBlock (redstone driven)
            "open",                                              // doors / trapdoors (toggle at runtime)
            "lit",                                               // furnace / redstone lamp (on/off at runtime)
            "occupied",                                          // beds (player sleeping)
            "bloom",                                             // sculk (auto)
            "enabled",                                           // HopperBlock (redstone driven)
            "powered",                                           // Repeater / Comparator / Lever / Button / Pressure plate
            "locked",                                            // RepeaterBlock
            "crafting",                                          // CrafterBlock
            "drag",                                              // BubbleColumnBlock
            "charges",                                           // RespawnAnchorBlock
            "candles",                                           // CandleCakeBlock
            "bites",                                             // CakeBlock
            "age",                                               // Fire / Crops / Plants
            "distance",                                          // Leaves / Scaffolding
            "persistent",                                        // Leaves
            "stage",                                             // Saplings
            "moisture",                                          // Farmland
            "signal_fire",                                       // Campfire
            "unstable"                                           // TNT
    );

    /** Preference order for support blocks (ground first, ceiling last). */
    private static final Direction[] SUPPORT_SEARCH_ORDER = {
            Direction.DOWN, Direction.NORTH, Direction.SOUTH, Direction.WEST, Direction.EAST, Direction.UP
    };

    /** Hit heights on side faces: >0.5 => TOP half stair / top slab. */
    private static final double[] HIT_HEIGHTS = {0.75, 0.25};

    /** Max deviation from a cardinal before `Direction.getNearest` flips. */
    private static final float YAW_MARGIN = 25.0f;
    private static final float PITCH_MARGIN = 89.0f;

    // =====================================================================
    // State comparison (IMPROVEMENT 3)
    // =====================================================================

    public static boolean statesMatch(BlockState want, BlockState have) {
        if (want == have) {
            return true;
        }
        if (want == null || have == null) {
            return false;
        }
        // FIX: fuzzy match — same block type, ignoring minor state differences
        if (Baritone.settings().builderFuzzyBlockMatch.value) {
            return want.getBlock() == have.getBlock();
        }
        if (want.getBlock() != have.getBlock()) {
            return false;
        }
        for (Property<?> p : want.getProperties()) {
            if (DYNAMIC_PROPERTIES.contains(p.getName())) {
                continue;
            }
            if (!have.hasProperty(p) || !genericEquals(want, p, have)) {
                return false;
            }
        }
        return true;
    }

    @SuppressWarnings({"unchecked", "rawtypes"})
    private static boolean genericEquals(BlockState a, Property p, BlockState b) {
        return a.getValue(p).equals(b.getValue(p));
    }

    // =====================================================================
    // Placement resolution (BUG 4)
    // =====================================================================

    /** Fully resolved "how to place this state" — what to click and how to look. */
    public static final class PlacementPlan {
        public final BlockPos target;     // cell the block goes into
        public final BlockState wanted;   // schematic state
        public final BlockPos support;    // block we click on
        public final Direction face;      // clicked face of `support` (points at target)
        public final Vec3 hit;            // exact click location
        public final Rotation rotation;   // required look while clicking
        public final boolean sneak;       // support is interactive -> hold shift
        public int slot = -1;             // hotbar slot with the item (set by BuilderProcess)

        PlacementPlan(BlockPos target, BlockState wanted, BlockPos support, Direction face,
                      Vec3 hit, Rotation rotation, boolean sneak) {
            this.target = target;
            this.wanted = wanted;
            this.support = support;
            this.face = face;
            this.hit = hit;
            this.rotation = rotation;
            this.sneak = sneak;
        }

        public BlockHitResult toHitResult() {
            return new BlockHitResult(hit, face, support, false);
        }

        public boolean stillValid(Level world) {
            BlockState cur = world.getBlockState(target);
            if (statesMatch(wanted, cur)) {
                return false; // already done
            }
            if (!(cur.getBlock() instanceof AirBlock) && !isReplaceable(cur) && cur.getFluidState().isEmpty()) {
                return false; // something solid is in the way now
            }
            return isUsableSupport(world, support, world.getBlockState(support), face);
        }
    }

    /**
     * Resolves an exact click that produces `want` at `target`, or null.
     * Non-interactive supports are preferred (no sneak needed); interactive
     * supports are tried in a second pass with sneak (clicking a chest face
     * without shift opens the GUI instead of placing).
     */
    public static PlacementPlan resolve(Level world, LocalPlayer player, BlockPos target, BlockState want) {
        BlockState cur = world.getBlockState(target);
        if (statesMatch(want, cur)) {
            return null; // target already built
        }
        if (!(cur.getBlock() instanceof AirBlock) && !isReplaceable(cur) && cur.getFluidState().isEmpty()) {
            return null; // occupied by solid obstruction (needs break phase)
        }
        if (!Baritone.settings().rotationResolver.value) {
            return legacyResolve(world, player, target, want);
        }
        // For redstone-dependent blocks (wire, comparator, repeater) canSurvive may
        // fail if neighboring blocks aren't yet placed. Skip the check for those —
        // the server will reject the placement gracefully if it truly can't survive.
        boolean skipSurviveCheck = want.getBlock() instanceof RedStoneWireBlock
                || want.getBlock() instanceof DiodeBlock  // repeater + comparator
                || want.getBlock() instanceof TorchBlock
                || want.getBlock() instanceof WallTorchBlock
                || want.getBlock() instanceof BaseRailBlock
                || want.getBlock() instanceof RedstoneLampBlock
                || want.getBlock() instanceof LeverBlock
                || want.getBlock() instanceof ButtonBlock
                || want.getBlock() instanceof SignBlock
                || want.getBlock() instanceof BannerBlock
                || want.getBlock() instanceof WallBannerBlock
                || want.getBlock() instanceof LanternBlock
                || want.getBlock() instanceof TripWireBlock
                || want.getBlock() instanceof TripWireHookBlock
                || want.getBlock() instanceof CarpetBlock;
        if (!skipSurviveCheck) {
            try {
                if (!want.canSurvive(world, target)) {
                    return null; // the server would reject this right now
                }
            } catch (Exception ignored) {
                // canSurvive can throw on some modded blocks — treat as survivable
            }
        }
        net.minecraft.world.item.Item wantedItem = getItemForState(want);
        ItemStack stack = (wantedItem != null && wantedItem != net.minecraft.world.item.Items.AIR)
                ? new ItemStack(wantedItem)
                : new ItemStack(want.getBlock());
        Direction[] lookOrder = looksOrderedByCurrentRotation(player);

        for (boolean allowInteractive = false; ; allowInteractive = true) {
            for (Direction supportDir : SUPPORT_SEARCH_ORDER) {
                BlockPos support = target.relative(supportDir);
                Direction face = supportDir.getOpposite();
                BlockState supportState = world.getBlockState(support);
                boolean interactive = requiresSneakToPlaceAgainst(supportState);
                if (interactive && !allowInteractive) {
                    continue;
                }
                if (!isUsableSupport(world, support, supportState, face)) {
                    continue;
                }
                for (double height : HIT_HEIGHTS) {
                    Vec3 hit = hitVecOnFace(support, face, height);
                    if (!isVisible(world, player, support, hit)) {
                        continue;
                    }
                    double reach = Baritone.settings().antiCheatCompat.value ? 4.0D : Baritone.settings().builderPlacementReach.value;
                    BlockHitResult hitResult = new BlockHitResult(hit, face, support, false);

                    // 1. First test: natural rotation pointing directly at hit point (most human and GrimAC-safe)
                    Rotation naturalRot = SmoothLookHelper.lookAt(player.getEyePosition(), hit);
                    if (raycastHitsFace(world, player, naturalRot, support, face, reach)) {
                        Direction naturalLook = nearestLook(naturalRot);
                        Direction naturalHoriz = nearestHorizontal(naturalRot.getYaw());
                        SimulatedPlaceContext sim = new SimulatedPlaceContext(
                                player, stack, hitResult, naturalLook, naturalHoriz, interactive);
                        BlockState predicted;
                        try {
                            predicted = want.getBlock().getStateForPlacement(sim);
                        } catch (Exception e) {
                            predicted = null;
                        }
                        if (predicted != null && statesMatch(want, predicted)) {
                            return new PlacementPlan(target.immutable(), want, support.immutable(),
                                    face, hit, naturalRot, interactive);
                        }
                    }

                    // 2. Second test: directional orientation adjustments, strictly guarded by raycast verification
                    for (Direction look : lookOrder) {
                        Rotation rotation = rotationFor(look, hit, player.getEyePosition());
                        // GrimAC guard: NEVER accept a rotation that misses the target block face!
                        if (!raycastHitsFace(world, player, rotation, support, face, reach)) {
                            continue;
                        }
                        Direction horizontal = nearestHorizontal(rotation.getYaw());
                        SimulatedPlaceContext sim = new SimulatedPlaceContext(
                                player, stack, hitResult, look, horizontal, interactive);
                        BlockState predicted;
                        try {
                            predicted = want.getBlock().getStateForPlacement(sim);
                        } catch (Exception e) {
                            predicted = null;
                        }
                        if (predicted != null && statesMatch(want, predicted)) {
                            return new PlacementPlan(target.immutable(), want, support.immutable(),
                                    face, hit, rotation, interactive);
                        }
                    }
                }
            }
            if (allowInteractive) {
                return null; // both passes exhausted
            }
        }
    }

    /** rotationResolver=false: old behavior — first visible support, aim at it. */
    private static PlacementPlan legacyResolve(Level world, LocalPlayer player, BlockPos target, BlockState want) {
        BlockState cur = world.getBlockState(target);
        if (statesMatch(want, cur)) {
            return null;
        }
        if (!(cur.getBlock() instanceof AirBlock) && !isReplaceable(cur) && cur.getFluidState().isEmpty()) {
            return null;
        }
        for (Direction supportDir : SUPPORT_SEARCH_ORDER) {
            BlockPos support = target.relative(supportDir);
            Direction face = supportDir.getOpposite();
            if (!isUsableSupport(world, support, world.getBlockState(support), face)) {
                continue;
            }
            Vec3 hit = hitVecOnFace(support, face, 0.5);
            if (!isVisible(world, player, support, hit)) {
                continue;
            }
            Rotation rot = SmoothLookHelper.lookAt(player.getEyePosition(), hit);
            return new PlacementPlan(target.immutable(), want, support.immutable(), face, hit, rot, false);
        }
        return null;
    }

    // =====================================================================
    // Helpers
    // =====================================================================

    public static net.minecraft.world.item.Item getItemForState(BlockState state) {
        if (state == null) return net.minecraft.world.item.Items.AIR;
        Block b = state.getBlock();
        net.minecraft.world.item.Item item = b.asItem();
        if (item != null && item != net.minecraft.world.item.Items.AIR) {
            return item;
        }
        if (b instanceof RedStoneWireBlock) return net.minecraft.world.item.Items.REDSTONE;
        if (b == Blocks.REDSTONE_WALL_TORCH) return net.minecraft.world.item.Items.REDSTONE_TORCH;
        if (b == Blocks.WALL_TORCH) return net.minecraft.world.item.Items.TORCH;
        if (b == Blocks.SOUL_WALL_TORCH) return net.minecraft.world.item.Items.SOUL_TORCH;
        return net.minecraft.world.item.Items.AIR;
    }

    public static boolean isReplaceable(BlockState state) {
        if (state == null) {
            return true;
        }
        Block b = state.getBlock();
        return b instanceof AirBlock || b instanceof LiquidBlock || state.canBeReplaced();
    }

    /** True if the block at pos is a full cube (for scaffolding support checks). */
    public static boolean isFullCube(BlockState state, Level level, BlockPos pos) {
        return state.isCollisionShapeFullBlock(level, pos);
    }

    /**
     * True if any adjacent face of `spot` belongs to a block that can be right-clicked
     * as a placement support (used for scaffold eligibility check).
     */
    public static boolean hasClickableNeighbor(Level level, BlockPos spot) {
        for (Direction d : Direction.values()) {
            BlockPos n = spot.relative(d);
            BlockState ns = level.getBlockState(n);
            if (isUsableSupport(level, n, ns, d.getOpposite())) {
                return true;
            }
        }
        return false;
    }

    public static boolean isUsableSupport(Level world, BlockPos support, BlockState state, Direction clickedFace) {
        if (state.getBlock() instanceof AirBlock) {
            return false;
        }
        if (!state.getFluidState().isEmpty()) {
            return false; // liquids can't be clicked
        }
        if (isReplaceable(state)) {
            return false; // would place INTO it instead of against it
        }
        return world.hasChunkAt(support);
    }

    /** Click point on a face, nudged 0.001 into the support to avoid boundary ambiguity. */
    private static Vec3 hitVecOnFace(BlockPos support, Direction face, double height) {
        double x = support.getX() + 0.5 + face.getStepX() * 0.499;
        double z = support.getZ() + 0.5 + face.getStepZ() * 0.499;
        double y;
        switch (face) {
            case UP -> y = support.getY() + 0.999;
            case DOWN -> y = support.getY() + 0.001;
            default -> y = support.getY() + height; // side faces: height controls HALF / slab TYPE
        }
        return new Vec3(x, y, z);
    }

    /**
     * Rotation whose nearest/horizontal direction is exactly `look` (so the
     * server's getStateForPlacement sees the same facing we simulated), while
     * aiming as closely at the hit point as those margins allow.
     */
    private static Rotation rotationFor(Direction look, Vec3 hit, Vec3 eye) {
        double dx = hit.x - eye.x, dy = hit.y - eye.y, dz = hit.z - eye.z;
        double horiz = Math.sqrt(dx * dx + dz * dz);
        float yawToHit = (float) Math.toDegrees(Math.atan2(-dx, dz));
        float pitchToHit = (float) -Math.toDegrees(Math.atan2(dy, horiz));

        if (look.getAxis().isHorizontal()) {
            float baseYaw = cardinalYaw(look);
            float dYaw = MthWrap.clampYaw(yawToHit - baseYaw);
            float yaw = baseYaw + net.minecraft.util.Mth.clamp(dYaw, -YAW_MARGIN, YAW_MARGIN);
            float pitch = net.minecraft.util.Mth.clamp(pitchToHit, -PITCH_MARGIN, PITCH_MARGIN);
            return new Rotation(yaw, pitch);
        }
        // Vertical look (observer/piston facing up or down)
        float pitch = look == Direction.UP
                ? net.minecraft.util.Mth.clamp(pitchToHit, -85.0f, -55.0f)
                : net.minecraft.util.Mth.clamp(pitchToHit, 55.0f, 85.0f);
        return new Rotation(net.minecraft.util.Mth.wrapDegrees(yawToHit), pitch);
    }

    private static final class MthWrap {
        static float clampYaw(double d) { return net.minecraft.util.Mth.wrapDegrees((float) d); }
    }

    private static float cardinalYaw(Direction d) {
        return switch (d) {
            case SOUTH -> 0.0f;
            case WEST -> 90.0f;
            case NORTH -> 180.0f;
            case EAST -> -90.0f;
            default -> 0.0f;
        };
    }

    private static Direction nearestHorizontal(float yaw) {
        yaw = net.minecraft.util.Mth.wrapDegrees(yaw);
        if (yaw < -135.0f || yaw >= 135.0f) return Direction.NORTH;
        if (yaw >= 45.0f) return Direction.WEST;
        if (yaw >= -45.0f) return Direction.SOUTH;
        return Direction.EAST;
    }

    /** Try the look directions closest to our current facing first (less turning). */
    private static Direction[] looksOrderedByCurrentRotation(LocalPlayer player) {
        Vec3 look = player.getLookAngle();
        final double lx = look.x, ly = look.y, lz = look.z;
        Direction[] dirs = Direction.values().clone();
        Arrays.sort(dirs, (a, b) -> Double.compare(
                b.getStepX() * lx + b.getStepY() * ly + b.getStepZ() * lz,
                a.getStepX() * lx + a.getStepY() * ly + a.getStepZ() * lz));
        return dirs;
    }

    public static Direction nearestLook(Rotation rot) {
        float pitch = rot.getPitch();
        if (pitch <= -45.0f) {
            return Direction.UP;
        } else if (pitch >= 45.0f) {
            return Direction.DOWN;
        } else {
            return nearestHorizontal(rot.getYaw());
        }
    }

    /**
     * Checks if casting a ray from player's eyes along the given rotation
     * intersects `support` on the expected `face` within reach.
     * Uses COLLIDER to match server-side GrimAC simulation.
     */
    public static boolean raycastHitsFace(Level world, LocalPlayer player, Rotation rot, BlockPos support, Direction face, double reach) {
        Vec3 eye = player.getEyePosition();
        float yawRad = (float) Math.toRadians(rot.getYaw());
        float pitchRad = (float) Math.toRadians(rot.getPitch());
        double xz = Math.cos(pitchRad);
        double dx = -Math.sin(yawRad) * xz;
        double dy = -Math.sin(pitchRad);
        double dz = Math.cos(yawRad) * xz;
        Vec3 end = eye.add(dx * reach, dy * reach, dz * reach);
        BlockHitResult clip = world.clip(new ClipContext(
                eye, end, ClipContext.Block.COLLIDER, ClipContext.Fluid.NONE, player));
        return clip != null
                && clip.getType() == HitResult.Type.BLOCK
                && support.equals(clip.getBlockPos())
                && clip.getDirection() == face;
    }

    /** The straight line eye -> hit must land on the support block (COLLIDER for GrimAC parity). */
    private static boolean isVisible(Level world, LocalPlayer player, BlockPos support, Vec3 hit) {
        Vec3 eye = player.getEyePosition();
        BlockHitResult clip = world.clip(new ClipContext(
                eye, hit, ClipContext.Block.COLLIDER, ClipContext.Fluid.NONE, player));
        return clip != null && support.equals(clip.getBlockPos());
    }

    /**
     * Blocks whose right-click opens a GUI / toggles state. Placing against
     * them requires sneak (over-inclusion is harmless — extra sneak is legal).
     */
    public static boolean requiresSneakToPlaceAgainst(BlockState state) {
        Block b = state.getBlock();
        return b instanceof ChestBlock          // also TrappedChest
                || b instanceof BarrelBlock
                || b instanceof ShulkerBoxBlock
                || b instanceof EnderChestBlock
                || b instanceof HopperBlock
                || b instanceof DispenserBlock   // also Dropper
                || b instanceof CraftingTableBlock
                || b instanceof AbstractFurnaceBlock // furnace / blast / smoker
                || b instanceof CrafterBlock     // 1.21 crafters
                || b instanceof BrewingStandBlock
                || b instanceof LecternBlock
                || b instanceof AnvilBlock
                || b instanceof StonecutterBlock
                || b instanceof CartographyTableBlock
                || b instanceof SmithingTableBlock
                || b instanceof BellBlock
                || b instanceof LeverBlock
                || b instanceof ButtonBlock
                || b instanceof DiodeBlock       // repeaters / comparators
                || b instanceof NoteBlock
                || b instanceof JukeboxBlock
                || b instanceof DoorBlock
                || b instanceof TrapDoorBlock
                || b instanceof FenceGateBlock
                || b instanceof BedBlock
                || b instanceof FlowerPotBlock
                || b instanceof DaylightDetectorBlock;
    }

    /**
     * BlockPlaceContext with a *simulated* look direction, so vanilla's own
     * getStateForPlacement() tells us what the server would place.
     */
    static final class SimulatedPlaceContext extends BlockPlaceContext {
        private final Direction look;
        private final Direction horizontal;
        private final Direction[] nearestDirs;
        private final boolean sneak;
        private final LocalPlayer thePlayer;

        SimulatedPlaceContext(LocalPlayer player, ItemStack stack, BlockHitResult hit,
                              Direction look, Direction horizontal, boolean sneak) {
            super(player, InteractionHand.MAIN_HAND, stack, hit);
            this.look = look;
            this.horizontal = horizontal;
            this.sneak = sneak;
            this.thePlayer = player;
            this.nearestDirs = orderedByLook(look);
        }

        @Override
        public Direction getNearestLookingDirection() {
            return this.look;
        }

        @Override
        public Direction[] getNearestLookingDirections() {
            return this.nearestDirs.clone();
        }

        @Override
        public Direction getHorizontalDirection() {
            return this.horizontal != null ? this.horizontal : this.thePlayer.getDirection();
        }

        @Override
        public boolean isSecondaryUseActive() {
            return this.sneak;
        }
    }

    private static Direction[] orderedByLook(Direction look) {
        final double lx = look.getStepX(), ly = look.getStepY(), lz = look.getStepZ();
        Direction[] dirs = Direction.values().clone();
        Arrays.sort(dirs, (a, b) -> Double.compare(
                b.getStepX() * lx + b.getStepY() * ly + b.getStepZ() * lz,
                a.getStepX() * lx + a.getStepY() * ly + a.getStepZ() * lz));
        return dirs;
    }
}
