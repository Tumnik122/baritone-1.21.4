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

package baritone.launch.mixins;

import baritone.optimization.FpsOptimizer;
import baritone.utils.accessor.IEntityRenderManager;
import net.minecraft.client.renderer.culling.Frustum;
import net.minecraft.client.renderer.entity.EntityRenderDispatcher;
import net.minecraft.world.entity.Entity;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

@Mixin(EntityRenderDispatcher.class)
public class MixinEntityRenderManager implements IEntityRenderManager {

    @Inject(
            method = "shouldRender",
            at = @At("HEAD"),
            cancellable = true,
            require = 0
    )
    private <E extends Entity> void onShouldRender(E entity, Frustum frustum, double camX, double camY, double camZ, CallbackInfoReturnable<Boolean> cir) {
        if (!FpsOptimizer.shouldRenderEntity(entity, frustum, camX, camY, camZ)) {
            cir.setReturnValue(false);
        }
    }

    @Override
    public double renderPosX() {
        return ((EntityRenderDispatcher) (Object) this).camera.getPosition().x;
    }

    @Override
    public double renderPosY() {
        return ((EntityRenderDispatcher) (Object) this).camera.getPosition().y;
    }

    @Override
    public double renderPosZ() {
        return ((EntityRenderDispatcher) (Object) this).camera.getPosition().z;
    }
}
