package baritone.utils.builder;

import baritone.api.BaritoneAPI;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.util.Mth;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.context.BlockPlaceContext;
import net.minecraft.world.level.ClipContext;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.*;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.BlockStateProperties;
import net.minecraft.world.level.block.state.properties.Property;
import net.minecraft.world.level.block.state.properties.RailShape;
import net.minecraft.world.level.block.state.properties.SlabType;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;

/**
 * Vantage point solver for orientation dependent blocks (stairs, slabs, trapdoors, pistons,
 * observers, repeaters, rails, logs, ...).
 */
public final class VantagePointSolver {

    private VantagePointSolver() {
    }

    // ------------------------------------------------------------------ tunables

    public static final double MIN_REACH_STRICT = 1.2D;
    public static final double MIN_REACH_RELAXED = 0.5D;

    private static final double EYE_STANDING = 1.62D;
    private static final double EYE_SNEAKING = 1.27D;
    private static final double HALF_WIDTH = 0.3D;
    private static final double PLAYER_HEIGHT = 1.8D;

    private static final int RADIUS_XZ = 4;
    private static final int BELOW = 4;
    private static final int ABOVE = 2;

    private static final double[] SIDE_OFFSETS = {0.25, 0.75, 0.35, 0.65, 0.15, 0.85};
    private static final double[] FREE_OFFSETS = {0.5, 0.25, 0.75, 0.35, 0.65, 0.15, 0.85};

    private static final double[][] JITTER = {{0.25, 0.25}, {-0.25, 0.25}, {0.25, -0.25}, {-0.25, -0.25}};

    private static final Property<?>[] ORIENTATION_PROPS = {
            BlockStateProperties.FACING,
            BlockStateProperties.HORIZONTAL_FACING,
            BlockStateProperties.FACING_HOPPER,
            BlockStateProperties.HALF,
            BlockStateProperties.SLAB_TYPE,
            BlockStateProperties.AXIS,
            BlockStateProperties.HORIZONTAL_AXIS,
            BlockStateProperties.ROTATION_16,
            BlockStateProperties.ATTACH_FACE,
            BlockStateProperties.DOOR_HINGE,
            BlockStateProperties.ORIENTATION,
            BlockStateProperties.RAIL_SHAPE,
            BlockStateProperties.RAIL_SHAPE_STRAIGHT,
            BlockStateProperties.BED_PART
    };

    public record Aim(BlockPos support, Direction face, Vec3 hitVec, float yaw, float pitch,
                      boolean needsSneak, double distance, double quality) {
    }

    public record VantagePoint(BlockPos stand, Aim aim, double score) {
    }

    public static BlockPos findVantagePoint(Level world, LocalPlayer player, BlockPos target, BlockState desiredState) {
        return solve(world, player, target, desiredState).map(VantagePoint::stand).orElse(null);
    }

    public static BlockPos findVantagePoint(Level world, LocalPlayer player, BlockPos target, BlockState desiredState,
                                            BlockStateResolver.PlacementPlan plan) {
        return findVantagePoint(world, player, target, desiredState);
    }

    public static Optional<VantagePoint> solve(Level world, LocalPlayer player, BlockPos target, BlockState desired) {
        double max = maxReach(player);
        Optional<VantagePoint> strict = search(world, player, target, desired, MIN_REACH_STRICT, max);
        if (strict.isPresent()) {
            return strict;
        }
        return search(world, player, target, desired, MIN_REACH_RELAXED, max);
    }

    public static double maxReach(LocalPlayer player) {
        return Math.min(BaritoneAPI.getSettings().builderPlacementReach.value, player.blockInteractionRange() - 0.25D);
    }

    public static Optional<Aim> resolveAim(Level world, Entity viewer, Vec3 feet, BlockPos target, BlockState desired,
                                           double minReach, double maxReach) {
        Item item = desired.getBlock().asItem();
        if (item == Items.AIR) {
            return Optional.empty();
        }
        if (playerBox(feet).intersects(new AABB(target))) {
            return Optional.empty();
        }
        ItemStack stack = new ItemStack(item);
        Aim best = null;

        for (Direction toSupport : Direction.values()) {
            BlockPos support = target.relative(toSupport);
            BlockState sState = world.getBlockState(support);
            if (sState.isAir() || sState.canBeReplaced() || sState.getShape(world, support).isEmpty()) {
                continue;
            }
            Direction face = toSupport.getOpposite();
            boolean sneak = isInteractive(sState);
            Vec3 eye = feet.add(0, sneak ? EYE_SNEAKING : EYE_STANDING, 0);

            if (eye.distanceTo(facePoint(support, face, 0.5, 0.5)) > maxReach + 0.9D) {
                continue;
            }

            Vec3 normal = new Vec3(face.getStepX(), face.getStepY(), face.getStepZ());
            double[] as;
            double[] bs;
            switch (face.getAxis()) {
                case Y -> {
                    as = FREE_OFFSETS;
                    bs = FREE_OFFSETS;
                }
                case X -> {
                    as = SIDE_OFFSETS;
                    bs = FREE_OFFSETS;
                }
                default -> {
                    as = FREE_OFFSETS;
                    bs = SIDE_OFFSETS;
                }
            }

            for (double a : as) {
                for (double b : bs) {
                    Vec3 hit = facePoint(support, face, a, b);
                    Vec3 view = hit.subtract(eye);
                    double dist = view.length();
                    if (dist < minReach || dist > maxReach) {
                        continue;
                    }
                    if (view.dot(normal) > -1.0E-3D) {
                        continue;
                    }
                    float yaw = Mth.wrapDegrees((float) (Mth.atan2(view.z, view.x) * 180.0D / Math.PI) - 90.0F);
                    double horiz = Math.sqrt(view.x * view.x + view.z * view.z);
                    float pitch = Mth.clamp((float) -(Mth.atan2(view.y, horiz) * 180.0D / Math.PI), -90.0F, 90.0F);

                    if (!simulatesTo(world, viewer, stack, desired, target, support, face, hit, yaw, pitch, sneak)) {
                        continue;
                    }
                    if (!clipHits(world, viewer, eye, view, hit, support, face)) {
                        continue;
                    }
                    double quality = dist + Math.abs(a - 0.5D) + Math.abs(b - 0.5D) + (sneak ? 3.0D : 0.0D);
                    if (best == null || quality < best.quality()) {
                        best = new Aim(support, face, hit, yaw, pitch, sneak, dist, quality);
                    }
                }
            }
        }
        return Optional.ofNullable(best);
    }

    public static Optional<BlockHitResult> verifyRay(Level world, Entity viewer, Vec3 eye, float yaw, float pitch,
                                                     double maxReach, BlockPos support, Direction face) {
        Vec3 look = viewVector(yaw, pitch);
        Vec3 end = eye.add(look.scale(maxReach));
        BlockHitResult r = world.clip(new ClipContext(eye, end, ClipContext.Block.OUTLINE, ClipContext.Fluid.NONE, viewer));
        if (r.getType() != HitResult.Type.BLOCK || !r.getBlockPos().equals(support) || r.getDirection() != face) {
            return Optional.empty();
        }
        Vec3 loc = r.getLocation();
        if (eye.distanceTo(loc) > maxReach) {
            return Optional.empty();
        }
        double fx = loc.x - support.getX();
        double fy = loc.y - support.getY();
        double fz = loc.z - support.getZ();
        double[] fr = switch (face.getAxis()) {
            case X -> new double[]{fy, fz};
            case Y -> new double[]{fx, fz};
            case Z -> new double[]{fx, fy};
        };
        for (double f : fr) {
            if (f < 0.05D || f > 0.95D) {
                return Optional.empty();
            }
        }
        return Optional.of(r);
    }

    public static boolean needsVantage(BlockState desired) {
        if (desired.getBlock() instanceof BaseRailBlock) {
            return true;
        }
        for (Property<?> p : ORIENTATION_PROPS) {
            if (!desired.hasProperty(p)) {
                continue;
            }
            if (p == BlockStateProperties.SLAB_TYPE && desired.getValue(BlockStateProperties.SLAB_TYPE) == SlabType.DOUBLE) {
                continue;
            }
            return true;
        }
        return false;
    }

    public static int placementPhase(BlockState s) {
        Block b = s.getBlock();
        if (b instanceof BaseRailBlock) {
            RailShape shape = null;
            if (s.hasProperty(BlockStateProperties.RAIL_SHAPE)) {
                shape = s.getValue(BlockStateProperties.RAIL_SHAPE);
            } else if (s.hasProperty(BlockStateProperties.RAIL_SHAPE_STRAIGHT)) {
                shape = s.getValue(BlockStateProperties.RAIL_SHAPE_STRAIGHT);
            }
            return shape != null && shape.isSlope() ? 4 : 3;
        }
        if (b instanceof DiodeBlock || b instanceof RedStoneWireBlock || b instanceof ButtonBlock
                || b instanceof LeverBlock || b instanceof BasePressurePlateBlock) {
            return 5;
        }
        if (needsVantage(s)) {
            return 2;
        }
        return s.canOcclude() ? 0 : 1;
    }

    public static boolean hasAnySupport(Level world, BlockPos target) {
        for (Direction d : Direction.values()) {
            BlockPos p = target.relative(d);
            BlockState s = world.getBlockState(p);
            if (!s.isAir() && !s.canBeReplaced() && !s.getShape(world, p).isEmpty()) {
                return true;
            }
        }
        return false;
    }

    public static boolean railSupportReady(Level world, BlockPos railPos) {
        BlockPos below = railPos.below();
        return world.getBlockState(below).isFaceSturdy(world, below, Direction.UP);
    }

    public static boolean isInteractive(BlockState s) {
        Block b = s.getBlock();
        return b instanceof ChestBlock || b instanceof EnderChestBlock || b instanceof AbstractFurnaceBlock
                || b instanceof TrapDoorBlock || b instanceof DoorBlock || b instanceof FenceGateBlock
                || b instanceof HopperBlock || b instanceof CraftingTableBlock || b instanceof BarrelBlock
                || b instanceof ShulkerBoxBlock || b instanceof DispenserBlock || b instanceof ButtonBlock
                || b instanceof LeverBlock || b instanceof DiodeBlock || b instanceof NoteBlock
                || b instanceof AnvilBlock || b instanceof BedBlock || b instanceof BeaconBlock
                || b instanceof BrewingStandBlock || b instanceof LecternBlock || b instanceof LoomBlock
                || b instanceof GrindstoneBlock || b instanceof StonecutterBlock
                || b instanceof CartographyTableBlock || b instanceof SmithingTableBlock
                || b instanceof EnchantingTableBlock || b instanceof JukeboxBlock || b instanceof ComposterBlock
                || b instanceof CommandBlock || b instanceof CrafterBlock || b instanceof ChiseledBookShelfBlock
                || b instanceof DecoratedPotBlock || b instanceof BellBlock || b instanceof AbstractCauldronBlock
                || b instanceof CakeBlock || b instanceof DaylightDetectorBlock || b instanceof RedStoneWireBlock;
    }

    private static Optional<VantagePoint> search(Level world, LocalPlayer player, BlockPos target, BlockState desired,
                                                 double minReach, double maxReach) {
        List<BlockPos> standCandidates = new ArrayList<>();
        Vec3 playerPos = player.position();
        BlockPos.MutableBlockPos stand = new BlockPos.MutableBlockPos();

        for (int dx = -RADIUS_XZ; dx <= RADIUS_XZ; dx++) {
            for (int dz = -RADIUS_XZ; dz <= RADIUS_XZ; dz++) {
                if (Math.sqrt(dx * dx + dz * dz) > maxReach + 1.5D) {
                    continue;
                }
                for (int dy = -BELOW; dy <= ABOVE; dy++) {
                    stand.set(target.getX() + dx, target.getY() + dy, target.getZ() + dz);
                    if (isStandable(world, stand)) {
                        standCandidates.add(stand.immutable());
                    }
                }
            }
        }
        if (standCandidates.isEmpty()) {
            return Optional.empty();
        }
        standCandidates.sort(Comparator.comparingDouble(p -> p.distToCenterSqr(playerPos.x, playerPos.y, playerPos.z)));

        List<VantagePoint> candidates = new ArrayList<>();
        for (BlockPos pos : standCandidates) {
            Vec3 feet = new Vec3(pos.getX() + 0.5D, pos.getY(), pos.getZ() + 0.5D);
            Optional<Aim> aim = resolveAim(world, player, feet, target, desired, minReach, maxReach);
            if (aim.isEmpty()) {
                continue;
            }
            double score = playerPos.distanceTo(feet)
                    + Math.abs(pos.getY() - player.getBlockY()) * 1.5D
                    + aim.get().quality() * 0.25D;
            candidates.add(new VantagePoint(pos, aim.get(), score));
            if (candidates.size() >= 12) {
                break;
            }
        }
        if (candidates.isEmpty()) {
            return Optional.empty();
        }
        candidates.sort(Comparator.comparingDouble(VantagePoint::score));

        VantagePoint best = null;
        double bestScore = Double.MAX_VALUE;
        for (int i = 0; i < Math.min(8, candidates.size()); i++) {
            VantagePoint c = candidates.get(i);
            Vec3 feet = new Vec3(c.stand().getX() + 0.5D, c.stand().getY(), c.stand().getZ() + 0.5D);
            int ok = 0;
            for (double[] j : JITTER) {
                if (resolveAim(world, player, feet.add(j[0], 0, j[1]), target, desired, minReach, maxReach).isPresent()) {
                    ok++;
                }
            }
            double s = c.score() - ok * 0.75D;
            if (s < bestScore) {
                bestScore = s;
                best = c;
            }
        }
        return Optional.ofNullable(best);
    }

    private static boolean isStandable(Level world, BlockPos feet) {
        BlockPos head = feet.above();
        BlockPos floorPos = feet.below();
        BlockState floor = world.getBlockState(floorPos);
        return passable(world, feet) && passable(world, head)
                && floor.isFaceSturdy(world, floorPos, Direction.UP) && !isHazard(floor);
    }

    private static boolean passable(Level world, BlockPos pos) {
        BlockState s = world.getBlockState(pos);
        return s.getCollisionShape(world, pos).isEmpty() && s.getFluidState().isEmpty() && !isHazard(s);
    }

    private static boolean isHazard(BlockState s) {
        return s.is(Blocks.FIRE) || s.is(Blocks.SOUL_FIRE) || s.is(Blocks.MAGMA_BLOCK) || s.is(Blocks.CACTUS)
                || s.is(Blocks.CAMPFIRE) || s.is(Blocks.SOUL_CAMPFIRE) || s.is(Blocks.COBWEB)
                || s.is(Blocks.SWEET_BERRY_BUSH) || s.is(Blocks.POWDER_SNOW) || s.is(Blocks.WITHER_ROSE);
    }

    private static boolean simulatesTo(Level world, Entity viewer, ItemStack stack, BlockState desired, BlockPos target,
                                       BlockPos support, Direction face, Vec3 hit, float yaw, float pitch, boolean sneak) {
        try {
            SimContext ctx = new SimContext(world, viewer, stack, new BlockHitResult(hit, face, support, false), yaw, pitch, sneak);
            if (!ctx.getClickedPos().equals(target) || !ctx.canPlace()) {
                return false;
            }
            BlockState placed = desired.getBlock().getStateForPlacement(ctx);
            if (placed == null || !orientationMatches(placed, desired)) {
                return false;
            }
            return placed.canSurvive(world, target);
        } catch (RuntimeException e) {
            return false;
        }
    }

    private static boolean orientationMatches(BlockState placed, BlockState desired) {
        if (placed.getBlock() != desired.getBlock()) {
            return false;
        }
        for (Property<?> p : ORIENTATION_PROPS) {
            if (!desired.hasProperty(p) || !placed.hasProperty(p)) {
                continue;
            }
            Object want = desired.getValue(p);
            if (want == SlabType.DOUBLE) {
                continue;
            }
            if (!want.equals(placed.getValue(p))) {
                return false;
            }
        }
        return true;
    }

    private static boolean clipHits(Level world, Entity viewer, Vec3 eye, Vec3 view, Vec3 hit, BlockPos support, Direction face) {
        Vec3 end = hit.add(view.normalize().scale(0.05D));
        BlockHitResult r = world.clip(new ClipContext(eye, end, ClipContext.Block.COLLIDER, ClipContext.Fluid.NONE, viewer));
        return r.getType() == HitResult.Type.BLOCK
                && r.getBlockPos().equals(support)
                && r.getDirection() == face
                && r.getLocation().distanceToSqr(hit) < 0.04D;
    }

    private static AABB playerBox(Vec3 feet) {
        return new AABB(feet.x - HALF_WIDTH, feet.y, feet.z - HALF_WIDTH,
                feet.x + HALF_WIDTH, feet.y + PLAYER_HEIGHT, feet.z + HALF_WIDTH);
    }

    private static Vec3 facePoint(BlockPos p, Direction face, double a, double b) {
        return switch (face.getAxis()) {
            case Y -> new Vec3(p.getX() + a, p.getY() + (face == Direction.UP ? 1.0D : 0.0D), p.getZ() + b);
            case X -> new Vec3(p.getX() + (face == Direction.EAST ? 1.0D : 0.0D), p.getY() + a, p.getZ() + b);
            case Z -> new Vec3(p.getX() + a, p.getY() + b, p.getZ() + (face == Direction.SOUTH ? 1.0D : 0.0D));
        };
    }

    private static Vec3 viewVector(float yaw, float pitch) {
        float f = pitch * 0.017453292F;
        float g = -yaw * 0.017453292F;
        float cg = Mth.cos(g);
        float sg = Mth.sin(g);
        float cf = Mth.cos(f);
        float sf = Mth.sin(f);
        return new Vec3(sg * cf, -sf, cg * cf);
    }

    private static final class SimContext extends BlockPlaceContext {
        private final float yaw;
        private final boolean sneaking;
        private final Direction horizontal;
        private final Direction vertical;
        private final Direction[] ordered;

        SimContext(Level world, Entity viewer, ItemStack stack, BlockHitResult hit, float yaw, float pitch, boolean sneaking) {
            super(world, (viewer instanceof net.minecraft.world.entity.player.Player p) ? p : null, InteractionHand.MAIN_HAND, stack, hit);
            this.yaw = yaw;
            this.sneaking = sneaking;
            this.horizontal = Direction.fromYRot(yaw);
            this.vertical = pitch < 0.0F ? Direction.UP : Direction.DOWN;
            final Vec3 look = viewVector(yaw, pitch);
            Direction[] dirs = Direction.values().clone();
            Arrays.sort(dirs, Comparator.comparingDouble((Direction d) ->
                    -(look.x * d.getStepX() + look.y * d.getStepY() + look.z * d.getStepZ())));
            this.ordered = dirs;
        }

        @Override
        public Direction getHorizontalDirection() {
            return horizontal;
        }

        @Override
        public Direction getNearestLookingDirection() {
            return ordered[0];
        }



        @Override
        public Direction[] getNearestLookingDirections() {
            Direction[] arr = ordered.clone();
            if (this.replaceClicked) {
                return arr;
            }
            Direction opposite = getClickedFace().getOpposite();
            int i = 0;
            while (i < arr.length && arr[i] != opposite) {
                i++;
            }
            if (i > 0 && i < arr.length) {
                System.arraycopy(arr, 0, arr, 1, i);
                arr[0] = opposite;
            }
            return arr;
        }

        @Override
        public float getRotation() {
            return yaw;
        }

        @Override
        public boolean isSecondaryUseActive() {
            return sneaking;
        }
    }
}
