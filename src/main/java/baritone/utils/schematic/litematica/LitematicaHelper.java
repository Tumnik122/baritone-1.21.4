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

package baritone.utils.schematic.litematica;

import baritone.api.schematic.CompositeSchematic;
import baritone.api.schematic.IStaticSchematic;
import baritone.api.utils.Helper;
import baritone.utils.schematic.StaticSchematic;
import fi.dy.masa.litematica.Litematica;
import fi.dy.masa.litematica.data.DataManager;
import fi.dy.masa.litematica.schematic.placement.SchematicPlacement;
import fi.dy.masa.litematica.schematic.placement.SubRegionPlacement;
import fi.dy.masa.litematica.world.SchematicWorldHandler;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Vec3i;
import net.minecraft.util.Tuple;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.Mirror;
import net.minecraft.world.level.block.Rotation;
import net.minecraft.world.level.block.state.BlockState;

import java.lang.reflect.Method;
import java.util.*;

/**
 * Helper class that provides access or processes data related to Litematica schematics.
 * Completely hardened against NoSuchMethodError, classloader discrepancies,
 * and API changes between Litematica versions (1.12 through 1.21.4+).
 */
public final class LitematicaHelper {

    /**
     * @return if Litematica is installed and loaded.
     */
    public static boolean isLitematicaPresent() {
        try {
            Class<?> flClass = Class.forName("net.fabricmc.loader.api.FabricLoader");
            Object fl = flClass.getMethod("getInstance").invoke(null);
            Object loaded = flClass.getMethod("isModLoaded", String.class).invoke(fl, "litematica");
            if (Boolean.TRUE.equals(loaded)) {
                return true;
            }
        } catch (Throwable ignored) {
        }
        try {
            Class.forName(Litematica.class.getName());
            return true;
        } catch (Throwable ignored) {
        }
        try {
            Class.forName("fi.dy.masa.litematica.Litematica");
            return true;
        } catch (Throwable ignored) {
        }
        try {
            Thread.currentThread().getContextClassLoader().loadClass("fi.dy.masa.litematica.Litematica");
            return true;
        } catch (Throwable ignored) {
        }
        return false;
    }

    /**
     * Retrieves all schematic placements using reflection to bypass naming inconsistencies
     * across different Litematica versions (e.g. getAllSchematicsPlacements vs getAllSchematicPlacements).
     */
    @SuppressWarnings("unchecked")
    public static List<?> getAllPlacements() {
        try {
            Object manager = DataManager.getSchematicPlacementManager();
            if (manager == null) {
                return Collections.emptyList();
            }
            Class<?> managerClass = manager.getClass();
            for (String methodName : new String[]{
                    "getAllSchematicPlacements",
                    "getAllSchematicsPlacements",
                    "getAllPlacements",
                    "getSchematicPlacements",
                    "getPlacements"
            }) {
                try {
                    Method m = managerClass.getMethod(methodName);
                    Object res = m.invoke(manager);
                    if (res instanceof List) {
                        return (List<?>) res;
                    }
                } catch (NoSuchMethodException ignored) {
                }
            }
            for (Method m : managerClass.getMethods()) {
                if (m.getParameterCount() == 0 && List.class.isAssignableFrom(m.getReturnType())) {
                    if (m.getName().toLowerCase().contains("placement")) {
                        try {
                            Object res = m.invoke(manager);
                            if (res instanceof List) {
                                return (List<?>) res;
                            }
                        } catch (Throwable ignored) {
                        }
                    }
                }
            }
        } catch (Throwable t) {
            Helper.HELPER.logDebug("Error querying Litematica placements: " + t.getMessage());
        }
        return Collections.emptyList();
    }

    /**
     * Retrieves the currently selected placement in Litematica GUI, if available.
     */
    public static Object getSelectedPlacement() {
        try {
            Object manager = DataManager.getSchematicPlacementManager();
            if (manager == null) {
                return null;
            }
            Class<?> managerClass = manager.getClass();
            for (String methodName : new String[]{
                    "getSelectedSchematicPlacement",
                    "getSelectedPlacement",
                    "getCurrentPlacement"
            }) {
                try {
                    Method m = managerClass.getMethod(methodName);
                    Object res = m.invoke(manager);
                    if (res != null) {
                        return res;
                    }
                } catch (NoSuchMethodException ignored) {
                }
            }
        } catch (Throwable ignored) {
        }
        return null;
    }

    /**
     * @return if {@code i} is a valid placement index, or if i < 0 whether any placement exists.
     */
    public static boolean hasLoadedSchematic(int i) {
        List<?> placements = getAllPlacements();
        if (i < 0) {
            return getSelectedPlacement() != null || !placements.isEmpty();
        }
        return 0 <= i && i < placements.size();
    }

    public static boolean hasAnyPlacements() {
        return getSelectedPlacement() != null || !getAllPlacements().isEmpty();
    }

    public static int getPlacementCount() {
        return getAllPlacements().size();
    }

    public static List<String> getPlacementDescriptions() {
        List<?> placements = getAllPlacements();
        Object selected = getSelectedPlacement();
        List<String> result = new ArrayList<>();
        for (int i = 0; i < placements.size(); i++) {
            Object p = placements.get(i);
            String name = getPlacementName(p);
            BlockPos origin = getPlacementOrigin(p);
            boolean isSel = (p == selected);
            String posStr = origin != null ? String.format("%d %d %d", origin.getX(), origin.getY(), origin.getZ()) : "?";
            result.add(String.format("#%d: %s at [%s]%s", i + 1, name, posStr, isSel ? " (Selected)" : ""));
        }
        return result;
    }

    private static Object getPlacement(int i) {
        List<?> placements = getAllPlacements();
        if (i < 0) {
            Object sel = getSelectedPlacement();
            if (sel != null) {
                return sel;
            }
            return !placements.isEmpty() ? placements.get(0) : null;
        }
        return (0 <= i && i < placements.size()) ? placements.get(i) : null;
    }

    private static String getPlacementName(Object placement) {
        if (placement == null) return "Unknown";
        try {
            Method m = placement.getClass().getMethod("getName");
            Object val = m.invoke(placement);
            if (val != null) return val.toString();
        } catch (Throwable ignored) {
        }
        return "Schematic";
    }

    private static BlockPos getPlacementOrigin(Object placement) {
        if (placement == null) return BlockPos.ZERO;
        try {
            Method m = placement.getClass().getMethod("getOrigin");
            Object val = m.invoke(placement);
            if (val instanceof BlockPos) return (BlockPos) val;
            if (val instanceof Vec3i) {
                Vec3i v = (Vec3i) val;
                return new BlockPos(v.getX(), v.getY(), v.getZ());
            }
        } catch (Throwable ignored) {
        }
        return BlockPos.ZERO;
    }

    private static Rotation getPlacementRotation(Object placement) {
        if (placement == null) return Rotation.NONE;
        try {
            Method m = placement.getClass().getMethod("getRotation");
            Object val = m.invoke(placement);
            if (val instanceof Rotation) return (Rotation) val;
        } catch (Throwable ignored) {
        }
        return Rotation.NONE;
    }

    private static Mirror getPlacementMirror(Object placement) {
        if (placement == null) return Mirror.NONE;
        try {
            Method m = placement.getClass().getMethod("getMirror");
            Object val = m.invoke(placement);
            if (val instanceof Mirror) return (Mirror) val;
        } catch (Throwable ignored) {
        }
        return Mirror.NONE;
    }

    @SuppressWarnings("unchecked")
    private static Map<String, ?> getPlacementSubRegions(Object placement) {
        if (placement == null) return Collections.emptyMap();
        Class<?> cls = placement.getClass();
        for (String mName : new String[]{
                "getEnabledRelativeSubRegionPlacements",
                "getSubRegionPlacements",
                "getRelativeSubRegionPlacements",
                "getAllSubRegionsPlacements"
        }) {
            try {
                Method m = cls.getMethod(mName);
                Object val = m.invoke(placement);
                if (val instanceof Map) {
                    return (Map<String, ?>) val;
                }
            } catch (NoSuchMethodException ignored) {
            } catch (Throwable ignored) {
            }
        }
        for (Method m : cls.getMethods()) {
            if (m.getParameterCount() == 0 && Map.class.isAssignableFrom(m.getReturnType())) {
                if (m.getName().toLowerCase().contains("subregion")) {
                    try {
                        Object val = m.invoke(placement);
                        if (val instanceof Map) {
                            return (Map<String, ?>) val;
                        }
                    } catch (Throwable ignored) {
                    }
                }
            }
        }
        return Collections.emptyMap();
    }

    private static Object getPlacementSchematic(Object placement) {
        if (placement == null) return null;
        try {
            Method m = placement.getClass().getMethod("getSchematic");
            return m.invoke(placement);
        } catch (Throwable ignored) {
        }
        return null;
    }

    private static Vec3i getSchematicAreaSize(Object schematic, String areaName) {
        if (schematic == null) return Vec3i.ZERO;
        try {
            Method m = schematic.getClass().getMethod("getAreaSize", String.class);
            Object val = m.invoke(schematic, areaName);
            if (val instanceof Vec3i) return (Vec3i) val;
        } catch (Throwable ignored) {
        }
        return Vec3i.ZERO;
    }

    private static Vec3i getSubPlacementPos(Object subPlacement) {
        if (subPlacement == null) return Vec3i.ZERO;
        try {
            Method m = subPlacement.getClass().getMethod("getPos");
            Object val = m.invoke(subPlacement);
            if (val instanceof Vec3i) return (Vec3i) val;
        } catch (Throwable ignored) {
        }
        return Vec3i.ZERO;
    }

    private static Rotation getSubPlacementRotation(Object subPlacement) {
        if (subPlacement == null) return Rotation.NONE;
        try {
            Method m = subPlacement.getClass().getMethod("getRotation");
            Object val = m.invoke(subPlacement);
            if (val instanceof Rotation) return (Rotation) val;
        } catch (Throwable ignored) {
        }
        return Rotation.NONE;
    }

    private static Mirror getSubPlacementMirror(Object subPlacement) {
        if (subPlacement == null) return Mirror.NONE;
        try {
            Method m = subPlacement.getClass().getMethod("getMirror");
            Object val = m.invoke(subPlacement);
            if (val instanceof Mirror) return (Mirror) val;
        } catch (Throwable ignored) {
        }
        return Mirror.NONE;
    }

    private static boolean isSubPlacementEnabled(Object subPlacement) {
        if (subPlacement == null) return false;
        try {
            Method m = subPlacement.getClass().getMethod("isEnabled");
            Object val = m.invoke(subPlacement);
            if (val instanceof Boolean) return (Boolean) val;
        } catch (Throwable ignored) {
        }
        return true;
    }

    private static Vec3i transform(Vec3i in, Mirror mirror, Rotation rotation) {
        if (in == null) return Vec3i.ZERO;
        if (mirror == null) mirror = Mirror.NONE;
        if (rotation == null) rotation = Rotation.NONE;

        int x = in.getX();
        int z = in.getZ();
        if (mirror == Mirror.LEFT_RIGHT) {
            z = -z;
        } else if (mirror == Mirror.FRONT_BACK) {
            x = -x;
        }
        switch (rotation) {
            case CLOCKWISE_90:
                return new Vec3i(-z, in.getY(), x);
            case CLOCKWISE_180:
                return new Vec3i(-x, in.getY(), -z);
            case COUNTERCLOCKWISE_90:
                return new Vec3i(z, in.getY(), -x);
            default:
                return new Vec3i(x, in.getY(), z);
        }
    }

    /**
     * @param i index of the Schematic in the schematic placement list (or negative for selected/first).
     * @return The transformed schematic and the position of its minimum corner
     */
    public static Tuple<IStaticSchematic, Vec3i> getSchematic(int i) {
        Object placement = getPlacement(i);
        if (placement == null) {
            return null;
        }

        Level schematicWorld = null;
        try {
            schematicWorld = SchematicWorldHandler.getSchematicWorld();
        } catch (Throwable t) {
            try {
                Class<?> swhClass = Class.forName("fi.dy.masa.litematica.world.SchematicWorldHandler");
                Method m = swhClass.getMethod("getSchematicWorld");
                Object val = m.invoke(null);
                if (val instanceof Level) {
                    schematicWorld = (Level) val;
                }
            } catch (Throwable ignored) {
            }
        }

        if (schematicWorld == null) {
            Helper.HELPER.logDebug("Litematica schematic world is null or not loaded yet.");
            return null;
        }

        String placementName = getPlacementName(placement);
        BlockPos placementOrigin = getPlacementOrigin(placement);
        Mirror placementMirror = getPlacementMirror(placement);
        Rotation placementRotation = getPlacementRotation(placement);
        Object placementSchematic = getPlacementSchematic(placement);
        Map<String, ?> subRegionPlacements = getPlacementSubRegions(placement);

        int minX = Integer.MAX_VALUE;
        int minY = Integer.MAX_VALUE;
        int minZ = Integer.MAX_VALUE;
        HashMap<Vec3i, StaticSchematic> subRegions = new HashMap<>();

        for (Map.Entry<String, ?> entry : subRegionPlacements.entrySet()) {
            Object subPlacement = entry.getValue();
            if (!isSubPlacementEnabled(subPlacement)) {
                continue;
            }

            Vec3i rawSubPos = getSubPlacementPos(subPlacement);
            Mirror subMirror = getSubPlacementMirror(subPlacement);
            Rotation subRotation = getSubPlacementRotation(subPlacement);

            Vec3i pos = transform(rawSubPos, placementMirror, placementRotation);
            Vec3i rawSize = getSchematicAreaSize(placementSchematic, entry.getKey());
            Vec3i size = transform(rawSize, placementMirror, placementRotation);
            size = transform(size, subMirror, subRotation);

            int mx = Math.min(size.getX() + 1, 0);
            int my = Math.min(size.getY() + 1, 0);
            int mz = Math.min(size.getZ() + 1, 0);
            minX = Math.min(minX, pos.getX() + mx);
            minY = Math.min(minY, pos.getY() + my);
            minZ = Math.min(minZ, pos.getZ() + mz);

            BlockPos origin = placementOrigin.offset(pos).offset(mx, my, mz);
            int dimX = Math.max(1, Math.abs(size.getX()));
            int dimZ = Math.max(1, Math.abs(size.getZ()));
            int dimY = Math.max(1, Math.abs(size.getY()));
            BlockState[][][] states = new BlockState[dimX][dimZ][dimY];

            for (int x = 0; x < dimX; x++) {
                for (int z = 0; z < dimZ; z++) {
                    for (int y = 0; y < dimY; y++) {
                        try {
                            BlockState state = schematicWorld.getBlockState(origin.offset(x, y, z));
                            states[x][z][y] = state != null ? state : Blocks.AIR.defaultBlockState();
                        } catch (Throwable ignored) {
                            states[x][z][y] = Blocks.AIR.defaultBlockState();
                        }
                    }
                }
            }
            StaticSchematic schematic = new StaticSchematic(states);
            subRegions.put(pos.offset(mx, my, mz), schematic);
        }

        if (subRegions.isEmpty()) {
            return null;
        }

        LitematicaPlacementSchematic composite = new LitematicaPlacementSchematic(placementName);
        for (Map.Entry<Vec3i, StaticSchematic> entry : subRegions.entrySet()) {
            Vec3i pos = entry.getKey().offset(-minX, -minY, -minZ);
            composite.put(entry.getValue(), pos.getX(), pos.getY(), pos.getZ());
        }
        return new Tuple<>(composite, placementOrigin.offset(minX, minY, minZ));
    }

    private static class LitematicaPlacementSchematic extends CompositeSchematic implements IStaticSchematic {
        private final String name;

        public LitematicaPlacementSchematic(String name) {
            super(0, 0, 0);
            this.name = name;
        }

        @Override
        public BlockState getDirect(int x, int y, int z) {
            if (inSchematic(x, y, z, null)) {
                return desiredState(x, y, z, null, Collections.emptyList());
            }
            return null;
        }

        @Override
        public String toString() {
            return name;
        }
    }
}
