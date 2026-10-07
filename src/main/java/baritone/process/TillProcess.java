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
import baritone.api.process.PathingCommand;
import baritone.api.process.PathingCommandType;
import baritone.api.selection.ISelection;
import baritone.api.utils.BetterBlockPos;
import baritone.api.utils.Rotation;
import baritone.api.utils.RotationUtils;
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

        logDirect("§a[Till] Zaczynam oranie " + pending.size() + " bloków. Użyj #stop aby zatrzymać.");
        active = true;
        tillTick = 0L;
        currentTarget = null;
        targetTicks = 0;
    }

    @Override
    public boolean isActive() {
        return active;
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
            logDirect("§a[Till] Oranie zakończone! Wszystkie bloki zaorzone.");
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

        BetterBlockPos playerPos = ctx.playerFeet();
        Vec3 head = ctx.playerHead();
        double reachDist = Math.min(3.9, ctx.playerController().getBlockReachDistance());
        double reach2 = reachDist * reachDist;

        // Szukaj bloków w zasięgu do natychmiastowego zaorania
        List<BlockPos> reachable = new ArrayList<>();
        for (BlockPos pos : pending) {
            Vec3 center = Vec3.atCenterOf(pos);
            if (head.distanceToSqr(center) <= reach2) {
                reachable.add(pos);
            }
        }

        // Sortuj po odległości
        reachable.sort(Comparator.comparingDouble(p ->
                head.distanceToSqr(p.getX() + 0.5, p.getY() + 0.5, p.getZ() + 0.5)));

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

                // Wybierz motykę
                if (selectHoe()) {
                    ctx.playerController().syncHeldItem();

                    if (pos.equals(currentTarget)) {
                        targetTicks++;
                    } else {
                        currentTarget = pos;
                        targetTicks = 0;
                    }

                    // Kliknij prawym na górną powierzchnię bloku
                    Vec3 hitVec = new Vec3(pos.getX() + 0.5, pos.getY() + 1.0, pos.getZ() + 0.5);
                    BlockHitResult hit = new BlockHitResult(hitVec, Direction.UP, pos, false);
                    InteractionResult result = ctx.playerController().processRightClickBlock(
                            ctx.player(), ctx.world(), InteractionHand.MAIN_HAND, hit);

                    if (result.consumesAction()) {
                        ctx.player().swing(InteractionHand.MAIN_HAND);
                        pending.remove(pos);
                        currentTarget = null;
                        targetTicks = 0;
                        return new PathingCommand(null, PathingCommandType.REQUEST_PAUSE);
                    }

                    // Zbyt długo przy tym bloku — skip
                    if (targetTicks >= 15) {
                        pending.remove(pos);
                        currentTarget = null;
                        targetTicks = 0;
                    }

                    return new PathingCommand(null, PathingCommandType.REQUEST_PAUSE);
                }
            }
        }

        // Żaden blok nie w zasięgu — idź do najbliższego
        currentTarget = null;
        targetTicks = 0;

        // Buduj cele pathingu (najbliższe 16 bloków)
        List<BlockPos> sorted = new ArrayList<>(pending);
        sorted.sort(Comparator.comparingDouble(p -> playerPos.distSqr(p)));

        List<Goal> goals = new ArrayList<>();
        for (int i = 0; i < Math.min(16, sorted.size()); i++) {
            goals.add(new GoalGetToBlock(sorted.get(i)));
        }

        if (goals.isEmpty()) {
            return new PathingCommand(null, PathingCommandType.REQUEST_PAUSE);
        }

        return new PathingCommand(new GoalComposite(goals.toArray(new Goal[0])), PathingCommandType.SET_GOAL_AND_PATH);
    }

    private boolean hasHoe() {
        for (ItemStack stack : ctx.player().getInventory().items) {
            if (!stack.isEmpty() && stack.getItem() instanceof HoeItem) return true;
        }
        // Sprawdź też offhand
        ItemStack offhand = ctx.player().getInventory().offhand.get(0);
        return !offhand.isEmpty() && offhand.getItem() instanceof HoeItem;
    }

    private boolean selectHoe() {
        // Sprawdź aktualnie trzymany przedmiot
        ItemStack held = ctx.player().getMainHandItem();
        if (!held.isEmpty() && held.getItem() instanceof HoeItem) return true;

        // Szukaj motyki w hotbarze
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
            double gcd = RotationEngine.getGcd();
            float curY = ctx.player().getYRot();
            float curP = ctx.player().getXRot();
            double qY = RotationEngine.quantizeToGcd(Mth.wrapDegrees(target.getYaw() - curY), gcd);
            double qP = RotationEngine.quantizeToGcd(target.getPitch() - curP, gcd);
            ctx.player().setYRot((float) (curY + qY));
            ctx.player().setXRot((float) Mth.clamp(curP + qP, -89.5, 89.5));
            baritone.getLookBehavior().updateTarget(target, true);
        } else {
            baritone.getLookBehavior().updateTarget(target, true);
        }
    }
}
