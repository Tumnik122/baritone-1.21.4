package com.astra.revolution.schematic;

import net.minecraft.SharedConstants;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Vec3i;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.LongArrayTag;
import net.minecraft.nbt.NbtIo;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.Property;

import java.io.IOException;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;

/**
 * ASTRA REVOLUTION — NATIVE LITEMATICA EXPORTER
 * Captures all live world BlockStates and BlockEntities within an Area Selection
 * and serializes them into a standard v6 .litematic file.
 */
public final class LitematicaExporter {

    private LitematicaExporter() {}

    public static Path exportSelectionToLitematic(
            Level level,
            SelectionManager selection,
            String schematicName,
            String authorName,
            Path outputDirectory
    ) throws IOException {
        if (!selection.hasCompleteSelection()) {
            throw new IllegalStateException("Oba narożniki (Corner 1 i Corner 2) muszą być ustawione.");
        }

        Files.createDirectories(outputDirectory);
        String cleanName = schematicName.trim().isEmpty() ? "unnamed_schematic" : schematicName.trim();
        String sanitizedFile = cleanName.replaceAll("[^a-zA-Z0-9._-]", "_");
        if (!sanitizedFile.toLowerCase(Locale.ROOT).endsWith(".litematic")) {
            sanitizedFile += ".litematic";
        }
        Path targetFile = outputDirectory.resolve(sanitizedFile);

        BlockPos minPos = selection.getMinPos();
        Vec3i size = selection.getDimensions();
        int width = size.getX();
        int height = size.getY();
        int length = size.getZ();

        // 1. Zbuduj paletę (indeks 0 to zawsze minecraft:air wg specyfikacji Litematiki)
        List<BlockState> paletteList = new ArrayList<>();
        Map<BlockState, Integer> paletteIndexMap = new LinkedHashMap<>();
        BlockState airState = Blocks.AIR.defaultBlockState();
        paletteList.add(airState);
        paletteIndexMap.put(airState, 0);

        int totalVolume = width * height * length;
        int[] indexedVolume = new int[totalVolume];
        int nonAirBlocks = 0;
        ListTag tileEntitiesTag = new ListTag();

        for (int y = 0; y < height; y++) {
            for (int z = 0; z < length; z++) {
                for (int x = 0; x < width; x++) {
                    BlockPos worldPos = minPos.offset(x, y, z);
                    BlockState state = level.getBlockState(worldPos);
                    if (!state.isAir()) {
                        nonAirBlocks++;
                    }

                    int paletteId = paletteIndexMap.computeIfAbsent(state, s -> {
                        int newId = paletteList.size();
                        paletteList.add(s);
                        return newId;
                    });

                    int volumeIdx = (y * length + z) * width + x;
                    indexedVolume[volumeIdx] = paletteId;

                    BlockEntity blockEntity = level.getBlockEntity(worldPos);
                    if (blockEntity != null) {
                        try {
                            CompoundTag beTag = blockEntity.saveCustomOnly(level.registryAccess());
                            beTag.putInt("x", x);
                            beTag.putInt("y", y);
                            beTag.putInt("z", z);
                            tileEntitiesTag.add(beTag);
                        } catch (Throwable ignored) {}
                    }
                }
            }
        }

        // 2. Spakuj indeksy bloków w ciągły 64-bitowy bit-stream Litematiki
        int bitsPerBlock = Math.max(2, Integer.SIZE - Integer.numberOfLeadingZeros(Math.max(1, paletteList.size() - 1)));
        LitematicaBitPacker packer = new LitematicaBitPacker(totalVolume, bitsPerBlock);
        for (int i = 0; i < totalVolume; i++) {
            packer.setAt(i, indexedVolume[i]);
        }

        ListTag paletteTag = new ListTag();
        for (BlockState state : paletteList) {
            paletteTag.add(serializeBlockState(state));
        }

        // 3. Zbuduj Region
        CompoundTag regionTag = new CompoundTag();
        regionTag.put("Position", writeVec3i(Vec3i.ZERO));
        regionTag.put("Size", writeVec3i(size));
        regionTag.put("BlockStatePalette", paletteTag);
        regionTag.put("BlockStates", new LongArrayTag(packer.getLongArray()));
        regionTag.put("TileEntities", tileEntitiesTag);
        regionTag.put("Entities", new ListTag());
        regionTag.put("PendingBlockTicks", new ListTag());
        regionTag.put("PendingFluidTicks", new ListTag());

        CompoundTag regionsCompound = new CompoundTag();
        regionsCompound.put(cleanName, regionTag);

        // 4. Zbuduj Metadata
        long now = System.currentTimeMillis();
        CompoundTag metadata = new CompoundTag();
        metadata.putString("Name", cleanName);
        metadata.putString("Author", authorName != null ? authorName : "Astra");
        metadata.putString("Description", "Exported via Astra Native Litematica Studio");
        metadata.putInt("RegionCount", 1);
        metadata.putInt("TotalVolume", totalVolume);
        metadata.putInt("TotalBlocks", nonAirBlocks);
        metadata.putLong("TimeCreated", now);
        metadata.putLong("TimeModified", now);
        metadata.put("EnclosingSize", writeVec3i(size));

        // 5. Zbuduj Root Compound
        CompoundTag root = new CompoundTag();
        root.putInt("Version", 6);
        root.putInt("SubVersion", 1);
        root.putInt("MinecraftDataVersion", SharedConstants.getCurrentVersion().getDataVersion().getVersion());
        root.put("Metadata", metadata);
        root.put("Regions", regionsCompound);

        try (OutputStream out = Files.newOutputStream(targetFile)) {
            NbtIo.writeCompressed(root, out);
        }

        return targetFile;
    }

    private static CompoundTag serializeBlockState(BlockState state) {
        CompoundTag tag = new CompoundTag();
        ResourceLocation key = BuiltInRegistries.BLOCK.getKey(state.getBlock());
        tag.putString("Name", key.toString());

        Collection<Property<?>> properties = state.getProperties();
        if (!properties.isEmpty()) {
            CompoundTag propsTag = new CompoundTag();
            for (Property<?> prop : properties) {
                propsTag.putString(prop.getName(), getPropValueString(state, prop));
            }
            tag.put("Properties", propsTag);
        }
        return tag;
    }

    @SuppressWarnings("unchecked")
    private static <T extends Comparable<T>> String getPropValueString(BlockState state, Property<T> prop) {
        return prop.getName(state.getValue(prop));
    }

    private static CompoundTag writeVec3i(Vec3i vec) {
        CompoundTag tag = new CompoundTag();
        tag.putInt("x", vec.getX());
        tag.putInt("y", vec.getY());
        tag.putInt("z", vec.getZ());
        return tag;
    }

    /**
     * Bit-packer dopasowany do formatu spanned bitów w Litematice (przekracza granice 64-bitowych słów long).
     */
    private static final class LitematicaBitPacker {
        private final long[] data;
        private final int bitsPerEntry;
        private final long maxEntryValue;

        LitematicaBitPacker(int capacity, int bitsPerEntry) {
            this.bitsPerEntry = bitsPerEntry;
            this.maxEntryValue = (1L << bitsPerEntry) - 1L;
            long totalBits = (long) capacity * bitsPerEntry;
            int longCount = (int) ((totalBits + 63L) >>> 6);
            this.data = new long[longCount];
        }

        void setAt(long index, int value) {
            long startBit = index * bitsPerEntry;
            int startLongIdx = (int) (startBit >>> 6);
            int endLongIdx = (int) (((index + 1L) * bitsPerEntry - 1L) >>> 6);
            int bitOffset = (int) (startBit & 63L);
            long maskedVal = ((long) value) & maxEntryValue;

            data[startLongIdx] = (data[startLongIdx] & ~(maxEntryValue << bitOffset)) | (maskedVal << bitOffset);
            if (startLongIdx != endLongIdx && endLongIdx < data.length) {
                int bitsWritten = 64 - bitOffset;
                int bitsRemaining = bitsPerEntry - bitsWritten;
                long upperMask = (1L << bitsRemaining) - 1L;
                data[endLongIdx] = (data[endLongIdx] & ~upperMask) | (maskedVal >>> bitsWritten);
            }
        }

        long[] getLongArray() {
            return data;
        }
    }
}
