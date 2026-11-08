package baritone.launch.mixins;

import com.astra.revolution.visuals.AstraVisualRemovals;
import com.mojang.blaze3d.vertex.PoseStack;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.ScreenEffectRenderer;
import net.minecraft.client.renderer.texture.TextureAtlasSprite;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Mixin ScreenEffectRenderer — czyści pole widzenia gracza.
 * Inspirowane MoonDLC InGameOverlayRendererMixin.
 */
@Mixin(ScreenEffectRenderer.class)
public abstract class MixinScreenEffectRenderer {

    @Inject(
            method = "renderFire",
            at = @At("HEAD"),
            cancellable = true,
            require = 0
    )
    private static void onRenderFire(PoseStack poseStack, MultiBufferSource bufferSource, CallbackInfo ci) {
        if (AstraVisualRemovals.isLowFireEnabled()) {
            ci.cancel();
        }
    }

    @Inject(
            method = "renderTex",
            at = @At("HEAD"),
            cancellable = true,
            require = 0
    )
    private static void onRenderTex(TextureAtlasSprite sprite, PoseStack poseStack, MultiBufferSource bufferSource, CallbackInfo ci) {
        if (AstraVisualRemovals.isNoSuffocationEnabled()) {
            ci.cancel();
        }
    }

    @Inject(
            method = "renderWater",
            at = @At("HEAD"),
            cancellable = true,
            require = 0
    )
    private static void onRenderWater(Minecraft minecraft, PoseStack poseStack, MultiBufferSource bufferSource, CallbackInfo ci) {
        if (AstraVisualRemovals.isClearWaterEnabled()) {
            ci.cancel();
        }
    }
}
