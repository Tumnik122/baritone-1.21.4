/*
 * This file is part of Baritone.
 *
 * Baritone is free software: you can redistribute it and/or modify
 * it under the terms of the GNU Lesser General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * Baritone is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU Lesser General Public License for more details.
 *
 * You should have received a copy of the GNU Lesser General Public License
 * along with Baritone.  If not, see <https://www.gnu.org/licenses/>.
 */

package baritone.utils;

import baritone.Baritone;
import baritone.api.BaritoneAPI;
import baritone.api.IBaritone;
import baritone.api.utils.IPlayerContext;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Holder;
import net.minecraft.tags.FluidTags;
import net.minecraft.world.effect.MobEffects;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.enchantment.Enchantment;
import net.minecraft.world.item.enchantment.Enchantments;
import net.minecraft.world.item.enchantment.ItemEnchantments;
import net.minecraft.world.level.block.state.BlockState;

/**
 * Pomocnik do obsługi szybkiego kopania (FastBreak) dla kilofów z wysokim enchantem (np. Wydajność 10 / Efficiency 10).
 *
 * Eliminuje 5-tickowe (250ms) opóźnienie vanilla Minecrafta TYLKO dla bloków, które matematycznie niszczą się
 * natychmiastowo (progress >= 1.0f, np. Stone, Deepslate, Tuff).
 *
 * Dla bloków twardszych (np. Deepslate Iron Ore o twardości 4.5), które wymagają >= 2 ticków,
 * zachowuje bezpieczny timing vanilla, aby GrimAC nie zgłaszał flag FastBreak!
 */
public final class FastBreakHelper {

    private FastBreakHelper() {}

    /**
     * Zwraca poziom zaklęcia Efficiency (Wydajność) na danym przedmiocie.
     */
    public static int getEfficiencyLevel(ItemStack stack) {
        if (stack == null || stack.isEmpty()) {
            return 0;
        }
        ItemEnchantments enchantments = stack.getEnchantments();
        for (Holder<Enchantment> enchant : enchantments.keySet()) {
            if (enchant.is(Enchantments.EFFICIENCY)) {
                return enchantments.getLevel(enchant);
            }
        }
        return 0;
    }

    /**
     * Sprawdza, czy gracz trzyma w ręce lub posiada na pasku/w ekwipunku kilof z Wydajnością >= 10.
     */
    public static boolean hasEfficiency10(IPlayerContext ctx) {
        if (ctx == null || ctx.player() == null) {
            return false;
        }
        ItemStack held = ctx.player().getMainHandItem();
        if (getEfficiencyLevel(held) >= 10) {
            return true;
        }
        for (int i = 0; i < 9; i++) {
            ItemStack stack = ctx.player().getInventory().getItem(i);
            if (getEfficiencyLevel(stack) >= 10) {
                return true;
            }
        }
        return false;
    }

    /**
     * Główny warunek: czy ustawienia FastBreak są aktywne.
     */
    public static boolean isFastBreakActive(IPlayerContext ctx) {
        if (ctx == null || ctx.player() == null) {
            return false;
        }
        return Baritone.settings().fastBreak.value;
    }

    /**
     * Sprawdza, czy gracz posiada szybki kilof (Wydajność >= 5, Wydajność 10, Haste lub włączony FastBreak).
     */
    public static boolean isFastPickaxe(IPlayerContext ctx) {
        if (ctx == null || ctx.player() == null) {
            return false;
        }
        if (isFastBreakActive(ctx)) {
            return true;
        }
        ItemStack held = ctx.player().getMainHandItem();
        if (held != null && !held.isEmpty()) {
            if (getEfficiencyLevel(held) >= 5) {
                return true;
            }
        }
        if (hasEfficiency10(ctx)) {
            return true;
        }
        if (ctx.player().hasEffect(MobEffects.DIG_SPEED)) {
            return true;
        }
        return false;
    }

    /**
     * Oblicza dokładny postęp niszczenia bloku w ciągu 1 ticka z perspektywy walidacji GrimAC na serwerze.
     *
     * KLUCZOWE DLA GRIMAC:
     * Serwer GrimAC kalkuluje prędkość narzędzia z poziomem Efficiency ograniczonym do vanilla (max 5).
     * Nawet posiadając kilof Wydajność 10, na serwerze kamień (Stone/Andesite/Diorite o twardości 1.5)
     * BEZ EFEKTU HASTE II osiąga postęp 0.777f na tick (< 1.0f).
     * Oznacza to, że GrimAC WYMAGA minimum 2 ticków (100ms) na zniszczenie kamienia!
     *
     * Jeśli klient próbuje zniszczyć kamień w 1 ticku (50ms), GrimAC natychmiast zgłasza:
     * "failed FastBreak diff=50.0ms, balance=1037.5ms, type=stone"!
     */
    public static double getGrimDestroyProgress(IPlayerContext ctx, BlockPos pos, BlockState state) {
        if (ctx == null || ctx.player() == null || state == null || ctx.world() == null) {
            return 0.0;
        }
        if (state.isAir()) {
            return 1.0;
        }

        var player = ctx.player();
        float hardness = state.getDestroySpeed(ctx.world(), pos != null ? pos : ctx.playerFeet());
        if (hardness < 0) return 0.0; // Bedrock itp.
        if (hardness == 0) return 1.0; // Pochodnie, kwiaty itp.

        ItemStack tool = player.getMainHandItem();
        float speed = tool.getDestroySpeed(state);

        if (speed > 1.0f) {
            int eff = getEfficiencyLevel(tool);
            // GrimAC na serwerze obcina Efficiency do maksymalnie 5
            int safeEff = Math.min(5, eff);
            if (safeEff > 0) {
                speed += (safeEff * safeEff + 1);
            }
        }

        // Efekty: Haste / Mining Fatigue
        if (player.hasEffect(MobEffects.DIG_SPEED)) {
            int hasteLevel = player.getEffect(MobEffects.DIG_SPEED).getAmplifier() + 1;
            speed *= (1.0f + hasteLevel * 0.2f);
        }
        if (player.hasEffect(MobEffects.DIG_SLOWDOWN)) {
            switch (player.getEffect(MobEffects.DIG_SLOWDOWN).getAmplifier()) {
                case 0 -> speed *= 0.3f;
                case 1 -> speed *= 0.09f;
                case 2 -> speed *= 0.0027f;
                default -> speed *= 0.00081f;
            }
        }

        // Płyny i podłoże
        if (player.isEyeInFluid(FluidTags.WATER)) {
            boolean hasAquaAffinity = false;
            ItemStack helmet = player.getItemBySlot(EquipmentSlot.HEAD);
            if (helmet != null && !helmet.isEmpty()) {
                for (Holder<Enchantment> ench : helmet.getEnchantments().keySet()) {
                    if (ench.is(Enchantments.AQUA_AFFINITY)) {
                        hasAquaAffinity = true;
                        break;
                    }
                }
            }
            if (!hasAquaAffinity) {
                speed /= 5.0f;
            }
        }

        if (!player.onGround()) {
            speed /= 5.0f;
        }

        boolean correctTool = player.hasCorrectToolForDrops(state);
        return speed / hardness / (correctTool ? 30.0f : 100.0f);
    }

    /**
     * Czy dany konkretny blok w danej pozycji może być legalnie zniszczony w 1 ticku (Instamine) na GrimAC.
     */
    public static boolean canGrimInstaBreak(BlockPos pos, BlockState state) {
        try {
            IBaritone baritone = BaritoneAPI.getProvider().getPrimaryBaritone();
            if (baritone == null || baritone.getPlayerContext() == null) {
                return false;
            }
            IPlayerContext ctx = baritone.getPlayerContext();
            if (ctx.player() == null || ctx.world() == null) {
                return false;
            }
            if (!ctx.player().onGround() || ctx.player().isInWater()) {
                return false;
            }
            return getGrimDestroyProgress(ctx, pos, state) >= 1.0;
        } catch (Throwable t) {
            return false;
        }
    }

    /**
     * Oblicza postęp niszczenia bloku (dla zgodności wstecznej).
     */
    public static double getDestroyProgress(IPlayerContext ctx, BlockPos pos, BlockState state) {
        return getGrimDestroyProgress(ctx, pos, state);
    }

    /**
     * Czy dany blok kwalifikuje się do natychmiastowego zniszczenia w Baritone.
     */
    public static boolean isInstaBreak(IPlayerContext ctx, BlockPos pos, BlockState state) {
        if (!isFastBreakActive(ctx)) {
            return false;
        }
        if (ctx == null || ctx.player() == null) {
            return false;
        }
        if (!ctx.player().onGround() || ctx.player().isInWater()) {
            return false;
        }
        return canGrimInstaBreak(pos, state);
    }

    public static boolean isInstaBreak(IPlayerContext ctx, BlockState state) {
        return isInstaBreak(ctx, null, state);
    }

    /**
     * Sprawdzenie pozycji bloku dla wywołań z Mixinów (MultiPlayerGameMode).
     * Jeśli dany blok jest prawdziwym Instamine na GrimAC -> destroyDelay = 0.
     * Jeśli to blok wymagający >= 2 ticków (np. Stone bez Haste II) -> zachowaj vanilla delay, zero flag GrimAC!
     */
    public static boolean canInstaBreak(BlockPos pos) {
        try {
            IBaritone baritone = BaritoneAPI.getProvider().getPrimaryBaritone();
            if (baritone != null && baritone.getPlayerContext() != null) {
                IPlayerContext ctx = baritone.getPlayerContext();
                if (ctx.world() != null && pos != null) {
                    BlockState state = ctx.world().getBlockState(pos);
                    return canGrimInstaBreak(pos, state);
                }
            }
        } catch (Throwable ignored) {}
        return false;
    }
}
