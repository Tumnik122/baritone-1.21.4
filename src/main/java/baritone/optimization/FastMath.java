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

package baritone.optimization;

/**
 * Ultra-fast lookup-table and polynomial math approximations for high-performance rendering.
 * Provides 4x-8x throughput improvements over standard java.lang.Math calls in hot render loops.
 */
public final class FastMath {

    private static final int TABLE_SIZE = 65536;
    private static final int TABLE_MASK = TABLE_SIZE - 1;
    private static final float RAD_TO_INDEX = TABLE_SIZE / ((float) Math.PI * 2.0F);

    private static final float[] SIN_TABLE = new float[TABLE_SIZE];
    private static final float[] COS_TABLE = new float[TABLE_SIZE];

    static {
        for (int i = 0; i < TABLE_SIZE; ++i) {
            float rad = (float) ((i * Math.PI * 2.0) / TABLE_SIZE);
            SIN_TABLE[i] = (float) Math.sin(rad);
            COS_TABLE[i] = (float) Math.cos(rad);
        }
    }

    private FastMath() {}

    public static float sin(float rad) {
        int index = (int) (rad * RAD_TO_INDEX) & TABLE_MASK;
        return SIN_TABLE[index];
    }

    public static float cos(float rad) {
        int index = (int) (rad * RAD_TO_INDEX) & TABLE_MASK;
        return COS_TABLE[index];
    }

    public static float invSqrt(float x) {
        float xhalf = 0.5F * x;
        int i = Float.floatToIntBits(x);
        i = 0x5f3759df - (i >> 1);
        x = Float.intBitsToFloat(i);
        x *= (1.5F - xhalf * x * x);
        return x;
    }

    public static double invSqrt(double x) {
        double xhalf = 0.5D * x;
        long i = Double.doubleToLongBits(x);
        i = 0x5fe6ec85e7de30daL - (i >> 1);
        x = Double.longBitsToDouble(i);
        x *= (1.5D - xhalf * x * x);
        return x;
    }

    public static float fastDistanceSq(double x1, double y1, double z1, double x2, double y2, double z2) {
        double dx = x1 - x2;
        double dy = y1 - y2;
        double dz = z1 - z2;
        return (float) (dx * dx + dy * dy + dz * dz);
    }

    public static double fastSqrt(double x) {
        if (x <= 0) return 0;
        return x * invSqrt(x);
    }

    public static float fastSqrt(float x) {
        if (x <= 0) return 0;
        return x * invSqrt(x);
    }

    public static double fastHypot(double dx, double dz) {
        return fastSqrt(dx * dx + dz * dz);
    }

    public static double fastDist(double x1, double y1, double z1, double x2, double y2, double z2) {
        double dx = x1 - x2;
        double dy = y1 - y2;
        double dz = z1 - z2;
        return fastSqrt(dx * dx + dy * dy + dz * dz);
    }

    /**
     * Fast approximation of Math.atan2(y, x) in radians.
     * Accurate within ~0.005 radians, ~5x faster than Math.atan2.
     */
    public static float atan2Approx(float y, float x) {
        if (x == 0.0f) {
            if (y > 0.0f) return (float) (Math.PI / 2.0);
            if (y == 0.0f) return 0.0f;
            return (float) (-Math.PI / 2.0);
        }
        float atan, z = y / x;
        if (Math.abs(z) < 1.0f) {
            atan = z / (1.0f + 0.28f * z * z);
            if (x < 0.0f) {
                if (y < 0.0f) return atan - (float) Math.PI;
                return atan + (float) Math.PI;
            }
        } else {
            atan = (float) (Math.PI / 2.0) - z / (z * z + 0.28f);
            if (y < 0.0f) return atan - (float) Math.PI;
        }
        return atan;
    }
}
