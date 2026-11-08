package baritone.launch.mixins;

import com.mojang.blaze3d.platform.Window;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Zabezpieczenie przed crashem sterownika GPU i OpenGL przy minimalizacji okna Minecraft.
 * Podczas minimalizacji na Windows GLFW przekazuje do Window zdarzenia onFramebufferResize(0, 0)
 * oraz onResize(0, 0), co powoduje błąd alokacji FBO (glTexImage2D z wymiarem 0) lub dzielenie przez zero.
 */
@Mixin(Window.class)
public class MixinWindow {

    @Inject(method = "onFramebufferResize", at = @At("HEAD"), cancellable = true)
    private void guardZeroSizedFramebuffer(long windowPointer, int width, int height, CallbackInfo ci) {
        if (width <= 0 || height <= 0) {
            ci.cancel();
        }
    }

    @Inject(method = "onResize", at = @At("HEAD"), cancellable = true)
    private void guardZeroSizedWindow(long windowPointer, int width, int height, CallbackInfo ci) {
        if (width <= 0 || height <= 0) {
            ci.cancel();
        }
    }
}

