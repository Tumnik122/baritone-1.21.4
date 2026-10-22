package com.astra.revolution.schematic;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Vec3i;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.tags.FluidTags;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.*;
import net.minecraft.world.level.block.piston.MovingPistonBlock;
import net.minecraft.world.level.block.piston.PistonHeadBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.Property;
import net.minecraft.world.level.material.FluidState;

import baritone.api.schematic.ISchematic;
import java.util.*;

/**
 * ASTRA REVOLUTION — NATIVE SCHEMATIC DATA MODEL (TRANSFORM & AUDIT AWARE)
 * Represents an indexed 3D volume of BlockStates and BlockEntity NBT tags,
 * supporting live Rotation (0°, 90°, 180°, 270°) and Mirror transformations,
 * fast spatial indexing, and semantic world diffing.
 * Implements Baritone ISchematic for 100% native compatibility with Baritone's BuilderProcess.
 */
public final class AstraSchematic implements ISchematic {

    private final String name;
    private final Vec3i rawSize;
    private final BlockState[] rawBlockStates;
    private final Map<BlockPos, CompoundTag> blockEntities;
    private final Map<Block, Integer> materialRequirements;

    private Rotation rotation = Rotation.NONE;
    private Mirror mirror = Mirror.NONE;

    public AstraSchematic(
            String name,
            Vec3i rawSize,
            BlockState[] rawBlockStates,
            Map<BlockPos, CompoundTag> blockEntities
    ) {
        this.name = Objects.requireNonNull(name, "name");
        this.rawSize = Objects.requireNonNull(rawSize, "rawSize");
        this.rawBlockStates = Objects.requireNonNull(rawBlockStates, "rawBlockStates");
        this.blockEntities = Collections.unmodifiableMap(new HashMap<>(blockEntities));
        this.materialRequirements = computeMaterialRequirements(rawBlockStates);
    }

    private static Map<Block, Integer> computeMaterialRequirements(BlockState[] states) {
        Map<Block, Integer> counts = new LinkedHashMap<>();
        for (BlockState state : states) {
            if (state != null && !state.isAir()) {
                counts.merge(state.getBlock(), 1, Integer::sum);
            }
        }
        return Collections.unmodifiableMap(counts);
    }

    public String getName() {
        return name;
    }

    public Rotation getRotation() {
        return rotation;
    }

    public void setRotation(Rotation rotation) {
        this.rotation = Objects.requireNonNull(rotation);
    }

    public void cycleRotation() {
        Rotation[] values = Rotation.values();
        this.rotation = values[(this.rotation.ordinal() + 1) % values.length];
    }

    public Mirror getMirror() {
        return mirror;
    }

    public void setMirror(Mirror mirror) {
        this.mirror = Objects.requireNonNull(mirror);
    }

    public void cycleMirror() {
        Mirror[] values = Mirror.values();
        this.mirror = values[(this.mirror.ordinal() + 1) % values.length];
    }

    /**
     * Efektywna szerokość (X) po uwzględnieniu obrotu o 90/270 stopni.
     */
    public int getWidth() {
        return (rotation == Rotation.CLOCKWISE_90 || rotation == Rotation.COUNTERCLOCKWISE_90)
                ? rawSize.getZ()
                : rawSize.getX();
    }

    public int getHeight() {
        return rawSize.getY();
    }

    /**
     * Efektywna długość (Z) po uwzględnieniu obrotu o 90/270 stopni.
     */
    public int getLength() {
        return (rotation == Rotation.CLOCKWISE_90 || rotation == Rotation.COUNTERCLOCKWISE_90)
                ? rawSize.getX()
                : rawSize.getZ();
    }

    public Vec3i getTotalSize() {
        return new Vec3i(getWidth(), getHeight(), getLength());
    }

    public int getTotalBlocks() {
        int count = 0;
        for (BlockState state : rawBlockStates) {
            if (state != null && !state.isAir()) {
                count++;
            }
        }
        return count;
    }

    public boolean isInBounds(int x, int y, int z) {
        return x >= 0 && x < getWidth()
                && y >= 0 && y < getHeight()
                && z >= 0 && z < getLength();
    }

    private int toRawIndex(int rawX, int rawY, int rawZ) {
        return (rawY * rawSize.getZ() + rawZ) * rawSize.getX() + rawX;
    }

    /**
     * Maps transformed local coordinates (x, y, z) back into raw unrotated/unmirrored
     * schematic coordinates.
     */
    public BlockPos toRawPosition(int x, int y, int z) {
        int rx = x;
        int rz = z;
        int rawW = rawSize.getX();
        int rawL = rawSize.getZ();

        switch (rotation) {
            case CLOCKWISE_90 -> {
                rx = z;
                rz = rawL - 1 - x;
            }
            case CLOCKWISE_180 -> {
                rx = rawW - 1 - x;
                rz = rawL - 1 - z;
            }
            case COUNTERCLOCKWISE_90 -> {
                rx = rawW - 1 - z;
                rz = x;
            }
            default -> {}
        }

        switch (mirror) {
            case LEFT_RIGHT -> rz = rawL - 1 - rz;
            case FRONT_BACK -> rx = rawW - 1 - rx;
            default -> {}
        }

        return new BlockPos(rx, y, rz);
    }

    /**
     * Retrieves a defensive copy of the BlockEntity CompoundTag for transformed coordinates (x, y, z).
     */
    public Optional<CompoundTag> getTransformedBlockEntityData(int x, int y, int z) {
        if (!isInBounds(x, y, z)) {
            return Optional.empty();
        }
        BlockPos rawPos = toRawPosition(x, y, z);
        CompoundTag tag = blockEntities.get(rawPos);
        return tag != null ? Optional.of(tag.copy()) : Optional.empty();
    }

    /**
     * Mapuje przetransformowane współrzędne (x, y, z) na surowe współrzędne schematu
     * oraz stosuje transformacje BlockState (odbicie lustrzane i obrót).
     */
    public BlockState getTransformedState(int x, int y, int z) {
        if (!isInBounds(x, y, z)) {
            return Blocks.AIR.defaultBlockState();
        }

        BlockPos rawPos = toRawPosition(x, y, z);
        BlockState rawState = rawBlockStates[toRawIndex(rawPos.getX(), y, rawPos.getZ())];
        if (rawState == null || rawState.isAir()) {
            return Blocks.AIR.defaultBlockState();
        }

        BlockState transformed = rawState;
        if (mirror != Mirror.NONE) {
            transformed = transformed.mirror(mirror);
        }
        if (rotation != Rotation.NONE) {
            transformed = transformed.rotate(rotation);
        }
        return transformed;
    }

    public BlockState getDesiredState(int x, int y, int z) {
        return getTransformedState(x, y, z);
    }

    // =========================================================================
    // BARITONE ISchematic IMPLEMENTATION
    // =========================================================================

    @Override
    public int widthX() {
        return getWidth();
    }

    @Override
    public int heightY() {
        return getHeight();
    }

    @Override
    public int lengthZ() {
        return getLength();
    }

    @Override
    public boolean inSchematic(int x, int y, int z, BlockState currentState) {
        if (!isInBounds(x, y, z)) {
            return false;
        }
        BlockState state = getTransformedState(x, y, z);
        if (state == null) {
            return false;
        }
        // If schematic specifies a block, we are in schematic
        if (!state.isAir()) {
            return true;
        }
        // If schematic specifies air, but world has an obstacle that should be cleared
        return currentState != null && !currentState.isAir() && !currentState.canBeReplaced();
    }

    @Override
    public BlockState desiredState(int x, int y, int z, BlockState current, List<BlockState> approxPlaceable) {
        return getTransformedState(x, y, z);
    }

    @Override
    public void reset() {
    }

    public BlockState getDesiredState(BlockPos relativePos) {
        return getTransformedState(relativePos.getX(), relativePos.getY(), relativePos.getZ());
    }

    public Optional<CompoundTag> getBlockEntityData(BlockPos relativePos) {
        return Optional.ofNullable(blockEntities.get(relativePos));
    }

    public Map<Block, Integer> getMaterialRequirements() {
        return materialRequirements;
    }

    public record MaterialStatus(Block block, int totalRequired, int placedInWorld, int missingInWorld) {}

    public List<MaterialStatus> computeMaterialProgress(Level level, BlockPos origin) {
        Map<Block, Integer> placedCounts = new HashMap<>();
        for (int y = 0; y < getHeight(); y++) {
            for (int z = 0; z < getLength(); z++) {
                for (int x = 0; x < getWidth(); x++) {
                    BlockState target = getTransformedState(x, y, z);
                    if (target.isAir()) {
                        continue;
                    }
                    BlockPos pos = origin.offset(x, y, z);
                    BlockState world = level.getBlockState(pos);
                    if (matchesStateSemantics(level, pos, world, target)) {
                        placedCounts.merge(target.getBlock(), 1, Integer::sum);
                    }
                }
            }
        }

        List<MaterialStatus> result = new ArrayList<>();
        for (Map.Entry<Block, Integer> entry : materialRequirements.entrySet()) {
            Block block = entry.getKey();
            int req = entry.getValue();
            int placed = placedCounts.getOrDefault(block, 0);
            result.add(new MaterialStatus(block, req, placed, Math.max(0, req - placed)));
        }
        result.sort(Comparator.comparingInt(MaterialStatus::missingInWorld).reversed());
        return result;
    }

    private static final Set<String> VEGETATION_DYNAMIC_PROPS = Set.of(
            "age", "stage", "moisture", "berries", "honey_level", "growth", "leaves", "distance", "persistent"
    );

    private static final Set<String> REDSTONE_DYNAMIC_PROPS = Set.of(
            "powered", "power", "lit", "triggered", "enabled", "extended", "short", "signal_fire", "has_book"
    );

    private static final Set<String> AUTO_CONNECTION_PROPS = Set.of(
            "north", "east", "south", "west", "up", "down", "shape", "in_wall"
    );

    /**
     * Decyduje, czy dany blok ze schematu w ogóle powinien trafić do kolejki stawiania.
     * Ignoruje powietrze oraz NIESAMODZIELNĄ (płynącą) wodę/lawę, która wytworzy się sama ze źródła.
     */
    public static boolean shouldScheduleForPlacement(BlockState targetState) {
        if (targetState.isAir()) {
            return false;
        }
        // Jeśli to LiquidBlock (woda/lawa), planujemy stawianie TYLKO dla bloków źródłowych (level == 0).
        // Woda płynąca (level 1..7) rozpłynie się sama po postawieniu źródła.
        if (targetState.getBlock() instanceof LiquidBlock) {
            return targetState.getFluidState().isSource();
        }
        // Głowy tłoków oraz ruchome tłoki nigdy nie są stawiane ręcznie z ekwipunku
        if (targetState.getBlock() instanceof PistonHeadBlock || targetState.getBlock() instanceof MovingPistonBlock) {
            return false;
        }
        return true;
    }

    public static boolean matchesStateSemantics(BlockState worldState, BlockState targetState) {
        return matchesStateSemantics(null, null, worldState, targetState);
    }

    /**
     * Bezkompromisowe porównanie semantyczne stanu świata ze stanem docelowym w .litematic.
     */
    public static boolean matchesStateSemantics(Level level, BlockPos pos, BlockState worldState, BlockState targetState) {
        if (targetState == null || targetState.isAir()) {
            if (worldState == null || worldState.isAir()) {
                return true;
            }
            // Rozlana woda lub ciecz w miejscu, gdzie schemat ma powietrze, nie jest błędem konstrukcyjnym
            return worldState.getBlock() instanceof LiquidBlock || !worldState.getFluidState().isEmpty();
        }
        if (worldState == null) {
            return false;
        }

        FluidState worldFluid = (level != null && pos != null)
                ? level.getFluidState(pos)
                : worldState.getFluidState();

        // 1. Obsługa bloków czystej wody (Blocks.WATER)
        if (targetState.is(Blocks.WATER) || targetState.getFluidState().is(FluidTags.WATER)) {
            return worldFluid.is(FluidTags.WATER);
        }

        // 2. Obsługa bloków czystej lawy (Blocks.LAVA)
        if (targetState.is(Blocks.LAVA) || targetState.getFluidState().is(FluidTags.LAVA)) {
            if (!worldFluid.is(FluidTags.LAVA)) {
                return false;
            }
            boolean targetRequiresSource = targetState.getFluidState().isSource();
            return !targetRequiresSource || worldFluid.isSource();
        }

        // 3. Wariant Dirt -> Farmland / GrassBlock -> Dirt (gdy trzcina lub blok nad ziemią zmienił trawę w ziemię)
        if (isCompatibleSoilVariant(worldState.getBlock(), targetState.getBlock())) {
            return true;
        }

        // 4. Porównanie instancji bloku
        if (!worldState.is(targetState.getBlock())) {
            return false;
        }

        // 5. Porównanie właściwości z wykluczeniem cech dynamicznych
        Block block = targetState.getBlock();
        for (Property<?> property : targetState.getProperties()) {
            if (isDynamicProperty(block, property)) {
                continue;
            }
            if (!worldState.getValue(property).equals(targetState.getValue(property))) {
                return false;
            }
        }

        return true;
    }

    public static boolean isDynamicProperty(Block block, Property<?> property) {
        String name = property.getName();

        // Wyjątek: ChorusFlowerBlock używa "age" (0..5), gdzie age=5 to martwy kwiat (osobny cel dekoracyjny)
        if (block instanceof ChorusFlowerBlock && "age".equals(name)) {
            return false;
        }

        // 1. Cechy wegetacyjne i wzrostu roślin
        if (VEGETATION_DYNAMIC_PROPS.contains(name)) {
            return true;
        }

        // 2. Cechy zasilania i aktywacji Redstone
        if (REDSTONE_DYNAMIC_PROPS.contains(name)) {
            // Wyjątek: Dźwignia (LeverBlock) - jej stan "powered" jest konfiguracją manualną przełącznika, a nie dynamicznym impulsem obwodu
            if (block instanceof LeverBlock && "powered".equals(name)) {
                return false;
            }
            return true;
        }

        // 3. Automatyczne łączenie płotów, murków, szyb, schodów i proszku redstone
        if (AUTO_CONNECTION_PROPS.contains(name) && isAutoConnectingBlock(block)) {
            return true;
        }

        return false;
    }

    private static boolean isAutoConnectingBlock(Block block) {
        return block instanceof CrossCollisionBlock // FenceBlock, IronBarsBlock, StainedGlassPaneBlock
                || block instanceof WallBlock
                || block instanceof StairBlock
                || block instanceof RedStoneWireBlock
                || block instanceof TripWireBlock
                || block instanceof ChorusPlantBlock
                || block instanceof BaseRailBlock;
    }

    private static boolean isCompatibleSoilVariant(Block worldBlock, Block targetBlock) {
        return isSoil(worldBlock) && isSoil(targetBlock);
    }

    private static boolean isSoil(Block b) {
        return b == Blocks.DIRT || b == Blocks.GRASS_BLOCK || b == Blocks.PODZOL
                || b == Blocks.FARMLAND || b == Blocks.DIRT_PATH || b == Blocks.COARSE_DIRT;
    }
}
