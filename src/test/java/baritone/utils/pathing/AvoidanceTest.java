package baritone.utils.pathing;

import baritone.api.BaritoneAPI;
import baritone.api.Settings;
import baritone.api.utils.BetterBlockPos;
import it.unimi.dsi.fastutil.longs.Long2DoubleOpenHashMap;
import net.minecraft.SharedConstants;
import net.minecraft.server.Bootstrap;
import org.junit.BeforeClass;
import org.junit.Test;

import static org.junit.Assert.*;

public class AvoidanceTest {

    @BeforeClass
    public static void setup() {
        SharedConstants.tryDetectVersion();
        Bootstrap.bootStrap();
    }

    @Test
    public void testAvoidanceCoefficientInsideAndOutside() {
        Avoidance avoid = new Avoidance(0, 64, 0, 4.0D, 5);

        // Center
        assertEquals(4.0D, avoid.coefficient(0, 64, 0), 0.001D);

        // Within radius (distance 3)
        assertEquals(4.0D, avoid.coefficient(3, 64, 0), 0.001D);

        // On boundary (distance 5: (3, 64, 4) -> 9 + 16 = 25 <= 25)
        assertEquals(4.0D, avoid.coefficient(3, 64, 4), 0.001D);

        // Strictly outside radius (distance 6)
        assertEquals(1.0D, avoid.coefficient(6, 64, 0), 0.001D);
        assertEquals(1.0D, avoid.coefficient(4, 64, 4), 0.001D); // 16 + 16 = 32 > 25
    }

    @Test
    public void testApplySpherical() {
        Avoidance avoid = new Avoidance(10, 50, 10, 3.0D, 2);
        Long2DoubleOpenHashMap map = new Long2DoubleOpenHashMap();
        map.defaultReturnValue(1.0D);

        avoid.applySpherical(map);

        assertFalse(map.isEmpty());

        long centerHash = BetterBlockPos.longHash(10, 50, 10);
        assertTrue(map.containsKey(centerHash));
        assertEquals(3.0D, map.get(centerHash), 0.001D);

        long edgeHash = BetterBlockPos.longHash(12, 50, 10); // dist = 2
        assertTrue(map.containsKey(edgeHash));
        assertEquals(3.0D, map.get(edgeHash), 0.001D);

        long outsideHash = BetterBlockPos.longHash(13, 50, 10); // dist = 3 > 2
        assertEquals(1.0D, map.get(outsideHash), 0.001D);
    }

    @Test
    public void testMobAvoidanceSettings() {
        Settings settings = new Settings();
        assertNotNull(settings);

        // Avoidance should be enabled by default
        assertTrue("Avoidance should be enabled by default", settings.avoidance.value);

        // Mob specific flags should be true
        assertTrue(settings.mobAvoidanceCreeper.value);
        assertTrue(settings.mobAvoidanceSkeleton.value);
        assertTrue(settings.mobAvoidanceZombie.value);
        assertTrue(settings.mobAvoidanceEnderman.value);

        // Radii should be positive
        assertTrue(settings.creeperAvoidanceRadius.value >= 8);
        assertTrue(settings.skeletonAvoidanceRadius.value >= 10);
        assertTrue(settings.zombieAvoidanceRadius.value >= 6);
        assertTrue(settings.endermanAvoidanceRadius.value >= 6);

        // Coefficients should penalize path (> 1.0)
        assertTrue(settings.creeperAvoidanceCoefficient.value > 1.0D);
        assertTrue(settings.skeletonAvoidanceCoefficient.value > 1.0D);
        assertTrue(settings.zombieAvoidanceCoefficient.value > 1.0D);
        assertTrue(settings.endermanAvoidanceCoefficient.value > 1.0D);
    }
}
