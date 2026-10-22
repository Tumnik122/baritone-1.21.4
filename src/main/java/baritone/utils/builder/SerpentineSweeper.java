package baritone.utils.builder;

import net.minecraft.core.BlockPos;
import net.minecraft.world.phys.Vec3;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/**
 * Boustrophedon traversal for ONE horizontal layer of a rectangular fill.
 *
 * Geometry ("trailing" mode): the player stands on the finished layer below
 * (feet Y == layerY), walks along a row and fills the cells it has just left,
 * placing against the TOP face of the (already solid) block under each target cell.
 * Walking forward into a cell you are about to fill is impossible for a solid
 * volume, so blocks are placed BEHIND the player, not ahead of him.
 *
 * No world access in here -> easy to unit test.
 */
public final class SerpentineSweeper {

    public record MoveKeys(boolean forward, boolean back, boolean left, boolean right) {}

    private final int minX, maxX, minZ, maxZ, layerY;
    private final boolean alongX;
    private final int rows;
    private int row;

    public SerpentineSweeper(int minX, int maxX, int minZ, int maxZ, int layerY, boolean alongX) {
        this.minX = minX;
        this.maxX = maxX;
        this.minZ = minZ;
        this.maxZ = maxZ;
        this.layerY = layerY;
        this.alongX = alongX;
        this.rows = alongX ? (maxZ - minZ + 1) : (maxX - minX + 1);
    }

    // ---------- row bookkeeping ----------

    public boolean finished() { return row >= rows; }
    public void advance() { row++; }
    public int row() { return row; }
    public int dir() { return (row & 1) == 0 ? 1 : -1; }
    public int rowLength() { return hi() - lo() + 1; }

    private int rowFixed(int r) { return (alongX ? minZ : minX) + r; }
    private int lo() { return alongX ? minX : minZ; }
    private int hi() { return alongX ? maxX : maxZ; }
    private int startCoord() { return dir() > 0 ? lo() : hi(); }
    private int endCoord() { return dir() > 0 ? hi() : lo(); }

    private BlockPos cell(int along, int fixed) {
        return alongX ? new BlockPos(along, layerY, fixed) : new BlockPos(fixed, layerY, along);
    }

    private static Vec3 center(BlockPos p) { return new Vec3(p.getX() + 0.5, p.getY(), p.getZ() + 0.5); }

    public BlockPos startCell() { return cell(startCoord(), rowFixed(row)); }
    public Vec3 startPoint() { return center(startCell()); }

    private double alongOf(Vec3 p) { return alongX ? p.x : p.z; }
    private double fixedOf(Vec3 p) { return alongX ? p.z : p.x; }

    /** Player has reached (almost) the centre of the last cell of the row. */
    public boolean rowDone(Vec3 pos) {
        return (alongOf(pos) - (endCoord() + 0.5)) * dir() >= -0.15;
    }

    /** Desired horizontal movement direction (x,z in a Vec3, y = 0), with lane-keeping on the row line. */
    public Vec3 moveDirection(Vec3 pos) {
        double lane = (rowFixed(row) + 0.5) - fixedOf(pos);
        lane = Math.max(-0.6, Math.min(0.6, lane * 2.0));
        double a = dir();
        return alongX ? new Vec3(a, 0, lane) : new Vec3(lane, 0, a);
    }

    // ---------- placement candidates ----------

    /**
     * Cells (current row + previous row leftovers) inside a trailing window behind the player,
     * nearest to the player first. Caller filters by "still needs a block" / reachability.
     */
    public List<BlockPos> candidates(Vec3 pos, int maxBack) {
        int pa = (int) Math.floor(alongOf(pos));
        int from = pa - dir() * maxBack;
        int aLo = Math.max(lo(), Math.min(from, pa));
        int aHi = Math.min(hi(), Math.max(from, pa));
        List<BlockPos> out = new ArrayList<>();
        for (int r = row; r >= Math.max(0, row - 1); r--) {
            for (int a = aLo; a <= aHi; a++) out.add(cell(a, rowFixed(r)));
        }
        out.sort(Comparator.comparingDouble(c -> center(c).distanceToSqr(pos.x, c.getY(), pos.z)));
        return out;
    }

    // ---------- steering ----------

    /**
     * Converts a desired world direction into WASD relative to the yaw we are commanding.
     * Lets the bot keep walking along the row while LookBehavior aims backwards/down.
     */
    public static MoveKeys steer(double dx, double dz, float yawDeg) {
        double len = Math.hypot(dx, dz);
        if (len < 1e-4) return new MoveKeys(false, false, false, false);
        dx /= len;
        dz /= len;
        double yaw = Math.toRadians(yawDeg);
        double fx = -Math.sin(yaw), fz = Math.cos(yaw);   // forward
        double rx = -Math.cos(yaw), rz = -Math.sin(yaw);  // right-hand
        double f = dx * fx + dz * fz;
        double s = dx * rx + dz * rz;
        return new MoveKeys(f > 0.35, f < -0.35, s < -0.35, s > 0.35);
    }
}
