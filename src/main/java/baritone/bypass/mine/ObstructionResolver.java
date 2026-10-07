package baritone.bypass.mine;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.level.BlockGetter;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.function.Predicate;
import java.util.function.ToDoubleFunction;

/**
 * Ruda zasłonięta jednym (lub dwoma) blokami w zasięgu: zamiast kręcić się lub obchodzić dookoła,
 * wyznacza minimalny kosztowo zestaw bloków-przeszkód, po usunięciu których cel ma ODPORNY punkt
 * celowania — weryfikowane symulacją "co by było, gdyby" (nakładka powietrza w {@link VoxelRay}).
 */
public final class ObstructionResolver {

    public sealed interface Result permits Direct, Clear, Impossible {}

    /** Cel jest już bezpośrednio trafialny — żadnej przeszkody. */
    public record Direct(FaceAimSolver.Aim aim) implements Result {}

    /** Najpierw skopać 'obstacles' (w tej kolejności), potem cel parametrem 'finalAim'. */
    public record Clear(List<BlockPos> obstacles, FaceAimSolver.Aim finalAim) implements Result {}

    /** Brak sensownego planu — zmiana miejsca stania albo odpuszczenie bloku. */
    public record Impossible(String why) implements Result {}

    private final FaceAimSolver solver;
    private final double blockRange;
    private final int maxDepth;

    public ObstructionResolver(FaceAimSolver solver, double blockRange, int maxDepth) {
        this.solver = solver;
        this.blockRange = blockRange;
        this.maxDepth = maxDepth;
    }

    public Result resolve(BlockGetter level, List<Vec3> eyes, BlockPos target, float yaw, float pitch,
                          AABB body, boolean onGround,
                          Predicate<BlockPos> breakable, ToDoubleFunction<BlockPos> ticks, double maxExtraTicks) {
        Set<BlockPos> removed = new LinkedHashSet<>();
        List<BlockPos> order = new ArrayList<>();
        double spent = 0.0;

        for (int depth = 0; depth <= maxDepth; depth++) {
            Optional<FaceAimSolver.Aim> aim = solver.solve(level, eyes, target, yaw, pitch, removed, null);
            if (aim.isPresent()) {
                return order.isEmpty() ? new Direct(aim.get()) : new Clear(List.copyOf(order), aim.get());
            }
            if (depth == maxDepth) break;

            BlockPos pick = pickBlocker(level, eyes, target, yaw, pitch, body, onGround, removed, breakable, ticks);
            if (pick == null) return new Impossible("brak usuwalnej przeszkody w zasięgu");
            spent += ticks.applyAsDouble(pick);
            if (spent > maxExtraTicks) return new Impossible("koszt usuwania przekracza budżet");
            removed.add(pick); // kolejne iteracje liczą świat tak, jakby pick już zniknął
            order.add(pick);
        }
        return new Impossible("więcej niż " + maxDepth + " przeszkód");
    }

    /**
     * Głosowanie: rzucamy rzadką siatkę promieni w widoczne ściany celu i liczymy, który blok jest
     * pierwszym trafionym (≠ target) w zasięgu. Wygrywa najtańszy na jeden "zablokowany promień",
     * o ile sam jest bezpieczny, kopalny i ODPORNIE trafialny.
     */
    private BlockPos pickBlocker(BlockGetter level, List<Vec3> eyes, BlockPos target, float yaw, float pitch,
                                 AABB body, boolean onGround, Set<BlockPos> removed,
                                 Predicate<BlockPos> breakable, ToDoubleFunction<BlockPos> ticks) {
        if (eyes == null || eyes.isEmpty()) return null;
        Vec3 eye = eyes.get(0);
        double limit = blockRange - 0.35;
        Map<BlockPos, Integer> votes = new HashMap<>();
        for (Direction d : Direction.values()) {
            if (!FaceAimSolver.faceTowardsEye(target, d, eye)) continue;
            for (int i = 0; i < 5; i++) {
                for (int j = 0; j < 5; j++) {
                    Vec3 p = FaceAimSolver.facePoint(target, d, 0.1 + 0.8 * i / 4.0, 0.1 + 0.8 * j / 4.0, 0.02);
                    Vec3 dir = p.subtract(eye).normalize();
                    VoxelRay.Hit h = VoxelRay.cast(level, eye, eye.add(dir.scale(blockRange)), removed);
                    if (h == null || h.pos().equals(target)) continue;
                    if (h.location().distanceTo(eye) > limit) continue; // za daleko = problem zasięgu, nie przeszkoda
                    votes.merge(h.pos(), 1, Integer::sum);
                }
            }
        }
        BlockPos best = null;
        double bestCost = Double.MAX_VALUE;
        for (var e : votes.entrySet()) {
            BlockPos b = e.getKey();
            if (breakable != null && !breakable.test(b)) continue;
            if (SafetyGate.check(level, b, body, onGround) != SafetyGate.Hazard.NONE) continue;
            if (solver.solve(level, eyes, b, yaw, pitch, removed, null).isEmpty()) continue;
            double cost = (ticks != null ? ticks.applyAsDouble(b) : 1.0) / e.getValue();
            if (cost < bestCost) {
                bestCost = cost;
                best = b;
            }
        }
        return best;
    }
}
