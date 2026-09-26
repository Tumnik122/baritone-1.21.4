package baritone.bypass;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import org.junit.Test;

import java.util.List;

import static org.junit.Assert.*;

public class BypassStaircaseStepTest {
    @Test
    public void ascentClearsHeadroomButNeverDestroysTheStep() {
        BlockPos feet = new BlockPos(12, -59, -4);
        for (Direction direction : Direction.Plane.HORIZONTAL) {
            BypassStaircaseStep step = BypassStaircaseStep.create(feet, direction, true);
            assertEquals(feet.relative(direction).above(), step.destination());
            assertEquals(feet.relative(direction), step.support());
            assertEquals(List.of(feet.above(2), step.destination(), step.destination().above()), step.clearance());
            assertFalse(step.clearance().contains(step.support()));
            assertFalse(step.clearance().contains(feet.below()));
        }
    }

    @Test
    public void descentIsOnlyOneBlockAndKeepsItsLandingFloor() {
        BlockPos feet = new BlockPos(0, -54, 0);
        BypassStaircaseStep step = BypassStaircaseStep.create(feet, Direction.NORTH, false);
        assertEquals(feet.north().below(), step.destination());
        assertEquals(feet.north().below(2), step.support());
        assertEquals(List.of(feet.north().above(), feet.north(), feet.north().below()), step.clearance());
        assertFalse(step.clearance().contains(step.support()));
    }

    @Test
    public void jumpApexIsNotALandingEvenAtTheTargetY() {
        BlockPos source = new BlockPos(0, -56, 0);
        BypassStaircaseStep step = BypassStaircaseStep.create(source, Direction.EAST, true);
        assertFalse(step.hasLanded(source.above(), false));
        assertFalse(step.hasLanded(step.destination(), false));
        assertFalse(step.hasLanded(source, true));
        assertTrue(step.hasLanded(step.destination(), true));
        assertEquals(source, step.source());
        assertEquals(source.east().above(), step.destination());
    }

    @Test
    public void stepCopiesMutableBlockPositions() {
        BlockPos.MutableBlockPos source = new BlockPos.MutableBlockPos(0, -56, 0);
        BypassStaircaseStep step = BypassStaircaseStep.create(source, Direction.EAST, true);
        source.set(100, 100, 100);
        assertEquals(new BlockPos(0, -56, 0), step.source());
    }

    @Test(expected = IllegalArgumentException.class)
    public void rejectsAVerticalReconnectDirection() {
        BypassStaircaseStep.create(BlockPos.ZERO, Direction.UP, true);
    }

    @Test
    public void belowTargetAlwaysResumesAscentWithoutAMinuteDelay() {
        assertEquals(BypassProcess.State.ASCENDING_STAIRCASE, BypassProcess.travelState(-59, -55));
        assertEquals(BypassProcess.State.TUNNELING, BypassProcess.travelState(-55, -55));
        assertEquals(BypassProcess.State.DESCENDING_STAIRCASE, BypassProcess.travelState(-54, -55));
    }
}
