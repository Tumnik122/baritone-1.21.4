package dev.astra.baritone.process;

import com.astra.revolution.schematic.AstraSchematic;
import com.astra.revolution.schematic.AstraSchematicManager;
import com.astra.revolution.schematic.AstraSchematicManager.SchematicBuildQueue;
import net.minecraft.core.BlockPos;

/**
 * BUILDER PROCESS ADAPTER (dev.astra.baritone.process)
 * Provides the BuilderProcess API interface delegating to AstraSchematicManager.
 */
public final class BuilderProcess {

    private final AstraSchematicManager manager;

    public BuilderProcess() {
        this.manager = AstraSchematicManager.getInstance();
    }

    public BuilderProcess(Object unused) {
        this.manager = AstraSchematicManager.getInstance();
    }

    public boolean isBuildingActive() {
        return manager.isBuildingActive();
    }

    public void startBuilding() {
        manager.startBuilding();
    }

    public void stopBuilding() {
        manager.stopBuilding();
    }

    public boolean isAuraPrinterMode() {
        return manager.isAuraPrinterMode();
    }

    public void setAuraPrinterMode(boolean aura) {
        manager.setAuraPrinterMode(aura);
    }

    public int getPlacementCooldownTicks() {
        return manager.getPlacementCooldownTicks();
    }

    public void setPlacementCooldownTicks(int ticks) {
        manager.setPlacementCooldownTicks(ticks);
    }

    public void rebuildQueue() {
        manager.rebuildQueue();
    }

    public SchematicBuildQueue getBuildQueue() {
        return manager.getBuildQueue();
    }

    public void onSchematicLoaded(AstraSchematic schematic, BlockPos origin) {
        manager.setPlacementOrigin(origin);
    }

    public void onClientTick() {
        manager.onClientTick();
    }
}
