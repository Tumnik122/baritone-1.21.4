package baritone.launch.mixins.perf;

import com.astra.revolution.perf.AstraPerformanceConfig;
import net.minecraft.client.particle.Particle;
import net.minecraft.client.particle.ParticleEngine;
import net.minecraft.core.particles.ParticleOptions;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

@Mixin(ParticleEngine.class)
public abstract class MixinParticleEnginePerf {

    @Inject(
        method = "createParticle(Lnet/minecraft/core/particles/ParticleOptions;DDDDDD)Lnet/minecraft/client/particle/Particle;",
        at = @At("HEAD"),
        cancellable = true,
        require = 0
    )
    private void astra$cancelParticleCreation(
            ParticleOptions parameters,
            double x, double y, double z,
            double vx, double vy, double vz,
            CallbackInfoReturnable<Particle> cir
    ) {
        if (AstraPerformanceConfig.DISABLE_PARTICLES) {
            cir.setReturnValue(null);
        }
    }

    @Inject(method = "tick", at = @At("HEAD"), cancellable = true, require = 0)
    private void astra$cancelParticleTick(CallbackInfo ci) {
        if (AstraPerformanceConfig.DISABLE_PARTICLES) {
            ci.cancel();
        }
    }
}
