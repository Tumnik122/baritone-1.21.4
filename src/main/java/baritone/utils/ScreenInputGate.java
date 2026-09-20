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

package baritone.utils;

/** Globalny licznik ticków wyciszenia inputu (ochrona ChatC). */
public final class ScreenInputGate {

    private static int suppressTicks = 0;

    private ScreenInputGate() {}

    /** Wycisz input na kolejne `ticks` ticków. */
    public static void suppress(int ticks) {
        suppressTicks = Math.max(suppressTicks, ticks);
    }

    public static boolean isSuppressed() {
        return suppressTicks > 0;
    }

    /** Wywoływane raz na tick z PlayerMovementInput. */
    public static void tickDown() {
        if (suppressTicks > 0) {
            suppressTicks--;
        }
    }
}
