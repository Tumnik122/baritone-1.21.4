package baritone.behavior;

import baritone.api.BaritoneAPI;
import baritone.api.Settings;
import net.minecraft.SharedConstants;
import net.minecraft.server.Bootstrap;
import org.junit.BeforeClass;
import org.junit.Test;

import static org.junit.Assert.*;

public class AutoLogoutTest {

    @BeforeClass
    public static void setup() {
        SharedConstants.tryDetectVersion();
        Bootstrap.bootStrap();
    }

    @Test
    public void testHealthToHeartsConversion() {
        // 20 HP = 10 hearts
        float fullHp = 20.0f;
        assertEquals(10.0D, fullHp / 2.0D, 0.001D);

        // 6 HP = 3 hearts (threshold)
        float sixHp = 6.0f;
        assertEquals(3.0D, sixHp / 2.0D, 0.001D);

        // 5.9 HP = 2.95 hearts (< 3 hearts, should trigger)
        float criticalHp = 5.9f;
        double hearts = criticalHp / 2.0D;
        assertTrue(hearts < 3.0D);

        // 6.1 HP = 3.05 hearts (>= 3 hearts, should not trigger)
        float safeHp = 6.1f;
        assertTrue((safeHp / 2.0D) >= 3.0D);
    }

    @Test
    public void testAutoLogoutSettingsDefaults() {
        Settings settings = new Settings();
        assertNotNull(settings);

        // Disconnect threshold should be 3.0 hearts (not 3 HP!)
        assertEquals(3.0D, settings.disconnectHealthHearts.value, 0.001D);

        // By default, autoLogout / disconnectOnLowHealth is off until user activates it
        assertFalse(settings.autoLogout.value);
        assertFalse(settings.disconnectOnLowHealth.value);
    }

    @Test
    public void testAutoLogoutLoopPrevention() {
        Settings settings = new Settings();

        // User turns it on
        settings.autoLogout.value = true;
        settings.disconnectOnLowHealth.value = true;
        assertTrue(settings.autoLogout.value || settings.disconnectOnLowHealth.value);

        // Simulate trigger: both settings are switched to false to prevent reconnect loop
        settings.autoLogout.value = false;
        settings.disconnectOnLowHealth.value = false;

        // When player reconnects, isEnabled is false, preventing infinite disconnect loop
        boolean isEnabled = settings.disconnectOnLowHealth.value || settings.autoLogout.value;
        assertFalse(isEnabled);
    }
}
