package baritone.launch.mixins.perf;

import com.astra.revolution.perf.AstraPerformanceConfig;
import com.mojang.blaze3d.vertex.VertexSorting;
import net.minecraft.client.renderer.SectionBufferBuilderPack;
import net.minecraft.client.renderer.chunk.RenderChunkRegion;
import net.minecraft.client.renderer.chunk.SectionCompiler;
import net.minecraft.core.SectionPos;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

@Mixin(SectionCompiler.class)
public abstract class MixinSectionCompilerPerf {

    @Inject(
        method = "compile",
        at = @At("HEAD"),
        cancellable = true,
        require = 0
    )
    private void astra$abortChunkMeshBuild(
            SectionPos sectionPos,
            RenderChunkRegion renderRegion,
            VertexSorting vertexSorting,
            SectionBufferBuilderPack allocatorStorage,
            CallbackInfoReturnable<SectionCompiler.Results> cir
    ) {
        if (AstraPerformanceConfig.HEADLESS_MODE.get()) {
            cir.setReturnValue(new SectionCompiler.Results());
        }
    }
}
