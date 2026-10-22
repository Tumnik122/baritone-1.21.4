package baritone.launch.mixins.perf;

import com.astra.revolution.perf.AstraPerformanceConfig;
import net.minecraft.client.resources.sounds.SoundInstance;
import net.minecraft.client.sounds.SoundEngine;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(SoundEngine.class)
public abstract class MixinSoundEnginePerf {

    @Inject(method = "reload", at = @At("HEAD"), cancellable = true, require = 0)
    private void astra$disableOpenALInit(CallbackInfo ci) {
        if (AstraPerformanceConfig.DISABLE_AUDIO) {
            ci.cancel();
        }
    }

    @Inject(method = "play(Lnet/minecraft/client/resources/sounds/SoundInstance;)V", at = @At("HEAD"), cancellable = true, require = 0)
    private void astra$skipSoundPlay(SoundInstance sound, CallbackInfo ci) {
        if (AstraPerformanceConfig.DISABLE_AUDIO) {
            ci.cancel();
        }
    }

    @Inject(method = "tick(Z)V", at = @At("HEAD"), cancellable = true, require = 0)
    private void astra$skipSoundTick(boolean paused, CallbackInfo ci) {
        if (AstraPerformanceConfig.DISABLE_AUDIO) {
            ci.cancel();
        }
    }
}
