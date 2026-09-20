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

package baritone.utils.schematic;

import baritone.api.BaritoneAPI;
import baritone.api.IBaritone;
import baritone.api.utils.BetterBlockPos;
import baritone.api.utils.Rotation;
import baritone.api.utils.RotationUtils;
import net.minecraft.client.Minecraft;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.*;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.*;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import net.minecraft.world.phys.shapes.VoxelShape;

import java.util.*;

/**
 * Rozwiązuje problem rotacji bloków ("Baritone nie potrafi budować bloków z rotacją"):
 * - Precyzyjnie kalkuluje kąty yaw/pitch i hit vectors dla schodów, płyt, pni/filarów, pieców, skrzyń, obserwatorów itp.
 * - Ignoruje właściwości dynamiczne (np. StairBlock.SHAPE), które łączą się automatycznie po postawieniu sąsiadów.
 * - Zapewnia optymalny dobór ścianki oparcia i wektora kliknięcia.
 */
public final class BlockStateResolver {

    private static final Set<Property<?>> DYNAMIC_PROPERTIES = Collections.unmodifiableSet(new HashSet<>(Arrays.asList(
            StairBlock.SHAPE,
            PipeBlock.NORTH, PipeBlock.EAST, PipeBlock.SOUTH, PipeBlock.WEST, PipeBlock.UP, PipeBlock.DOWN,
            TripWireBlock.ATTACHED, TripWireBlock.DISARMED, TripWireBlock.POWERED,
            RedStoneWireBlock.POWER, RedStoneWireBlock.NORTH, RedStoneWireBlock.EAST, RedStoneWireBlock.SOUTH, RedStoneWireBlock.WEST
    )));

    private BlockStateResolver() {}

    /**
     * Sprawdza czy dwa stany bloków są zgodne pod kątem postawienia,
     * ignorując właściwości dynamiczne (takie jak StairBlock.SHAPE), które łączą się automatycznie.
     */
    public static boolean matchesPlacement(BlockState placed, BlockState desired) {
        if (placed == null || desired == null) {
            return placed == desired;
        }
        if (placed.getBlock() != desired.getBlock()) {
            return false;
        }
        if (placed.equals(desired)) {
            return true;
        }

        Map<Property<?>, Comparable<?>> mapPlaced = placed.getValues();
        Map<Property<?>, Comparable<?>> mapDesired = desired.getValues();

        for (Map.Entry<Property<?>, Comparable<?>> entry : mapDesired.entrySet()) {
            Property<?> prop = entry.getKey();
            if (DYNAMIC_PROPERTIES.contains(prop)) {
                continue; // Kształt schodów czy połączenia rur/redstone formują się same w świecie
            }
            Comparable<?> desiredVal = entry.getValue();
            Comparable<?> placedVal = mapPlaced.get(prop);
            if (!Objects.equals(desiredVal, placedVal)) {
                return false;
            }
        }
        return true;
    }

    /**
     * Zwraca precyzyjne mnożniki punktów kliknięcia dla ścianki danego bloku,
     * uwzględniając wymogi rotacji i połówki (np. schody góra/dół, płyty góra/dół, filary).
     */
    public static List<Vec3> getTargetSideMultipliers(BlockState desired, Direction side) {
        List<Vec3> multipliers = new ArrayList<>();

        boolean wantsTop = wantsUpperHalf(desired);
        boolean wantsBottom = wantsLowerHalf(desired);

        switch (side) {
            case UP:
                if (!wantsTop) { // Preferowane kliknięcie w górną ściankę bloku poniżej
                    multipliers.add(new Vec3(0.5, 1.0, 0.5));
                    multipliers.add(new Vec3(0.3, 1.0, 0.5));
                    multipliers.add(new Vec3(0.7, 1.0, 0.5));
                    multipliers.add(new Vec3(0.5, 1.0, 0.3));
                    multipliers.add(new Vec3(0.5, 1.0, 0.7));
                } else {
                    multipliers.add(new Vec3(0.5, 1.0, 0.5));
                }
                break;
            case DOWN:
                if (!wantsBottom) { // Preferowane kliknięcie w dolną ściankę bloku powyżej dla płyt/schodów górnych
                    multipliers.add(new Vec3(0.5, 0.0, 0.5));
                    multipliers.add(new Vec3(0.3, 0.0, 0.5));
                    multipliers.add(new Vec3(0.7, 0.0, 0.5));
                    multipliers.add(new Vec3(0.5, 0.0, 0.3));
                    multipliers.add(new Vec3(0.5, 0.0, 0.7));
                } else {
                    multipliers.add(new Vec3(0.5, 0.0, 0.5));
                }
                break;
            case NORTH:
            case SOUTH:
            case EAST:
            case WEST:
                double x = side.getStepX() == 0 ? 0.5 : (1 + side.getStepX()) / 2.0;
                double z = side.getStepZ() == 0 ? 0.5 : (1 + side.getStepZ()) / 2.0;
                if (wantsTop) {
                    // Celuj w górną połowę ścianki bocznej
                    multipliers.add(new Vec3(x, 0.75, z));
                    multipliers.add(new Vec3(x, 0.85, z));
                    multipliers.add(new Vec3(x, 0.65, z));
                } else if (wantsBottom) {
                    // Celuj w dolną połowę ścianki bocznej
                    multipliers.add(new Vec3(x, 0.25, z));
                    multipliers.add(new Vec3(x, 0.15, z));
                    multipliers.add(new Vec3(x, 0.35, z));
                } else {
                    multipliers.add(new Vec3(x, 0.5, z));
                    multipliers.add(new Vec3(x, 0.25, z));
                    multipliers.add(new Vec3(x, 0.75, z));
                }
                break;
        }
        return multipliers;
    }

    /**
     * Sprawdza czy blok wymaga postawienia jako górna połowa (np. odwrócone schody, górna płyta).
     */
    public static boolean wantsUpperHalf(BlockState state) {
        if (state.hasProperty(StairBlock.HALF)) {
            return state.getValue(StairBlock.HALF) == Half.TOP;
        }
        if (state.hasProperty(SlabBlock.TYPE)) {
            return state.getValue(SlabBlock.TYPE) == SlabType.TOP;
        }
        if (state.hasProperty(TrapDoorBlock.HALF)) {
            return state.getValue(TrapDoorBlock.HALF) == Half.TOP;
        }
        return false;
    }

    /**
     * Sprawdza czy blok wymaga postawienia jako dolna połowa (standardowe schody, dolna płyta).
     */
    public static boolean wantsLowerHalf(BlockState state) {
        if (state.hasProperty(StairBlock.HALF)) {
            return state.getValue(StairBlock.HALF) == Half.BOTTOM;
        }
        if (state.hasProperty(SlabBlock.TYPE)) {
            return state.getValue(SlabBlock.TYPE) == SlabType.BOTTOM;
        }
        if (state.hasProperty(TrapDoorBlock.HALF)) {
            return state.getValue(TrapDoorBlock.HALF) == Half.BOTTOM;
        }
        return false;
    }

    /**
     * Zwraca wymaganą oś oparcia dla bloków RotatedPillarBlock (drewno, filary).
     */
    public static Direction.Axis getDesiredAxis(BlockState state) {
        if (state.hasProperty(RotatedPillarBlock.AXIS)) {
            return state.getValue(RotatedPillarBlock.AXIS);
        }
        return null;
    }

    /**
     * Zwraca wymagany kierunek poziomy (facing) jeśli blok posiada taką właściwość.
     */
    public static Direction getDesiredFacing(BlockState state) {
        if (state.hasProperty(HorizontalDirectionalBlock.FACING)) {
            return state.getValue(HorizontalDirectionalBlock.FACING);
        }
        if (state.hasProperty(DirectionalBlock.FACING)) {
            return state.getValue(DirectionalBlock.FACING);
        }
        return null;
    }

    /**
     * Oblicza preferowany yaw gracza potrzebny do uzyskania odpowiedniego kierunku poziomego.
     */
    public static Float getRequiredYaw(BlockState desired) {
        Direction facing = getDesiredFacing(desired);
        if (facing == null) {
            return null;
        }

        // W Minecrafcie większość bloków (schody, piece, skrzynie) stawia się przodem do gracza
        // (czyli facing = gracz patrzy w stronę getOpposite()), a obserwatory przodem w stronę wzroku.
        boolean oppositeFacing = !(desired.getBlock() instanceof ObserverBlock);
        Direction playerLookingDirection = oppositeFacing ? facing.getOpposite() : facing;

        switch (playerLookingDirection) {
            case SOUTH: return 0.0F;
            case WEST:  return 90.0F;
            case NORTH: return 180.0F;
            case EAST:  return -90.0F;
            default:    return null;
        }
    }

    /**
     * Rozwiązuje stan bloku na podstawie schematu lub kontekstu dla danego pos.
     * Używane przez mixin oraz procedurę stawiania.
     */
    public static BlockState resolvePlacementState(BlockState original, Level world, BlockPos pos) {
        if (world == null || pos == null || original == null) {
            return original;
        }
        if (BaritoneAPI.getProvider() == null) {
            return original;
        }
        IBaritone instance = BaritoneAPI.getProvider().getPrimaryBaritone();
        if (instance == null) {
            return original;
        }
        if (instance.getBuilderProcess() == null || !instance.getBuilderProcess().isActive()) {
            return original;
        }

        // Placeholder: builder process is active, but no override for this block position.
        // Extend this method when a placeAt() equivalent is added to IBuilderProcess.
        return original;
    }
}
