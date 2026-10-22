package baritone.utils.builder;

import baritone.api.IBaritone;
import baritone.api.pathing.goals.GoalBlock;
import baritone.api.process.PathingCommand;
import baritone.api.process.PathingCommandType;
import baritone.api.utils.IPlayerContext;
import baritone.api.utils.Rotation;
import baritone.api.utils.input.Input;
import net.minecraft.client.Minecraft;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.inventory.ClickType;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.phys.Vec3;

/**
 * Drives one layer of a rectangular fill. NOT compiled/tested against your fork -
 * check the API calls marked (VERIFY).
 *
 * ---- Hook in BuilderProcess ----
 * field:  private SweepDriver sweep;
 *
 * in build(...) / when a FillSchematic is the active schematic and no sweep is running:
 *     // lowest layer that still has missing cells; (x0,y0,z0) = schematic origin
 *     sweep = new SweepDriver(baritone,
 *         new SerpentineSweeper(x0, x0 + w - 1, z0, z0 + l - 1, layerY, true), fillBlock);
 *
 * at the very top of onTick(calcFailed, isSafeToCancel):
 *     if (sweep != null) {
 *         PathingCommand c = sweep.onTick(isSafeToCancel);
 *         if (c != null) return c;
 *         sweep = null;   // finished / bailed out -> normal BuilderProcess logic cleans up leftovers,
 *     }                   // then start a new sweep for layerY + 1
 *
 * Also: Baritone.settings().rightClickSpeed.value = 2;  (VERIFY: BlockPlaceHelper's own delay;
 * the scheduler enforces its 2/3-tick gap on top of it.)
 */
public final class SweepDriver {

    private static final int TRAIL = 3;          // how many cells behind the player are considered
    private static final int AIR_GRACE = 6;

    private final IBaritone baritone;
    private final SerpentineSweeper sweeper;
    private final Block fill;
    private final SweepPlacementScheduler scheduler = new SweepPlacementScheduler();

    private boolean approaching = true;
    private int airTicks;
    private int tick;
    private int lastInvTick = -100;
    private float cmdYaw;
    private boolean restockGate;

    public SweepDriver(IBaritone baritone, SerpentineSweeper sweeper, Block fill) {
        this.baritone = baritone;
        this.sweeper = sweeper;
        this.fill = fill;
    }

    /** @return a PathingCommand to keep control, or null when the sweep is over / must bail out. */
    public PathingCommand onTick(boolean isSafeToCancel) {
        tick++;
        IPlayerContext ctx = baritone.getPlayerContext();
        LocalPlayer p = ctx.player();
        Level level = ctx.world();

        if (sweeper.finished()) {
            releaseAll();
            return null;
        }
        airTicks = p.onGround() ? 0 : airTicks + 1;
        if (airTicks > AIR_GRACE || p.isInWater() || p.isInLava()) {
            releaseAll();
            return null;
        }

        // 1) get to the start of the layer with normal pathing, once
        if (approaching) {
            Vec3 s = sweeper.startPoint();
            if (Math.hypot(p.getX() - s.x, p.getZ() - s.z) < 0.35 && p.onGround()
                    && Math.abs(p.getY() - s.y) < 0.2) {
                approaching = false;
                restockGate = true;
            } else {
                releaseAll();
                return new PathingCommand(new GoalBlock(sweeper.startCell()), PathingCommandType.SET_GOAL_AND_PATH);
            }
        }

        // 2) hotbar: only ever touched while standing still (row start / row end)
        boolean still = p.getDeltaMovement().horizontalDistanceSqr() < 0.0004;
        if (restockGate) {
            if (hotbarCount(p) >= sweeper.rowLength() + 4 || !restockOnce(p, still)) {
                restockGate = false;
            } else {
                releaseAll();
                return new PathingCommand(null, PathingCommandType.REQUEST_PAUSE);
            }
        }
        int slot = findHotbarSlot(p);
        if (slot < 0) {                      // empty mid-row: stop, restock, continue
            releaseAll();
            if (!restockOnce(p, still)) return null; // nothing left in inventory -> let BuilderProcess handle it
            return new PathingCommand(null, PathingCommandType.REQUEST_PAUSE);
        }
        if (p.getInventory().selected != slot) {
            p.getInventory().selected = slot;
            if (p.connection != null) {
                p.connection.send(new net.minecraft.network.protocol.game.ServerboundSetCarriedItemPacket(slot));
            }
        }

        // 3) placement (aim / click)
        Vec3 pos = p.position();
        SweepPlacementScheduler.Decision d = scheduler.step(level, p,
                sweeper.candidates(pos, TRAIL), cell -> needsBlock(level, cell));
        if (d.aim() != null) {
            cmdYaw = d.aim().yaw();
            baritone.getLookBehavior().updateTarget(new Rotation(d.aim().yaw(), d.aim().pitch()), true);
        } else if (cmdYaw == 0f) {
            cmdYaw = p.getYRot();
        }
        baritone.getInputOverrideHandler().setInputForceState(Input.CLICK_RIGHT, d.click());

        // 4) continuous movement along the row, independent of where the camera points
        Vec3 dir = sweeper.moveDirection(pos);
        SerpentineSweeper.MoveKeys k = SerpentineSweeper.steer(dir.x, dir.z, cmdYaw);
        baritone.getInputOverrideHandler().setInputForceState(Input.MOVE_FORWARD, k.forward());
        baritone.getInputOverrideHandler().setInputForceState(Input.MOVE_BACK, k.back());
        baritone.getInputOverrideHandler().setInputForceState(Input.MOVE_LEFT, k.left());
        baritone.getInputOverrideHandler().setInputForceState(Input.MOVE_RIGHT, k.right());
        baritone.getInputOverrideHandler().setInputForceState(Input.SPRINT, false);

        // 5) edge safety: sneak when the floor 0.8 blocks ahead is not solid
        Vec3 n = dir.normalize();
        BlockPos ahead = BlockPos.containing(pos.x + n.x * 0.8, pos.y - 0.5, pos.z + n.z * 0.8);
        boolean floorAhead = level.getBlockState(ahead).isFaceSturdy(level, ahead, Direction.UP);
        baritone.getInputOverrideHandler().setInputForceState(Input.SNEAK, !floorAhead);

        // 6) row bookkeeping
        if (sweeper.rowDone(pos)) {
            sweeper.advance();
            restockGate = !sweeper.finished();
        }
        return new PathingCommand(null, PathingCommandType.REQUEST_PAUSE); // (VERIFY) keeps pathing paused, inputs stay ours
    }

    // ---------------- helpers ----------------

    private boolean needsBlock(Level level, BlockPos cell) {
        var st = level.getBlockState(cell);
        return !st.is(fill) && st.canBeReplaced();
    }

    public void releaseAll() {
        for (Input i : new Input[]{Input.MOVE_FORWARD, Input.MOVE_BACK, Input.MOVE_LEFT, Input.MOVE_RIGHT,
                Input.SNEAK, Input.SPRINT, Input.CLICK_RIGHT, Input.JUMP}) {
            baritone.getInputOverrideHandler().setInputForceState(i, false);
        }
        if (baritone.getLookBehavior() != null) {
            baritone.getLookBehavior().updateTarget(null, false);
        }
    }

    private int findHotbarSlot(LocalPlayer p) {
        for (int i = 0; i < 9; i++) if (isFill(p.getInventory().getItem(i))) return i;
        return -1;
    }

    private int hotbarCount(LocalPlayer p) {
        int n = 0;
        for (int i = 0; i < 9; i++) {
            ItemStack s = p.getInventory().getItem(i);
            if (isFill(s)) n += s.getCount();
        }
        return n;
    }

    private boolean isFill(ItemStack s) {
        return !s.isEmpty() && s.getItem() == fill.asItem();
    }

    /**
     * Moves one stack from main inventory into the hotbar (SWAP click).
     * Debounced to >= 3 ticks between clicks and only while standing still.
     * @return false if there is nothing to restock from (or no free hotbar slot)
     */
    private boolean restockOnce(LocalPlayer p, boolean still) {
        int from = -1;
        for (int i = 9; i < 36; i++) if (isFill(p.getInventory().getItem(i))) { from = i; break; }
        if (from < 0) return false;

        int to = -1;
        for (int i = 0; i < 9; i++) if (p.getInventory().getItem(i).isEmpty()) { to = i; break; }
        if (to < 0) return false;

        if (still && tick - lastInvTick >= 3) {
            Minecraft mc = Minecraft.getInstance();
            mc.gameMode.handleInventoryMouseClick(p.inventoryMenu.containerId, from, to, ClickType.SWAP, p);
            lastInvTick = tick;
        }
        return true;
    }
}
