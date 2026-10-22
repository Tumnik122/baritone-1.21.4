package baritone.launch.mixins.perf;

import com.astra.revolution.perf.AstraPerformanceConfig;
import com.mojang.blaze3d.resource.GraphicsResourceAllocator;
import net.minecraft.client.Camera;
import net.minecraft.client.DeltaTracker;
import net.minecraft.client.renderer.GameRenderer;
import net.minecraft.client.renderer.LevelRenderer;
import org.joml.Matrix4f;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(LevelRenderer.class)
public abstract class MixinLevelRendererPerf {

    @Inject(
        method = "renderLevel",
        at = @At("HEAD"),
        cancellable = true,
        require = 0
    )
    private void astra$skipWorldRender(
            GraphicsResourceAllocator graphicsResourceAllocator,
            DeltaTracker deltaTracker,
            boolean renderBlockOutline,
            Camera camera,
            GameRenderer gameRenderer,
            Matrix4f matrix4f,
            Matrix4f matrix4f2,
            CallbackInfo ci
    ) {
        if (AstraPerformanceConfig.HEADLESS_MODE.get()) {
            ci.cancel();
        }
    }

    /**
     * Prevents queueing chunk rebuilds when running in dedicated headless mode.
     */
    @Inject(
        method = "setSectionDirty(III)V",
        at = @At("HEAD"),
        cancellable = true,
        require = 0
    )
    private void astra$skipChunkRebuildSchedule(int x, int y, int z, CallbackInfo ci) {
        if (AstraPerformanceConfig.HEADLESS_MODE.get()) {
            ci.cancel();
        }
    }
}
