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

package baritone.launch.mixins;

import baritone.Baritone;
import baritone.utils.schematic.BlockStateResolver;
import net.minecraft.core.BlockPos;
import net.minecraft.world.item.BlockItem;
import net.minecraft.world.item.context.BlockPlaceContext;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.state.BlockState;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * Przechwytuje stawianie bloków i poprawia rotację.
 * Naprawia: "Baritone nie potrafi budować bloków z rotacją".
 */
@Mixin(BlockItem.class)
public abstract class MixinBlockItem {

    @Inject(
            method = "getPlacementState",
            at = @At("RETURN"),
            cancellable = true
    )
    private void onGetPlacementState(BlockPlaceContext context, CallbackInfoReturnable<BlockState> cir) {
        if (!Baritone.settings().rotationResolver.value) {
            return;
        }

        BlockState original = cir.getReturnValue();
        if (original == null) {
            return;
        }

        BlockPos pos = context.getClickedPos();
        Level level = context.getLevel();
        if (level == null) {
            return;
        }

        // Popraw rotację używając BlockStateResolver
        BlockState corrected = BlockStateResolver.resolvePlacementState(original, level, pos);
        if (corrected != null && corrected != original) {
            cir.setReturnValue(corrected);
        }
    }
}
