package baritone.launch.mixins;

import com.astra.revolution.visuals.ChinaHatLayer;
import net.minecraft.client.model.PlayerModel;
import net.minecraft.client.player.AbstractClientPlayer;
import net.minecraft.client.renderer.entity.EntityRendererProvider;
import net.minecraft.client.renderer.entity.LivingEntityRenderer;
import net.minecraft.client.renderer.entity.player.PlayerRenderer;
import net.minecraft.client.renderer.entity.state.PlayerRenderState;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Rejestruje autentyczny China Hat Layer (MoonDLC class_1925) na modelu gracza.
 */
@Mixin(PlayerRenderer.class)
public abstract class MixinPlayerRenderer extends LivingEntityRenderer<AbstractClientPlayer, PlayerRenderState, PlayerModel> {

    public MixinPlayerRenderer(EntityRendererProvider.Context context, PlayerModel model, float shadowRadius) {
        super(context, model, shadowRadius);
    }

    @Inject(
            method = "<init>",
            at = @At("TAIL"),
            require = 0
    )
    private void astra$addChinaHatLayer(EntityRendererProvider.Context context, boolean useSlimModel, CallbackInfo ci) {
        this.addLayer(new ChinaHatLayer(this));
    }
}
