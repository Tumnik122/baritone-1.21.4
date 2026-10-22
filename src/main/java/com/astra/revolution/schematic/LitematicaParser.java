package com.astra.revolution.schematic;

import com.mojang.brigadier.exceptions.CommandSyntaxException;
import net.minecraft.commands.arguments.blocks.BlockStateParser;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Holder;
import net.minecraft.core.HolderLookup;
import net.minecraft.core.Vec3i;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.nbt.*;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.Property;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;

/**
 * ASTRA REVOLUTION — NATIVE LITEMATICA & SPONGE SCHEMATIC PARSER
 * Supports .litematic (v5, v6, v7) and .schem (v1, v2, v3).
 */
public final class LitematicaParser {

    private LitematicaParser() {}

    public static AstraSchematic load(Path file) throws IOException {
        String fileName = file.getFileName().toString().toLowerCase(Locale.ROOT);
        try (InputStream in = Files.newInputStream(file)) {
            CompoundTag root = NbtIo.readCompressed(in, NbtAccounter.unlimitedHeap());
            if (fileName.endsWith(".litematic") || root.contains("Regions", Tag.TAG_COMPOUND)) {
                return parseLitematic(file.getFileName().toString(), root);
            } else if (fileName.endsWith(".schem") || root.contains("Palette", Tag.TAG_COMPOUND) || root.contains("Blocks", Tag.TAG_COMPOUND)) {
                return parseSpongeSchem(file.getFileName().toString(), root);
            }
            throw new IOException("Unsupported schematic format in file: " + file);
        }
    }

    private static AstraSchematic parseLitematic(String fallbackName, CompoundTag root) throws IOException {
        int version = root.getInt("Version");
        // Version 4 to 7 share the Regions tag layout
        if (version < 4 && !root.contains("Regions", Tag.TAG_COMPOUND)) {
            throw new IOException("Unsupported Litematica version: " + version + " (expected v4-v7)");
        }

        CompoundTag metadata = root.getCompound("Metadata");
        String name = metadata.contains("Name", Tag.TAG_STRING) ? metadata.getString("Name") : fallbackName;

        CompoundTag regionsTag = root.getCompound("Regions");
        if (regionsTag.isEmpty()) {
            throw new IOException("Litematic file contains no regions.");
        }

        // First pass: calculate global bounding box across all regions
        int minX = Integer.MAX_VALUE, minY = Integer.MAX_VALUE, minZ = Integer.MAX_VALUE;
        int maxX = Integer.MIN_VALUE, maxY = Integer.MIN_VALUE, maxZ = Integer.MIN_VALUE;

        for (String regionName : regionsTag.getAllKeys()) {
            CompoundTag region = regionsTag.getCompound(regionName);
            Vec3i pos = readVec3i(region.getCompound("Position"));
            Vec3i size = readVec3i(region.getCompound("Size"));

            int regMinX = size.getX() < 0 ? pos.getX() + size.getX() + 1 : pos.getX();
            int regMaxX = size.getX() < 0 ? pos.getX() : pos.getX() + size.getX() - 1;
            int regMinY = size.getY() < 0 ? pos.getY() + size.getY() + 1 : pos.getY();
            int regMaxY = size.getY() < 0 ? pos.getY() : pos.getY() + size.getY() - 1;
            int regMinZ = size.getZ() < 0 ? pos.getZ() + size.getZ() + 1 : pos.getZ();
            int regMaxZ = size.getZ() < 0 ? pos.getZ() : pos.getZ() + size.getZ() - 1;

            minX = Math.min(minX, regMinX);
            minY = Math.min(minY, regMinY);
            minZ = Math.min(minZ, regMinZ);
            maxX = Math.max(maxX, regMaxX);
            maxY = Math.max(maxY, regMaxY);
            maxZ = Math.max(maxZ, regMaxZ);
        }

        int totalW = (maxX - minX) + 1;
        int totalH = (maxY - minY) + 1;
        int totalL = (maxZ - minZ) + 1;
        Vec3i totalSize = new Vec3i(totalW, totalH, totalL);

        BlockState[] globalStates = new BlockState[totalW * totalH * totalL];
        Arrays.fill(globalStates, Blocks.AIR.defaultBlockState());
        Map<BlockPos, CompoundTag> globalBlockEntities = new HashMap<>();

        // Second pass: unpack each region's palette, spanned bit-array, and tile entities
        for (String regionName : regionsTag.getAllKeys()) {
            CompoundTag region = regionsTag.getCompound(regionName);
            Vec3i pos = readVec3i(region.getCompound("Position"));
            Vec3i size = readVec3i(region.getCompound("Size"));

            int regMinX = (size.getX() < 0 ? pos.getX() + size.getX() + 1 : pos.getX()) - minX;
            int regMinY = (size.getY() < 0 ? pos.getY() + size.getY() + 1 : pos.getY()) - minY;
            int regMinZ = (size.getZ() < 0 ? pos.getZ() + size.getZ() + 1 : pos.getZ()) - minZ;

            int absW = Math.abs(size.getX());
            int absH = Math.abs(size.getY());
            int absL = Math.abs(size.getZ());

            ListTag paletteTag = region.getList("BlockStatePalette", Tag.TAG_COMPOUND);
            BlockState[] palette = new BlockState[paletteTag.size()];
            for (int i = 0; i < paletteTag.size(); i++) {
                palette[i] = readBlockStateFromTag(paletteTag.getCompound(i));
            }

            long[] packedStates = region.getLongArray("BlockStates");
            int bitsPerBlock = Math.max(2, Integer.SIZE - Integer.numberOfLeadingZeros(Math.max(1, palette.length - 1)));
            LitematicaBitUnpacker unpacker = new LitematicaBitUnpacker(packedStates, bitsPerBlock);

            for (int y = 0; y < absH; y++) {
                for (int z = 0; z < absL; z++) {
                    for (int x = 0; x < absW; x++) {
                        long localIndex = ((long) y * absL + z) * absW + x;
                        int paletteIndex = unpacker.getAt(localIndex);
                        BlockState state = (paletteIndex >= 0 && paletteIndex < palette.length)
                                ? palette[paletteIndex]
                                : Blocks.AIR.defaultBlockState();

                        int gx = regMinX + x;
                        int gy = regMinY + y;
                        int gz = regMinZ + z;
                        if (gx >= 0 && gx < totalW && gy >= 0 && gy < totalH && gz >= 0 && gz < totalL) {
                            int globalIdx = (gy * totalL + gz) * totalW + gx;
                            globalStates[globalIdx] = state;
                        }
                    }
                }
            }

            ListTag tileEntities = region.getList("TileEntities", Tag.TAG_COMPOUND);
            for (int i = 0; i < tileEntities.size(); i++) {
                CompoundTag te = tileEntities.getCompound(i).copy();
                int tx = te.getInt("x") + regMinX;
                int ty = te.getInt("y") + regMinY;
                int tz = te.getInt("z") + regMinZ;
                globalBlockEntities.put(new BlockPos(tx, ty, tz), te);
            }
        }

        return new AstraSchematic(name, totalSize, globalStates, globalBlockEntities);
    }

    private static BlockState readBlockStateFromTag(CompoundTag tag) {
        ResourceLocation blockKey = ResourceLocation.tryParse(tag.getString("Name"));
        Block block = blockKey == null
                ? Blocks.AIR
                : BuiltInRegistries.BLOCK.get(blockKey)
                .map(Holder.Reference::value)
                .orElse(Blocks.AIR);
        CompoundTag properties = tag.getCompound("Properties");
        BlockState blockState = block.defaultBlockState();
        if (properties == null || properties.isEmpty()) {
            return blockState;
        }

        for (String key : properties.getAllKeys()) {
            try {
                Property<?> property = block.getStateDefinition().getProperty(key);
                if (property != null) {
                    String val = properties.getString(key);
                    blockState = setPropertyValue(blockState, property, val);
                }
            } catch (Throwable ignored) {
            }
        }
        return blockState;
    }

    private static <T extends Comparable<T>> BlockState setPropertyValue(BlockState state, Property<T> property, String value) {
        try {
            Optional<T> parsed = property.getValue(value);
            if (parsed.isPresent()) {
                return state.setValue(property, parsed.get());
            }
        } catch (Throwable ignored) {
        }
        return state;
    }

    private static AstraSchematic parseSpongeSchem(String name, CompoundTag root) throws IOException {
        CompoundTag schematicRoot = root.contains("Schematic", Tag.TAG_COMPOUND)
                ? root.getCompound("Schematic")
                : root;

        int width = Short.toUnsignedInt(schematicRoot.getShort("Width"));
        int height = Short.toUnsignedInt(schematicRoot.getShort("Height"));
        int length = Short.toUnsignedInt(schematicRoot.getShort("Length"));
        Vec3i totalSize = new Vec3i(width, height, length);

        CompoundTag paletteTag;
        byte[] blockData;
        ListTag blockEntitiesTag;

        if (schematicRoot.contains("Blocks", Tag.TAG_COMPOUND)) {
            CompoundTag blocksContainer = schematicRoot.getCompound("Blocks");
            paletteTag = blocksContainer.getCompound("Palette");
            blockData = blocksContainer.getByteArray("Data");
            blockEntitiesTag = blocksContainer.getList("BlockEntities", Tag.TAG_COMPOUND);
        } else {
            paletteTag = schematicRoot.getCompound("Palette");
            blockData = schematicRoot.getByteArray("BlockData");
            blockEntitiesTag = schematicRoot.contains("BlockEntities", Tag.TAG_LIST)
                    ? schematicRoot.getList("BlockEntities", Tag.TAG_COMPOUND)
                    : schematicRoot.getList("TileEntities", Tag.TAG_COMPOUND);
        }

        Map<Integer, BlockState> palette = new HashMap<>();
        for (String stateString : paletteTag.getAllKeys()) {
            int id = paletteTag.getInt(stateString);
            BlockState state = parseBlockStateString(stateString);
            palette.put(id, state);
        }

        BlockState[] states = new BlockState[width * height * length];
        Arrays.fill(states, Blocks.AIR.defaultBlockState());

        int dataIndex = 0;
        for (int y = 0; y < height; y++) {
            for (int z = 0; z < length; z++) {
                for (int x = 0; x < width; x++) {
                    int paletteId = 0;
                    int bitOffset = 0;
                    while (dataIndex < blockData.length) {
                        byte b = blockData[dataIndex++];
                        paletteId |= (b & 0x7F) << bitOffset;
                        if ((b & 0x80) == 0) {
                            break;
                        }
                        bitOffset += 7;
                    }
                    int idx = (y * length + z) * width + x;
                    states[idx] = palette.getOrDefault(paletteId, Blocks.AIR.defaultBlockState());
                }
            }
        }

        Map<BlockPos, CompoundTag> blockEntities = new HashMap<>();
        for (int i = 0; i < blockEntitiesTag.size(); i++) {
            CompoundTag be = blockEntitiesTag.getCompound(i).copy();
            int[] posArr = be.getIntArray("Pos");
            if (posArr.length == 3) {
                blockEntities.put(new BlockPos(posArr[0], posArr[1], posArr[2]), be);
            }
        }

        return new AstraSchematic(name, totalSize, states, blockEntities);
    }

    private static Vec3i readVec3i(CompoundTag tag) {
        return new Vec3i(tag.getInt("x"), tag.getInt("y"), tag.getInt("z"));
    }

    private static BlockState parseBlockStateString(String s) {
        try {
            int bracket = s.indexOf('[');
            String loc = bracket >= 0 ? s.substring(0, bracket) : s;
            ResourceLocation blockKey = ResourceLocation.tryParse(loc);
            Block block = blockKey == null ? Blocks.AIR : BuiltInRegistries.BLOCK.get(blockKey).map(Holder.Reference::value).orElse(Blocks.AIR);
            BlockState state = block.defaultBlockState();
            if (bracket >= 0 && s.endsWith("]")) {
                String props = s.substring(bracket + 1, s.length() - 1);
                for (String p : props.split(",")) {
                    String[] kv = p.split("=");
                    if (kv.length == 2) {
                        Property<?> prop = block.getStateDefinition().getProperty(kv[0].trim());
                        if (prop != null) {
                            state = setPropertyValue(state, prop, kv[1].trim());
                        }
                    }
                }
            }
            return state;
        } catch (Exception e) {
            return Blocks.AIR.defaultBlockState();
        }
    }

    /**
     * Exact bit-stream unpacker matching Litematica's spanned long[] layout.
     */
    private static final class LitematicaBitUnpacker {
        private final long[] data;
        private final int bitsPerEntry;
        private final long maxEntryValue;

        LitematicaBitUnpacker(long[] data, int bitsPerEntry) {
            this.data = data;
            this.bitsPerEntry = bitsPerEntry;
            this.maxEntryValue = (1L << bitsPerEntry) - 1L;
        }

        int getAt(long index) {
            long startBit = index * bitsPerEntry;
            int startLongIdx = (int) (startBit >>> 6);
            int endLongIdx = (int) (((index + 1L) * bitsPerEntry - 1L) >>> 6);
            int bitOffset = (int) (startBit & 63L);

            if (startLongIdx >= data.length) {
                return 0;
            }

            if (startLongIdx == endLongIdx) {
                return (int) ((data[startLongIdx] >>> bitOffset) & maxEntryValue);
            } else {
                int endBitOffset = 64 - bitOffset;
                long lower = data[startLongIdx] >>> bitOffset;
                long upper = (endLongIdx < data.length ? data[endLongIdx] : 0L) << endBitOffset;
                return (int) ((lower | upper) & maxEntryValue);
            }
        }
    }
}
