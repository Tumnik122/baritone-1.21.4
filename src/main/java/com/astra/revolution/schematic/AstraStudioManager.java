package com.astra.revolution.schematic;

import net.minecraft.core.BlockPos;

import java.nio.file.Path;
import java.util.List;

/**
 * ASTRA STUDIO MANAGER FACADE
 * Bridges AstraStudioManager API to AstraSchematicManager.
 */
public final class AstraStudioManager {

    private static final AstraStudioManager INSTANCE = new AstraStudioManager();

    public static AstraStudioManager getInstance() {
        return INSTANCE;
    }

    private final AstraSchematicManager manager = AstraSchematicManager.getInstance();

    private AstraStudioManager() {}

    public static BlockPos getTargetedOrPlayerBlockPos() {
        return AstraSchematicManager.getTargetedOrPlayerBlockPos();
    }

    public AstraSchematic getActiveSchematic() {
        return manager.getActiveSchematic();
    }

    public BlockPos getSchematicOrigin() {
        return manager.getSchematicOrigin();
    }

    public void setSchematicOrigin(BlockPos origin) {
        manager.setSchematicOrigin(origin);
    }

    public void nudgeOrigin(int dx, int dy, int dz) {
        manager.nudgeOrigin(dx, dy, dz);
    }

    public AstraSchematicManager getBuilderProcess() {
        return manager;
    }

    public boolean isBuildingActive() {
        return manager.isBuildingActive();
    }

    public SchematicHologramRenderer getHologramRenderer() {
        return manager.getHologramRenderer();
    }

    public String getStatusBannerMessage() {
        return manager.getStatusBannerMessage();
    }

    public void setStatusBannerMessage(String msg) {
        manager.setStatusBannerMessage(msg);
    }

    public void unloadSchematic() {
        manager.unloadSchematic();
    }

    public List<Path> listAvailableSchematics() {
        return manager.listAvailableSchematics();
    }

    public void loadSchematicFile(Path file, BlockPos origin) {
        manager.loadSchematicFile(file, origin);
    }

    public SelectionManager getSelectionManager() {
        return manager.getSelectionManager();
    }

    public Path getSchematicsDir() {
        return manager.getSchematicsDir();
    }

    public void onClientTick() {
        manager.onClientTick();
    }
}
