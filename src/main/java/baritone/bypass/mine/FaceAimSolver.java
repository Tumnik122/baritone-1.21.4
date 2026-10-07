package baritone.bypass.mine;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.util.Mth;
import net.minecraft.world.level.BlockGetter;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.Vec3;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;
import java.util.Set;

/**
 * Wybór ściany + punktu trafienia, który jest odporny na:
 *  - ocieranie promienia o krawędź sąsiada (test "pierścienia" zaburzeń kątowych),
 *  - kwantyzację rotacji (GCD myszy),
 *  - przesunięcie oczu między stanem znanym serwerowi a następnym pakietem (Eyes / LastSent),
 *  - limit zasięgu (liczony do punktu trafienia, z zapasem).
 */
public final class FaceAimSolver {

    public record Config(double blockRange, double reachSafety, int grid, double faceMargin,
                         double inset, double robustDeg, int finalists) {
        public static Config defaults(double blockRange, RotationGrid g) {
            // robustDeg: promień pierścienia zaburzeń; >= 2.5 kroku siatki rotacji
            return new Config(blockRange, 0.35, 7, 0.08, 0.02, Math.max(0.35, 2.5 * g.stepDeg()), 8);
        }
    }

    /** Wynik: ściana = ta, którą zwraca waniliowy raycast (i która pójdzie w pakiecie). */
    public record Aim(BlockPos block, Direction face, Vec3 point, float yaw, float pitch,
                      double reach, double clearanceDeg) {}

    private record Cand(Direction face, Vec3 point, float yaw, float pitch, double rotDelta, double offCenter) {}

    private static final double DEG = 180.0 / Math.PI;

    private final Config cfg;
    private final RotationGrid grid;

    public FaceAimSolver(Config cfg, RotationGrid grid) {
        this.cfg = cfg;
        this.grid = grid;
    }

    /**
     * @param eyes     pozycje oczu z {@link Eyes#forNextPacket}
     * @param removed  bloki traktowane jako powietrze (do symulacji "po usunięciu przeszkody"); może być pusty
     * @param previous poprzedni wynik dla tego samego bloku (histereza - brak skakania między punktami); może być null
     */
    public Optional<Aim> solve(BlockGetter level, List<Vec3> eyes, BlockPos target,
                               float curYaw, float curPitch, Set<BlockPos> removed, Aim previous) {
        if (eyes == null || eyes.isEmpty()) return Optional.empty();
        double reachLimit = cfg.blockRange() - cfg.reachSafety();
        Vec3 eye0 = eyes.get(0);

        // 0) Histereza: jeśli poprzedni punkt nadal ma wystarczający luz - zostajemy przy nim
        if (previous != null && previous.block().equals(target)) {
            Cand c = candidate(eye0, previous.face(), previous.point(), curYaw, curPitch, 0.0);
            Optional<Aim> keep = evaluate(level, eyes, target, c, removed, reachLimit);
            if (keep.isPresent() && keep.get().clearanceDeg() >= cfg.robustDeg()) return keep;
        }

        // 1) Kandydaci: siatka na każdej ścianie zwróconej ku oku, z marginesem od krawędzi
        List<Cand> cands = new ArrayList<>();
        double m = cfg.faceMargin();
        int n = cfg.grid();
        for (Direction d : Direction.values()) {
            if (!faceTowardsEye(target, d, eye0)) continue;
            BlockPos nb = target.relative(d);
            if (!removed.contains(nb)) {
                BlockState nbState = level.getBlockState(nb);
                if (!nbState.isAir() && Block.isFaceFull(nbState.getShape(level, nb), d.getOpposite())) {
                    continue;
                }
            }
            for (int i = 0; i < n; i++) {
                for (int j = 0; j < n; j++) {
                    double u = m + (1 - 2 * m) * (i + 0.5) / n;
                    double v = m + (1 - 2 * m) * (j + 0.5) / n;
                    cands.add(candidate(eye0, d, facePoint(target, d, u, v, cfg.inset()),
                            curYaw, curPitch, Math.hypot(u - 0.5, v - 0.5)));
                }
            }
        }
        cands.sort(Comparator.comparingDouble(Cand::rotDelta)); // najpierw najmniejszy ruch kamery

        // 2) Tani test (1 promień na oko) -> drogi test odporności tylko dla finalistów
        Optional<Aim> best = Optional.empty();
        double bestScore = -1e9;
        int finals = 0;
        double rho = cfg.robustDeg();
        for (Cand c : cands) {
            if (check(level, eyes, c.yaw(), c.pitch(), target, c.face(), removed, reachLimit) == null) continue;
            Optional<Aim> a = evaluate(level, eyes, target, c, removed, reachLimit);
            if (a.isEmpty() || a.get().clearanceDeg() < 0.5 * rho) continue;
            double score = Math.min(a.get().clearanceDeg(), 2 * rho) / rho
                    - c.rotDelta() / 90.0
                    - 0.3 * c.offCenter();
            if (score > bestScore) {
                bestScore = score;
                best = a;
            }
            if (++finals >= cfg.finalists()) break;
        }
        return best;
    }

    private Optional<Aim> evaluate(BlockGetter level, List<Vec3> eyes, BlockPos target, Cand c,
                                   Set<BlockPos> removed, double reachLimit) {
        Direction face = check(level, eyes, c.yaw(), c.pitch(), target, c.face(), removed, reachLimit);
        if (face == null) return Optional.empty();
        Vec3 eye0 = eyes.get(0);
        VoxelRay.Hit h = VoxelRay.cast(level, eye0,
                eye0.add(look(c.yaw(), c.pitch()).scale(cfg.blockRange())), removed);
        double reach = h == null ? 0.0 : h.location().distanceTo(eye0);
        double clr = clearance(level, eyes, c, target, face, removed, reachLimit);
        return Optional.of(new Aim(target, face, c.point(), c.yaw(), c.pitch(), reach, clr));
    }

    /**
     * Promień z KAŻDEGO oka (rotacja po kwantyzacji) musi: trafić dokładnie w target, w tę samą ścianę,
     * w odległości (oko -> punkt trafienia) <= blockRange - reachSafety.
     */
    private Direction check(BlockGetter level, List<Vec3> eyes, float yaw, float pitch, BlockPos target,
                            Direction expect, Set<BlockPos> removed, double reachLimit) {
        Vec3 look = look(yaw, pitch);
        Direction seen = expect;
        for (Vec3 eye : eyes) {
            VoxelRay.Hit h = VoxelRay.cast(level, eye, eye.add(look.scale(cfg.blockRange())), removed);
            if (h == null || !h.pos().equals(target)) return null;
            if (h.location().distanceTo(eye) > reachLimit) return null;
            if (seen == null) seen = h.face();
            else if (seen != h.face()) return null;
        }
        return seen;
    }

    /**
     * Kątowy "luz" punktu: największy promień r (stopnie), dla którego wszystkie 8 zaburzeń o promieniu r
     * (yaw/pitch) nadal trafiają w ten sam blok i ścianę. 0 = punkt ociera się o krawędź sąsiada.
     */
    private double clearance(BlockGetter level, List<Vec3> eyes, Cand c, BlockPos target, Direction face,
                             Set<BlockPos> removed, double reachLimit) {
        double rho = cfg.robustDeg();
        double[] radii = {0.25 * rho, 0.5 * rho, rho, 1.5 * rho, 2.0 * rho};
        double ok = 0.0;
        double cosPitch = Math.max(0.2, Math.cos(Math.toRadians(c.pitch())));
        for (double r : radii) {
            boolean pass = true;
            for (int k = 0; k < 8 && pass; k++) {
                double a = k * Math.PI / 4.0;
                float dy = (float) (r * Math.cos(a) / cosPitch); // yaw skalowany, by promień kątowy był stały
                float dp = (float) (r * Math.sin(a));
                pass = check(level, eyes, c.yaw() + dy, Mth.clamp(c.pitch() + dp, -90f, 90f),
                        target, face, removed, reachLimit) == face;
            }
            if (!pass) break;
            ok = r;
        }
        return ok;
    }

    private Cand candidate(Vec3 eye, Direction face, Vec3 p, float curYaw, float curPitch, double offCenter) {
        Vec3 dir = p.subtract(eye);
        float yaw = grid.snapYaw(curYaw, yawOf(dir));
        float pitch = grid.snapPitch(curPitch, pitchOf(dir));
        double delta = angleDeg(look(curYaw, curPitch), look(yaw, pitch));
        return new Cand(face, p, yaw, pitch, delta, offCenter);
    }

    // ------------------------------------------------------------------ Geometria

    /** Ściana jest widoczna tylko wtedy, gdy oko leży PO ZEWNĘTRZNEJ stronie jej płaszczyzny. */
    public static boolean faceTowardsEye(BlockPos pos, Direction d, Vec3 eye) {
        double plane = switch (d.getAxis()) {
            case X -> pos.getX() + (d.getAxisDirection() == Direction.AxisDirection.POSITIVE ? 1.0 : 0.0);
            case Y -> pos.getY() + (d.getAxisDirection() == Direction.AxisDirection.POSITIVE ? 1.0 : 0.0);
            case Z -> pos.getZ() + (d.getAxisDirection() == Direction.AxisDirection.POSITIVE ? 1.0 : 0.0);
        };
        double e = switch (d.getAxis()) {
            case X -> eye.x;
            case Y -> eye.y;
            case Z -> eye.z;
        };
        return (e - plane) * d.getAxisDirection().getStep() > 0.02;
    }

    /** Punkt (u,v) in [0,1]^2 na ścianie d bloku pos, cofnięty o 'inset' DO WNĘTRZA bloku (unika remisów na płaszczyźnie). */
    public static Vec3 facePoint(BlockPos pos, Direction d, double u, double v, double inset) {
        double x = pos.getX(), y = pos.getY(), z = pos.getZ();
        boolean pos_ = d.getAxisDirection() == Direction.AxisDirection.POSITIVE;
        switch (d.getAxis()) {
            case X -> { x += pos_ ? 1 - inset : inset; y += u; z += v; }
            case Y -> { y += pos_ ? 1 - inset : inset; x += u; z += v; }
            case Z -> { z += pos_ ? 1 - inset : inset; x += u; y += v; }
        }
        return new Vec3(x, y, z);
    }

    /** Dokładnie Entity#calculateViewVector (z waniliowymi tablicami sin/cos). */
    public static Vec3 look(float yaw, float pitch) {
        float f = pitch * ((float) Math.PI / 180F);
        float g = -yaw * ((float) Math.PI / 180F);
        float h = Mth.cos(g), i = Mth.sin(g), j = Mth.cos(f), k = Mth.sin(f);
        return new Vec3(i * j, -k, h * j);
    }

    public static float yawOf(Vec3 d) {
        return (float) (Math.atan2(-d.x, d.z) * DEG);
    }

    public static float pitchOf(Vec3 d) {
        return (float) (-Math.atan2(d.y, Math.sqrt(d.x * d.x + d.z * d.z)) * DEG);
    }

    public static double angleDeg(Vec3 a, Vec3 b) {
        double den = a.length() * b.length();
        if (den < 1.0E-9) return 0.0;
        return Math.acos(Math.max(-1.0, Math.min(1.0, a.dot(b) / den))) * DEG;
    }
}
