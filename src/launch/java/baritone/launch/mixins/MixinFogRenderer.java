package baritone.launch.mixins;

import com.astra.revolution.visuals.AstraVisualsEngine;
import net.minecraft.client.Camera;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.renderer.FogParameters;
import net.minecraft.client.renderer.FogRenderer;
import org.joml.Vector4f;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * Injects Custom Atmospheric Fog color & parameters into Minecraft's FogRenderer.
 */
@Mixin(FogRenderer.class)
public abstract class MixinFogRenderer {

    @Inject(method = "computeFogColor", at = @At("RETURN"), cancellable = true, require = 0)
    private static void astra$onComputeFogColor(Camera camera, float partialTicks, ClientLevel level, int renderDistance, float bossColorModifier, CallbackInfoReturnable<Vector4f> cir) {
        if (AstraVisualsEngine.isCustomFogEnabled()) {
            cir.setReturnValue(AstraVisualsEngine.getCustomFogColor(cir.getReturnValue(), camera));
        }
    }

    @Inject(method = "setupFog", at = @At("RETURN"), cancellable = true, require = 0)
    private static void astra$onSetupFog(Camera camera, FogRenderer.FogMode fogMode, Vector4f fogColor, float renderDistance, boolean thickFog, float partialTicks, CallbackInfoReturnable<FogParameters> cir) {
        if (AstraVisualsEngine.isCustomFogEnabled()) {
            cir.setReturnValue(AstraVisualsEngine.modifyFogParameters(cir.getReturnValue(), camera, renderDistance, fogMode));
        }
    }
}
