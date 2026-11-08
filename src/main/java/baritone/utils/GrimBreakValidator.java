package baritone.utils;

import net.minecraft.client.player.LocalPlayer;
import net.minecraft.core.BlockPos;
import net.minecraft.tags.FluidTags;
import net.minecraft.world.effect.MobEffectInstance;
import net.minecraft.world.effect.MobEffectUtil;
import net.minecraft.world.effect.MobEffects;
import net.minecraft.world.entity.ai.attributes.Attributes;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.BlockGetter;
import net.minecraft.world.level.block.state.BlockState;

/**
 * Kalkulator czystego postępu niszczenia bloku zgodny z walidacją GrimAC 1.21.4.
 * Uniemożliwia wysłanie STOP_DESTROY_BLOCK przed czasem wymaganym przez serwer.
 */
public final class GrimBreakValidator {

    private GrimBreakValidator() {}

    /**
     * Wylicza dokładny przyrost destroyProgress w bieżącym ticku (zgodnie z GrimAC 1.21.4),
     * uwzględniając stan onGround z początku i końca ticku.
     */
    public static float getGrimDestroyProgress(LocalPlayer player, BlockGetter level, BlockPos pos, BlockState state, boolean wasOnGroundLastTick) {
        if (player == null || level == null || state == null || pos == null) return 0.0f;

        float hardness = state.getDestroySpeed(level, pos);
        if (hardness < 0.0f) return 0.0f;
        if (hardness == 0.0f) return 1.0f;

        ItemStack tool = player.getMainHandItem();
        float speed = tool.getDestroySpeed(state);

        if (speed > 1.0f) {
            speed += (float) player.getAttributeValue(Attributes.MINING_EFFICIENCY);
        }

        if (MobEffectUtil.hasDigSpeed(player)) {
            speed *= 1.0f + (MobEffectUtil.getDigSpeedAmplification(player) + 1) * 0.2f;
        }

        MobEffectInstance fatigue = player.getEffect(MobEffects.DIG_SLOWDOWN);
        if (fatigue != null) {
            float multiplier = switch (fatigue.getAmplifier()) {
                case 0 -> 0.3f;
                case 1 -> 0.09f;
                case 2 -> 0.027f;
                default -> 8.1E-4f;
            };
            speed *= multiplier;
        }

        speed *= (float) player.getAttributeValue(Attributes.BLOCK_BREAK_SPEED);

        if (player.isEyeInFluid(FluidTags.WATER)) {
            speed *= (float) player.getAttributeValue(Attributes.SUBMERGED_MINING_SPEED);
        }

        // GrimAC nakłada karę /5.0f jeśli gracz nie był na ziemi na początku lub końcu ticku ruchu
        if (!player.onGround() || !wasOnGroundLastTick) {
            speed /= 5.0f;
        }

        float divisor = player.hasCorrectToolForDrops(state) ? 30.0f : 100.0f;
        return (speed / hardness) / divisor;
    }

    public static float getVanillaDestroyProgress(LocalPlayer player, BlockGetter level, BlockPos pos, BlockState state) {
        return getGrimDestroyProgress(player, level, pos, state, player != null && player.onGround());
    }

    /**
     * Minimalna liczba ticków wymagana przez GrimAC na zniszczenie bloku (+1 tick bufora bezpieczeństwa).
     */
    public static int getRequiredTicks(LocalPlayer player, BlockGetter level, BlockPos pos, BlockState state, boolean wasOnGroundLastTick) {
        float delta = getGrimDestroyProgress(player, level, pos, state, wasOnGroundLastTick);
        if (delta >= 1.0f) return 0;
        if (delta <= 0.0f) return Integer.MAX_VALUE;
        // +1 tick bufora bezpieczeństwa na jitter pakietów i opóźnienia sieciowe (eliminuje flary delay=91ms..109ms)
        return (int) Math.ceil(1.0f / delta) + 1;
    }

    public static int getRequiredTicks(LocalPlayer player, BlockGetter level, BlockPos pos, BlockState state) {
        return getRequiredTicks(player, level, pos, state, player != null && player.onGround());
    }
}
