package baritone.bypass;

import baritone.Baritone;
import baritone.api.pathing.goals.GoalBlock;
import baritone.api.pathing.goals.GoalStrictDirection;
import baritone.api.pathing.goals.GoalYLevel;
import baritone.api.process.IBaritoneProcess;
import baritone.api.process.PathingCommand;
import baritone.api.process.PathingCommandType;
import baritone.api.utils.BetterBlockPos;
import baritone.api.utils.Rotation;
import baritone.api.utils.RotationUtils;
import baritone.api.utils.input.Input;
import baritone.utils.BaritoneProcessHelper;
import baritone.utils.ContinuousBreakController;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.monster.Enemy;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.material.Fluids;
import net.minecraft.world.phys.Vec3;

import java.nio.file.Path;
import java.util.*;
import java.util.concurrent.ThreadLocalRandom;

/**
 * Główny proces wydobywczy zintegrowany z Baritone z optymalizacjami pod kątem GrimAC.
 * Realizuje branch mining na Y=-55, priorytetyzację rud, unikanie płynów i mobów,
 * oraz procedurę emergency disconnect przy niskim zdrowiu.
 */
public class BypassProcess extends BaritoneProcessHelper implements IBaritoneProcess {

    private final BypassConfig config = new BypassConfig();
    private final List<String> targetOres = new ArrayList<>();
    private final Set<Block> targetOreBlocks = new HashSet<>();

    private boolean active = false;
    private String phase = "idle";
    private int oresMined = 0;
    private Direction tunnelDirection = Direction.NORTH;
    private int segmentLength = 0;
    private int targetSegmentLength = 20;

    private BlockPos currentTargetBlock = null;
    private int settlingTicks = 0;

    public BypassProcess(Baritone baritone) {
        super(baritone);
    }

    public void startMining(List<String> ores) {
        this.targetOres.clear();
        this.targetOres.addAll(ores);
        resolveTargetBlocks();

        if (ctx.player() != null) {
            this.tunnelDirection = ctx.player().getDirection();
        } else {
            this.tunnelDirection = Direction.NORTH;
        }

        this.segmentLength = 0;
        this.targetSegmentLength = config.minLengthBeforeTurn +
                ThreadLocalRandom.current().nextInt(config.maxLengthBeforeTurn - config.minLengthBeforeTurn + 1);
        this.active = true;
        this.phase = "starting";
        logDirect(String.format("§a[Bypass] Uruchomiono kopanie GrimAC-safe dla rud: %s", targetOres));
        logDirect(String.format("§7[Bypass] Poziom Y=%d, wzorzec=%s, kierunek początkowy=%s",
                config.yLevel, config.pattern, tunnelDirection));
    }

    public void resumeFromData(ReconnectData data) {
        if (data == null) return;
        this.targetOres.clear();
        if (data.ores != null) {
            this.targetOres.addAll(data.ores);
        }
        resolveTargetBlocks();
        try {
            this.tunnelDirection = Direction.valueOf(data.tunnel_direction);
        } catch (Exception e) {
            this.tunnelDirection = Direction.NORTH;
        }
        this.oresMined = data.ores_mined;
        this.active = true;
        this.phase = "resumed";
        logDirect(String.format("§a[Bypass] Wznowiono sesję z pliku reconnect_data.json! Wykopano wcześniej: %d rud.", oresMined));
    }

    public void cancel() {
        this.active = false;
        this.phase = "idle";
        this.currentTargetBlock = null;
        this.settlingTicks = 0;
        RotationEngine.reset();
        baritone.getInputOverrideHandler().clearAllKeys();
        logDirect("§e[Bypass] Proces kopania został zatrzymany.");
    }

    @Override
    public boolean isActive() {
        return active;
    }

    @Override
    public PathingCommand onTick(boolean calcFailed, boolean isSafeToCancel) {
        if (!active || ctx.player() == null || ctx.world() == null) {
            return null;
        }

        // 1. KRYTYCZNA OCHRONA (Safety check)
        float health = ctx.player().getHealth();
        Entity nearestEnemy = ctx.entitiesStream()
                .filter(e -> e instanceof Enemy)
                .filter(e -> e.isAlive() && !e.isSpectator())
                .min(Comparator.comparingDouble(e -> e.distanceToSqr(ctx.player())))
                .orElse(null);

        double enemyDist = nearestEnemy != null ? nearestEnemy.distanceTo(ctx.player()) : Double.MAX_VALUE;

        // Warunek awaryjny: health <= 10.0 (5 serc) i wrogi mob <= 20 blokow
        if (health <= config.healthThreshold && enemyDist <= config.mobDcDistance) {
            logDirect(String.format(Locale.ROOT,
                    "§c[Bypass] ZAGROŻENIE ŻYCIA: %.1f HP, wrogi mob w odległości %.1f bloków! Zapisuję sesję i natychmiast rozłączam...",
                    health, enemyDist));
            Path savePath = baritone.getDirectory().resolve(config.saveFile);
            ReconnectData.save(savePath, ctx.player(), new ArrayList<>(targetOres), tunnelDirection.name(), phase, oresMined);
            cancel();
            if (ctx.player().connection != null && ctx.player().connection.getConnection() != null) {
                ctx.player().connection.getConnection().disconnect(Component.literal("§c[Bypass] Auto-disconnect: low health & mob nearby"));
            } else {
                ctx.world().disconnect();
            }
            return new PathingCommand(null, PathingCommandType.CANCEL_AND_SET_GOAL);
        }

        // Reakcja na moby w trakcie kopania
        if (enemyDist <= config.mobRetreat) {
            phase = "retreating_mob";
            BlockPos retreatPos = ctx.playerFeet().relative(tunnelDirection.getOpposite(), 3);
            return new PathingCommand(new GoalBlock(retreatPos), PathingCommandType.FORCE_REVALIDATE_GOAL_AND_PATH);
        } else if (enemyDist <= config.mobStop) {
            phase = "paused_mob";
            baritone.getInputOverrideHandler().clearAllKeys();
            return new PathingCommand(null, PathingCommandType.REQUEST_PAUSE);
        }

        // 2. OSIĄGANIE DOCELOWEGO POZIOMU Y (-55)
        int currentY = ctx.playerFeet().getY();
        if (currentY != config.yLevel) {
            phase = "descending";
            return new PathingCommand(new GoalYLevel(config.yLevel), PathingCommandType.REVALIDATE_GOAL_AND_PATH);
        }

        // 3. SKANOWANIE RUD W PROMIENIU 5 BLOKÓW
        BlockPos bestOrePos = findBestOreNearby(ctx.playerFeet(), config.oreRadius);
        if (bestOrePos != null) {
            phase = "mining_ore";
            currentTargetBlock = bestOrePos;

            // Jeśli ruda jest w zasięgu kopania
            if (RotationUtils.reachable(ctx, bestOrePos, config.reachLimit).isPresent()) {
                Vec3 targetCenter = Vec3.atCenterOf(bestOrePos);
                Rotation rot = RotationEngine.lookAt(ctx.player().getEyePosition(), targetCenter);
                RotationEngine.apply(ctx.player(), rot, baritone.settings());

                if (RotationEngine.isSettled(config.preRotationTicks)) {
                    baritone.getInputOverrideHandler().setInputForceState(Input.CLICK_LEFT, true);
                    ContinuousBreakController.notifyBreaking(ctx, new BetterBlockPos(bestOrePos));

                    // Jeśli blok został właśnie skasowany
                    if (ctx.world().getBlockState(bestOrePos).isAir()) {
                        RotationEngine.notifyBlockBroken(bestOrePos);
                        oresMined++;
                        baritone.getInputOverrideHandler().setInputForceState(Input.CLICK_LEFT, false);
                        currentTargetBlock = null;
                    }
                }
                return new PathingCommand(null, PathingCommandType.REQUEST_PAUSE);
            } else {
                return new PathingCommand(new GoalBlock(bestOrePos), PathingCommandType.REVALIDATE_GOAL_AND_PATH);
            }
        }

        // 4. TUNELOWANIE 1x2 (Wysokość 2, Szerokość 1) NA POZIOMIE Y
        phase = "tunneling";
        BlockPos feetAhead = ctx.playerFeet().relative(tunnelDirection);
        BlockPos headAhead = feetAhead.above();

        // Sprawdź czy przed nami nie ma płynów (lawa/woda)
        if (isAdjacentToFluid(feetAhead) || isAdjacentToFluid(headAhead)) {
            logDirect("§6[Bypass] Wykryto płyn (lawa/woda) na drodze tunelu. Wykonuję bezpieczny zwrot...");
            turnTunnel();
            return new PathingCommand(null, PathingCommandType.REQUEST_PAUSE);
        }

        // Sprawdź czy korytarz wymaga przekopania
        BlockState stateFeet = ctx.world().getBlockState(feetAhead);
        BlockState stateHead = ctx.world().getBlockState(headAhead);

        BlockPos toBreak = null;
        if (!stateHead.isAir() && !stateHead.canBeReplaced()) {
            toBreak = headAhead;
        } else if (!stateFeet.isAir() && !stateFeet.canBeReplaced()) {
            toBreak = feetAhead;
        }

        if (toBreak != null) {
            Vec3 targetCenter = Vec3.atCenterOf(toBreak);
            Rotation rot = RotationEngine.lookAt(ctx.player().getEyePosition(), targetCenter);
            RotationEngine.apply(ctx.player(), rot, baritone.settings());

            if (RotationEngine.isSettled(config.preRotationTicks)) {
                baritone.getInputOverrideHandler().setInputForceState(Input.CLICK_LEFT, true);
                ContinuousBreakController.notifyBreaking(ctx, new BetterBlockPos(toBreak));

                if (ctx.world().getBlockState(toBreak).isAir()) {
                    RotationEngine.notifyBlockBroken(toBreak);
                    baritone.getInputOverrideHandler().setInputForceState(Input.CLICK_LEFT, false);
                    segmentLength++;
                    checkBranchTurn();
                }
            }
            return new PathingCommand(null, PathingCommandType.REQUEST_PAUSE);
        }

        // Przejście w przód tunelu
        baritone.getInputOverrideHandler().setInputForceState(Input.CLICK_LEFT, false);
        return new PathingCommand(new GoalStrictDirection(ctx.playerFeet(), tunnelDirection), PathingCommandType.REVALIDATE_GOAL_AND_PATH);
    }

    private void checkBranchTurn() {
        if (segmentLength >= targetSegmentLength) {
            ThreadLocalRandom rng = ThreadLocalRandom.current();
            if (rng.nextDouble() < config.branchChance) {
                turnTunnel();
            } else {
                targetSegmentLength += 10;
            }
        }
    }

    private void turnTunnel() {
        ThreadLocalRandom rng = ThreadLocalRandom.current();
        tunnelDirection = rng.nextBoolean() ? tunnelDirection.getClockWise() : tunnelDirection.getCounterClockWise();
        segmentLength = 0;
        targetSegmentLength = config.minLengthBeforeTurn +
                rng.nextInt(config.maxLengthBeforeTurn - config.minLengthBeforeTurn + 1);
        logDirect(String.format("§a[Bypass] Nowa odnoga tunelu w kierunku: %s (długość: %d)", tunnelDirection, targetSegmentLength));
    }

    private boolean isAdjacentToFluid(BlockPos pos) {
        net.minecraft.world.level.Level level = ctx.world();
        if (level == null) return false;
        for (Direction dir : Direction.values()) {
            BlockState adj = level.getBlockState(pos.relative(dir));
            if (config.lavaAvoid && (adj.is(Blocks.LAVA) || adj.getFluidState().is(Fluids.LAVA) || adj.getFluidState().is(Fluids.FLOWING_LAVA))) {
                return true;
            }
            if (config.waterAvoid && (adj.is(Blocks.WATER) || adj.getFluidState().is(Fluids.WATER) || adj.getFluidState().is(Fluids.FLOWING_WATER))) {
                return true;
            }
        }
        return false;
    }

    private BlockPos findBestOreNearby(BlockPos center, int radius) {
        if (targetOreBlocks.isEmpty() || ctx.world() == null) return null;
        net.minecraft.world.level.Level level = ctx.world();

        BlockPos bestPos = null;
        int bestPriority = Integer.MAX_VALUE;
        double bestDistSq = Double.MAX_VALUE;

        int cx = center.getX();
        int cy = center.getY();
        int cz = center.getZ();

        for (int dx = -radius; dx <= radius; dx++) {
            for (int dy = -radius; dy <= radius; dy++) {
                for (int dz = -radius; dz <= radius; dz++) {
                    if (dx * dx + dy * dy + dz * dz > radius * radius) continue;
                    BlockPos p = new BlockPos(cx + dx, cy + dy, cz + dz);
                    BlockState state = level.getBlockState(p);
                    Block b = state.getBlock();

                    if (targetOreBlocks.contains(b)) {
                        int prio = getOrePriority(b);
                        double dSq = p.distSqr(center);
                        if (prio < bestPriority || (prio == bestPriority && dSq < bestDistSq)) {
                            bestPriority = prio;
                            bestDistSq = dSq;
                            bestPos = p;
                        }
                    }
                }
            }
        }
        return bestPos;
    }

    private int getOrePriority(Block block) {
        ResourceLocation key = BuiltInRegistries.BLOCK.getKey(block);
        if (key == null) return Integer.MAX_VALUE;
        String path = key.getPath();

        for (int i = 0; i < config.priority.size(); i++) {
            String ore = config.priority.get(i);
            List<String> validNames = config.getOreBlockNames(ore);
            if (validNames.contains(path)) {
                return i;
            }
        }
        return Integer.MAX_VALUE;
    }

    private void resolveTargetBlocks() {
        targetOreBlocks.clear();
        for (String oreName : targetOres) {
            List<String> names = config.getOreBlockNames(oreName);
            for (String name : names) {
                ResourceLocation loc = ResourceLocation.tryParse(name.contains(":") ? name : "minecraft:" + name);
                if (loc != null) {
                    BuiltInRegistries.BLOCK.getOptional(loc).ifPresent(targetOreBlocks::add);
                }
            }
        }
    }

    @Override
    public boolean isTemporary() {
        return false;
    }

    @Override
    public void onLostControl() {
        cancel();
    }

    @Override
    public double priority() {
        return DEFAULT_PRIORITY + 2.0D;
    }

    @Override
    public String displayName0() {
        return String.format("BYPASS [%s] (%d mined)", phase.toUpperCase(Locale.ROOT), oresMined);
    }

    // Gettery do statusu
    public BypassConfig getConfig() { return config; }
    public List<String> getTargetOres() { return Collections.unmodifiableList(targetOres); }
    public int getOresMined() { return oresMined; }
    public String getPhase() { return phase; }
    public Direction getTunnelDirection() { return tunnelDirection; }
}
