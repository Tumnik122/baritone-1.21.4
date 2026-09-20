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

package baritone.utils.schematic.format.defaults;

import baritone.api.schematic.CompositeSchematic;
import baritone.api.schematic.IStaticSchematic;
import baritone.utils.schematic.StaticSchematic;
import net.minecraft.core.Holder;
import net.minecraft.core.Vec3i;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.Property;
import org.apache.commons.lang3.Validate;

import javax.annotation.Nullable;
import java.util.Collections;
import java.util.Optional;

/**
 * Based on EmersonDove's work
 * <a href="https://github.com/cabaletta/baritone/pull/2544">...</a>
 *
 * @author rycbar
 * @since 22.09.2022
 */
public final class LitematicaSchematic extends CompositeSchematic implements IStaticSchematic {

    /**
     * @param nbtTagCompound a decompressed file stream aka nbt data.
     * @param rotated        if the schematic is rotated by 90°.
     */
    public LitematicaSchematic(CompoundTag nbt) {
        super(0, 0, 0);
        fillInSchematic(nbt);
    }

    /**
     * @return Array of subregion tags.
     */
    private static CompoundTag[] getRegions(CompoundTag nbt) {
        return nbt.getCompound("Regions").getAllKeys().stream()
                .map(nbt.getCompound("Regions")::getCompound)
                .toArray(CompoundTag[]::new);
    }

    /**
     * Gets both ends from a region box for a given axis and returns the lower one.
     *
     * @param s axis that should be read.
     * @return the lower coord of the requested axis.
     */
    private static int getMinOfSubregion(CompoundTag subReg, String s) {
        int a = subReg.getCompound("Position").getInt(s);
        int b = subReg.getCompound("Size").getInt(s);
        return Math.min(a, a + b + 1);
    }

    /**
     * @param blockStatePalette List of all different block types used in the schematic.
     * @return Array of BlockStates.
     */
    private static BlockState[] getBlockList(ListTag blockStatePalette) {
        BlockState[] blockList = new BlockState[blockStatePalette.size()];

        for (int i = 0; i < blockStatePalette.size(); i++) {
            CompoundTag tag = (CompoundTag) blockStatePalette.get(i);
            ResourceLocation blockKey = ResourceLocation.tryParse(tag.getString("Name"));
            Block block = blockKey == null
                ? Blocks.AIR
                : BuiltInRegistries.BLOCK.get(blockKey)
                    .map(Holder.Reference::value)
                    .orElse(Blocks.AIR);
            CompoundTag properties = tag.getCompound("Properties");

            blockList[i] = getBlockState(block, properties);
        }
        return blockList;
    }

    /**
     * @param block      block.
     * @param properties List of Properties the block has.
     * @return A blockState.
     */
    private static BlockState getBlockState(Block block, CompoundTag properties) {
        BlockState blockState = block.defaultBlockState();
        if (properties == null || properties.isEmpty()) {
            return blockState;
        }

        for (String key : properties.getAllKeys()) {
            try {
                Property<?> property = block.getStateDefinition().getProperty(key);
                if (property != null) {
                    String propertyValue = properties.getString(key);
                    blockState = setPropertyValue(blockState, property, propertyValue);
                }
            } catch (Throwable ignored) {
                // Ignore unrecognized or incompatible property values across MC versions
            }
        }
        return blockState;
    }

    /**
     * @author Emerson
     */
    private static <T extends Comparable<T>> BlockState setPropertyValue(BlockState state, Property<T> property, String value) {
        try {
            Optional<T> parsed = property.getValue(value);
            if (parsed.isPresent()) {
                return state.setValue(property, parsed.get());
            }
        } catch (Throwable ignored) {
        }
        // Fallback: keep current/default state if property value is not recognized
        return state;
    }

    /**
     * @param amountOfBlockTypes amount of block types in the schematic.
     * @return amount of bits used to encode a block.
     */
    private static int getBitsPerBlock(int amountOfBlockTypes) {
        return (int) Math.max(2, Math.ceil(Math.log(Math.max(1, amountOfBlockTypes)) / Math.log(2)));
    }

    /**
     * Calculates the volume of the subregion. As size can be a negative value we take the absolute value of the
     * multiplication as the volume still holds a positive amount of blocks.
     *
     * @return the volume of the subregion.
     */
    private static long getVolume(CompoundTag subReg) {
        CompoundTag size = subReg.getCompound("Size");
        return Math.abs((long) size.getInt("x") * (long) size.getInt("y") * (long) size.getInt("z"));
    }

    /**
     * @param s axis.
     * @return the lowest coordinate of that axis of the schematic.
     */
    private static int getMinOfSchematic(CompoundTag nbt, String s) {
        int n = Integer.MAX_VALUE;
        for (CompoundTag subReg : getRegions(nbt)) {
            n = Math.min(n, getMinOfSubregion(subReg, s));
        }
        return n;
    }

    /**
     * reads the file data.
     */
    private void fillInSchematic(CompoundTag nbt) {
        Vec3i offsetMinCorner = new Vec3i(getMinOfSchematic(nbt, "x"), getMinOfSchematic(nbt, "y"), getMinOfSchematic(nbt, "z"));
        for (CompoundTag subReg : getRegions(nbt)) {
            ListTag usedBlockTypes = subReg.getList("BlockStatePalette", 10);
            BlockState[] blockList = getBlockList(usedBlockTypes);

            int bitsPerBlock = getBitsPerBlock(Math.max(1, usedBlockTypes.size()));
            long regionVolume = getVolume(subReg);
            long[] blockStateArray = subReg.getLongArray("BlockStates");

            LitematicaBitArray bitArray = new LitematicaBitArray(bitsPerBlock, regionVolume, blockStateArray);
            writeSubregionIntoSchematic(subReg, offsetMinCorner, blockList, bitArray);
        }
    }

    /**
     * Writes the file data in to the IBlockstate array.
     *
     * @param blockList list with the different block types used in the schematic.
     * @param bitArray  bit array that holds the placement pattern.
     */
    private void writeSubregionIntoSchematic(CompoundTag subReg, Vec3i offsetMinCorner, BlockState[] blockList, LitematicaBitArray bitArray) {
        int offsetX = getMinOfSubregion(subReg, "x") - offsetMinCorner.getX();
        int offsetY = getMinOfSubregion(subReg, "y") - offsetMinCorner.getY();
        int offsetZ = getMinOfSubregion(subReg, "z") - offsetMinCorner.getZ();
        CompoundTag size = subReg.getCompound("Size");
        int sizeX = Math.abs(size.getInt("x"));
        int sizeY = Math.abs(size.getInt("y"));
        int sizeZ = Math.abs(size.getInt("z"));
        BlockState[][][] states = new BlockState[sizeX][sizeZ][sizeY];
        int index = 0;
        BlockState defaultState = (blockList != null && blockList.length > 0 && blockList[0] != null)
                ? blockList[0]
                : Blocks.AIR.defaultBlockState();

        for (int y = 0; y < sizeY; y++) {
            for (int z = 0; z < sizeZ; z++) {
                for (int x = 0; x < sizeX; x++) {
                    if (blockList == null || blockList.length == 0) {
                        states[x][z][y] = Blocks.AIR.defaultBlockState();
                    } else if (blockList.length == 1) {
                        // When palette has only 1 block, Litematica often omits BlockStates array
                        states[x][z][y] = defaultState;
                    } else {
                        int paletteIdx = bitArray.getAt(index);
                        if (paletteIdx >= 0 && paletteIdx < blockList.length && blockList[paletteIdx] != null) {
                            states[x][z][y] = blockList[paletteIdx];
                        } else {
                            states[x][z][y] = defaultState;
                        }
                    }
                    index++;
                }
            }
        }
        this.put(new StaticSchematic(states), offsetX, offsetY, offsetZ);
    }

    @Override
    public BlockState getDirect(int x, int y, int z) {
        return desiredState(x, y, z, null, Collections.emptyList());
    }

    /**
     * @author maruohon
     * Class from the Litematica mod by maruohon
     * Usage under LGPLv3 with the permission of the author.
     * <a href="https://github.com/maruohon/litematica">...</a>
     */
    private static class LitematicaBitArray {
        /**
         * The long array that is used to store the data for this BitArray.
         */
        private final long[] longArray;
        /**
         * Number of bits a single entry takes up
         */
        private final int bitsPerEntry;
        /**
         * The maximum value for a single entry. This also works as a bitmask for a single entry.
         * For instance, if bitsPerEntry were 5, this value would be 31 (ie, {@code 0b00011111}).
         */
        private final long maxEntryValue;
        /**
         * Number of entries in this array (<b>not</b> the length of the long array that internally backs this array)
         */
        private final long arraySize;

        public LitematicaBitArray(int bitsPerEntryIn, long arraySizeIn, @Nullable long[] longArrayIn) {
            this.arraySize = Math.max(0L, arraySizeIn);
            this.bitsPerEntry = Math.max(1, Math.min(32, bitsPerEntryIn));
            this.maxEntryValue = (1L << this.bitsPerEntry) - 1L;

            if (longArrayIn != null && longArrayIn.length > 0) {
                this.longArray = longArrayIn;
            } else {
                int requiredLen = (int) (roundUp(this.arraySize * (long) this.bitsPerEntry, 64L) / 64L);
                this.longArray = new long[Math.max(1, requiredLen)];
            }
        }

        public static long roundUp(long number, long interval) {
            int sign = 1;
            if (interval == 0) {
                return 0;
            } else if (number == 0) {
                return interval;
            } else {
                if (number < 0) {
                    sign = -1;
                }

                long i = number % (interval * sign);
                return i == 0 ? number : number + (interval * sign) - i;
            }
        }

        public int getAt(long index) {
            if (index < 0L || index >= this.arraySize || this.longArray.length == 0) {
                return 0;
            }
            long startOffset = index * (long) this.bitsPerEntry;
            int startArrIndex = (int) (startOffset >> 6); // startOffset / 64
            int endArrIndex = (int) (((index + 1L) * (long) this.bitsPerEntry - 1L) >> 6);
            int startBitOffset = (int) (startOffset & 0x3F); // startOffset % 64

            if (startArrIndex >= this.longArray.length) {
                return 0;
            }

            if (startArrIndex == endArrIndex) {
                return (int) (this.longArray[startArrIndex] >>> startBitOffset & this.maxEntryValue);
            } else {
                int endOffset = 64 - startBitOffset;
                long endVal = (endArrIndex < this.longArray.length) ? this.longArray[endArrIndex] : 0L;
                return (int) ((this.longArray[startArrIndex] >>> startBitOffset | endVal << endOffset) & this.maxEntryValue);
            }
        }

        public long size() {
            return this.arraySize;
        }
    }
}
