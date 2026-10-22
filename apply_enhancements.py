import sys

path = r'c:\Users\Administrator\Desktop\Gry i Mody\baritone-1.21.4\baritone-1.21.4\src\main\java\baritone\process\FarmProcess.java'
with open(path, 'r', encoding='utf-8', errors='replace') as f:
    content = f.read()

# 1. Imports check: ensure Long2IntOpenHashMap, Long2LongOpenHashMap are imported
if 'import it.unimi.dsi.fastutil.longs.Long2IntOpenHashMap;' not in content:
    content = content.replace(
        'import it.unimi.dsi.fastutil.longs.LongOpenHashSet;',
        'import it.unimi.dsi.fastutil.longs.LongOpenHashSet;\nimport it.unimi.dsi.fastutil.longs.Long2IntOpenHashMap;\nimport it.unimi.dsi.fastutil.longs.Long2LongOpenHashMap;'
    )

# 2. Add instance fields below harvestQueue and plantQueue
old_queues = '''    private final java.util.ArrayDeque<BlockPos> harvestQueue = new java.util.ArrayDeque<>(64);
    private final java.util.ArrayDeque<BlockPos> plantQueue   = new java.util.ArrayDeque<>(64);
    private int queueVersion = 0; // inkrementowany przy każdym nowym scanie, żeby unieważnić starą kolejkę'''

new_queues = '''    private final java.util.ArrayDeque<BlockPos> harvestQueue = new java.util.ArrayDeque<>(64);
    private final java.util.ArrayDeque<BlockPos> plantQueue   = new java.util.ArrayDeque<>(64);
    private int queueVersion = 0; // inkrementowany przy każdym nowym scanie, żeby unieważnić starą kolejkę

    // ── STABILIZACJA CELU A* (Row Anchor — rekomendacja Argon 4) ──
    private BlockPos stableRowAnchorPos = null;
    private Goal stableRowGoal = null;
    private int stableRowGoalTicks = 0;
    private int sweepDirection = 1;
    private float lastHarvestYaw = Float.NaN;

    private final Long2IntOpenHashMap failedPlantClicks = new Long2IntOpenHashMap();
    private final Long2LongOpenHashMap unplantableBlacklist = new Long2LongOpenHashMap();
    private static final long UNPLANTABLE_TTL_TICKS = 2400L;
    private static final int MAX_PLANT_FAIL_CLICKS = 4;'''

assert old_queues in content, 'old_queues not found'
content = content.replace(old_queues, new_queues, 1)

# 3. Reset in farm(), resetWorkState(), onLostControl()
old_farm_reset = '''        harvestQueue.clear();
        plantQueue.clear();
        queueVersion++;'''

new_farm_reset = '''        harvestQueue.clear();
        plantQueue.clear();
        queueVersion++;
        stableRowAnchorPos = null;
        stableRowGoal = null;
        stableRowGoalTicks = 0;
        sweepDirection = 1;
        lastHarvestYaw = Float.NaN;
        failedPlantClicks.clear();
        unplantableBlacklist.clear();'''

assert old_farm_reset in content, 'old_farm_reset not found'
content = content.replace(old_farm_reset, new_farm_reset, 1)

old_reset_state = '''        harvestQueue.clear();
        plantQueue.clear();
        queueVersion++;'''

new_reset_state = '''        harvestQueue.clear();
        plantQueue.clear();
        queueVersion++;
        stableRowAnchorPos = null;
        stableRowGoal = null;
        stableRowGoalTicks = 0;
        sweepDirection = 1;
        lastHarvestYaw = Float.NaN;
        failedPlantClicks.clear();'''

assert old_reset_state in content, 'old_reset_state not found'
content = content.replace(old_reset_state, new_reset_state, 1)

old_lost_control = '''        harvestQueue.clear();
        plantQueue.clear();
        queueVersion++;
        baritone.getInputOverrideHandler().clearAllKeys();'''

new_lost_control = '''        harvestQueue.clear();
        plantQueue.clear();
        queueVersion++;
        stableRowAnchorPos = null;
        stableRowGoal = null;
        stableRowGoalTicks = 0;
        sweepDirection = 1;
        lastHarvestYaw = Float.NaN;
        failedPlantClicks.clear();
        unplantableBlacklist.clear();
        baritone.getInputOverrideHandler().clearAllKeys();'''

assert old_lost_control in content, 'old_lost_control not found'
content = content.replace(old_lost_control, new_lost_control, 1)

# 4. applyAimRotation (blockMovement = false)
old_aim_rot = '''    private void applyAimRotation(Rotation targetRot, boolean fastMode) {
        if (fastMode) {
            // Delegate entirely to LookBehavior so only one rotation packet
            // is sent per tick through the normal game loop.
            // Sending an extra ServerboundMovePlayerPacket.Rot here caused
            // GrimAC TickTimer(flying, packets=2) and AimDuplicateLook flags.
            baritone.getLookBehavior().updateTarget(targetRot, true);
        } else {
            baritone.getLookBehavior().updateTarget(limitedTurn(targetRot), true);
        }
    }'''

new_aim_rot = '''    private void applyAimRotation(Rotation targetRot, boolean fastMode) {
        if (fastMode) {
            // blockMovement = false gwarantuje, ze gracz nie wyhamowuje sprintu podczas obracania kamery
            baritone.getLookBehavior().updateTarget(targetRot, false);
        } else {
            baritone.getLookBehavior().updateTarget(limitedTurn(targetRot), false);
        }
    }

    private Vec3 getCropAimVec(BlockPos p) {
        Vec3 head = ctx.playerHead();
        BlockState state = ctx.world().getBlockState(p);
        double horizDist = Math.hypot((p.getX() + 0.5D) - head.x, (p.getZ() + 0.5D) - head.z);

        double targetY = switch (state.getBlock()) {
            case CropBlock ignored -> horizDist < 2.1D ? p.getY() + 0.32D : p.getY() + 0.52D;
            case NetherWartBlock ignored -> p.getY() + 0.40D;
            case CocoaBlock ignored -> p.getY() + 0.55D;
            default -> p.getY() + 0.45D;
        };

        double dx = Mth.clamp(head.x - (p.getX() + 0.5D), -0.26D, 0.26D);
        double dz = Mth.clamp(head.z - (p.getZ() + 0.5D), -0.26D, 0.26D);
        return new Vec3(p.getX() + 0.5D + dx, targetY, p.getZ() + 0.5D + dz);
    }

    private Vec3 getPlantAimVec(BlockPos farmlandPos) {
        Vec3 head = ctx.playerHead();
        double dx = Mth.clamp(head.x - (farmlandPos.getX() + 0.5D), -0.28D, 0.28D);
        double dz = Mth.clamp(head.z - (farmlandPos.getZ() + 0.5D), -0.28D, 0.28D);
        return new Vec3(farmlandPos.getX() + 0.5D + dx, farmlandPos.getY() + 0.9375D, farmlandPos.getZ() + 0.5D + dz);
    }

    private void updateSweepState(Rotation targetRotation) {
        float currentYaw = ctx.playerRotations().getYaw();
        if (!Float.isNaN(lastHarvestYaw)) {
            float delta = Mth.wrapDegrees(targetRotation.getYaw() - currentYaw);
            if (Math.abs(delta) > 1.5F) {
                sweepDirection = delta > 0.0F ? 1 : -1;
            }
        }
        lastHarvestYaw = currentYaw;
    }

    private boolean selectSeedsForPlanting() {
        if (ctx.player() == null) return false;
        boolean selected = (replantItem != null
                && baritone.getInventoryBehavior().throwaway(true, this::isReplantItem, true))
                || baritone.getInventoryBehavior().throwaway(true, this::isPlantable, true);
        if (selected) {
            ctx.playerController().syncHeldItem();
        }
        ItemStack held = ctx.player().getMainHandItem();
        return !held.isEmpty()
                && !(held.getItem() instanceof DiggerItem)
                && !(held.getItem() instanceof SwordItem)
                && isPlantable(held);
    }

    private boolean hasSufficientLightForCrop(BlockPos farmlandPos) {
        Level world = ctx.world();
        if (world == null) {
            return false;
        }
        BlockPos cropPos = farmlandPos.above();
        return world.getRawBrightness(cropPos, 0) >= 8 || world.canSeeSky(cropPos);
    }

    private boolean isPlantableFarmland(BlockPos farmlandPos) {
        long key = farmlandPos.asLong();
        if (unplantableBlacklist.containsKey(key)) {
            if (farmTicks < unplantableBlacklist.get(key)) {
                return false;
            }
            unplantableBlacklist.remove(key);
        }
        return hasSufficientLightForCrop(farmlandPos);
    }

    private void markUnplantable(BlockPos pos, List<BlockPos> openFarmland) {
        long key = pos.asLong();
        unplantableBlacklist.put(key, farmTicks + UNPLANTABLE_TTL_TICKS);
        failedPlantClicks.remove(key);
        if (openFarmland != null) {
            openFarmland.remove(pos);
        }
        farmlandSet.remove(key);
        plantQueue.remove(pos);
    }'''

assert old_aim_rot in content, 'old_aim_rot not found'
content = content.replace(old_aim_rot, new_aim_rot, 1)

# 5. Row Anchor helper methods above GoalFarmTarget
anchor_helpers = '''    /**
     * Kotwiczenie celu na koncu rzedu upraw (Row Anchor - rekomendacja Argon 4).
     * Zapobiega nieustannemu przeliczaniu A* (Path Flapping) co 100-300ms po kazdym scietym klocku.
     */
    private Goal updateOrKeepStableRowGoal(List<BlockPos> matureCrops, List<BlockPos> emptyFarmland, BetterBlockPos playerPos) {
        stableRowGoalTicks++;

        if (stableRowAnchorPos != null && stableRowGoal != null && stableRowGoalTicks < 50) {
            double distSq = playerPos.distSqr(stableRowAnchorPos);
            boolean anchorReached = distSq <= 2.56D;
            boolean hasTasksNear = hasValidTasksNearAnchor(stableRowAnchorPos, matureCrops, emptyFarmland);

            if (!anchorReached && hasTasksNear) {
                return stableRowGoal;
            }
        }

        List<BlockPos> pool = (!matureCrops.isEmpty()) ? matureCrops : emptyFarmland;
        if (pool.isEmpty()) {
            stableRowAnchorPos = null;
            stableRowGoal = null;
            return null;
        }

        BlockPos nearest = null;
        double bestDist = Double.MAX_VALUE;
        for (BlockPos p : pool) {
            if (isBlacklisted(p)) continue;
            double d = playerPos.distSqr(p);
            if (d < bestDist) {
                bestDist = d;
                nearest = p;
            }
        }
        if (nearest == null) {
            stableRowAnchorPos = null;
            stableRowGoal = null;
            return null;
        }

        BlockPos farAnchor = computeFarRowAnchor(nearest, pool);
        if (!farAnchor.equals(stableRowAnchorPos) || stableRowGoal == null) {
            stableRowAnchorPos = farAnchor;
            stableRowGoal = new GoalNear(new BetterBlockPos(farAnchor), 1);
            stableRowGoalTicks = 0;
        }
        return stableRowGoal;
    }

    private boolean hasValidTasksNearAnchor(BlockPos anchor, List<BlockPos> matureCrops, List<BlockPos> emptyFarmland) {
        double radiusSq = 3.5D * 3.5D;
        for (BlockPos p : matureCrops) {
            if (!isBlacklisted(p) && p.distSqr(anchor) <= radiusSq) {
                return true;
            }
        }
        for (BlockPos p : emptyFarmland) {
            if (!isBlacklisted(p) && p.distSqr(anchor) <= radiusSq) {
                return true;
            }
        }
        return false;
    }

    private BlockPos computeFarRowAnchor(BlockPos start, List<BlockPos> pool) {
        List<BlockPos> xCorridor = new ArrayList<>();
        List<BlockPos> zCorridor = new ArrayList<>();
        int sX = start.getX();
        int sY = start.getY();
        int sZ = start.getZ();

        for (BlockPos p : pool) {
            if (isBlacklisted(p) || Math.abs(p.getY() - sY) > 1) continue;
            if (Math.abs(p.getZ() - sZ) <= 1 && Math.abs(p.getX() - sX) <= 16) {
                xCorridor.add(p);
            }
            if (Math.abs(p.getX() - sX) <= 1 && Math.abs(p.getZ() - sZ) <= 16) {
                zCorridor.add(p);
            }
        }

        List<BlockPos> dominant = (xCorridor.size() >= zCorridor.size()) ? xCorridor : zCorridor;
        if (dominant.isEmpty()) {
            return start;
        }

        BlockPos far = start;
        double maxDist = 0;
        for (BlockPos p : dominant) {
            double d = start.distSqr(p);
            if (d > maxDist) {
                maxDist = d;
                far = p;
            }
        }
        return far;
    }\n\n'''

content = content.replace(
    '    public static final class GoalFarmTarget implements Goal, IGoalRenderPos {',
    anchor_helpers + '    public static final class GoalFarmTarget implements Goal, IGoalRenderPos {'
)

# 6. Replace Sections 6, 7, 8 in onTickInternal
start_marker = '        // ─────────────────────────────────────────────────────────────────\n        // 6. NATYCHMIASTOWY REPLANT NA KLOCKU'
end_marker = '        // ─────────────────────────────────────────────────────────────────\n        // 9. SADZENIE BRODAWEK (Soul Sand)'

idx_start = content.find(start_marker)
idx_end = content.find(end_marker)
assert idx_start != -1, 'start_marker not found'
assert idx_end != -1, 'end_marker not found'

pipeline_code = '''        // ─────────────────────────────────────────────────────────────────
        // 6. PIPELINE FAZA 1: Trailing / Underfoot Replant & Immediate Replant
        // (Pilne sadzenie pod stopami lub tuz za graczem, by nie zostawiac pustej ziemi)
        // ─────────────────────────────────────────────────────────────────
        final boolean fastMode = Baritone.settings().farmFastMode.value;
        final int maxActionsThisTick = (fastMode || isPracaModeActive()) ? 2 : 1;
        int actionsRemaining = maxActionsThisTick;

        if (actionsRemaining > 0 && Baritone.settings().replantCrops.value && haveSeeds) {
            // A. immediateReplantPos bufor
            if (immediateReplantPos != null) {
                replantAttemptTicks++;
                Vec3 plantVec = getPlantAimVec(immediateReplantPos);
                double distSq = head.distanceToSqr(plantVec);
                Rotation rotToPlant = RotationUtils.calcRotationFromVec3d(head, plantVec, ctx.playerRotations());
                BlockState soilState = ctx.world().getBlockState(immediateReplantPos);
                Block soilBlock = soilState.getBlock();
                boolean isFarmland = (soilBlock == Blocks.FARMLAND);
                boolean isSoulSand = (soilBlock == Blocks.SOUL_SAND && Baritone.settings().replantNetherWart.value);
                BlockState aboveState = ctx.world().getBlockState(immediateReplantPos.above());

                if (replantAttemptTicks > 6
                        || distSq > reach2
                        || rotToPlant.getPitch() > 80.0F
                        || (!isFarmland && !isSoulSand)
                        || (!aboveState.isAir() && !(aboveState.getBlock() instanceof CropBlock crop && !crop.isMaxAge(aboveState)))
                        || (isFarmland && !isPlantableFarmland(immediateReplantPos))) {
                    immediateReplantPos = null;
                    replantAttemptTicks = 0;
                } else if ((isFarmland && selectSeedsForPlanting()) || (isSoulSand && baritone.getInventoryBehavior().throwaway(true, this::isNetherWart, true))) {
                    applyAimRotation(rotToPlant, fastMode);
                    updateSweepState(rotToPlant);
                    if (fastMode || ctx.isLookingAt(immediateReplantPos)) {
                        BlockHitResult plantHit = new BlockHitResult(plantVec, Direction.UP, immediateReplantPos, false);
                        InteractionResult res = ctx.playerController().processRightClickBlock(
                                ctx.player(), ctx.world(), InteractionHand.MAIN_HAND, plantHit);
                        if (res.consumesAction()) {
                            ctx.player().swing(InteractionHand.MAIN_HAND);
                            failedPlantClicks.remove(immediateReplantPos.asLong());
                            openFarmland.remove(immediateReplantPos);
                            farmlandSet.remove(immediateReplantPos.asLong());
                            if (locations != null) locations.remove(immediateReplantPos);
                            if (plantQueue != null) plantQueue.remove(immediateReplantPos);
                            immediateReplantPos = null;
                            replantAttemptTicks = 0;
                            lastProgressTick = farmTicks;
                            acted = true;
                            actionsRemaining--;
                        }
                    }
                    handledNearbyAction = true;
                }
            }

            // B. Trailing / Underfoot openFarmland
            if (actionsRemaining > 0 && !openFarmland.isEmpty()) {
                BlockPos urgentFarmland = null;
                double bestUrgentDist = Double.MAX_VALUE;
                for (BlockPos fPos : openFarmland) {
                    if (!isPlantableFarmland(fPos)) continue;
                    BlockState upState = ctx.world().getBlockState(fPos.above());
                    if (!upState.isAir() && !(upState.getBlock() instanceof CropBlock c && !c.isMaxAge(upState))) continue;

                    double d = Math.hypot((fPos.getX() + 0.5D) - head.x, (fPos.getZ() + 0.5D) - head.z);
                    if (d <= 1.65D && head.distanceToSqr(fPos.getX() + 0.5D, fPos.getY() + 0.9375D, fPos.getZ() + 0.5D) <= reach2) {
                        if (d < bestUrgentDist) {
                            bestUrgentDist = d;
                            urgentFarmland = fPos;
                        }
                    }
                }

                if (urgentFarmland != null && selectSeedsForPlanting()) {
                    Vec3 plantVec = getPlantAimVec(urgentFarmland);
                    Rotation rotToPlant = RotationUtils.calcRotationFromVec3d(head, plantVec, ctx.playerRotations());
                    applyAimRotation(rotToPlant, fastMode);
                    updateSweepState(rotToPlant);
                    if (fastMode || ctx.isLookingAt(urgentFarmland)) {
                        BlockHitResult plantHit = new BlockHitResult(plantVec, Direction.UP, urgentFarmland, false);
                        InteractionResult res = ctx.playerController().processRightClickBlock(
                                ctx.player(), ctx.world(), InteractionHand.MAIN_HAND, plantHit);
                        if (res.consumesAction()) {
                            ctx.player().swing(InteractionHand.MAIN_HAND);
                            failedPlantClicks.remove(urgentFarmland.asLong());
                            openFarmland.remove(urgentFarmland);
                            farmlandSet.remove(urgentFarmland.asLong());
                            if (locations != null) locations.remove(urgentFarmland);
                            if (plantQueue != null) plantQueue.remove(urgentFarmland);
                            lastProgressTick = farmTicks;
                            acted = true;
                            actionsRemaining--;
                        }
                    }
                    handledNearbyAction = true;
                }
            }
        }

        // ─────────────────────────────────────────────────────────────────
        // 7. PIPELINE FAZA 2: Scinanie plonow w biegu (Forward Cone Harvest)
        // ─────────────────────────────────────────────────────────────────
        candidates.clear();
        if (!plantOnly && actionsRemaining > 0) {
            final int playerY = playerPos.getY();
            for (BlockPos p : toBreak) {
                if (!isBlacklisted(p)
                        && Math.abs(p.getY() - playerY) <= 1
                        && head.distanceToSqr(p.getX() + 0.5D, p.getY() + 0.5D, p.getZ() + 0.5D) <= reach2
                        && readyForHarvest(ctx.world(), p, ctx.world().getBlockState(p))) {
                    candidates.add(p);
                }
            }
            if (currentTargetPos != null && !candidates.contains(currentTargetPos)) {
                currentTargetPos = null;
                targetTicks = 0;
            }
            candidates.sort(Comparator.comparingDouble(this::aimScore));

            int harvestCount = 0;
            for (BlockPos pos : candidates) {
                if (actionsRemaining <= 0) break;
                BlockState bState = ctx.world().getBlockState(pos);
                if (!readyForHarvest(ctx.world(), pos, bState)) continue;
                final boolean isInstant = bState.getDestroySpeed(ctx.world(), pos) == 0.0F;

                Vec3 cropAimVec = getCropAimVec(pos);
                Optional<Rotation> rot = RotationUtils.reachableOffset(ctx, pos, cropAimVec, blockReachDistance, false);
                if (!rot.isPresent()) {
                    rot = RotationUtils.reachable(ctx, pos, blockReachDistance);
                }
                if (!rot.isPresent()) {
                    if (head.distanceToSqr(cropAimVec) <= reach2) {
                        BlockHitResult hit = ctx.world().clip(new ClipContext(head, cropAimVec,
                                ClipContext.Block.COLLIDER, ClipContext.Fluid.NONE, ctx.player()));
                        if (hit.getType() == HitResult.Type.MISS || hit.getBlockPos().equals(pos)) {
                            rot = Optional.of(RotationUtils.calcRotationFromVec3d(head, cropAimVec, ctx.playerRotations()));
                        }
                    }
                }
                if (!rot.isPresent()) continue;

                if (harvestCount > 0) {
                    Rotation curRot = ctx.playerRotations();
                    float yawDiff = Math.abs(Mth.wrapDegrees(rot.get().getYaw() - curRot.getYaw()));
                    float pitchDiff = Math.abs(rot.get().getPitch() - curRot.getPitch());
                    if (yawDiff > 55.0f || pitchDiff > 45.0f) {
                        continue;
                    }
                } else {
                    applyAimRotation(rot.get(), fastMode);
                    updateSweepState(rot.get());
                }

                if (!isInstant) {
                    MovementHelper.switchToBestToolFor(ctx, bState);
                }
                if (pos.equals(currentTargetPos)) {
                    targetTicks++;
                } else {
                    currentTargetPos = pos;
                    targetTicks = 0;
                }

                final boolean aimed = fastMode || ctx.isLookingAt(pos);
                if (aimed) {
                    ctx.playerController().syncHeldItem();
                    HitResult trace = ctx.objectMouseOver();
                    Direction breakFace = (trace instanceof BlockHitResult bhr && bhr.getBlockPos().equals(pos))
                            ? bhr.getDirection()
                            : BaritonePlayerController.getSafeBreakFace(ctx.player(), pos, Direction.UP);

                    if (isInstant) {
                        if (ctx.playerController().clickBlock(pos, breakFace)) {
                            ctx.player().swing(InteractionHand.MAIN_HAND);
                            acted = true;
                            lastProgressTick = farmTicks;
                            harvestCount++;
                            actionsRemaining--;
                            if (bState.getBlock() == Blocks.WHEAT) {
                                totalWheatHarvested++;
                            }
                            if (locations != null) locations.remove(pos);

                            if (tooManyAttempts(pos)) {
                                currentTargetPos = null;
                                targetTicks = 0;
                            } else if (Baritone.settings().replantCrops.value) {
                                Block belowBlock = ctx.world().getBlockState(pos.below()).getBlock();
                                if (belowBlock == Blocks.FARMLAND && hasPlantableSeeds()) {
                                    Item seed = seedFor(bState.getBlock());
                                    boolean planted = false;
                                    if (fastMode && actionsRemaining > 0 && selectSeedsForPlanting()) {
                                        Vec3 plantTarget = getPlantAimVec(pos.below());
                                        BlockHitResult plantHit = new BlockHitResult(plantTarget, Direction.UP, pos.below(), false);
                                        InteractionResult res = ctx.playerController().processRightClickBlock(
                                                ctx.player(), ctx.world(), InteractionHand.MAIN_HAND, plantHit);
                                        if (res.consumesAction()) {
                                            ctx.player().swing(InteractionHand.MAIN_HAND);
                                            openFarmland.remove(pos.below());
                                            farmlandSet.remove(pos.below().asLong());
                                            if (locations != null) locations.remove(pos.below());
                                            if (plantQueue != null) plantQueue.remove(pos.below());
                                            planted = true;
                                            actionsRemaining--;
                                        }
                                    }
                                    if (!planted) {
                                        openFarmland.add(pos.below());
                                        farmlandSet.add(pos.below().asLong());
                                        immediateReplantPos = pos.below();
                                        replantAttemptTicks = 0;
                                        replantItem = seed;
                                        currentTargetPos = null;
                                        targetTicks = 0;
                                    }
                                }
                            }
                        }
                    } else if (ctx.playerController().hasBrokenBlock()) {
                        ctx.playerController().clickBlock(pos, breakFace);
                        ctx.player().swing(InteractionHand.MAIN_HAND);
                        acted = true;
                        lastProgressTick = farmTicks;
                        harvestCount++;
                        actionsRemaining--;
                    } else if (ctx.playerController().onPlayerDamageBlock(pos, breakFace)) {
                        ctx.player().swing(InteractionHand.MAIN_HAND);
                    }
                }

                if (targetTicks >= (isInstant ? 12 : 80)) {
                    blacklistFor(pos, 60);
                    currentTargetPos = null;
                    targetTicks = 0;
                    continue;
                }
                handledNearbyAction = true;
                if (!isInstant) {
                    return new PathingCommand(null, PathingCommandType.REQUEST_PAUSE);
                }
                if (actionsRemaining <= 0) break;
            }
            if (candidates.isEmpty()) {
                sweepDirection = -sweepDirection;
            }
        }

        // ─────────────────────────────────────────────────────────────────
        // 8. PIPELINE FAZA 3: Dobicie sadzenia na zaoranej ziemi przed graczem
        // ─────────────────────────────────────────────────────────────────
        if (actionsRemaining > 0 && haveSeeds) {
            List<BlockPos> plantCandidates = new ArrayList<>();
            final int playerY = playerPos.getY();
            for (BlockPos p : openFarmland) {
                if (!isBlacklisted(p)
                        && Math.abs(p.getY() - playerY) <= 1
                        && head.distanceToSqr(p.getX() + 0.5D, p.getY() + 0.5D, p.getZ() + 0.5D) <= reach2) {
                    plantCandidates.add(p);
                }
            }
            if (currentPlantTargetPos != null && !plantCandidates.contains(currentPlantTargetPos)) {
                currentPlantTargetPos = null;
                plantTargetTicks = 0;
            }
            plantCandidates.sort(Comparator.comparingDouble(this::plantAimScore));

            for (BlockPos pos : plantCandidates) {
                if (actionsRemaining <= 0) break;
                if (!isPlantableFarmland(pos)) {
                    markUnplantable(pos, openFarmland);
                    continue;
                }
                Vec3 plantTarget = getPlantAimVec(pos);
                Optional<Rotation> rot = RotationUtils.reachableOffset(ctx, pos, plantTarget, blockReachDistance, false);
                if (!rot.isPresent()) {
                    rot = RotationUtils.reachable(ctx, pos, blockReachDistance);
                }
                if (!rot.isPresent()) {
                    if (head.distanceToSqr(plantTarget) <= reach2) {
                        BlockHitResult hit = ctx.world().clip(new ClipContext(head, plantTarget,
                                ClipContext.Block.COLLIDER, ClipContext.Fluid.NONE, ctx.player()));
                        if (hit.getType() == HitResult.Type.MISS || hit.getBlockPos().equals(pos)) {
                            rot = Optional.of(RotationUtils.calcRotationFromVec3d(head, plantTarget, ctx.playerRotations()));
                        }
                    }
                }
                if (rot.isPresent()) {
                    applyAimRotation(rot.get(), fastMode);
                    updateSweepState(rot.get());

                    if (!selectSeedsForPlanting()) continue;

                    if (pos.equals(currentPlantTargetPos)) {
                        plantTargetTicks++;
                    } else {
                        currentPlantTargetPos = pos;
                        plantTargetTicks = 0;
                    }

                    if (fastMode || ctx.isLookingAt(pos)) {
                        BlockHitResult plantHit = new BlockHitResult(plantTarget, Direction.UP, pos, false);
                        InteractionResult res = ctx.playerController().processRightClickBlock(
                                ctx.player(), ctx.world(), InteractionHand.MAIN_HAND, plantHit);
                        if (res.consumesAction()) {
                            ctx.player().swing(InteractionHand.MAIN_HAND);
                            failedPlantClicks.remove(pos.asLong());
                            openFarmland.remove(pos);
                            farmlandSet.remove(pos.asLong());
                            plantQueue.remove(pos);
                            if (locations != null) locations.remove(pos);
                            currentPlantTargetPos = null;
                            plantTargetTicks = 0;
                            acted = true;
                            lastProgressTick = farmTicks;
                            actionsRemaining--;
                            if (tooManyAttempts(pos)) {
                                currentPlantTargetPos = null;
                                plantTargetTicks = 0;
                            }
                        } else {
                            long key = pos.asLong();
                            int fails = failedPlantClicks.addTo(key, 1) + 1;
                            if (!hasSufficientLightForCrop(pos) || fails >= MAX_PLANT_FAIL_CLICKS || tooManyAttempts(pos)) {
                                markUnplantable(pos, openFarmland);
                                currentPlantTargetPos = null;
                                plantTargetTicks = 0;
                            }
                        }
                    }
                    handledNearbyAction = true;
                }
            }
        }\n\n'''

content = content[:idx_start] + pipeline_code + content[idx_end:]

# 7. Goal generation: Row Anchor
old_lane_code = '''            // A. Dojrzałe plony — priorytet trasy wzdłuż rzędów
            if (!plantOnly && !toBreak.isEmpty()) {
                harvestQueue.clear();
                List<BlockPos> goals = selectOptimizedHarvestGoals(toBreak, playerPos);
                for (BlockPos p : goals) {
                    harvestQueue.add(p);
                    goalz.add(new GoalFarmTarget(p));
                }
            } else if (haveSeeds && !openFarmland.isEmpty()) {
                // B. Sadzenie pustej ziemi — tylko gdy brak dojrzałych plonów (lub w trybie plantOnly)
                // Zapobiega to cofaniu się do pustej ziemi tuż po ścięciu plonu podczas sprintu!
                plantQueue.clear();
                List<BlockPos> goals = selectOptimizedHarvestGoals(openFarmland, playerPos);
                for (BlockPos p : goals) {
                    plantQueue.add(p);
                    goalz.add(new GoalFarmTarget(p));
                }
            }'''

new_lane_code = '''            // ── STABILNY ROW ANCHOR GOAL (Eliminacja Path Flappingu i spamu A* wg Argona 4) ──
            if (!toBreak.isEmpty() || (haveSeeds && !openFarmland.isEmpty())) {
                Goal stableGoal = updateOrKeepStableRowGoal(toBreak, openFarmland, playerPos);
                if (stableGoal != null) {
                    goalz.add(stableGoal);
                }
            }'''

assert old_lane_code in content, 'old_lane_code not found'
content = content.replace(old_lane_code, new_lane_code, 1)

# 8. Force sprint and final Goal
old_return_cmd = '''        if (goalz.isEmpty()) {
            return new PathingCommand(null, PathingCommandType.REQUEST_PAUSE);
        }
        return new PathingCommand(
                new GoalComposite(goalz.toArray(new Goal[0])),
                PathingCommandType.SET_GOAL_AND_PATH);'''

new_return_cmd = '''        if (goalz.isEmpty()) {
            return new PathingCommand(null, PathingCommandType.REQUEST_PAUSE);
        }
        if (Baritone.settings().farmFastMode.value) {
            baritone.getInputOverrideHandler().setInputForceState(Input.SPRINT, true);
        }
        Goal finalGoal = goalz.size() == 1 ? goalz.get(0) : new GoalComposite(goalz.toArray(new Goal[0]));
        return new PathingCommand(
                finalGoal,
                PathingCommandType.SET_GOAL_AND_PATH);'''

assert old_return_cmd in content, 'old_return_cmd not found'
content = content.replace(old_return_cmd, new_return_cmd, 1)

with open(path, 'w', encoding='utf-8') as f:
    f.write(content)

print('SUCCESSFULLY APPLIED ALL ARGON 4 ENHANCEMENTS!')
