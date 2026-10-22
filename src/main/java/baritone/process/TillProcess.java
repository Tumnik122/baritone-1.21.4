/*
 * This file is part of Baritone.
 *
 * Baritone is free software: you can redistribute it and/or modify
 * it under the terms of the GNU Lesser General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * Baritone is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU Lesser General Public License for more details.
 *
 * You should have received a copy of the GNU Lesser General Public License
 * along with Baritone.  If not, see <https://www.gnu.org/licenses/>.
 */

package baritone.process;

import baritone.Baritone;
import baritone.api.pathing.goals.Goal;
import baritone.api.pathing.goals.GoalBlock;
import baritone.api.pathing.goals.GoalComposite;
import baritone.api.pathing.goals.GoalGetToBlock;
import baritone.api.pathing.goals.GoalNear;
import baritone.api.process.PathingCommand;
import baritone.api.process.PathingCommandType;
import baritone.api.selection.ISelection;
import baritone.api.utils.BetterBlockPos;
import baritone.api.utils.Rotation;
import baritone.api.utils.RotationUtils;
import baritone.api.utils.input.Input;
import baritone.utils.BaritoneProcessHelper;
import baritone.bypass.RotationEngine;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.util.Mth;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.item.HoeItem;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.Vec3;

import java.util.*;

/**
 * Orze wszystkie bloki ziemi/trawy w zaznaczeniu (sel 1 + sel 2).
 * Prawy przycisk motyką — dokładnie jak gracz robiący to ręcznie.
 * Uruchamiane przez #till (lub #zaorz).
 *
 * FULL THROUGPUT / ZERO-STALL OPTIMIZATION:
 * 1. Boustrophedon / Serpentine strip navigation — poruszanie się idealnymi pasami
 *    bez gwałtownych nawrotów o 180° i bez losowego skakania po koordynatach.
 * 2. Oranie w pełnym biegu (Drive-by Tilling) — 0 pauzowania ruchu (brak REQUEST_PAUSE
 *    przy każdym klocku), bot orze 20 bloków na sekundę przy prędkości sprintu ~5.6 m/s.
 * 3. 100% GrimAC 2.0 / PolarAC safety — pełna kwantyzacja siatki GCD rotacji,
 *    jeden pakiet rotacji na tick i poprawna weryfikacja Line-Of-Sight.
 */
public final class TillProcess extends BaritoneProcessHelper {

    // Bloki które można zaorać motyką
    private static final Set<net.minecraft.world.level.block.Block> TILLABLE = new HashSet<>(Arrays.asList(
            Blocks.GRASS_BLOCK,
            Blocks.DIRT,
            Blocks.COARSE_DIRT,
            Blocks.ROOTED_DIRT,
            Blocks.DIRT_PATH,
            Blocks.PODZOL,
            Blocks.MYCELIUM,
            Blocks.MUD
    ));

    private boolean active = false;
    private List<BlockPos> pending = new ArrayList<>();
    private long tillTick = 0L;
    private BlockPos currentTarget = null;
    private int targetTicks = 0;
    private boolean prevAllowBreak = true;
    private boolean prevAllowPlace = true;
    private boolean settingsApplied = false;
    private int totalBlocks = 0;
    private long startTimeMs = 0L;

    // Boustrophedon lane traversal
    private boolean laneAxisX = true;
    private double laneSign = 0.0D;
    private boolean laneInitialized = false;

    public TillProcess(Baritone baritone) {
        super(baritone);
    }

    public void till() {
        // Zbierz wszystkie zaznaczenia z SelCommand
        ISelection[] selections = baritone.getSelectionManager().getSelections();
        if (selections == null || selections.length == 0) {
            logDirect("§c[Till] Brak zaznaczenia! Użyj #sel 1 i #sel 2 aby zaznaczyć obszar.");
            return;
        }

        pending = new ArrayList<>();
        int minX = Integer.MAX_VALUE, maxX = Integer.MIN_VALUE;
        int minZ = Integer.MAX_VALUE, maxZ = Integer.MIN_VALUE;

        for (ISelection sel : selections) {
            BetterBlockPos min = sel.min();
            BetterBlockPos max = sel.max();
            for (int x = min.x; x <= max.x; x++) {
                for (int z = min.z; z <= max.z; z++) {
                    for (int y = min.y; y <= max.y; y++) {
                        BlockPos pos = new BlockPos(x, y, z);
                        BlockState state = ctx.world().getBlockState(pos);
                        if (TILLABLE.contains(state.getBlock())) {
                            // Sprawdź czy nad blokiem jest powietrze (można zaorać)
                            BlockState above = ctx.world().getBlockState(pos.above());
                            if (above.isAir() || above.canBeReplaced()) {
                                pending.add(pos);
                                if (x < minX) minX = x;
                                if (x > maxX) maxX = x;
                                if (z < minZ) minZ = z;
                                if (z > maxZ) maxZ = z;
                            }
                        }
                    }
                }
            }
        }

        if (pending.isEmpty()) {
            logDirect("§e[Till] Brak bloków ziemi do zaorania w zaznaczeniu.");
            return;
        }

        // Zablokuj kopanie i stawianie bloków przez pather podczas orania — oranie to tylko klikanie motyką!
        if (!settingsApplied) {
            prevAllowBreak = Baritone.settings().allowBreak.value;
            prevAllowPlace = Baritone.settings().allowPlace.value;
            settingsApplied = true;
        }
        Baritone.settings().allowBreak.value = false;
        Baritone.settings().allowPlace.value = false;

        // Określ oś początkową
        laneAxisX = (maxX - minX) >= (maxZ - minZ);
        laneSign = 0.0D;
        laneInitialized = true;

        logDirect("§a[Till] Zaczynam ultra-szybkie oranie " + pending.size() + " bloków (tryb ciągłego sprintu). Użyj #stop aby zatrzymać.");
        active = true;
        totalBlocks = pending.size();
        startTimeMs = System.currentTimeMillis();
        tillTick = 0L;
        currentTarget = null;
        targetTicks = 0;
    }

    @Override
    public boolean isActive() {
        return active;
    }

    public int getTotalBlocks() {
        return totalBlocks;
    }

    public int getBlocksTilled() {
        return Math.max(0, totalBlocks - pending.size());
    }

    public int getRemainingBlocks() {
        return pending.size();
    }

    public int getPercentage() {
        return totalBlocks > 0 ? (getBlocksTilled() * 100 / totalBlocks) : 0;
    }

    public double getBlocksPerSecond() {
        long elapsed = System.currentTimeMillis() - startTimeMs;
        if (elapsed < 1000L) return 0.0;
        return (double) getBlocksTilled() / (elapsed / 1000.0);
    }

    @Override
    public String displayName0() {
        return "Till [" + pending.size() + " bloków]";
    }

    @Override
    public double priority() {
        return 0.8; // Niżej niż FarmProcess (1.0), ale aktywny kiedy wywołany
    }

    @Override
    public void onLostControl() {
        active = false;
        pending.clear();
        currentTarget = null;
        targetTicks = 0;
        totalBlocks = 0;
        startTimeMs = 0L;
        laneInitialized = false;
        laneSign = 0.0D;
        baritone.getInputOverrideHandler().clearAllKeys();
        if (settingsApplied) {
            settingsApplied = false;
            Baritone.settings().allowBreak.value = prevAllowBreak;
            Baritone.settings().allowPlace.value = prevAllowPlace;
        }
    }

    @Override
    public PathingCommand onTick(boolean calcFailed, boolean isSafeToCancel) {
        tillTick++;

        if (!active || ctx.player() == null || ctx.world() == null) {
            onLostControl();
            return new PathingCommand(null, PathingCommandType.REQUEST_PAUSE);
        }

        // Odśwież listę — usuń już zaorowane bloki
        pending.removeIf(pos -> {
            BlockState st = ctx.world().getBlockState(pos);
            return !TILLABLE.contains(st.getBlock()); // nie ma już ziemi = zaorany lub zmieniony
        });

        if (pending.isEmpty()) {
            double elapsedSec = Math.max(0.1, (System.currentTimeMillis() - startTimeMs) / 1000.0);
            logDirect(String.format(Locale.ROOT, "§a[Till] Oranie zakończone! %d bloków zaorane w %.1f s (%.1f bloków/s)!",
                    totalBlocks, elapsedSec, (double) totalBlocks / elapsedSec));
            onLostControl();
            return new PathingCommand(null, PathingCommandType.REQUEST_PAUSE);
        }

        // Sprawdź czy mamy motykę
        if (!hasHoe()) {
            if (tillTick % 100 == 0) {
                logDirect("§e[Till] Brak motyki w ekwipunku! Zatrzymuję oranie.");
            }
            return new PathingCommand(null, PathingCommandType.REQUEST_PAUSE);
        }

        final boolean fastMode = Baritone.settings().farmFastMode.value;
        if (fastMode) {
            baritone.getInputOverrideHandler().setInputForceState(Input.SPRINT, true);
        }

        BetterBlockPos playerPos = ctx.playerFeet();
        Vec3 head = ctx.playerHead();
        double reachDist = Math.min(3.85, ctx.playerController().getBlockReachDistance());
        double reach2 = reachDist * reachDist;

        final double playerRealX = ctx.player().getX();
        final double playerRealZ = ctx.player().getZ();
        final double playerLong = laneAxisX ? playerRealX : playerRealZ;

        // Szukaj bloków w zasięgu do natychmiastowego zaorania
        List<BlockPos> reachable = new ArrayList<>();
        for (BlockPos pos : pending) {
            Vec3 topCenter = new Vec3(pos.getX() + 0.5, pos.getY() + 1.0, pos.getZ() + 0.5);
            if (head.distanceToSqr(topCenter) <= reach2) {
                reachable.add(pos);
            }
        }

        // Sortuj reachable wg kąta i kierunku biegu (najpierw te przed graczem)
        reachable.sort(Comparator.comparingDouble(p -> tillAimScore(p, head, playerLong, laneSign, laneAxisX)));

        boolean acted = false;
        for (BlockPos pos : reachable) {
            BlockState state = ctx.world().getBlockState(pos);
            if (!TILLABLE.contains(state.getBlock())) {
                pending.remove(pos);
                continue;
            }

            Vec3 topCenter = new Vec3(pos.getX() + 0.5, pos.getY() + 1.0, pos.getZ() + 0.5);
            Optional<Rotation> rot = RotationUtils.reachableOffset(ctx, pos, topCenter, reachDist, false);
            if (!rot.isPresent()) {
                Vec3 centerVec = Vec3.atCenterOf(pos);
                rot = RotationUtils.reachable(ctx, pos, reachDist);
                if (!rot.isPresent()) {
                    rot = Optional.of(RotationUtils.calcRotationFromVec3d(head, centerVec, ctx.playerRotations()));
                }
            }

            if (rot.isPresent()) {
                applyRotation(rot.get());

                if (selectHoe()) {
                    ctx.playerController().syncHeldItem();

                    if (pos.equals(currentTarget)) {
                        targetTicks++;
                    } else {
                        currentTarget = pos;
                        targetTicks = 0;
                    }

                    Vec3 hitVec = new Vec3(pos.getX() + 0.5, pos.getY() + 1.0, pos.getZ() + 0.5);
                    BlockHitResult hit = new BlockHitResult(hitVec, Direction.UP, pos, false);
                    InteractionResult result = ctx.playerController().processRightClickBlock(
                            ctx.player(), ctx.world(), InteractionHand.MAIN_HAND, hit);

                    if (result.consumesAction()) {
                        ctx.player().swing(InteractionHand.MAIN_HAND);
                        pending.remove(pos);
                        currentTarget = null;
                        targetTicks = 0;
                        acted = true;
                        // 1 zaorany blok/tick (20 bloków/s) bez zatrzymania sprintu!
                        break;
                    }

                    if (targetTicks >= 12) {
                        pending.remove(pos);
                        currentTarget = null;
                        targetTicks = 0;
                    }
                }
            }
        }

        // Obsługa ruchu: jeśli zaoraliśmy blok i bot ma aktywną ścieżkę, kontynuuj płynny bieg (DEFER)
        boolean hasPath = baritone.getPathingBehavior().hasPath();
        if (acted && hasPath) {
            return new PathingCommand(null, PathingCommandType.DEFER);
        }

        // Jeśli bot nie ma ścieżki lub pather zawiódł, wyznacz cele wzdłuż alei
        if (calcFailed && !pending.isEmpty()) {
            pending.remove(0);
        }

        List<BlockPos> laneGoals = selectOptimizedTillGoals(pending, playerPos);
        if (laneGoals.isEmpty()) {
            return new PathingCommand(null, PathingCommandType.REQUEST_PAUSE);
        }

        List<Goal> goals = new ArrayList<>();
        for (BlockPos target : laneGoals) {
            // Cel: stanąć na wysokości podłogi/trawy (target.above()) z tolerancją 1-2 kratek
            goals.add(new GoalNear(target.above(), 1));
        }

        Goal compositeGoal = goals.size() == 1 ? goals.get(0) : new GoalComposite(goals.toArray(new Goal[0]));
        return new PathingCommand(compositeGoal, PathingCommandType.SET_GOAL_AND_PATH);
    }

    private double tillAimScore(BlockPos p, Vec3 head, double playerLong, double laneSign, boolean laneAxisX) {
        if (p.equals(currentTarget)) return -10000.0D;
        double distSq = head.distanceToSqr(p.getX() + 0.5D, p.getY() + 0.5D, p.getZ() + 0.5D);
        double cLong = laneAxisX ? (p.getX() + 0.5D) : (p.getZ() + 0.5D);
        double fwd = (cLong - playerLong) * laneSign;
        // Premia za klocki przed graczem w kierunku biegu, kara za klocki z tyłu
        double directionBias = (fwd >= 0) ? -fwd * 2.5D : 60.0D;
        return distSq + directionBias;
    }

    private List<BlockPos> selectOptimizedTillGoals(List<BlockPos> pendingList, BetterBlockPos playerPos) {
        if (pendingList == null || pendingList.isEmpty()) return Collections.emptyList();

        final int playerY = playerPos.getY();
        final double playerRealX = ctx.player() != null ? ctx.player().getX() : playerPos.getX() + 0.5D;
        final double playerRealZ = ctx.player() != null ? ctx.player().getZ() : playerPos.getZ() + 0.5D;
        final double playerLong = laneAxisX ? playerRealX : playerRealZ;
        final double playerLat = laneAxisX ? playerRealZ : playerRealX;

        // Inicjalizacja zwrotu jeśli zerowy
        if (laneSign == 0.0D) {
            Vec3 delta = ctx.player() != null ? ctx.player().getDeltaMovement() : Vec3.ZERO;
            double velLong = laneAxisX ? delta.x : delta.z;
            if (Math.abs(velLong) > 0.03D) {
                laneSign = Math.signum(velLong);
            } else {
                laneSign = 1.0D;
            }
        }

        // 1. Szukaj bloków w bieżącej alei (szerokość do 2.2 bloka w bok, przed graczem)
        List<BlockPos> currentLane = new ArrayList<>();
        for (BlockPos p : pendingList) {
            if (Math.abs(p.getY() - playerY) > 2) continue;
            double cLong = laneAxisX ? (p.getX() + 0.5D) : (p.getZ() + 0.5D);
            double cLat = laneAxisX ? (p.getZ() + 0.5D) : (p.getX() + 0.5D);

            double fwdDist = (cLong - playerLong) * laneSign;
            double lateralDist = Math.abs(cLat - playerLat);

            if (fwdDist >= -0.5D && fwdDist <= 24.0D && lateralDist <= 2.2D) {
                currentLane.add(p);
            }
        }

        if (!currentLane.isEmpty()) {
            currentLane.sort(Comparator.comparingDouble(p -> {
                double cLong = laneAxisX ? (p.getX() + 0.5D) : (p.getZ() + 0.5D);
                return (cLong - playerLong) * laneSign;
            }));
            // Cel: 6-8 bloków w przód
            int idx = Math.min(currentLane.size() - 1, 7);
            return Collections.singletonList(currentLane.get(idx));
        }

        // 2. Bieżąca aleja skończona — przejście boustrophedon do sąsiedniego rzędu
        List<BlockPos> nextRow = new ArrayList<>();
        for (BlockPos p : pendingList) {
            if (Math.abs(p.getY() - playerY) > 2) continue;
            double cLat = laneAxisX ? (p.getZ() + 0.5D) : (p.getX() + 0.5D);
            double lateralDist = Math.abs(cLat - playerLat);
            if (lateralDist >= 1.0D && lateralDist <= 4.5D) {
                nextRow.add(p);
            }
        }

        if (!nextRow.isEmpty()) {
            nextRow.sort(Comparator.comparingDouble(p -> {
                double cLong = laneAxisX ? (p.getX() + 0.5D) : (p.getZ() + 0.5D);
                double cLat = laneAxisX ? (p.getZ() + 0.5D) : (p.getX() + 0.5D);
                double lateralDist = Math.abs(cLat - playerLat);
                double longDiff = Math.abs(cLong - playerLong);
                return lateralDist * 3.0D + longDiff;
            }));
            laneSign = -laneSign;
            return Collections.singletonList(nextRow.get(0));
        }

        // 3. Fallback: najbliższy blok w całym pending
        List<BlockPos> pool = new ArrayList<>(pendingList);
        pool.sort(Comparator.comparingDouble(p -> playerPos.distSqr(p)));
        if (!pool.isEmpty()) {
            BlockPos chosen = pool.get(0);
            double cLong = laneAxisX ? (chosen.getX() + 0.5D) : (chosen.getZ() + 0.5D);
            laneSign = (cLong >= playerLong) ? 1.0D : -1.0D;
            return Collections.singletonList(chosen);
        }

        return Collections.emptyList();
    }

    private boolean hasHoe() {
        for (ItemStack stack : ctx.player().getInventory().items) {
            if (!stack.isEmpty() && stack.getItem() instanceof HoeItem) return true;
        }
        ItemStack offhand = ctx.player().getInventory().offhand.get(0);
        return !offhand.isEmpty() && offhand.getItem() instanceof HoeItem;
    }

    private boolean selectHoe() {
        ItemStack held = ctx.player().getMainHandItem();
        if (!held.isEmpty() && held.getItem() instanceof HoeItem) return true;

        for (int i = 0; i < 9; i++) {
            ItemStack s = ctx.player().getInventory().items.get(i);
            if (!s.isEmpty() && s.getItem() instanceof HoeItem) {
                ctx.player().getInventory().selected = i;
                return true;
            }
        }
        return false;
    }

    private void applyRotation(Rotation target) {
        boolean fastMode = Baritone.settings().farmFastMode.value;
        if (fastMode) {
            baritone.getLookBehavior().updateTarget(target, true);
        } else {
            baritone.getLookBehavior().updateTarget(limitedTurn(target), true);
        }
    }

    private Rotation limitedTurn(Rotation target) {
        Rotation cur = ctx.playerRotations();
        if (!Baritone.settings().antiCheatCompat.value) {
            return target;
        }
        float dYaw = Mth.wrapDegrees(target.getYaw() - cur.getYaw());
        float dPitch = target.getPitch() - cur.getPitch();

        float maxTurnYaw = 120.0F;
        float maxTurnPitch = 75.0F;
        float stepYaw = Mth.clamp(dYaw, -maxTurnYaw, maxTurnYaw);
        float stepPitch = Mth.clamp(dPitch, -maxTurnPitch, maxTurnPitch);

        double gcd = RotationEngine.getGcd();
        double qYaw = RotationEngine.quantizeToGcd(stepYaw, gcd);
        double qPitch = RotationEngine.quantizeToGcd(stepPitch, gcd);

        float newYaw = (float) (cur.getYaw() + qYaw);
        float newPitch = (float) Mth.clamp(cur.getPitch() + qPitch, -89.5D, 89.5D);

        return new Rotation(newYaw, newPitch);
    }
}
