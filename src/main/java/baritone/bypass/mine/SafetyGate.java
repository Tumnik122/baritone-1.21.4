package baritone.bypass.mine;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.util.Mth;
import net.minecraft.world.level.BlockGetter;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.FallingBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.AABB;

/**
 * Twarde reguły bezpieczeństwa "czy wolno teraz zniszczyć blok p".
 * Chroni przed:
 *  - wlewaniem lawy/wody (z góry lub boków),
 *  - osuwiskami (spadający piasek/żwir w kolumnie nad graczem),
 *  - zniszczeniem podłogi pod własnymi stopami, jeśli upadek byłby > 3 bloki lub w niebezpieczne podłoże.
 */
public final class SafetyGate {

    public enum Hazard { NONE, FLUID, FALLING_COLUMN, UNSAFE_FLOOR }

    private SafetyGate() {}

    public static Hazard check(BlockGetter level, BlockPos p, AABB body, boolean onGround) {
        if (fluidNeighbour(level, p)) return Hazard.FLUID;
        if (fallingAbove(level, p) && playerInColumnBelow(p, body)) return Hazard.FALLING_COLUMN;
        if (isSupportCell(p, body) && !floorBreakSafe(level, p, body, onGround)) return Hazard.UNSAFE_FLOOR;
        return Hazard.NONE;
    }

    /** Płyn wlewa się do pustej komórki z góry i z boków, nigdy "od dołu" - stąd pomijamy DOWN. */
    public static boolean fluidNeighbour(BlockGetter level, BlockPos p) {
        for (Direction d : Direction.values()) {
            if (d == Direction.DOWN) continue;
            if (!level.getFluidState(p.relative(d)).isEmpty()) return true;
        }
        return false;
    }

    public static boolean fallingAbove(BlockGetter level, BlockPos p) {
        return level.getBlockState(p.above()).getBlock() instanceof FallingBlock;
    }

    /**
     * Spadający blok ma hitbox 0.98x0.98 wyśrodkowany w kolumnie - gracz przylegający do ściany
     * (maxX == p.x) NIE jest pod nim. Dlatego nakładanie liczymy ściśle, bez marginesu.
     */
    public static boolean playerInColumnBelow(BlockPos p, AABB body) {
        if (body == null) return false;
        return body.maxX > p.getX() + 0.01 && body.minX < p.getX() + 0.99
                && body.maxZ > p.getZ() + 0.01 && body.minZ < p.getZ() + 0.99
                && p.getY() > Mth.floor(body.minY + 1.0E-3);
    }

    /** Komórka bezpośrednio pod stopami, na której hitbox gracza faktycznie się opiera. */
    public static boolean isSupportCell(BlockPos p, AABB body) {
        if (body == null) return false;
        return p.getY() == Mth.floor(body.minY - 1.0E-3)
                && body.maxX > p.getX() && body.minX < p.getX() + 1
                && body.maxZ > p.getZ() && body.minZ < p.getZ() + 1;
    }

    public static boolean floorBreakSafe(BlockGetter level, BlockPos p, AABB body, boolean onGround) {
        if (!onGround) return false;
        if (body == null) return true;
        // 1) Czy po zniszczeniu p hitbox nadal na czymś stoi?
        int x0 = Mth.floor(body.minX + 1.0E-3), x1 = Mth.floor(body.maxX - 1.0E-3);
        int z0 = Mth.floor(body.minZ + 1.0E-3), z1 = Mth.floor(body.maxZ - 1.0E-3);
        for (int x = x0; x <= x1; x++) {
            for (int z = z0; z <= z1; z++) {
                BlockPos c = new BlockPos(x, p.getY(), z);
                if (c.equals(p)) continue;
                if (!level.getBlockState(c).getCollisionShape(level, c).isEmpty()) return true;
            }
        }
        // 2) Brak innego oparcia: dopuszczalny kontrolowany upadek <= 3 bloki na bezpieczne podłoże
        for (int k = 1; k <= 4; k++) {
            BlockPos q = p.below(k);
            if (!level.getFluidState(q).isEmpty()) return false;
            BlockState s = level.getBlockState(q);
            if (!s.getCollisionShape(level, q).isEmpty()) {
                return k <= 3 && !s.is(Blocks.MAGMA_BLOCK) && !s.is(Blocks.CACTUS)
                        && !s.is(Blocks.CAMPFIRE) && !s.is(Blocks.FIRE);
            }
        }
        return false;
    }
}
