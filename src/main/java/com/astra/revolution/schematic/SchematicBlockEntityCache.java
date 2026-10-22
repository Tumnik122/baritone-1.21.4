package com.astra.revolution.schematic;

import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.blockentity.BlockEntityRenderDispatcher;
import net.minecraft.client.renderer.blockentity.BlockEntityRenderer;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.EntityBlock;
import net.minecraft.world.level.block.Mirror;
import net.minecraft.world.level.block.Rotation;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;

import javax.annotation.Nullable;
import java.util.*;

/**
 * Instantiates, hydrates, and caches virtual BlockEntity instances for schematic hologram rendering.
 * Prevents per-frame allocations and skips non-rendered tile entities (Hoppers, Barrels, Furnaces) in O(1) time.
 */
public final class SchematicBlockEntityCache {

    private final Map<BlockPos, BlockEntity> virtualEntities = new HashMap<>();
    private final Set<Block> blocksWithoutRenderer = Collections.newSetFromMap(new IdentityHashMap<>());

    private AstraSchematic cachedSchematic;
    private BlockPos cachedOrigin;
    private Rotation cachedRotation;
    private Mirror cachedMirror;
    private Level cachedLevel;

    public void validateCacheState(
            Level level,
            AstraSchematic schematic,
            BlockPos origin
    ) {
        if (this.cachedLevel != level
                || this.cachedSchematic != schematic
                || !Objects.equals(this.cachedOrigin, origin)
                || (schematic != null && this.cachedRotation != schematic.getRotation())
                || (schematic != null && this.cachedMirror != schematic.getMirror())) {
            clear();
            this.cachedLevel = level;
            this.cachedSchematic = schematic;
            this.cachedOrigin = origin != null ? origin.immutable() : null;
            this.cachedRotation = schematic != null ? schematic.getRotation() : null;
            this.cachedMirror = schematic != null ? schematic.getMirror() : null;
        }
    }

    /**
     * Returns a hydrated virtual BlockEntity if and only if the target BlockState owns a
     * BlockEntity with a registered client BlockEntityRenderer.
     */
    @Nullable
    public BlockEntity getOrCreateRenderableBlockEntity(
            Level level,
            AstraSchematic schematic,
            int sx, int sy, int sz,
            BlockPos worldPos,
            BlockState targetState
    ) {
        if (!targetState.hasBlockEntity()) {
            return null;
        }

        Block block = targetState.getBlock();
        if (!(block instanceof EntityBlock entityBlock) || blocksWithoutRenderer.contains(block)) {
            return null;
        }

        BlockPos immutableWorldPos = worldPos.immutable();
        BlockEntity existing = virtualEntities.get(immutableWorldPos);
        if (existing != null) {
            if (existing.getBlockState() != targetState) {
                existing.setBlockState(targetState);
            }
            return existing;
        }

        BlockEntity created;
        try {
            created = entityBlock.newBlockEntity(immutableWorldPos, targetState);
        } catch (Throwable t) {
            blocksWithoutRenderer.add(block);
            return null;
        }

        if (created == null) {
            blocksWithoutRenderer.add(block);
            return null;
        }

        created.setLevel(level);
        created.setBlockState(targetState);

        // Verify that a client BlockEntityRenderer actually exists for this BlockEntityType
        // (skips Hoppers, Droppers, Dispensers, Barrels, Furnaces, Crafters, etc.)
        BlockEntityRenderDispatcher dispatcher = Minecraft.getInstance().getBlockEntityRenderDispatcher();
        BlockEntityRenderer<BlockEntity> renderer = dispatcher.getRenderer(created);
        if (renderer == null) {
            blocksWithoutRenderer.add(block);
            return null;
        }

        // Hydrate NBT (SignText, BannerPatterns, Skull ResolvableProfile, DecoratedPot Sherds)
        schematic.getTransformedBlockEntityData(sx, sy, sz).ifPresent(nbt -> {
            try {
                nbt.putInt("x", immutableWorldPos.getX());
                nbt.putInt("y", immutableWorldPos.getY());
                nbt.putInt("z", immutableWorldPos.getZ());
                created.loadWithComponents(nbt, level.registryAccess());
                // Re-apply transformed BlockState in case loadWithComponents reset orientation
                created.setBlockState(targetState);
            } catch (Throwable ignored) {
                // Keep default unhydrated BlockEntity if schematic NBT came from an older data version
            }
        });

        virtualEntities.put(immutableWorldPos, created);
        return created;
    }

    public void clear() {
        virtualEntities.clear();
    }
}
