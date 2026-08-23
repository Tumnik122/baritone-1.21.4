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

import baritone.Baritone;
import net.minecraft.client.Camera;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.culling.Frustum;
import net.minecraft.core.BlockPos;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.decoration.ArmorStand;
import net.minecraft.world.entity.decoration.ItemFrame;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;

/**
 * High-performance render culling & optimization engine.
 * Drastically reduces draw calls, matrix calculations, and CPU-GPU bottlenecks.
 */
public final class FpsOptimizer {

    private FpsOptimizer() {}

    // Active camera and frustum references updated per frame
    public static Frustum currentFrustum = null;
    public static Camera currentCamera = null;
    public static double cameraX = 0;
    public static double cameraY = 0;
    public static double cameraZ = 0;

    // Real-time culling metrics for HUD
    public static volatile int culledEntitiesThisFrame = 0;
    public static volatile int culledBlockEntitiesThisFrame = 0;
    public static volatile int culledParticlesThisFrame = 0;

    private static int entityCounter = 0;
    private static int blockEntityCounter = 0;
    private static int particleCounter = 0;
    private static long lastFrameTime = 0;

    public static void onFrameStart(Camera camera, Frustum frustum) {
        currentCamera = camera;
        currentFrustum = frustum;
        if (camera != null) {
            Vec3 pos = camera.getPosition();
            cameraX = pos.x;
            cameraY = pos.y;
            cameraZ = pos.z;
        }

        long now = System.currentTimeMillis();
        if (now - lastFrameTime >= 250) { // Update HUD stats 4 times per second
            culledEntitiesThisFrame = entityCounter * 4;
            culledBlockEntitiesThisFrame = blockEntityCounter * 4;
            culledParticlesThisFrame = particleCounter * 4;
            entityCounter = 0;
            blockEntityCounter = 0;
            particleCounter = 0;
            lastFrameTime = now;
        }
    }

    public static void onWorldChange() {
        currentFrustum = null;
        currentCamera = null;
        entityCounter = 0;
        blockEntityCounter = 0;
        particleCounter = 0;
        culledEntitiesThisFrame = 0;
        culledBlockEntitiesThisFrame = 0;
        culledParticlesThisFrame = 0;
    }

    /**
     * Determines whether an entity should be rendered or culled to maximize FPS.
     */
    public static boolean shouldRenderEntity(Entity entity, Frustum frustum, double camX, double camY, double camZ) {
        if (!Baritone.settings().fpsBoostEnabled.value || !Baritone.settings().cullEntities.value) {
            return true;
        }

        Minecraft mc = Minecraft.getInstance();
        if (mc.player == null || entity == mc.player || entity == mc.player.getVehicle()) {
            return true;
        }

        // Distance culling
        double maxDist = Baritone.settings().entityRenderDistance.value;
        double maxDistSq = maxDist * maxDist;

        // Tighter distance limits for heavy decoration entities that cause massive FPS drops in bases
        if (entity instanceof ItemFrame || entity instanceof ArmorStand || entity instanceof ItemEntity) {
            double decorLimit = Math.min(maxDist, 32.0D);
            maxDistSq = decorLimit * decorLimit;
        }

        double dx = entity.getX() - camX;
        double dy = entity.getY() - camY;
        double dz = entity.getZ() - camZ;
        double distSq = dx * dx + dy * dy + dz * dz;

        if (distSq > maxDistSq) {
            entityCounter++;
            return false;
        }

        // Frustum culling
        Frustum effectiveFrustum = (frustum != null) ? frustum : currentFrustum;
        if (effectiveFrustum != null) {
            AABB boundingBox = entity.getBoundingBox();
            if (boundingBox != null && !effectiveFrustum.isVisible(boundingBox)) {
                entityCounter++;
                return false;
            }
        }

        return true;
    }

    /**
     * Determines whether a block entity (chest, sign, banner, etc.) should be rendered.
     */
    public static boolean shouldRenderBlockEntity(BlockEntity blockEntity) {
        if (!Baritone.settings().fpsBoostEnabled.value || !Baritone.settings().cullBlockEntities.value) {
            return true;
        }

        BlockPos pos = blockEntity.getBlockPos();
        double maxDist = Baritone.settings().blockEntityRenderDistance.value;
        double maxDistSq = maxDist * maxDist;

        double dx = (pos.getX() + 0.5D) - cameraX;
        double dy = (pos.getY() + 0.5D) - cameraY;
        double dz = (pos.getZ() + 0.5D) - cameraZ;
        double distSq = dx * dx + dy * dy + dz * dz;

        if (distSq > maxDistSq) {
            blockEntityCounter++;
            return false;
        }

        // Frustum culling for block entity bounding box
        if (currentFrustum != null) {
            AABB box = new AABB(pos.getX(), pos.getY(), pos.getZ(), pos.getX() + 1.0, pos.getY() + 1.0, pos.getZ() + 1.0);
            if (!currentFrustum.isVisible(box)) {
                blockEntityCounter++;
                return false;
            }
        }

        return true;
    }

    /**
     * Checks if a particle should be rendered or culled.
     */
    public static boolean shouldRenderParticle(double x, double y, double z) {
        if (!Baritone.settings().fpsBoostEnabled.value || !Baritone.settings().cullParticles.value) {
            return true;
        }

        // Distance check (24 blocks max for minor particle effects)
        double dx = x - cameraX;
        double dy = y - cameraY;
        double dz = z - cameraZ;
        if (dx * dx + dy * dy + dz * dz > 576.0D) { // 24^2
            particleCounter++;
            return false;
        }

        // Quick frustum check
        if (currentFrustum != null) {
            AABB particleBox = new AABB(x - 0.2, y - 0.2, z - 0.2, x + 0.2, y + 0.2, z + 0.2);
            if (!currentFrustum.isVisible(particleBox)) {
                particleCounter++;
                return false;
            }
        }

        return true;
    }

    /**
     * Checks if cloud rendering should be skipped because the player is underground.
     */
    public static boolean shouldSkipClouds() {
        if (!Baritone.settings().fpsBoostEnabled.value || !Baritone.settings().cullCloudsUnderground.value) {
            return false;
        }

        Minecraft mc = Minecraft.getInstance();
        if (mc.player == null || mc.level == null) {
            return false;
        }

        // If player is deep underground (below Y 55) or inside a dark covered area
        if (mc.player.getY() < 55.0) {
            return true;
        }

        BlockPos playerHead = BlockPos.containing(mc.player.getX(), mc.player.getEyeY(), mc.player.getZ());
        return !mc.level.canSeeSky(playerHead);
    }
}
