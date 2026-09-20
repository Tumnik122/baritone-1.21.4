package corz.build;

import baritone.api.utils.BetterBlockPos;
import baritone.utils.BlockStateInterface;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.state.BlockState;

import java.util.Collection;
import java.util.List;

/**
 * Klasa adaptera zgodności z prototypem corz.
 * Deleguje logikę sekwencjonowania i weryfikacji bezpośrednio do Baritone PlacementScheduler.
 */
public class PlacementScheduler {

    private final baritone.utils.schematic.PlacementScheduler scheduler = new baritone.utils.schematic.PlacementScheduler();

    public PlacementScheduler() {}

    public PlacementScheduler(Level world) {}

    public List<BetterBlockPos> schedulePlacements(Collection<BetterBlockPos> candidates, BlockPos playerFeet, BlockStateInterface bsi) {
        return scheduler.schedulePlacements(candidates, playerFeet, bsi);
    }

    public boolean isAwaitingVerification(Level world) {
        return scheduler.isAwaitingVerification(world);
    }

    public void notifyPlaced(BlockPos pos) {
        scheduler.notifyPlaced(pos);
    }

    public void reset() {
        scheduler.reset();
    }

    public baritone.utils.schematic.PlacementScheduler getScheduler() {
        return scheduler;
    }
}
