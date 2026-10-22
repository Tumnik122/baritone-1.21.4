package baritone.launch.mixins;

import com.astra.revolution.visuals.AstraVisualsEngine;
import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.vertex.PoseStack;
import net.minecraft.client.renderer.FogParameters;
import net.minecraft.client.renderer.SkyRenderer;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.ModifyVariable;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Injects Custom Celestial Sky disc colors, dark disc horizon blend & cosmic stars into SkyRenderer.
 */
@Mixin(SkyRenderer.class)
public abstract class MixinSkyRenderer {

    @ModifyVariable(method = "renderSkyDisc", at = @At("HEAD"), ordinal = 0, argsOnly = true, require = 0)
    private float astra$modifySkyRed(float red) {
        if (AstraVisualsEngine.isCustomSkyEnabled()) {
            return AstraVisualsEngine.getCustomSkyRgb()[0];
        }
        return red;
    }

    @ModifyVariable(method = "renderSkyDisc", at = @At("HEAD"), ordinal = 1, argsOnly = true, require = 0)
    private float astra$modifySkyGreen(float green) {
        if (AstraVisualsEngine.isCustomSkyEnabled()) {
            return AstraVisualsEngine.getCustomSkyRgb()[1];
        }
        return green;
    }

    @ModifyVariable(method = "renderSkyDisc", at = @At("HEAD"), ordinal = 2, argsOnly = true, require = 0)
    private float astra$modifySkyBlue(float blue) {
        if (AstraVisualsEngine.isCustomSkyEnabled()) {
            return AstraVisualsEngine.getCustomSkyRgb()[2];
        }
        return blue;
    }

    @Inject(method = "renderDarkDisc", at = @At(value = "INVOKE", target = "Lcom/mojang/blaze3d/vertex/PoseStack;pushPose()V"), require = 0)
    private void astra$onRenderDarkDisc(PoseStack poseStack, CallbackInfo ci) {
        if (AstraVisualsEngine.isCustomSkyEnabled()) {
            float[] dark = AstraVisualsEngine.getCustomDarkDiscRgb();
            RenderSystem.setShaderColor(dark[0], dark[1], dark[2], 1.0F);
        }
    }

    @Inject(method = "renderStars", at = @At(value = "INVOKE", target = "Lcom/mojang/blaze3d/systems/RenderSystem;setShaderFog(Lnet/minecraft/client/renderer/FogParameters;)V", ordinal = 0, shift = At.Shift.AFTER), require = 0)
    private void astra$onRenderStars(FogParameters fogParameters, float starBrightness, PoseStack poseStack, CallbackInfo ci) {
        if (AstraVisualsEngine.isCustomSkyEnabled() && AstraVisualsEngine.isCelestialStarsEnabled()) {
            float[] starTint = AstraVisualsEngine.getCustomStarTint(starBrightness);
            RenderSystem.setShaderColor(starTint[0], starTint[1], starTint[2], starTint[3]);
        }
    }
}
