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

package baritone.hud;

import java.util.ArrayList;
import java.util.List;

/**
 * Rejestr zdarzeń HUD (tech-log) z historią ostatnich powiadomień.
 */
public final class HudLog {

    private static final int MAX_ENTRIES = 10;
    private static final List<Entry> ENTRIES = new ArrayList<>();

    public record Entry(String text, long timestamp) {}

    private HudLog() {}

    public static synchronized void push(String text) {
        ENTRIES.add(0, new Entry(text, System.currentTimeMillis()));
        while (ENTRIES.size() > MAX_ENTRIES) {
            ENTRIES.remove(ENTRIES.size() - 1);
        }
    }

    public static synchronized int size() {
        return ENTRIES.size();
    }

    public static synchronized long age(int index) {
        if (index < 0 || index >= ENTRIES.size()) {
            return Long.MAX_VALUE;
        }
        return System.currentTimeMillis() - ENTRIES.get(index).timestamp();
    }

    public static synchronized String text(int index) {
        if (index < 0 || index >= ENTRIES.size()) {
            return "";
        }
        return ENTRIES.get(index).text();
    }
}
