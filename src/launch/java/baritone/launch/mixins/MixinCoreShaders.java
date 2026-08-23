/*
 * This file is part of Baritone.
 *
 * Baritone is free software: you can redistribute it and/or modify
 * it under the terms of the GNU Lesser General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 */

package baritone.launch.mixins;

import baritone.utils.BaritoneShaderPrograms;
import net.minecraft.client.renderer.CoreShaders;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** Registers Baritone shader programs before Minecraft preloads its core shaders. */
@Mixin(CoreShaders.class)
public class MixinCoreShaders {

    @Inject(method = "<clinit>", at = @At("TAIL"))
    private static void baritone$registerShaderPrograms(CallbackInfo ci) {
        BaritoneShaderPrograms.register();
    }
}
