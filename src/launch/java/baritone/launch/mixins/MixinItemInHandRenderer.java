package baritone.launch.mixins;

import com.astra.revolution.visuals.AstraHandShader;
import com.astra.revolution.visuals.AstraVisualsEngine;
import com.mojang.blaze3d.vertex.PoseStack;
import net.minecraft.client.player.AbstractClientPlayer;
import net.minecraft.client.renderer.ItemInHandRenderer;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.item.ItemStack;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Redirect;

/**
 * Redirects ItemInHandRenderer.renderArmWithItem into AstraHandShader
 * for radiant, artifact-free first-person hand & item rendering.
 */
@Mixin(ItemInHandRenderer.class)
public abstract class MixinItemInHandRenderer implements AstraHandShader.ArmRendererInvoker {

    @Shadow
    private void renderArmWithItem(
            AbstractClientPlayer player, float partialTicks, float pitch,
            InteractionHand hand, float swingProgress, ItemStack stack,
            float equipProgress, PoseStack poseStack, MultiBufferSource buffer,
            int combinedLight
    ) {
        throw new AssertionError();
    }

    @Override
    public void render(
            AbstractClientPlayer player, float partialTicks, float pitch,
            InteractionHand hand, float swingProgress, ItemStack stack,
            float equipProgress, PoseStack poseStack, MultiBufferSource buffer,
            int combinedLight
    ) {
        this.renderArmWithItem(player, partialTicks, pitch, hand, swingProgress, stack, equipProgress, poseStack, buffer, combinedLight);
    }

    @Redirect(
            method = "renderHandsWithItems",
            at = @At(
                    value = "INVOKE",
                    target = "Lnet/minecraft/client/renderer/ItemInHandRenderer;renderArmWithItem(Lnet/minecraft/client/player/AbstractClientPlayer;FFLnet/minecraft/world/InteractionHand;FLnet/minecraft/world/item/ItemStack;FLcom/mojang/blaze3d/vertex/PoseStack;Lnet/minecraft/client/renderer/MultiBufferSource;I)V"
            ),
            require = 0
    )
    private void astra$redirectRenderArmWithItem(
            ItemInHandRenderer instance,
            AbstractClientPlayer player, float partialTicks, float pitch,
            InteractionHand hand, float swingProgress, ItemStack stack,
            float equipProgress, PoseStack poseStack, MultiBufferSource buffer,
            int combinedLight
    ) {
        AstraHandShader.ArmRendererInvoker invoker = (AstraHandShader.ArmRendererInvoker) instance;
        try {
            if (!AstraVisualsEngine.isHandShadersEnabled()) {
                invoker.render(player, partialTicks, pitch, hand, swingProgress, stack, equipProgress, poseStack, buffer, combinedLight);
                return;
            }

            AstraHandShader.renderArmWithShader(
                    player, partialTicks, pitch, hand, swingProgress, stack, equipProgress, poseStack, buffer, combinedLight,
                    invoker
            );
        } catch (Throwable t) {
            // Bezpieczny fallback: jeśli shader napotkał jakikolwiek błąd, waniliowy render ręki nie zostanie przerwany
            try {
                invoker.render(player, partialTicks, pitch, hand, swingProgress, stack, equipProgress, poseStack, buffer, combinedLight);
            } catch (Throwable ignored) {
            }
        }
    }
}
