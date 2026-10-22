package baritone.launch.mixins.perf;

import com.astra.revolution.perf.AstraPerformanceConfig;
import com.mojang.blaze3d.platform.Window;
import com.mojang.blaze3d.TracyFrameCapture;
import net.minecraft.client.Minecraft;
import org.lwjgl.glfw.GLFW;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.Redirect;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import java.util.concurrent.locks.LockSupport;

@Mixin(Minecraft.class)
public abstract class MixinMinecraftPerf {

    @Unique private long astra$lastLoopTimeNs = System.nanoTime();

    /**
     * Prevents GLFW buffer swap / DWM composition ONLY when running in headless mode.
     * When headless mode is disabled (Bot 1), updateDisplay is called normally so
     * the game, splash screen and UI render smoothly.
     */
    @Redirect(
        method = "runTick(Z)V",
        at = @At(value = "INVOKE", target = "Lcom/mojang/blaze3d/platform/Window;updateDisplay(Lcom/mojang/blaze3d/TracyFrameCapture;)V"),
        require = 0
    )
    private void astra$conditionalUpdateDisplay(Window instance, TracyFrameCapture capture) {
        if (!AstraPerformanceConfig.isHeadless()) {
            long handle = instance.getWindow();
            if (handle != 0L && GLFW.glfwGetWindowAttrib(handle, GLFW.GLFW_VISIBLE) == GLFW.GLFW_FALSE) {
                GLFW.glfwShowWindow(handle);
            }
            instance.updateDisplay(capture);
        } else {
            long handle = instance.getWindow();
            if (handle != 0L && GLFW.glfwGetWindowAttrib(handle, GLFW.GLFW_VISIBLE) == GLFW.GLFW_TRUE) {
                GLFW.glfwHideWindow(handle);
            }
            GLFW.glfwPollEvents();
        }
    }

    /**
     * Paces the main client loop when running in headless mode so the thread does not busy-spin,
     * while guaranteeing sub-20ms responsiveness for packet tasks and exact 50ms client ticks.
     */
    @Inject(method = "runTick(Z)V", at = @At("RETURN"), require = 0)
    private void astra$paceHeadlessLoop(boolean renderLevel, CallbackInfo ci) {
        if (AstraPerformanceConfig.isHeadless()) {
            long targetIntervalNs = AstraPerformanceConfig.HEADLESS_LOOP_SLEEP_MS.get() * 1_000_000L;
            long elapsedNs = System.nanoTime() - this.astra$lastLoopTimeNs;
            long remainingNs = targetIntervalNs - elapsedNs;
            if (remainingNs > 250_000L) {
                LockSupport.parkNanos(remainingNs);
            }
        }
        this.astra$lastLoopTimeNs = System.nanoTime();
    }
}
