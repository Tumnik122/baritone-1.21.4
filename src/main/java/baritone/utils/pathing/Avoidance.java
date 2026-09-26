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

package baritone.utils.pathing;

import baritone.Baritone;
import baritone.api.utils.BetterBlockPos;
import baritone.api.utils.IPlayerContext;
import it.unimi.dsi.fastutil.longs.Long2DoubleOpenHashMap;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import net.minecraft.core.BlockPos;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.monster.AbstractSkeleton;
import net.minecraft.world.entity.monster.Creeper;
import net.minecraft.world.entity.monster.EnderMan;
import net.minecraft.world.entity.monster.Enemy;
import net.minecraft.world.entity.monster.Spider;
import net.minecraft.world.entity.monster.Zombie;
import net.minecraft.world.entity.monster.ZombifiedPiglin;

public class Avoidance {

    private final int centerX;
    private final int centerY;
    private final int centerZ;
    private final double coefficient;
    private final int radius;
    private final int radiusSq;

    public Avoidance(BlockPos center, double coefficient, int radius) {
        this(center.getX(), center.getY(), center.getZ(), coefficient, radius);
    }

    public Avoidance(int centerX, int centerY, int centerZ, double coefficient, int radius) {
        this.centerX = centerX;
        this.centerY = centerY;
        this.centerZ = centerZ;
        this.coefficient = coefficient;
        this.radius = radius;
        this.radiusSq = radius * radius;
    }

    public double coefficient(int x, int y, int z) {
        int xDiff = x - centerX;
        int yDiff = y - centerY;
        int zDiff = z - centerZ;
        return xDiff * xDiff + yDiff * yDiff + zDiff * zDiff <= radiusSq ? coefficient : 1.0D;
    }

    public static List<Avoidance> create(IPlayerContext ctx) {
        if (!Baritone.settings().avoidance.value) {
            return Collections.emptyList();
        }
        List<Avoidance> res = new ArrayList<>();
        double mobSpawnerCoeff = Baritone.settings().mobSpawnerAvoidanceCoefficient.value;
        if (mobSpawnerCoeff != 1.0D && ctx.worldData() != null && ctx.worldData().getCachedWorld() != null) {
            ctx.worldData().getCachedWorld().getLocationsOf("mob_spawner", 1, ctx.playerFeet().x, ctx.playerFeet().z, 2)
                    .forEach(mobspawner -> res.add(new Avoidance(mobspawner, mobSpawnerCoeff, Baritone.settings().mobSpawnerAvoidanceRadius.value)));
        }

        ctx.entitiesStream()
                .filter(entity -> entity instanceof Mob)
                .forEach(entity -> {
                    Mob mob = (Mob) entity;

                    // 1. Creeper: wysokie ryzyko śmiertelnego wybuchu, omijany szerokim łukiem
                    if (mob instanceof Creeper) {
                        if (Baritone.settings().mobAvoidanceCreeper.value) {
                            res.add(new Avoidance(mob.blockPosition(),
                                    Baritone.settings().creeperAvoidanceCoefficient.value,
                                    Baritone.settings().creeperAvoidanceRadius.value));
                        }
                        return;
                    }

                    // 2. Szkielety / Bogged / Stray / WitherSkeleton: moby dystansowe strzelające z łuków
                    if (mob instanceof AbstractSkeleton) {
                        if (Baritone.settings().mobAvoidanceSkeleton.value) {
                            res.add(new Avoidance(mob.blockPosition(),
                                    Baritone.settings().skeletonAvoidanceCoefficient.value,
                                    Baritone.settings().skeletonAvoidanceRadius.value));
                        }
                        return;
                    }

                    // 3. Zombie / Husk / Drowned: wrogie moby walczące wręcz
                    if (mob instanceof Zombie) {
                        if (!(mob instanceof ZombifiedPiglin) || ((ZombifiedPiglin) mob).getLastHurtByMob() != null) {
                            if (Baritone.settings().mobAvoidanceZombie.value) {
                                res.add(new Avoidance(mob.blockPosition(),
                                        Baritone.settings().zombieAvoidanceCoefficient.value,
                                        Baritone.settings().zombieAvoidanceRadius.value));
                            }
                        }
                        return;
                    }

                    // 4. Enderman: omijany, by nie wejść w niego ani nie spojrzeć mu w oczy
                    if (mob instanceof EnderMan enderman) {
                        if (Baritone.settings().mobAvoidanceEnderman.value) {
                            double coeff = enderman.isCreepy()
                                    ? Baritone.settings().endermanAvoidanceCoefficient.value * 2.0D
                                    : Baritone.settings().endermanAvoidanceCoefficient.value;
                            int radius = enderman.isCreepy()
                                    ? Baritone.settings().endermanAvoidanceRadius.value + 4
                                    : Baritone.settings().endermanAvoidanceRadius.value;
                            res.add(new Avoidance(mob.blockPosition(), coeff, radius));
                        }
                        return;
                    }

                    // 5. Pająki: wrogie w ciemności (poziom światła < 0.5)
                    if (mob instanceof Spider) {
                        if (ctx.player() != null && ctx.player().getLightLevelDependentMagicValue() < 0.5F) {
                            res.add(new Avoidance(mob.blockPosition(),
                                    Baritone.settings().mobAvoidanceCoefficient.value,
                                    Baritone.settings().mobAvoidanceRadius.value));
                        }
                        return;
                    }

                    // 6. Warden / Witch / Inne potwory (Enemy)
                    if (mob instanceof Enemy) {
                        boolean isWarden = mob.getType() == EntityType.WARDEN;
                        double dangerCoeff = isWarden ? 5.0D : Baritone.settings().mobAvoidanceCoefficient.value;
                        int dangerRadius = isWarden ? 16 : Baritone.settings().mobAvoidanceRadius.value;
                        res.add(new Avoidance(mob.blockPosition(), dangerCoeff, dangerRadius));
                    }
                });

        return res;
    }

    public void applySpherical(Long2DoubleOpenHashMap map) {
        for (int x = -radius; x <= radius; x++) {
            for (int y = -radius; y <= radius; y++) {
                for (int z = -radius; z <= radius; z++) {
                    if (x * x + y * y + z * z <= radius * radius) {
                        long hash = BetterBlockPos.longHash(centerX + x, centerY + y, centerZ + z);
                        map.put(hash, map.get(hash) * coefficient);
                    }
                }
            }
        }
    }
}
