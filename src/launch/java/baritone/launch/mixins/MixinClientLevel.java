package baritone.launch.mixins;

import com.astra.revolution.visuals.AstraVisualsEngine;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.world.phys.Vec3;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * Injects Custom Sky Color into ClientLevel.getSkyColor.
 */
@Mixin(ClientLevel.class)
public abstract class MixinClientLevel {

    @Inject(method = "getSkyColor", at = @At("RETURN"), cancellable = true, require = 0)
    private void astra$onGetSkyColor(Vec3 pos, float partialTicks, CallbackInfoReturnable<Integer> cir) {
        if (AstraVisualsEngine.isCustomSkyEnabled()) {
            cir.setReturnValue(AstraVisualsEngine.getCustomSkyColor(cir.getReturnValue()));
        }
    }
}
