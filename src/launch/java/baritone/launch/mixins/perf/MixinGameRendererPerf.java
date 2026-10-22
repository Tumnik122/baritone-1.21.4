package baritone.launch.mixins.perf;

import com.astra.revolution.perf.AstraPerformanceConfig;
import com.astra.revolution.visuals.AstraMotionBlurEngine;
import net.minecraft.client.DeltaTracker;
import net.minecraft.client.renderer.GameRenderer;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(GameRenderer.class)
public abstract class MixinGameRendererPerf {

    @Inject(
        method = "render(Lnet/minecraft/client/DeltaTracker;Z)V",
        at = @At("HEAD"),
        cancellable = true,
        require = 0
    )
    private void astra$skipGameRender(DeltaTracker deltaTracker, boolean renderLevel, CallbackInfo ci) {
        if (AstraPerformanceConfig.isHeadless()) {
            ci.cancel();
        }
    }

    @Inject(
        method = "render(Lnet/minecraft/client/DeltaTracker;Z)V",
        at = @At(
            value = "INVOKE",
            target = "Lcom/mojang/blaze3d/systems/RenderSystem;clear(I)V",
            ordinal = 0
        ),
        require = 0
    )
    private void astra$applyMotionBlur(DeltaTracker deltaTracker, boolean renderLevel, CallbackInfo ci) {
        if (renderLevel) {
            AstraMotionBlurEngine.getInstance().onRenderLevelFinished(deltaTracker);
        }
    }
}
